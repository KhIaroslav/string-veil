package io.github.khiaroslav.stringveil.sample

import io.github.khiaroslav.stringveil.annotations.DoNotObfuscate
import io.github.khiaroslav.stringveil.annotations.Obfuscate
import io.github.khiaroslav.stringveil.annotations.ObfuscationMethod

/**
 * Minimal demonstration of String Veil. Every string below marked for obfuscation is replaced in the
 * compiled bytecode with a call to the decoder over a randomized container — the plaintext never
 * appears in the `.class` file — yet it decodes to the original value at runtime.
 *
 * Inspect it yourself after `./gradlew -p sample run`:
 *
 *   javap -c -p sample/build/classes/kotlin/main/io/github/khiaroslav/stringveil/sample/MainKt.class
 *   strings sample/build/classes/kotlin/main/io/github/khiaroslav/stringveil/sample/MainKt.class \
 *     | grep internal   # prints nothing: the secrets are gone
 */

// Declaration-level @Obfuscate protects every string literal in its scope.
@Obfuscate
private val apiBaseUrl = "https://internal.example.com/api"

// Expression-level @Obfuscate protects a single literal.
private val featureFlag = @Obfuscate "enable-experimental-checkout"

// A specific method and repetition count, applied to the whole function scope.
@Obfuscate(method = ObfuscationMethod.AES, repetitions = 2)
private fun telemetryEndpoint(): String = "https://telemetry.internal.example.com/v3/ingest"

// @DoNotObfuscate opts a single literal back out — `shown` stays as plaintext on purpose,
// while `hidden` is still obfuscated by the enclosing @Obfuscate scope.
@Obfuscate
private fun banner(): String {
    val hidden = "internal-build-tag-42"
    val shown = @DoNotObfuscate "String Veil sample"
    return "$shown ($hidden)"
}

fun main() {
    println("These literals are obfuscated in the compiled bytecode but decode at runtime:")
    println()
    println("  apiBaseUrl        = $apiBaseUrl")
    println("  featureFlag       = $featureFlag")
    println("  telemetryEndpoint = ${telemetryEndpoint()}")
    println("  banner            = ${banner()}")
    println()
    println("Now inspect MainKt.class (see the file header) — the plaintext is not there.")
}
