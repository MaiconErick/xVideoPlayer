import java.text.SimpleDateFormat
import java.util.Date

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.app.xvideoplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.app.xvideoplayer"
        minSdk = 26
        targetSdk = 35
        versionCode = 33
        versionName = "2.10.0"

        // libmpv ships ~24 MB of native code per ABI; every device this runs on is arm64
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // signed with the local debug key so it installs over existing debug builds
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // mpv (FFmpeg-based engine: plays and seeks in every container/codec)
    implementation("dev.jdtech.mpv:libmpv:1.0.0")

    // FFmpegKit (lossless cutting: ffmpeg -c copy / ffprobe). Vendored with renamed .so files so
    // its FFmpeg doesn't collide with libmpv's — see src/main/jniLibs/README.txt
    implementation(files("libs/ffmpeg-kit-min-8.1.9-classes.jar"))
    implementation("com.arthenica:smart-exception-java:0.2.1")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.53.1")
    ksp("com.google.dagger:hilt-android-compiler:2.53.1")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Coil for thumbnails
    implementation("io.coil-kt:coil-compose:2.7.0")

    // SMB (jcifs-ng)
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10")

    // FTP (commons-net)
    implementation("commons-net:commons-net:3.11.1")

    // Gson
    implementation("com.google.code.gson:gson:2.11.0")

    // DocumentFile for SAF
    implementation("androidx.documentfile:documentfile:1.0.1")
}

// Every assembled APK is also copied to C:\DEV\Android\APKs as
// xVideoPlayer-<version>-<variant>-<yyyyMMdd-HHmm>.apk; only this app's two newest copies are kept.
val apkArchiveDir = file("C:/DEV/Android/APKs")
val apkAppName = "xVideoPlayer"
val apkVersionName = android.defaultConfig.versionName ?: "dev"
tasks.configureEach {
    val variant = when (name) { "assembleDebug" -> "debug"; "assembleRelease" -> "release"; else -> return@configureEach }
    val outDir = layout.buildDirectory.dir("outputs/apk/$variant")
    doLast {
        val apk = outDir.get().asFile.listFiles { f -> f.extension == "apk" }?.maxByOrNull { it.lastModified() } ?: return@doLast
        apkArchiveDir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm").format(Date())
        val target = File(apkArchiveDir, "$apkAppName-$apkVersionName-$variant-$stamp.apk")
        apk.copyTo(target, overwrite = true)
        apkArchiveDir.listFiles { f -> f.name.startsWith("$apkAppName-") && f.extension == "apk" }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(2)
            ?.forEach { it.delete() }
        println("APK copied to $target")
    }
}
