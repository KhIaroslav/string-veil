package io.github.khstov.stringveil.runtime

import kotlin.test.Test
import kotlin.test.assertFailsWith

class StringDecoderTest {
    @Test
    fun `rejects an empty container`() {
        assertFailsWith<IllegalArgumentException> {
            StringDecoder.decode(intArrayOf())
        }
    }

    @Test
    fun `rejects a structurally invalid container`() {
        assertFailsWith<IllegalArgumentException> {
            StringDecoder.decode(IntArray(23))
        }
    }
}
