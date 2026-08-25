package io.github.khiaroslav.stringveil.format

/**
 * The single source of truth for String Veil's container-format constants and mixing primitives,
 * shared byte-for-byte between the build-time cipher (`compiler-plugin`) and the JVM decoder
 * (`runtime`). The native C++ decoder mirrors these definitions by hand; the differential test in
 * `:native-differential` guards that mirror.
 *
 * Only the direction-agnostic parts live here (constants, hashing/masking, the ARX round function).
 * The forward operations (sealing, encryption, layer encoding) and their inverses live with the
 * cipher and the decoder respectively.
 */
@InternalStringVeilApi
public object StringVeilFormat {
    public const val MAX_REPETITIONS: Int = 16

    public const val PIPELINE_MAGIC: Int = 0x53564C32
    public const val PIPELINE_VERSION: Int = 1

    public const val AES_KEY_BYTES: Int = 16
    public const val AES_IV_BYTES: Int = 16
    public const val AES_TRANSFORMATION: String = "AES/CTR/NoPadding"

    public const val BASE64_MASK: Int = 0x346D2A11
    public const val SHIFT_MASK: Int = 0x51ED270B

    public const val HEADER_WORDS: Int = 4
    public const val KEY_WORDS: Int = 4
    public const val METADATA_WORDS: Int = 2 + KEY_WORDS
    public const val CONTAINER_OVERHEAD: Int = 7

    public const val HEADER_A: Int = 0x6D2B79F5
    public val HEADER_B: Int = 0xA5B35705.toInt()
    public const val HEADER_C: Int = 0x7F4A7C15

    public const val MIX_GOLDEN: Int = -0x61C88647
    public const val MIX_MURMUR_1: Int = -0x7A143595
    public const val MIX_MURMUR_2: Int = -0x3D4D51CB
    public const val MIX_ODD: Int = 0x27D4EB2D

    public val DELTAS: IntArray = intArrayOf(
        MIX_GOLDEN,
        0x7F4A7C15,
        0x6A09E667,
        0xBB67AE85.toInt(),
    )
    public val ROUNDS: IntArray = intArrayOf(32, 36, 40, 44)

    public fun mix32(input: Int): Int {
        var value = input
        value = (value xor (value ushr 16)) * MIX_MURMUR_1
        value = (value xor (value ushr 13)) * MIX_MURMUR_2
        return value xor (value ushr 16)
    }

    public fun outerVariant(seedA: Int, seedB: Int): Int =
        ((seedA xor Integer.rotateLeft(seedB, 9)) ushr 1) and 3

    public fun headerCheck(seedA: Int, seedB: Int, size: Int): Int =
        mix32(seedA xor Integer.rotateLeft(seedB, 7) xor size) xor HEADER_C

    public fun keyMask(seedA: Int, seedB: Int, index: Int, variant: Int): Int =
        maskWord(seedA, seedB, index * 19 + 5, variant) xor
            Integer.rotateLeft(seedB + MIX_ODD * (index + 1), index * 7 + 3)

    public fun storageMask(
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

    public fun maskWord(seedA: Int, seedB: Int, index: Int, variant: Int): Int {
        val base = mix32(seedA + index * MIX_GOLDEN) xor
            Integer.rotateLeft(seedB, (index * 7 + variant * 3) and 31)
        return when (variant) {
            0 -> mix32(base xor MIX_ODD)
            1 -> Integer.rotateLeft(mix32(base + MIX_MURMUR_1), 9)
            2 -> mix32(base xor Integer.rotateRight(seedA, (index + 11) and 31))
            else -> mix32(base + Integer.rotateLeft(seedB, (index + 17) and 31)) xor MIX_MURMUR_2
        }
    }

    public fun checksum(value: ByteArray, seedA: Int, seedB: Int): Int {
        var hash = seedA xor Integer.rotateLeft(seedB, 11) xor value.size
        value.forEach { byte ->
            hash = (hash xor (byte.toInt() and 0xFF)) * 0x01000193
            hash = Integer.rotateLeft(hash, 5) + MIX_GOLDEN
        }
        return mix32(hash)
    }

    public fun coprimeStep(modulus: Int, seed: Int): Int {
        var candidate = Math.floorMod(mix32(seed), modulus - 1) + 1
        while (gcd(candidate, modulus) != 1) {
            candidate = candidate % (modulus - 1) + 1
        }
        return candidate
    }

    public fun gcd(left: Int, right: Int): Int {
        var a = left
        var b = right
        while (b != 0) {
            val remainder = a % b
            a = b
            b = remainder
        }
        return a
    }

    public fun streamByte(seed: Int, index: Int): Int =
        mix32(seed + (index + 1) * MIX_GOLDEN + Integer.rotateLeft(index, index and 15)) ushr 24

    public fun xorStream(bytes: ByteArray, seed: Int): ByteArray =
        ByteArray(bytes.size) { index ->
            (bytes[index].toInt() xor streamByte(seed, index)).toByte()
        }

    public fun roundFunction(value: Int, sum: Int, key: Int, variant: Int): Int =
        when (variant) {
            0 -> (((value shl 4) xor (value ushr 5)) + value) xor (sum + key)
            1 -> (((value shl 5) xor (value ushr 3)) + Integer.rotateLeft(value, 1)) xor
                (sum + key)
            2 -> (Integer.rotateLeft(value, 4) + (value xor (value ushr 7))) xor
                (sum + key)
            else -> ((Integer.rotateLeft(value, 3) xor Integer.rotateRight(value, 6)) + value) xor
                (sum + key)
        }
}
