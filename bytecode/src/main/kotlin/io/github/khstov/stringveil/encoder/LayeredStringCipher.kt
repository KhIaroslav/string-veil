@file:OptIn(InternalStringVeilApi::class)

package io.github.khstov.stringveil.encoder

import io.github.khstov.stringveil.format.InternalStringVeilApi
import io.github.khstov.stringveil.format.StringVeilFormat.MAX_REPETITIONS
import java.security.SecureRandom

/**
 * Composes the two stages of the format: encode the plaintext through the [PipelineEncoder], then
 * seal the result with the [OuterContainerSealer]. The raw key and ciphertext are not emitted as
 * adjacent arrays. Temporary pipeline buffers are cleared on the successful path; callers remain
 * responsible for their input buffer, and immutable runtime strings cannot be wiped.
 */
public class LayeredStringCipher(
    random: SecureRandom = SecureRandom(),
) : StringCipher {
    private val pipelineEncoder = PipelineEncoder(random)
    private val sealer = OuterContainerSealer(random)

    override fun encrypt(
        value: ByteArray,
        context: EncryptionContext,
        config: ProtectionConfig,
    ): EncryptedString {
        require(value.isNotEmpty()) { "value must not be empty" }
        require(config.repetitions in 1..MAX_REPETITIONS) {
            "repetitions must be between 1 and $MAX_REPETITIONS"
        }

        val pipeline = pipelineEncoder.encode(value, config)
        return EncryptedString(sealer.seal(pipeline, context)).also { pipeline.fill(0) }
    }
}
