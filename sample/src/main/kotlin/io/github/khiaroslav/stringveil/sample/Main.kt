package io.github.khiaroslav.stringveil.sample

import io.github.khiaroslav.stringveil.annotations.DoNotObfuscate
import io.github.khiaroslav.stringveil.annotations.Obfuscate

/**
 * Minimal demonstration of String Veil. Every string marked for obfuscation is replaced in the
 * compiled bytecode with a call to the decoder over a randomized container — the plaintext never
 * appears in the `.class` file — yet it decodes to the original value at runtime.
 *
 * Inspect it yourself after `./gradlew -p sample run`:
 *
 *   strings sample/build/classes/kotlin/main/io/github/khiaroslav/stringveil/sample/MainKt.class \
 *     | grep internal   # prints nothing: the secrets are gone
 */

// @Obfuscate on a top-level val hides its literal.
@Obfuscate
private val apiBaseUrl = "https://internal.example.com/api"

// @Obfuscate on a function hides every string literal in its body.
@Obfuscate
private fun telemetryEndpoint(): String = "https://telemetry.internal.example.com/v3/ingest"

// @Obfuscate on a class hides all its fields; @DoNotObfuscate opts a single one back out.
@Obfuscate
private class BuildInfo {
    val featureFlag = "enable-experimental-checkout" // hidden

    @DoNotObfuscate
    val label = "String Veil sample" // kept as plaintext on purpose
}

// No annotation → left untouched.
private val plainLabel = "public-label"

fun main() {
    val info = BuildInfo()
    println("These literals are obfuscated in the compiled bytecode but decode at runtime:")
    println()
    println("  apiBaseUrl        = $apiBaseUrl")
    println("  telemetryEndpoint = ${telemetryEndpoint()}")
    println("  featureFlag       = ${info.featureFlag}")
    println()
    println("Left as plaintext on purpose:")
    println("  label             = ${info.label}")
    println("  plainLabel        = $plainLabel")
    println()
    println("Now inspect MainKt.class (see the file header) — the obfuscated plaintext is not there.")
}
