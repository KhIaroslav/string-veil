// Standalone example build that consumes String Veil straight from source. A top-level
// `includeBuild("..")` substitutes both the `io.github.khstov.string-veil` plugin and the
// runtime/annotations artifacts it pulls in from the surrounding project, so `./gradlew -p sample
// run` works with no publishing step.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

includeBuild("..")

rootProject.name = "string-veil-sample"
