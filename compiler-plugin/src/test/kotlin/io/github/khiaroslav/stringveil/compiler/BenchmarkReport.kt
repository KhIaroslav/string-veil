package io.github.khiaroslav.stringveil.compiler

import io.github.khiaroslav.stringveil.runtime.StringDecoder
import java.util.Locale

/**
 * Standalone report (not a test gate) that quantifies the two costs of obfuscation: how much bigger
 * a protected container is than its plaintext, and how long the runtime decoder takes to materialize
 * it. Run via the `:compiler-plugin:benchmark` Gradle task.
 *
 * The size figures are exact; the timing figures are a rough steady-state estimate (warmup + timed
 * loop, best of a few rounds) meant for orientation, not a precise microbenchmark.
 */
fun main() {
    val cipher = LayeredStringCipher()

    val samples = listOf(
        "short (14 B)" to "internal-value",
        "url (54 B)" to "https://internal.example.com/v3/telemetry?token=abcdef",
        "long (2 KB)" to buildString { repeat(256) { append("payload-").append(it % 10) } },
    )
    val configs = listOf(
        "BIT_SHIFT x1" to ProtectionConfig(ProtectionMethod.BIT_SHIFT, repetitions = 1),
        "BIT_XOR x1" to ProtectionConfig(ProtectionMethod.BIT_XOR, repetitions = 1),
        "BASE64 x1" to ProtectionConfig(ProtectionMethod.BASE64, repetitions = 1),
        "AES x1" to ProtectionConfig(ProtectionMethod.AES, repetitions = 1),
        "RANDOM_ALL x3" to ProtectionConfig(ProtectionMethod.RANDOM_ALL, repetitions = 3),
        "RANDOM_ALL x8" to ProtectionConfig(ProtectionMethod.RANDOM_ALL, repetitions = 8),
    )

    println("String Veil — size overhead and decode cost")
    println("(size is exact; decode time is a rough steady-state estimate)")
    println()
    println(
        "%-14s | %-14s | %8s | %11s | %9s | %10s".format(
            Locale.ROOT, "sample", "config", "plain B", "container B", "overhead", "decode",
        ),
    )
    println("-".repeat(80))

    for ((sampleLabel, text) in samples) {
        val plaintextBytes = text.encodeToByteArray().size
        for ((configLabel, config) in configs) {
            val containers = (1..24).map {
                cipher.encrypt(
                    text.encodeToByteArray(),
                    EncryptionContext("Benchmark.kt", it),
                    config,
                ).container
            }
            val averageBytes = containers.map { it.size }.average() * 4
            val overhead = averageBytes / plaintextBytes

            val container = containers.first()
            check(StringDecoder.decode(container) == text) { "decode mismatch for $configLabel" }
            val nanos = measureDecodeNanos(container)

            println(
                "%-14s | %-14s | %8d | %11.0f | %8.1f× | %8.0f ns".format(
                    Locale.ROOT, sampleLabel, configLabel, plaintextBytes, averageBytes, overhead, nanos,
                ),
            )
        }
        println("-".repeat(80))
    }
}

/** Best-of-3 steady-state nanoseconds per decode, after a warmup, with a sink to defeat DCE. */
private fun measureDecodeNanos(container: IntArray): Double {
    val warmup = 10_000
    val measured = 100_000
    var sink = 0L
    repeat(warmup) { sink += StringDecoder.decode(container).length }

    var best = Double.MAX_VALUE
    repeat(3) {
        val start = System.nanoTime()
        repeat(measured) { sink += StringDecoder.decode(container).length }
        val elapsed = System.nanoTime() - start
        best = minOf(best, elapsed.toDouble() / measured)
    }
    if (sink == Long.MIN_VALUE) println("") // keep `sink` observably used
    return best
}
