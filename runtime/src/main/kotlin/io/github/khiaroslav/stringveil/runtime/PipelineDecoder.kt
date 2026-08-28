@file:OptIn(InternalStringVeilApi::class)

package io.github.khiaroslav.stringveil.runtime

import io.github.khiaroslav.stringveil.format.InternalStringVeilApi
import io.github.khiaroslav.stringveil.format.StringVeilFormat.AES_IV_BYTES
import io.github.khiaroslav.stringveil.format.StringVeilFormat.AES_KEY_BYTES
import io.github.khiaroslav.stringveil.format.StringVeilFormat.AES_TRANSFORMATION
import io.github.khiaroslav.stringveil.format.StringVeilFormat.BASE64_MASK
import io.github.khiaroslav.stringveil.format.StringVeilFormat.MAX_REPETITIONS
import io.github.khiaroslav.stringveil.format.StringVeilFormat.PIPELINE_MAGIC
import io.github.khiaroslav.stringveil.format.StringVeilFormat.PIPELINE_VERSION
import io.github.khiaroslav.stringveil.format.StringVeilFormat.SHIFT_MASK
import io.github.khiaroslav.stringveil.format.StringVeilFormat.streamByte
import io.github.khiaroslav.stringveil.format.StringVeilFormat.xorStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Reverses the layer stack produced by the build-time `PipelineEncoder`: reads the self-describing
 * layer metadata and undoes each transform (AES-CTR, base64, XOR, bit-shift) in reverse order to
 * recover the original UTF-8 bytes.
 */
internal object PipelineDecoder {
    fun decode(pipeline: ByteArray): String {
        val cursor = ByteCursor(pipeline)
        if (cursor.readInt() != PIPELINE_MAGIC || cursor.readByte() != PIPELINE_VERSION) {
            invalidContainer()
        }

        val layerCount = cursor.readByte()
        if (layerCount !in 1..MAX_REPETITIONS) invalidContainer()
        val layers = ArrayList<LayerDescriptor>(layerCount)
        repeat(layerCount) {
            val method = PipelineMethod.fromId(cursor.readByte())
            val inputLength = cursor.readInt()
            val parameterLength = cursor.readUnsignedShort()
            if (inputLength < 0 || parameterLength != method.parameterBytes) invalidContainer()
            layers += LayerDescriptor(method, inputLength, cursor.readBytes(parameterLength))
        }

        val transformedLength = cursor.readInt()
        if (transformedLength < 0 || transformedLength != cursor.remaining) invalidContainer()
        var current = cursor.readBytes(transformedLength)

        try {
            for (layer in layers.asReversed()) {
                val previous = decodeLayer(layer, current)
                current.fill(0)
                current = previous
                layer.parameters.fill(0)
                if (current.size != layer.inputLength) invalidContainer()
            }
            return current.toString(Charsets.UTF_8)
        } finally {
            current.fill(0)
            layers.forEach { it.parameters.fill(0) }
        }
    }

    private fun decodeLayer(layer: LayerDescriptor, input: ByteArray): ByteArray =
        when (layer.method) {
            PipelineMethod.BIT_SHIFT -> input.unmaskAndRotate(layer.parameters.toInt())
            PipelineMethod.BIT_XOR -> xorStream(input, layer.parameters.toInt())
            PipelineMethod.BASE64 -> {
                val seed = layer.parameters.toInt()
                val unmasked = xorStream(input, seed xor BASE64_MASK)
                try {
                    decodeBase64(unmasked)
                } finally {
                    unmasked.fill(0)
                }
            }
            PipelineMethod.AES -> {
                val key = layer.parameters.copyOfRange(0, AES_KEY_BYTES)
                val iv = layer.parameters.copyOfRange(AES_KEY_BYTES, AES_KEY_BYTES + AES_IV_BYTES)
                try {
                    Cipher.getInstance(AES_TRANSFORMATION).run {
                        init(
                            Cipher.DECRYPT_MODE,
                            SecretKeySpec(key, "AES"),
                            IvParameterSpec(iv),
                        )
                        doFinal(input)
                    }
                } catch (_: Exception) {
                    invalidContainer()
                } finally {
                    key.fill(0)
                    iv.fill(0)
                }
            }
        }

