package io.github.khiaroslav.stringveil.androidtest

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.khiaroslav.stringveil.runtime.NativeStringDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a real Android runtime (an emulator in CI). This covers what the host-only differential
 * test cannot: whether the JNI shared library loads for the device's ABI
 * and decodes correctly, rather than silently degrading to the JVM fallback.
 */
@RunWith(AndroidJUnit4::class)
class NativeDecodeInstrumentedTest {
    @Test
    fun nativeLibraryLoadsAndDecodes() {
        assertTrue(
            "native library did not load for this ABI — @Obfuscate would fall back to the JVM decoder",
            NativeStringDecoder.isNativeAvailable(),
        )
        // The literal was rewritten to a NativeStringDecoder.decode(...) call; assert it round-trips.
        assertEquals(
            "https://internal.example.com/native-secret",
            ObfuscatedSecrets.endpoint,
        )
    }
}
