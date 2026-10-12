plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.podium.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.podium.android"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        // The DBT emits AArch64, so this only makes sense on 64-bit ARM phones.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sideload-friendly: signed with the debug key so the APK installs as is.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        // libPodiumNative.so + libpodium_jni.so are built by tools/build-android.sh into jniLibs/.
        jniLibs { useLegacyPackaging = true }
    }

    // keybag_bootstrap.bin, podium_syncd.bin, first_boot_state.plist, boot_read_files.txt
    // come straight from the iOS app's resources.
    sourceSets["main"].assets.srcDir("../../Podium/Resources/GuestTools")
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
