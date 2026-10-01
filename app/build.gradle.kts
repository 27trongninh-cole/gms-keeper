plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

base {
    archivesName.set("gms-keeper")
}

android {
    namespace = "com.ninfinity.gmsdoze"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ninfinity.gmsdoze"
        minSdk = 30            // Shizuku wireless debugging cần Android 11+
        targetSdk = 35
        versionCode = 15
        versionName = "1.10"
    }

    // Keystore cố định (commit trong repo) để các bản build sau cài đè được lên bản cũ.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // Ký bằng keystore cố định của repo để APK release cài được ngay
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }

    lint {
        checkReleaseBuilds = false
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
