@file:OptIn(InternalStringVeilApi::class)

package io.github.khiaroslav.stringveil.compiler

import io.github.khiaroslav.stringveil.format.InternalStringVeilApi
import io.github.khiaroslav.stringveil.format.StringVeilFormat.AES_IV_BYTES
import io.github.khiaroslav.stringveil.format.StringVeilFormat.AES_KEY_BYTES
import io.github.khiaroslav.stringveil.format.StringVeilFormat.AES_TRANSFORMATION
import io.github.khiaroslav.stringveil.format.StringVeilFormat.BASE64_MASK
import io.github.khiaroslav.stringveil.format.StringVeilFormat.PIPELINE_MAGIC
import io.github.khiaroslav.stringveil.format.StringVeilFormat.PIPELINE_VERSION
import io.github.khiaroslav.stringveil.format.StringVeilFormat.SHIFT_MASK
import io.github.khiaroslav.stringveil.format.StringVeilFormat.streamByte
import io.github.khiaroslav.stringveil.format.StringVeilFormat.xorStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encodes the plaintext through a randomized stack of reversible layers (bit-shift, XOR, base64,
 * AES-CTR) and serializes them, with their per-layer parameters, into a self-describing byte stream.
 * The inverse lives in the runtime `PipelineDecoder`.
 */
internal class PipelineEncoder(private val random: SecureRandom) {
    fun encode(value: ByteArray, config: ProtectionConfig): ByteArray {
        val layers = ArrayList<EncodedLayer>(config.repetitions)
        var transformed = value.copyOf()

        repeat(config.repetitions) {
            val method = selectMethod(config)
            val layer = encodeLayer(method, transformed)
            transformed.fill(0)
            transformed = layer.data
            layers += layer.copy(data = ByteArray(0))
        }

        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(PIPELINE_MAGIC)
                output.writeByte(PIPELINE_VERSION)
                output.writeByte(layers.size)
                layers.forEach { layer ->
                    output.writeByte(layer.method.id)
                    output.writeInt(layer.inputLength)
                    output.writeShort(layer.parameters.size)
                    output.write(layer.parameters)
                    layer.parameters.fill(0)
                }
                output.writeInt(transformed.size)
                output.write(transformed)
            }
            transformed.fill(0)
            bytes.toByteArray()
        }
    }

    private fun selectMethod(config: ProtectionConfig): PipelineMethod =
        when (config.method) {
            ProtectionMethod.BIT_SHIFT -> PipelineMethod.BIT_SHIFT
            ProtectionMethod.BIT_XOR -> PipelineMethod.BIT_XOR
            ProtectionMethod.BASE64 -> PipelineMethod.BASE64
            ProtectionMethod.AES -> PipelineMethod.AES
            ProtectionMethod.RANDOM_ALL -> PIPELINE_METHODS.random(random)
            ProtectionMethod.RANDOM_SELECTED -> {
                val selected = config.methods.mapNotNull(ProtectionMethod::asPipelineMethod)
                (selected.ifEmpty { PIPELINE_METHODS }).random(random)
            }
        }

    private fun encodeLayer(method: PipelineMethod, input: ByteArray): EncodedLayer =
        when (method) {
            PipelineMethod.BIT_SHIFT -> {
                val seed = random.nextInt()
                EncodedLayer(method, input.size, seed.toBytes(), input.rotateAndMask(seed))
            }
            PipelineMethod.BIT_XOR -> {
                val seed = random.nextInt()
                EncodedLayer(method, input.size, seed.toBytes(), xorStream(input, seed))
            }
            PipelineMethod.BASE64 -> {
                val seed = random.nextInt()
                val encoded = Base64.getEncoder().encode(input)
                EncodedLayer(method, input.size, seed.toBytes(), xorStream(encoded, seed xor BASE64_MASK))
                    .also { encoded.fill(0) }
            }
            PipelineMethod.AES -> {
                val key = ByteArray(AES_KEY_BYTES).also(random::nextBytes)
                val iv = ByteArray(AES_IV_BYTES).also(random::nextBytes)
                val cipher = Cipher.getInstance(AES_TRANSFORMATION).apply {
                    init(
                        Cipher.ENCRYPT_MODE,
                        SecretKeySpec(key, "AES"),
                        IvParameterSpec(iv),
                    )
                }
                val encrypted = cipher.doFinal(input)
                val parameters = key + iv
                key.fill(0)
                iv.fill(0)
                EncodedLayer(method, input.size, parameters, encrypted)
            }
        }

    private fun ByteArray.rotateAndMask(seed: Int): ByteArray =
        ByteArray(size) { index ->
            val shift = streamByte(seed, index) % 7 + 1
            val rotated = ((this[index].toInt() and 0xFF) shl shift) or
                ((this[index].toInt() and 0xFF) ushr (8 - shift))
            (rotated xor streamByte(seed xor SHIFT_MASK, index)).toByte()
        }

    private fun Int.toBytes(): ByteArray = byteArrayOf(
        (this ushr 24).toByte(),
        (this ushr 16).toByte(),
        (this ushr 8).toByte(),
        toByte(),
    )
}

private data class EncodedLayer(
    val method: PipelineMethod,
    val inputLength: Int,
    val parameters: ByteArray,
    val data: ByteArray,
)

private enum class PipelineMethod(val id: Int) {
    BIT_SHIFT(1),
    BIT_XOR(2),
    BASE64(3),
    AES(4),
}

private fun ProtectionMethod.asPipelineMethod(): PipelineMethod? =
    when (this) {
        ProtectionMethod.BIT_SHIFT -> PipelineMethod.BIT_SHIFT
        ProtectionMethod.BIT_XOR -> PipelineMethod.BIT_XOR
        ProtectionMethod.BASE64 -> PipelineMethod.BASE64
        ProtectionMethod.AES -> PipelineMethod.AES
        ProtectionMethod.RANDOM_ALL,
        ProtectionMethod.RANDOM_SELECTED,
        -> null
    }

private fun <T> List<T>.random(random: SecureRandom): T = get(random.nextInt(size))

private val PIPELINE_METHODS = PipelineMethod.entries
