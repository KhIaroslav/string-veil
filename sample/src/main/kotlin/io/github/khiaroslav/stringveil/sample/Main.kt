package io.github.khiaroslav.stringveil.sample

import io.github.khiaroslav.stringveil.annotations.DoNotObfuscate
import io.github.khiaroslav.stringveil.annotations.Obfuscate

/**
 * Minimal demonstration of supported String Veil declaration shapes. The selected direct literals
 * are replaced in the transformed bytecode with decoder calls over randomized containers and still
 * produce their original values at runtime.
 *
 * Inspect it yourself after `./gradlew -p sample run`:
 *
 *   strings sample/build/classes/kotlin/main/io/github/khiaroslav/stringveil/sample/MainKt.class \
 *     | grep internal   # prints nothing: the selected plaintexts are absent
 */

// @Obfuscate on a top-level val hides its literal.
@Obfuscate
private val apiBaseUrl = "https://internal.example.com/api"

// @Obfuscate on a function selects direct string literals in that bytecode method.
@Obfuscate
private fun telemetryEndpoint(): String = "https://telemetry.internal.example.com/v3/ingest"

// @Obfuscate on a class selects supported direct literals; @DoNotObfuscate opts one back out.
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
