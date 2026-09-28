plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cleanouthelper.organizer.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cleanouthelper.fileorganizer"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        // A fixed key kept in the repository so every build can install over the previous one.
        // It only identifies this sideloaded app; it is not a secret.
        create("sideload") {
            storeFile = file("sideload.keystore")
            storePassword = "fileorganizer"
            keyAlias = "fileorganizer"
            keyPassword = "fileorganizer"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("sideload")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.kotlin_module")
        }
    }

    lint {
        // MANAGE_EXTERNAL_STORAGE is the whole point of this app, which is installed directly rather than from Play.
        disable += setOf("ScopedStorage")
        abortOnError = false
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // On-device text reading and photo description. The models are bundled, so nothing is uploaded.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:image-labeling:17.0.9")
    // Reads the text inside PDFs.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}
