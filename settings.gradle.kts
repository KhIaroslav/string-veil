pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "string-veil"

include(
    ":annotations",
    ":compiler-plugin",
    ":gradle-plugin",
    ":native-runtime",
    ":runtime",
)
