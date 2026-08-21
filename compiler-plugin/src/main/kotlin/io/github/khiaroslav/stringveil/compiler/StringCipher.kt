@file:OptIn(InternalStringVeilApi::class)

package io.github.khiaroslav.stringveil.compiler

import io.github.khiaroslav.stringveil.format.InternalStringVeilApi
import io.github.khiaroslav.stringveil.format.StringVeilFormat.AES_IV_BYTES
import io.github.khiaroslav.stringveil.format.StringVeilFormat.AES_KEY_BYTES
import io.github.khiaroslav.stringveil.format.StringVeilFormat.AES_TRANSFORMATION
import io.github.khiaroslav.stringveil.format.StringVeilFormat.BASE64_MASK
import io.github.khiaroslav.stringveil.format.StringVeilFormat.CONTAINER_OVERHEAD
import io.github.khiaroslav.stringveil.format.StringVeilFormat.DELTAS
import io.github.khiaroslav.stringveil.format.StringVeilFormat.HEADER_A
import io.github.khiaroslav.stringveil.format.StringVeilFormat.HEADER_B
import io.github.khiaroslav.stringveil.format.StringVeilFormat.HEADER_WORDS
import io.github.khiaroslav.stringveil.format.StringVeilFormat.KEY_WORDS
import io.github.khiaroslav.stringveil.format.StringVeilFormat.MAX_REPETITIONS
import io.github.khiaroslav.stringveil.format.StringVeilFormat.METADATA_WORDS
import io.github.khiaroslav.stringveil.format.StringVeilFormat.MIX_GOLDEN
import io.github.khiaroslav.stringveil.format.StringVeilFormat.PIPELINE_MAGIC
import io.github.khiaroslav.stringveil.format.StringVeilFormat.PIPELINE_VERSION
import io.github.khiaroslav.stringveil.format.StringVeilFormat.ROUNDS
import io.github.khiaroslav.stringveil.format.StringVeilFormat.SHIFT_MASK
import io.github.khiaroslav.stringveil.format.StringVeilFormat.checksum
import io.github.khiaroslav.stringveil.format.StringVeilFormat.coprimeStep
import io.github.khiaroslav.stringveil.format.StringVeilFormat.headerCheck
import io.github.khiaroslav.stringveil.format.StringVeilFormat.keyMask
import io.github.khiaroslav.stringveil.format.StringVeilFormat.maskWord
import io.github.khiaroslav.stringveil.format.StringVeilFormat.mix32
import io.github.khiaroslav.stringveil.format.StringVeilFormat.outerVariant
import io.github.khiaroslav.stringveil.format.StringVeilFormat.roundFunction
import io.github.khiaroslav.stringveil.format.StringVeilFormat.storageMask
import io.github.khiaroslav.stringveil.format.StringVeilFormat.streamByte
import io.github.khiaroslav.stringveil.format.StringVeilFormat.xorStream
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
 *
 * The direction-agnostic format primitives (masking, hashing, the ARX round function) come from the
 * shared [io.github.khiaroslav.stringveil.format.StringVeilFormat]; only the forward operations live
 * here.
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

    private fun ByteArray.rotateAndMask(seed: Int): ByteArray =
        ByteArray(size) { index ->
            val shift = streamByte(seed, index) % 7 + 1
            val rotated = ((this[index].toInt() and 0xFF) shl shift) or
                ((this[index].toInt() and 0xFF) ushr (8 - shift))
            (rotated xor streamByte(seed xor SHIFT_MASK, index)).toByte()
        }

    private fun ByteArray.toWords(): IntArray =
        IntArray(size / 4) { wordIndex ->
            val offset = wordIndex * 4
            (this[offset].toInt() and 0xFF) or
                ((this[offset + 1].toInt() and 0xFF) shl 8) or
                ((this[offset + 2].toInt() and 0xFF) shl 16) or
                ((this[offset + 3].toInt() and 0xFF) shl 24)
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

private const val DEFAULT_REPETITIONS = 3
private const val BLOCK_BYTES = 8
private val PIPELINE_METHODS = PipelineMethod.entries
