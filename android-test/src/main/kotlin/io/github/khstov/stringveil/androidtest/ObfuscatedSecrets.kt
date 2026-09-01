package io.github.khstov.stringveil.androidtest

import io.github.khstov.stringveil.annotations.Obfuscate

/**
 * A single selected literal. On Android the plugin rewrites [endpoint] to a
 * `NativeStringDecoder.decode(...)` call, so reading it at runtime exercises the native path.
 */
@Obfuscate
object ObfuscatedSecrets {
    val endpoint: String = "https://internal.example.com/native-secret"
}
