// Standalone Android build whose only job is to prove the native decoder actually loads and decodes
// on a real Android runtime (an emulator in CI), not just on the host JVM the differential test uses.
//
// It consumes String Veil exactly like a real consumer would: from the local Maven repository. CI
// runs `./gradlew publishToMavenLocal` first, then `-p android-test connectedDebugAndroidTest`.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        mavenLocal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        mavenLocal()
    }
}

rootProject.name = "string-veil-android-test"
