// Standalone Android build whose only job is to check that the native decoder loads and decodes
// on a real Android runtime (an emulator in CI), not just on the host JVM the differential test uses.
//
// It consumes String Veil exactly like a real consumer would: from the local Maven repository. CI
// runs `./gradlew publishToMavenLocal` first, then `-p android-test connectedDebugAndroidTest`.
pluginManagement {
    repositories {
        // Must come first: the released version may already exist in public repositories.
        mavenLocal()
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        // Keep plugin and runtime artifacts on the same local build under test.
        mavenLocal()
        google()
        mavenCentral()
    }
}

rootProject.name = "string-veil-android-test"
