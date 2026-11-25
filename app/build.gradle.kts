plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val ytdl = "0.18.1"

android {
    namespace = "app.raftaaro"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.raftaaro"
        // Android 10+: downloads go to Download/Raftaaro through MediaStore, no storage permission.
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-prototype"

        ndk {
            // Phones only for the prototype; x86 emulators can be added back later.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        // yt-dlp's Python and ffmpeg ship as .so files that must be unpacked on install.
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:$ytdl")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdl")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
}
