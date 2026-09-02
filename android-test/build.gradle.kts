import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

plugins {
    id("com.android.library") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.3.21"
    // Resolved from the first-priority mavenLocal repository; Android emits native-bridge calls.
    id("io.github.khstov.string-veil") version "0.2.1"
}

android {
    namespace = "io.github.khstov.stringveil.androidtest"
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

/**
 * Fail-closed end-to-end guard: the obfuscated literal must not appear anywhere in the packaged
 * library artifact. Uses a raw byte scan of every class in the AAR's `classes.jar` — not
 * `grep`/`strings`, which mis-handle `.class` files (grep flags them binary; macOS `strings` mistakes
 * the `0xCAFEBABE` magic for a Mach-O binary). A byte scan also catches an *orphaned* constant-pool
 * entry — plaintext left in the pool with no live reference — which is exactly the leak the Android
 * transform was reworked to prevent.
 */
val verifyObfuscatedAar by tasks.registering {
    dependsOn("assembleRelease")
    val aar = layout.buildDirectory.file("outputs/aar/string-veil-android-test-release.aar")
    inputs.file(aar)
    doLast {
        val needle = "internal.example.com/native-secret".toByteArray()
        val leaks = mutableListOf<String>()
        ZipFile(aar.get().asFile).use { outer ->
            val classesJar = outer.getEntry("classes.jar")
                ?: throw GradleException("classes.jar not found in ${aar.get().asFile}")
            ZipInputStream(outer.getInputStream(classesJar)).use { classes ->
                var entry = classes.nextEntry
                while (entry != null) {
                    if (entry.name.endsWith(".class") && classes.readBytes().containsSequence(needle)) {
                        leaks += entry.name
                    }
                    entry = classes.nextEntry
                }
            }
        }
        if (leaks.isNotEmpty()) {
            throw GradleException(
                "String Veil leaked obfuscated plaintext into the AAR: ${leaks.joinToString()}",
            )
        }
        logger.lifecycle("verifyObfuscatedAar: no obfuscated plaintext in the packaged AAR")
    }
}

tasks.named("check") { dependsOn(verifyObfuscatedAar) }

fun ByteArray.containsSequence(needle: ByteArray): Boolean {
    if (needle.isEmpty() || needle.size > size) return false
    outer@ for (start in 0..size - needle.size) {
        for (i in needle.indices) if (this[start + i] != needle[i]) continue@outer
        return true
    }
    return false
}
