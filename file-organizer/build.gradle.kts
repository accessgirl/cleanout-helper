// The Kotlin and Android plugins are loaded once here, for every module. The Android plugin is
// only loaded when an Android SDK is installed (see settings.gradle.kts), so :core still builds
// and tests on computers without Android tools.
buildscript {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
        if (System.getProperty("fileorganizer.hasAndroidSdk") == "true") {
            classpath("com.android.tools.build:gradle:8.7.3")
        }
    }
}
