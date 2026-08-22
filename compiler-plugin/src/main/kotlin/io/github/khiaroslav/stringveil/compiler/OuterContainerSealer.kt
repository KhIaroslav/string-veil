@file:OptIn(InternalStringVeilApi::class)

package io.github.khiaroslav.stringveil.compiler

import io.github.khiaroslav.stringveil.format.InternalStringVeilApi
import io.github.khiaroslav.stringveil.format.StringVeilFormat.CONTAINER_OVERHEAD
import io.github.khiaroslav.stringveil.format.StringVeilFormat.DELTAS
import io.github.khiaroslav.stringveil.format.StringVeilFormat.HEADER_A
import io.github.khiaroslav.stringveil.format.StringVeilFormat.HEADER_B
import io.github.khiaroslav.stringveil.format.StringVeilFormat.HEADER_WORDS
import io.github.khiaroslav.stringveil.format.StringVeilFormat.KEY_WORDS
import io.github.khiaroslav.stringveil.format.StringVeilFormat.METADATA_WORDS
import io.github.khiaroslav.stringveil.format.StringVeilFormat.MIX_GOLDEN
import io.github.khiaroslav.stringveil.format.StringVeilFormat.ROUNDS
import io.github.khiaroslav.stringveil.format.StringVeilFormat.checksum
import io.github.khiaroslav.stringveil.format.StringVeilFormat.coprimeStep
import io.github.khiaroslav.stringveil.format.StringVeilFormat.headerCheck
import io.github.khiaroslav.stringveil.format.StringVeilFormat.keyMask
import io.github.khiaroslav.stringveil.format.StringVeilFormat.maskWord
import io.github.khiaroslav.stringveil.format.StringVeilFormat.mix32
import io.github.khiaroslav.stringveil.format.StringVeilFormat.outerVariant
import io.github.khiaroslav.stringveil.format.StringVeilFormat.roundFunction
import io.github.khiaroslav.stringveil.format.StringVeilFormat.storageMask
import java.security.SecureRandom

/**
 * Seals pipeline bytes into the randomized outer container: an ARX block cipher over the padded
 * body, masked key material and metadata, and a sparse coprime permutation into a decoy-filled
 * integer array. The inverse lives in the runtime `OuterContainer`.
 */
internal class OuterContainerSealer(private val random: SecureRandom) {
    fun seal(pipeline: ByteArray, context: EncryptionContext): IntArray {
        val seedA = random.nextInt() xor mix32(context.fileName.hashCode())
        val seedB = random.nextInt() xor mix32(context.startOffset)
        val variant = outerVariant(seedA, seedB)
        val key = IntArray(KEY_WORDS) { random.nextInt() }
        val padded = ByteArray(((pipeline.size + BLOCK_BYTES - 1) / BLOCK_BYTES) * BLOCK_BYTES)
            .also(random::nextBytes)
            .also { pipeline.copyInto(it) }
        val cipherWords = padded.toWords().also { encryptWords(it, key, variant) }

        val logical = IntArray(METADATA_WORDS + cipherWords.size)
        logical[0] = pipeline.size xor maskWord(seedA, seedB, -7, variant)
        logical[1] = checksum(pipeline, seedA, seedB) xor maskWord(seedA, seedB, -11, variant)
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
        return container
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

    private fun ByteArray.toWords(): IntArray =
        IntArray(size / 4) { wordIndex ->
            val offset = wordIndex * 4
            (this[offset].toInt() and 0xFF) or
                ((this[offset + 1].toInt() and 0xFF) shl 8) or
                ((this[offset + 2].toInt() and 0xFF) shl 16) or
                ((this[offset + 3].toInt() and 0xFF) shl 24)
        }
}

private const val BLOCK_BYTES = 8
