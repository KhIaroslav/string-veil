package io.github.khiaroslav.stringveil.encoder

import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the layer-selection policy of [PipelineEncoder], in particular that BASE64 (whose only
 * effect is a ~4/3 size increase) is never stacked when another method is available, while an
 * explicit BASE64-only request is still honored.
 */
class PipelineEncoderTest {
    private val encoder = PipelineEncoder(SecureRandom())

    @Test
    fun `RANDOM_ALL never stacks base64`() {
        repeat(300) {
            val pipeline = encoder.encode(
                "size-should-not-explode".encodeToByteArray(),
                ProtectionConfig(method = ProtectionMethod.RANDOM_ALL, repetitions = 8),
            )
            assertTrue(
                countBase64Layers(pipeline) <= 1,
                "RANDOM_ALL stacked base64 layers",
            )
        }
    }

    @Test
    fun `RANDOM_SELECTED with a mix never stacks base64`() {
        repeat(300) {
            val pipeline = encoder.encode(
                "size-should-not-explode".encodeToByteArray(),
                ProtectionConfig(
                    method = ProtectionMethod.RANDOM_SELECTED,
                    methods = setOf(ProtectionMethod.BASE64, ProtectionMethod.AES),
                    repetitions = 8,
                ),
            )
            assertTrue(
                countBase64Layers(pipeline) <= 1,
                "RANDOM_SELECTED stacked base64 layers",
            )
        }
    }

    @Test
    fun `explicit base64 is honored for every layer`() {
        val pipeline = encoder.encode(
            "value".encodeToByteArray(),
            ProtectionConfig(method = ProtectionMethod.BASE64, repetitions = 4),
        )
        assertEquals(4, countBase64Layers(pipeline), "explicit BASE64 should apply to every layer")
    }

    @Test
    fun `base64-only selection is honored for every layer`() {
        val pipeline = encoder.encode(
            "value".encodeToByteArray(),
            ProtectionConfig(
                method = ProtectionMethod.RANDOM_SELECTED,
                methods = setOf(ProtectionMethod.BASE64),
                repetitions = 4,
            ),
        )
        assertEquals(4, countBase64Layers(pipeline), "BASE64-only selection should apply to every layer")
    }

    /** Parses the self-describing pipeline header and counts BASE64 (method id 3) layers. */
    private fun countBase64Layers(pipeline: ByteArray): Int {
        var position = 0
        fun u8(): Int = pipeline[position++].toInt() and 0xFF
        fun u16(): Int = (u8() shl 8) or u8()
        fun skipInt() { position += 4 }

        skipInt() // magic
        u8() // version
        val layerCount = u8()
        var base64 = 0
        repeat(layerCount) {
            val methodId = u8()
            skipInt() // inputLength
            val parameterLength = u16()
            position += parameterLength
            if (methodId == BASE64_METHOD_ID) base64++
        }
        return base64
    }

    private companion object {
        const val BASE64_METHOD_ID = 3
    }
}
