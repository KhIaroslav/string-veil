@file:OptIn(InternalStringVeilApi::class)

package io.github.khiaroslav.stringveil.runtime

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

/**
 * Opens the randomized outer container: validates the header, undoes the sparse permutation, runs
 * the inverse ARX block cipher, and verifies the checksum, returning the pipeline bytes. Inverse of
 * the compiler's `OuterContainerSealer`.
 */
internal object OuterContainer {
    fun open(container: IntArray): ByteArray {
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
            if (checksum(pipeline, seedA, seedB) != expectedChecksum) {
                pipeline.fill(0)
                invalidContainer()
            }
            return pipeline
        } finally {
            logical.fill(0)
            key.fill(0)
            decoded.fill(0)
        }
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
}

private val MIN_CONTAINER_WORDS = (METADATA_WORDS + 2) * 2 + CONTAINER_OVERHEAD
