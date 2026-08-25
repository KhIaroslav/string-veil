package io.github.khiaroslav.stringveil.runtime

/**
 * Runtime counterpart of String Veil's build-time cipher. Composes the two inverse stages:
 * [OuterContainer] opens the randomized envelope into pipeline bytes, and [PipelineDecoder] reverses
 * the layer stack into the original string.
 */
public object StringDecoder {
    /** Materializes one protected UTF-8 string and wipes temporary plaintext buffers. */
    @JvmStatic
    public fun decode(container: IntArray): String {
        val pipeline = OuterContainer.open(container)
        return try {
            PipelineDecoder.decode(pipeline)
        } finally {
            pipeline.fill(0)
        }
    }
}

/** Uniform failure for every malformed or tampered container across the decoder. */
internal fun invalidContainer(): Nothing = throw IllegalArgumentException("Invalid protected string")
