package io.github.khstov.stringveil.encoder

import io.github.khstov.stringveil.runtime.StringDecoder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * A fixed seed must make obfuscation a pure function of the source position (reproducible/cacheable
 * builds); no seed must randomize every build; and either way the container must still decode.
 */
class SeedDeterminismTest {
    private val text = "https://internal.example.com/api"
    private val value = text.encodeToByteArray()
    private val context = EncryptionContext("Sample.kt", 3)
    private val config = ProtectionConfig()

    @Test
    fun `a fixed seed is byte-identical across cipher instances and still decodes`() {
        val a = LayeredStringCipher(seed = 42L).encrypt(value, context, config).container
        val b = LayeredStringCipher(seed = 42L).encrypt(value, context, config).container
        assertContentEquals(a, b, "same seed + same input must be byte-identical")
        assertEquals(text, StringDecoder.decode(a), "seeded container must still decode")
    }

    @Test
    fun `distinct positions and distinct seeds diverge`() {
        val base = LayeredStringCipher(seed = 42L).encrypt(value, context, config).container
        val otherPosition = LayeredStringCipher(seed = 42L)
            .encrypt(value, EncryptionContext("Sample.kt", 4), config).container
        val otherSeed = LayeredStringCipher(seed = 7L).encrypt(value, context, config).container
        assertFalse(base.contentEquals(otherPosition), "distinct positions must produce distinct containers")
        assertFalse(base.contentEquals(otherSeed), "distinct seeds must produce distinct containers")
    }

    @Test
    fun `no seed randomizes each build`() {
        val a = LayeredStringCipher().encrypt(value, context, config).container
        val b = LayeredStringCipher().encrypt(value, context, config).container
        assertFalse(a.contentEquals(b), "unseeded output must differ between builds")
    }
}
