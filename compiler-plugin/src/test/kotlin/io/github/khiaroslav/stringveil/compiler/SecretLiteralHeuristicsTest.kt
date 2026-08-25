package io.github.khiaroslav.stringveil.compiler

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SecretLiteralHeuristicsTest {
    @Test
    fun `flags well-known secret shapes`() {
        val secrets = listOf(
            "AKIAIOSFODNN7EXAMPLE",
            "ghp_1234567890abcdefABCDEFghijklmnopqr",
            "AIzaSyA1234567890abcdefghijklmnopqrstuvw",
            "xoxb-123456789012-abcdefABCDEF",
            "-----BEGIN RSA PRIVATE KEY-----",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N",
        )
        for (secret in secrets) {
            assertNotNull(SecretLiteralHeuristics.detect(secret), "should flag: $secret")
        }
    }

    @Test
    fun `flags a long high-entropy token`() {
        assertNotNull(
            SecretLiteralHeuristics.detect("Zx9Kq2Lp7Wm4Rt6Yv1Bn8Cd3Fg5Hj0Sk2Ml4Pq6Rw8Tz"),
            "should flag a random high-entropy token",
        )
    }

    @Test
    fun `does not flag ordinary strings`() {
        val benign = listOf(
            "internal-value",
            "https://internal.example.com/v3/telemetry?token=abc",
            "the quick brown fox jumps over the lazy dog many times over",
            "feature.flag.new_checkout_enabled",
            "секретное-значение-строки",
            "x",
        )
        for (value in benign) {
            assertNull(SecretLiteralHeuristics.detect(value), "should not flag: $value")
        }
    }
}
