@file:JvmName("StringVeil")

package io.github.khiaroslav.stringveil

/**
 * Marks a single string literal for obfuscation at the exact call site.
 *
 * The String Veil bytecode transform replaces `obfuscate("literal")` with a decode over a randomized
 * container, so the plaintext is gone from the compiled class — wherever the call appears, including
 * places a declaration-level [io.github.khiaroslav.stringveil.annotations.Obfuscate] cannot reach:
 * `by lazy { }` delegates, `companion object` / static initializers, custom getters, and
 * sub-expressions.
 *
 * Only a direct string-literal argument is supported; `obfuscate(someVariable)` cannot be protected
 * and is reported by the build. When the plugin is not applied this is an identity function, so code
 * still compiles and runs (unobfuscated).
 *
 * This is a separate tool from the `@Obfuscate` annotation — use the annotation for whole
 * declarations, and this marker for individual literals in forms the annotation does not reach. Do
 * not combine them on the same literal.
 */
public fun obfuscate(value: String): String = value
