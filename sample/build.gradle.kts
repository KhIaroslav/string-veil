plugins {
    alias(libs.plugins.kotlin.jvm)
    application
    // Resolved from the included build (`..`); no version needed.
    id("io.github.khstov.string-veil")
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("io.github.khstov.stringveil.sample.MainKt")
}
