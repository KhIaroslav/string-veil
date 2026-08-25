plugins {
    id("com.android.library") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.3.21"
    // Resolved from mavenLocal (publishToMavenLocal); on Android the plugin emits NATIVE decode calls.
    id("io.github.khiaroslav.string-veil") version "0.1.0-alpha01"
}

android {
    namespace = "io.github.khiaroslav.stringveil.androidtest"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
