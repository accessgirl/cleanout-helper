plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(kotlin("test"))
}

application {
    // Lets you run the organizer on a computer: ./gradlew :core:run --args="/path/to/folder --dry-run"
    mainClass.set("com.cleanouthelper.organizer.CliKt")
}

tasks.test {
    useJUnitPlatform()
}
