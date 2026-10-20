FFmpegKit (dev.ffmpegkit-maintained:ffmpeg-kit-min:8.1.9, FFmpeg n8.1.3, LGPL) native libs for the lossless cutter.

They are renamed (libavcodec.so -> libfkcodec.so, libc++_shared.so -> libfkc_shared.so, ...) with
same-length in-place edits of SONAME/NEEDED/verneed strings, because libmpv ships its own FFmpeg
under the original names. Each copy links only against its own set, so both coexist in-process.
Java classes: app/libs/ffmpeg-kit-min-8.1.9-classes.jar (com.arthenica.ffmpegkit.*).
To update: download the aar, extract jni/arm64-v8a, run tools/rename_ffmpegkit_libs.py.
