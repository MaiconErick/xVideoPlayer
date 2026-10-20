# libmpv: native code calls back into MPVLib (eventProperty/event/logMessage) by name
-keep class dev.jdtech.mpv.** { *; }
-dontwarn dev.jdtech.mpv.**

# jcifs-ng (SMB)
-keep class jcifs.** { *; }
-dontwarn jcifs.**
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-keep class org.slf4j.** { *; }
-dontwarn org.slf4j.**
-keep class javax.crypto.** { *; }

# Commons Net (FTP)
-keep class org.apache.commons.net.** { *; }
-dontwarn org.apache.commons.net.**

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep class com.app.xvideoplayer.data.model.** { *; }

# Hilt
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# Coil
-keep class coil.** { *; }

# jcifs/commons-net pull in optional JVM-only deps
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn javax.**
-dontwarn org.ietf.jgss.**

# FFmpegKit: native code calls back into FFmpegKitConfig (log/statistics/saf*) by name
-keep class com.arthenica.ffmpegkit.** { *; }
-keep class com.arthenica.smartexception.** { *; }
-dontwarn com.arthenica.**
