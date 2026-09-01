@file:OptIn(InternalStringVeilApi::class)

package io.github.khstov.stringveil.encoder

import io.github.khstov.stringveil.format.InternalStringVeilApi
import io.github.khstov.stringveil.format.StringVeilFormat.MAX_REPETITIONS
import java.security.SecureRandom
import java.util.Random

/**
 * Composes the two stages of the format: encode the plaintext through the [PipelineEncoder], then
 * seal the result with the [OuterContainerSealer]. The raw key and ciphertext are not emitted as
 * adjacent arrays. Temporary pipeline buffers are cleared on the successful path; callers remain
 * responsible for their input buffer, and immutable runtime strings cannot be wiped.
 *
 * When [seed] is null the randomness comes from a shared [SecureRandom], so every build produces a
 * different container (maximum diversity). When [seed] is set, each literal draws from a `Random`
 * seeded by `(seed, fileName, startOffset)`, so the output is a deterministic function of the source
 * and independent of the order literals are processed — enabling reproducible and cacheable builds.
 */
public class LayeredStringCipher(
    private val random: Random = SecureRandom(),
    private val seed: Long? = null,
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

        val callRandom = seed?.let { Random(deriveSeed(it, context)) } ?: random
        val pipeline = PipelineEncoder(callRandom).encode(value, config)
        return EncryptedString(OuterContainerSealer(callRandom).seal(pipeline, context))
            .also { pipeline.fill(0) }
    }

    /** A per-literal seed: stable for a given source position, distinct across positions. */
    private fun deriveSeed(userSeed: Long, context: EncryptionContext): Long {
        var hash = userSeed
        hash = hash * FNV_PRIME xor context.fileName.hashCode().toLong()
        hash = hash * FNV_PRIME xor context.startOffset.toLong()
        return hash
    }

    private companion object {
        const val FNV_PRIME = 0x100000001b3L
    }
}
