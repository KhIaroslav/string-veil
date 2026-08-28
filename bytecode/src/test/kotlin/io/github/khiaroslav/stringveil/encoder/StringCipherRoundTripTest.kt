package io.github.khiaroslav.stringveil.encoder

import io.github.khiaroslav.stringveil.runtime.StringDecoder
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

/**
 * Round-trips every corpus case through the build-time cipher and the JVM `runtime` decoder:
 * `LayeredStringCipher.encrypt(...)` -> `StringDecoder.decode(...)` must reproduce the input.
 *
 * This is the JVM half of the JVM<->native differential check. The native half reuses the same
 * [DifferentialCorpus] cases (see the `native-differential` module).
 */
class StringCipherRoundTripTest {
    private val cipher = LayeredStringCipher()

    private fun encode(case: DifferentialCase): IntArray =
        cipher.encrypt(
            value = case.plaintext.toByteArray(Charsets.UTF_8),
            context = EncryptionContext(
                fileName = "${case.label}.kt",
                startOffset = case.label.length,
            ),
            config = case.config,
        ).container

    @Test
    fun `deterministic corpus round-trips through the JVM decoder`() {
        val cases = DifferentialCorpus.deterministicCases()
        assertFalse(cases.isEmpty(), "corpus must not be empty")
        for (case in cases) {
            val container = encode(case)
            assertEquals(
                case.plaintext,
                StringDecoder.decode(container),
                "JVM decode mismatch for ${case.label}",
            )
        }
    }

    @Test
    fun `randomized fuzz cases round-trip through the JVM decoder`() {
        val random = SecureRandom()
        for (case in DifferentialCorpus.randomCases(count = 500, random = random)) {
            val container = encode(case)
            assertEquals(
                case.plaintext,
                StringDecoder.decode(container),
                "JVM decode mismatch for fuzz case ${case.label}",
            )
        }
    }

    @Test
    fun `the same plaintext yields different containers on each build`() {
        val case = DifferentialCase(
            label = "stability",
            plaintext = "internal-value",
            config = ProtectionConfig(ProtectionMethod.RANDOM_ALL, repetitions = 3),
        )
        val first = encode(case)
        val second = encode(case)
        assertNotEquals(
            first.toList(),
            second.toList(),
            "containers should be randomized per encode",
        )
        assertEquals(case.plaintext, StringDecoder.decode(first))
        assertEquals(case.plaintext, StringDecoder.decode(second))
    }

    @Test
    fun `plaintext bytes never appear verbatim in the container`() {
        val secret = "TOP-SECRET-NEEDLE-42"
        val container = encode(
            DifferentialCase(
                label = "needle",
                plaintext = secret,
                config = ProtectionConfig(ProtectionMethod.RANDOM_ALL, repetitions = 4),
            ),
        )
        val haystack = ByteArray(container.size * 4)
        container.forEachIndexed { i, word ->
            haystack[i * 4] = word.toByte()
            haystack[i * 4 + 1] = (word ushr 8).toByte()
            haystack[i * 4 + 2] = (word ushr 16).toByte()
            haystack[i * 4 + 3] = (word ushr 24).toByte()
        }
        val needle = secret.toByteArray(Charsets.UTF_8)
        val found = (0..haystack.size - needle.size).any { start ->
            needle.indices.all { haystack[start + it] == needle[it] }
        }
        assertFalse(found, "plaintext must not survive verbatim inside the container")
    }
}
