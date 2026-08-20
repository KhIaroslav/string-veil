package io.github.khiaroslav.stringveil.runtime

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Runtime counterpart of String Veil's build-time protected container. */
public object StringDecoder {
    /** Materializes one protected UTF-8 string and wipes temporary plaintext buffers. */
    @JvmStatic
    public fun decode(container: IntArray): String {
        if (
            container.size < MIN_CONTAINER_WORDS ||
            (container.size - CONTAINER_OVERHEAD) % 2 != 0
        ) {
            invalidContainer()
        }

        val seedA = container[0] xor HEADER_A
        val seedB = Integer.rotateRight(container[1] xor seedA, 13) xor HEADER_B
        if (container[2] != headerCheck(seedA, seedB, container.size)) invalidContainer()

        val variant = outerVariant(seedA, seedB)
        val logicalSize = (container.size - CONTAINER_OVERHEAD) / 2
        val bodySize = container.size - HEADER_WORDS
        val start = Math.floorMod(mix32(seedA + Integer.rotateLeft(seedB, 3)), bodySize)
        val step = coprimeStep(bodySize, seedA xor seedB)
        val logical = IntArray(logicalSize)
        val key = IntArray(KEY_WORDS)
        var decoded = ByteArray(0)

        try {
            repeat(logicalSize) { index ->
                val position = ((start.toLong() + index.toLong() * step) % bodySize).toInt()
                logical[index] = container[HEADER_WORDS + position] xor
                    storageMask(seedA, seedB, index, position, variant)
            }

            val length = logical[0] xor maskWord(seedA, seedB, -7, variant)
            val expectedChecksum = logical[1] xor maskWord(seedA, seedB, -11, variant)
            repeat(KEY_WORDS) { index ->
                key[index] = logical[2 + index] xor keyMask(seedA, seedB, index, variant)
            }

            val encryptedWordCount = logicalSize - METADATA_WORDS
            if (
                length <= 0 ||
                encryptedWordCount <= 0 ||
                encryptedWordCount % 2 != 0 ||
                length > encryptedWordCount * 4
            ) {
                invalidContainer()
            }

            val words = IntArray(encryptedWordCount)
            var chain = seedA xor Integer.rotateLeft(seedB, 7)
            repeat(encryptedWordCount) { index ->
                val word = logical[METADATA_WORDS + index] xor
                    Integer.rotateLeft(chain, (index + variant * 5) and 31) xor
                    maskWord(seedB, seedA, index + 37, variant)
                words[index] = word
                chain = word + MIX_GOLDEN
            }

            try {
                decryptWords(words, key, variant)
                decoded = words.toBytes()
            } finally {
                words.fill(0)
            }

            val pipeline = decoded.copyOf(length)
            return try {
                if (checksum(pipeline, seedA, seedB) != expectedChecksum) invalidContainer()
                decodePipeline(pipeline)
            } finally {
                pipeline.fill(0)
            }
        } finally {
            logical.fill(0)
            key.fill(0)
            decoded.fill(0)
        }
    }
}

