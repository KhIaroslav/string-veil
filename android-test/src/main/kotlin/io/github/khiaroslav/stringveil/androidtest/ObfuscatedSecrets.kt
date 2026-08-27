package io.github.khiaroslav.stringveil.androidtest

import io.github.khiaroslav.stringveil.annotations.Obfuscate

/**
 * A single obfuscated literal. On Android the plugin compiles [endpoint] into a
 * `NativeStringDecoder.decode(...)` call, so reading it at runtime exercises the native decoder.
 */
@Obfuscate
object ObfuscatedSecrets {
    val endpoint: String = "https://internal.example.com/native-secret"
}