    private fun ByteArray.unmaskAndRotate(seed: Int): ByteArray =
        ByteArray(size) { index ->
            val shift = streamByte(seed, index) % 7 + 1
            val rotated = (this[index].toInt() and 0xFF) xor streamByte(seed xor SHIFT_MASK, index)
            ((rotated ushr shift) or (rotated shl (8 - shift))).toByte()
        }

    private fun ByteArray.toInt(): Int {
        if (size != 4) invalidContainer()
        return ((this[0].toInt() and 0xFF) shl 24) or
            ((this[1].toInt() and 0xFF) shl 16) or
            ((this[2].toInt() and 0xFF) shl 8) or
            (this[3].toInt() and 0xFF)
    }

    private fun decodeBase64(encoded: ByteArray): ByteArray {
        if (encoded.isEmpty() || encoded.size % 4 != 0) invalidContainer()
        val padding = when {
            encoded[encoded.lastIndex] == '='.code.toByte() &&
                encoded[encoded.lastIndex - 1] == '='.code.toByte() -> 2
            encoded[encoded.lastIndex] == '='.code.toByte() -> 1
            else -> 0
        }
        val output = ByteArray(encoded.size / 4 * 3 - padding)
        var source = 0
        var target = 0
        while (source < encoded.size) {
            val a = base64Value(encoded[source++])
            val b = base64Value(encoded[source++])
            val cByte = encoded[source++]
            val dByte = encoded[source++]
            val c = if (cByte == '='.code.toByte()) 0 else base64Value(cByte)
            val d = if (dByte == '='.code.toByte()) 0 else base64Value(dByte)
            if (target < output.size) output[target++] = ((a shl 2) or (b ushr 4)).toByte()
            if (target < output.size) output[target++] = ((b shl 4) or (c ushr 2)).toByte()
            if (target < output.size) output[target++] = ((c shl 6) or d).toByte()
        }
        return output
    }

    private fun base64Value(byte: Byte): Int =
        when (val value = byte.toInt() and 0xFF) {
            in 'A'.code..'Z'.code -> value - 'A'.code
            in 'a'.code..'z'.code -> value - 'a'.code + 26
            in '0'.code..'9'.code -> value - '0'.code + 52
            '+'.code -> 62
            '/'.code -> 63
            else -> invalidContainer()
        }
}

private data class LayerDescriptor(
    val method: PipelineMethod,
    val inputLength: Int,
    val parameters: ByteArray,
)

private enum class PipelineMethod(
    val id: Int,
    val parameterBytes: Int,
) {
    BIT_SHIFT(1, 4),
    BIT_XOR(2, 4),
    BASE64(3, 4),
    AES(4, AES_KEY_BYTES + AES_IV_BYTES),
    ;

    companion object {
        fun fromId(id: Int): PipelineMethod =
            entries.firstOrNull { it.id == id } ?: invalidContainer()
    }
}

private class ByteCursor(private val bytes: ByteArray) {
    private var position: Int = 0
    val remaining: Int get() = bytes.size - position

    fun readByte(): Int {
        ensureAvailable(1)
        return bytes[position++].toInt() and 0xFF
    }

    fun readUnsignedShort(): Int = (readByte() shl 8) or readByte()

    fun readInt(): Int =
        (readByte() shl 24) or
            (readByte() shl 16) or
            (readByte() shl 8) or
            readByte()

    fun readBytes(length: Int): ByteArray {
        ensureAvailable(length)
        return bytes.copyOfRange(position, position + length).also { position += length }
    }

    private fun ensureAvailable(length: Int) {
        if (length < 0 || position > bytes.size - length) invalidContainer()
    }
}
