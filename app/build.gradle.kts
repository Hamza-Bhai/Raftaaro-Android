import com.android.build.api.variant.FilterConfiguration
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

val ytdl = "0.18.1"
val abis = listOf("armeabi-v7a", "arm64-v8a", "x86_64")

android {
    namespace = "io.github.hamzabhai.raftaaro"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.hamzabhai.raftaaro"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include(*abis.toTypedArray())
            isUniversalApk = true
        }
    }

    // Release key and passwords live in local.properties (git-ignored) or, on CI, in environment
    // variables. Without them a local release build is signed with the debug key, for testing only.
    val props = Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    fun secret(key: String, env: String): String? = props.getProperty(key) ?: System.getenv(env)
    val storeFilePath = secret("release.storeFile", "RELEASE_STORE_FILE")

    signingConfigs {
        if (storeFilePath != null) create("release") {
            storeFile = rootProject.file(storeFilePath)
            storePassword = secret("release.storePassword", "RELEASE_STORE_PASSWORD")
            keyAlias = secret("release.keyAlias", "RELEASE_KEY_ALIAS")
            keyPassword = secret("release.keyPassword", "RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    dependenciesInfo {
        // F-Droid cannot verify Google's encrypted dependency block
        includeInApk = false
        includeInBundle = false
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Split APKs get versionCode * 10 + 1/2/3 so F-Droid and updates pick the right one; universal keeps * 10.
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.find { it.filterType == FilterConfiguration.FilterType.ABI }?.identifier
            val base = output.versionCode.get() ?: 1
            output.versionCode.set(base * 10 + (abi?.let { abis.indexOf(it) + 1 } ?: 0))
        }
    }
}

dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:$ytdl")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdl")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-service:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.work:work-testing:2.9.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
