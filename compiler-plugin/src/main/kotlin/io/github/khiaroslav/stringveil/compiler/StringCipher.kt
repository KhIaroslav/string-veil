package io.github.khiaroslav.stringveil.compiler

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal interface StringCipher {
    fun encrypt(
        value: ByteArray,
        context: EncryptionContext,
        config: ProtectionConfig,
    ): EncryptedString
}

internal data class EncryptionContext(
    val fileName: String,
    val startOffset: Int,
)

internal data class EncryptedString(
    val container: IntArray,
)

internal data class ProtectionConfig(
    val method: ProtectionMethod = ProtectionMethod.RANDOM_ALL,
    val methods: Set<ProtectionMethod> = emptySet(),
    val repetitions: Int = DEFAULT_REPETITIONS,
    val engine: ProtectionEngine = ProtectionEngine.AUTO,
)

internal enum class ProtectionEngine {
    AUTO,
    JVM,
    NATIVE,
}

internal enum class ProtectionMethod {
    BIT_SHIFT,
    BIT_XOR,
    BASE64,
    AES,
    RANDOM_ALL,
    RANDOM_SELECTED,
}

/**
 * Builds a configurable transformation pipeline, then seals it in a randomized outer envelope.
 * The raw key and ciphertext are never emitted as adjacent arrays.
 */
internal class LayeredStringCipher(
    private val random: SecureRandom = SecureRandom(),
) : StringCipher {
    override fun encrypt(
        value: ByteArray,
        context: EncryptionContext,
        config: ProtectionConfig,
    ): EncryptedString {
        require(value.isNotEmpty()) { "value must not be empty" }
        require(config.repetitions in 1..MAX_REPETITIONS) {
            "repetitions must be between 1 and $MAX_REPETITIONS"
        }

        val pipeline = encodePipeline(value, config)
        return seal(
            value = pipeline,
            context = context,
        ).also { pipeline.fill(0) }
    }

    private fun encodePipeline(value: ByteArray, config: ProtectionConfig): ByteArray {
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
                EncodedLayer(method, input.size, seed.toBytes(), input.xorStream(seed))
            }
            PipelineMethod.BASE64 -> {
                val seed = random.nextInt()
                val encoded = Base64.getEncoder().encode(input)
                EncodedLayer(method, input.size, seed.toBytes(), encoded.xorStream(seed xor BASE64_MASK))
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

    private fun seal(value: ByteArray, context: EncryptionContext): EncryptedString {
        val seedA = random.nextInt() xor mix32(context.fileName.hashCode())
        val seedB = random.nextInt() xor mix32(context.startOffset)
        val variant = outerVariant(seedA, seedB)
        val key = IntArray(KEY_WORDS) { random.nextInt() }
        val padded = ByteArray(((value.size + BLOCK_BYTES - 1) / BLOCK_BYTES) * BLOCK_BYTES)
            .also(random::nextBytes)
            .also { value.copyInto(it) }
        val cipherWords = padded.toWords().also { encryptWords(it, key, variant) }

        val logical = IntArray(METADATA_WORDS + cipherWords.size)
        logical[0] = value.size xor maskWord(seedA, seedB, -7, variant)
        logical[1] = checksum(value, seedA, seedB) xor maskWord(seedA, seedB, -11, variant)
        repeat(KEY_WORDS) { index ->
            logical[2 + index] = key[index] xor keyMask(seedA, seedB, index, variant)
        }

        var chain = seedA xor Integer.rotateLeft(seedB, 7)
        cipherWords.forEachIndexed { index, word ->
            logical[METADATA_WORDS + index] =
                word xor
                    Integer.rotateLeft(chain, (index + variant * 5) and 31) xor
                    maskWord(seedB, seedA, index + 37, variant)
            chain = word + MIX_GOLDEN
        }

        val container = IntArray(logical.size * 2 + CONTAINER_OVERHEAD) { random.nextInt() }
        container[0] = seedA xor HEADER_A
        container[1] = Integer.rotateLeft(seedB xor HEADER_B, 13) xor seedA
        container[2] = headerCheck(seedA, seedB, container.size)

        val bodySize = container.size - HEADER_WORDS
        val start = Math.floorMod(mix32(seedA + Integer.rotateLeft(seedB, 3)), bodySize)
        val step = coprimeStep(bodySize, seedA xor seedB)
        logical.forEachIndexed { index, word ->
            val position = ((start.toLong() + index.toLong() * step) % bodySize).toInt()
            container[HEADER_WORDS + position] =
                word xor storageMask(seedA, seedB, index, position, variant)
        }

        key.fill(0)
        padded.fill(0)
        cipherWords.fill(0)
        logical.fill(0)
        return EncryptedString(container)
    }
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

private fun ByteArray.rotateAndMask(seed: Int): ByteArray =
    ByteArray(size) { index ->
        val shift = streamByte(seed, index) % 7 + 1
        val rotated = ((this[index].toInt() and 0xFF) shl shift) or
            ((this[index].toInt() and 0xFF) ushr (8 - shift))
        (rotated xor streamByte(seed xor SHIFT_MASK, index)).toByte()
    }

private fun ByteArray.xorStream(seed: Int): ByteArray =
    ByteArray(size) { index ->
        (this[index].toInt() xor streamByte(seed, index)).toByte()
    }

private fun streamByte(seed: Int, index: Int): Int =
    mix32(seed + (index + 1) * MIX_GOLDEN + Integer.rotateLeft(index, index and 15)) ushr 24

private fun Int.toBytes(): ByteArray = byteArrayOf(
    (this ushr 24).toByte(),
    (this ushr 16).toByte(),
    (this ushr 8).toByte(),
    toByte(),
)

private const val DEFAULT_REPETITIONS = 3
internal const val MAX_REPETITIONS = 16
private const val PIPELINE_MAGIC = 0x53564C32
private const val PIPELINE_VERSION = 1
private const val AES_KEY_BYTES = 16
private const val AES_IV_BYTES = 16
private const val AES_TRANSFORMATION = "AES/CTR/NoPadding"
private const val BASE64_MASK = 0x346D2A11
private const val SHIFT_MASK = 0x51ED270B
private val PIPELINE_METHODS = PipelineMethod.entries

private const val HEADER_WORDS = 4
private const val KEY_WORDS = 4
private const val METADATA_WORDS = 2 + KEY_WORDS
private const val BLOCK_BYTES = 8
private const val CONTAINER_OVERHEAD = 7
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

private fun ByteArray.toWords(): IntArray =
    IntArray(size / 4) { wordIndex ->
        val offset = wordIndex * 4
        (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or
            ((this[offset + 3].toInt() and 0xFF) shl 24)
    }

private fun encryptWords(words: IntArray, key: IntArray, variant: Int) {
    val delta = DELTAS[variant]
    val rounds = ROUNDS[variant]
    var offset = 0
    while (offset < words.size) {
        var left = words[offset]
        var right = words[offset + 1]
        var sum = 0
        repeat(rounds) {
            left += roundFunction(
                right,
                sum,
                key[((sum and 3) xor variant) and 3],
                variant,
            )
            sum += delta
            right += roundFunction(
                left,
                sum,
                key[(((sum ushr 11) and 3) xor ((variant + 1) and 3)) and 3],
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

private val DELTAS = intArrayOf(
    MIX_GOLDEN,
    0x7F4A7C15,
    0x6A09E667,
    0xBB67AE85.toInt(),
)
private val ROUNDS = intArrayOf(32, 36, 40, 44)