private fun decodePipeline(pipeline: ByteArray): String {
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
        PipelineMethod.BIT_XOR -> input.xorStream(layer.parameters.toInt())
        PipelineMethod.BASE64 -> {
            val seed = layer.parameters.toInt()
            val unmasked = input.xorStream(seed xor BASE64_MASK)
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

private fun ByteArray.unmaskAndRotate(seed: Int): ByteArray =
    ByteArray(size) { index ->
        val shift = streamByte(seed, index) % 7 + 1
        val rotated = (this[index].toInt() and 0xFF) xor streamByte(seed xor SHIFT_MASK, index)
        ((rotated ushr shift) or (rotated shl (8 - shift))).toByte()
    }

private fun ByteArray.xorStream(seed: Int): ByteArray =
    ByteArray(size) { index ->
        (this[index].toInt() xor streamByte(seed, index)).toByte()
    }

private fun streamByte(seed: Int, index: Int): Int =
    mix32(seed + (index + 1) * MIX_GOLDEN + Integer.rotateLeft(index, index and 15)) ushr 24

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

private fun invalidContainer(): Nothing = throw IllegalArgumentException("Invalid protected string")

private const val MAX_REPETITIONS = 16
private const val PIPELINE_MAGIC = 0x53564C32
private const val PIPELINE_VERSION = 1
private const val AES_KEY_BYTES = 16
private const val AES_IV_BYTES = 16
private const val AES_TRANSFORMATION = "AES/CTR/NoPadding"
private const val BASE64_MASK = 0x346D2A11
private const val SHIFT_MASK = 0x51ED270B

private const val HEADER_WORDS = 4
private const val KEY_WORDS = 4
private const val METADATA_WORDS = 2 + KEY_WORDS
private const val CONTAINER_OVERHEAD = 7
private const val MIN_CONTAINER_WORDS = (METADATA_WORDS + 2) * 2 + CONTAINER_OVERHEAD
private const val HEADER_A = 0x6D2B79F5
private val HEADER_B = 0xA5B35705.toInt()
private const val HEADER_C = 0x7F4A7C15
private const val MIX_GOLDEN = -0x61C88647
private const val MIX_MURMUR_1 = -0x7A143595
private const val MIX_MURMUR_2 = -0x3D4D51CB
private const val MIX_ODD = 0x27D4EB2D

private fun outerVariant(seedA: Int, seedB: Int): Int =
    ((seedA xor Integer.rotateLeft(seedB, 9)) ushr 1) and 3

private fun headerCheck(seedA: Int, seedB: Int, size: Int): Int =
    mix32(seedA xor Integer.rotateLeft(seedB, 7) xor size) xor HEADER_C

private fun keyMask(seedA: Int, seedB: Int, index: Int, variant: Int): Int =
    maskWord(seedA, seedB, index * 19 + 5, variant) xor
        Integer.rotateLeft(seedB + MIX_ODD * (index + 1), index * 7 + 3)

private fun storageMask(
    seedA: Int,
    seedB: Int,
    index: Int,
    position: Int,
    variant: Int,
): Int =
    maskWord(
        seedA xor (position + 1) * MIX_MURMUR_2,
        seedB + (index + 1) * MIX_ODD,
        index xor position,
        variant,
    )

private fun maskWord(
    seedA: Int,
    seedB: Int,
    index: Int,
    variant: Int,
): Int {
    val base = mix32(seedA + index * MIX_GOLDEN) xor
        Integer.rotateLeft(seedB, (index * 7 + variant * 3) and 31)
    return when (variant) {
        0 -> mix32(base xor MIX_ODD)
        1 -> Integer.rotateLeft(mix32(base + MIX_MURMUR_1), 9)
        2 -> mix32(base xor Integer.rotateRight(seedA, (index + 11) and 31))
        else -> mix32(base + Integer.rotateLeft(seedB, (index + 17) and 31)) xor MIX_MURMUR_2
    }
}

private fun checksum(value: ByteArray, seedA: Int, seedB: Int): Int {
    var hash = seedA xor Integer.rotateLeft(seedB, 11) xor value.size
    value.forEach { byte ->
        hash = (hash xor (byte.toInt() and 0xFF)) * 0x01000193
        hash = Integer.rotateLeft(hash, 5) + MIX_GOLDEN
    }
    return mix32(hash)
}

private fun coprimeStep(modulus: Int, seed: Int): Int {
    var candidate = Math.floorMod(mix32(seed), modulus - 1) + 1
    while (gcd(candidate, modulus) != 1) {
        candidate = candidate % (modulus - 1) + 1
    }
    return candidate
}

private fun gcd(left: Int, right: Int): Int {
    var a = left
    var b = right
    while (b != 0) {
        val remainder = a % b
        a = b
        b = remainder
    }
    return a
}

private fun mix32(input: Int): Int {
    var value = input
    value = (value xor (value ushr 16)) * MIX_MURMUR_1
    value = (value xor (value ushr 13)) * MIX_MURMUR_2
    return value xor (value ushr 16)
}

private fun decryptWords(words: IntArray, key: IntArray, variant: Int) {
    val delta = DELTAS[variant]
    val rounds = ROUNDS[variant]
    var offset = 0
    while (offset < words.size) {
        var left = words[offset]
        var right = words[offset + 1]
        var sum = delta * rounds
        repeat(rounds) {
            right -= roundFunction(
                left,
                sum,
                key[(((sum ushr 11) and 3) xor ((variant + 1) and 3)) and 3],
                variant,
            )
            sum -= delta
            left -= roundFunction(
                right,
                sum,
                key[((sum and 3) xor variant) and 3],
                variant,
            )
        }
        words[offset] = left
        words[offset + 1] = right
        offset += 2
    }
}

private fun roundFunction(value: Int, sum: Int, key: Int, variant: Int): Int =
    when (variant) {
        0 -> (((value shl 4) xor (value ushr 5)) + value) xor (sum + key)
        1 -> (((value shl 5) xor (value ushr 3)) + Integer.rotateLeft(value, 1)) xor
            (sum + key)
        2 -> (Integer.rotateLeft(value, 4) + (value xor (value ushr 7))) xor
            (sum + key)
        else -> ((Integer.rotateLeft(value, 3) xor Integer.rotateRight(value, 6)) + value) xor
            (sum + key)
    }

private fun IntArray.toBytes(): ByteArray =
    ByteArray(size * 4).also { bytes ->
        forEachIndexed { wordIndex, word ->
            val offset = wordIndex * 4
            bytes[offset] = word.toByte()
            bytes[offset + 1] = (word ushr 8).toByte()
            bytes[offset + 2] = (word ushr 16).toByte()
            bytes[offset + 3] = (word ushr 24).toByte()
        }
    }

private val DELTAS = intArrayOf(
    MIX_GOLDEN,
    0x7F4A7C15,
    0x6A09E667,
    0xBB67AE85.toInt(),
)
private val ROUNDS = intArrayOf(32, 36, 40, 44)
