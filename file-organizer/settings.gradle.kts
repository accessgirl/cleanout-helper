pluginManagement {
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
}

dependencyResolutionManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "file-organizer"

// The organizing logic lives in :core (plain Kotlin, runs and tests on any computer).
// The phone app in :app needs the Android SDK, so it is only included when one is installed.
include(":core")

val sdkFromProperties = file("local.properties").takeIf { it.exists() }
    ?.readLines()?.firstOrNull { it.startsWith("sdk.dir=") }?.substringAfter("=")
val hasAndroidSdk = listOfNotNull(
    System.getenv("ANDROID_HOME"),
    System.getenv("ANDROID_SDK_ROOT"),
    sdkFromProperties,
).any { file(it).isDirectory }

if (hasAndroidSdk) {
    include(":app")
} else {
    println("Android SDK not found: building the :core module only.")
}
