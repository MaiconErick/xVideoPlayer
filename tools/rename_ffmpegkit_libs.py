"""Rename FFmpegKit's native libs so they can live next to libmpv's own FFmpeg.

usage: python rename_ffmpegkit_libs.py <aar-extracted>/jni/arm64-v8a <out-dir>
Then copy <out-dir>/*.so to app/src/main/jniLibs/arm64-v8a/ and classes.jar to app/libs/.

Same-length names are swapped in place in SONAME / DT_NEEDED / verneed strings, so no ELF
offsets move. Check the result with llvm-readelf -d / -V from the NDK.
"""
import os, sys

REN = {
    b'libavcodec.so':    b'libfkcodec.so',
    b'libavformat.so':   b'libfkformat.so',
    b'libavutil.so':     b'libfkutil.so',
    b'libavfilter.so':   b'libfkfilter.so',
    b'libavdevice.so':   b'libfkdevice.so',
    b'libswscale.so':    b'libfkscale.so',
    b'libswresample.so': b'libfkresample.so',
    b'libc++_shared.so': b'libfkc_shared.so',
}

src, dst = sys.argv[1], sys.argv[2]
os.makedirs(dst, exist_ok=True)
for f in os.listdir(src):
    data = open(os.path.join(src, f), 'rb').read()
    for old, new in REN.items():
        assert len(old) == len(new)
        data = data.replace(b'\0' + old + b'\0', b'\0' + new + b'\0').replace(b'/' + old + b'\0', b'/' + new + b'\0')
    out = REN.get(f.encode(), f.encode()).decode()
    open(os.path.join(dst, out), 'wb').write(data)
    print(f, '->', out)
