package io.github.khstov.stringveil.encoder

import io.github.khstov.stringveil.runtime.StringDecoder
import java.io.File
import java.util.Locale

/**
 * Standalone report (not a test gate) that quantifies the costs of obfuscation per protection config:
 * container-size overhead, the per-literal build-time **encode** cost, and the per-use runtime
 * **decode** cost. Run via the `:bytecode:benchmark` Gradle task, which writes the table to
 * `BENCHMARKS.md` at the repository root (falls back to stdout when the output path is unset).
 *
 * Size and overhead are exact. Encode and decode times are steady-state estimates (warmup +
 * best-of-3) meant for orientation, not precise microbenchmarks — compare before/after a change on
 * the same machine in one run; they are not comparable across machines or CI runners.
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

    val md = StringBuilder()
    md.appendLine("# String Veil benchmarks")
    md.appendLine()
    md.appendLine(
        "Regenerate with `./gradlew :bytecode:benchmark`. Size and overhead are exact; **encode** " +
            "(per-literal build cost) and **decode** (per-use runtime cost) are steady-state estimates " +
            "(warmup + best-of-3) and indicative only. Compare before/after a change on the same " +
            "machine in one run — the timings are not comparable across machines or CI runners.",
    )

    for ((sampleLabel, text) in samples) {
        val plaintext = text.encodeToByteArray()
        md.appendLine()
        md.appendLine("## $sampleLabel")
        md.appendLine()
        md.appendLine("| config | plain B | container B | overhead | encode | decode |")
        md.appendLine("|---|--:|--:|--:|--:|--:|")
        for ((configLabel, config) in configs) {
            val containers = (1..24).map {
                cipher.encrypt(plaintext, EncryptionContext("Benchmark.kt", it), config).container
            }
            val averageBytes = containers.map { it.size }.average() * 4
            val overhead = averageBytes / plaintext.size
            check(StringDecoder.decode(containers.first()) == text) { "decode mismatch for $configLabel" }

            val encodeNanos = measureEncodeNanos(cipher, plaintext, config)
            val decodeNanos = measureDecodeNanos(containers.first())

            md.appendLine(
                "| %s | %d | %.0f | %.1f× | %.0f ns | %.0f ns |".format(
                    Locale.ROOT, configLabel, plaintext.size, averageBytes, overhead, encodeNanos, decodeNanos,
                ),
            )
        }
    }
    md.appendLine()

    val outputPath = System.getProperty("stringVeil.benchmarkOutput")
    if (outputPath != null) {
        File(outputPath).writeText(md.toString())
        println("Wrote benchmark report to $outputPath")
    } else {
        print(md)
    }
}

/** Best-of-3 steady-state nanoseconds per `encrypt`, after a warmup, with a sink to defeat DCE. */
private fun measureEncodeNanos(
    cipher: LayeredStringCipher,
    plaintext: ByteArray,
    config: ProtectionConfig,
): Double {
    val warmup = 1_000
    val measured = 5_000
    var sink = 0L
    var index = 0
    repeat(warmup) {
        sink += cipher.encrypt(plaintext, EncryptionContext("Benchmark.kt", index++), config).container.size
    }
    var best = Double.MAX_VALUE
    repeat(3) {
        val start = System.nanoTime()
        repeat(measured) {
            sink += cipher.encrypt(plaintext, EncryptionContext("Benchmark.kt", index++), config).container.size
        }
        best = minOf(best, (System.nanoTime() - start).toDouble() / measured)
    }
    if (sink == Long.MIN_VALUE) print("") // keep `sink` observably used
    return best
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
    if (sink == Long.MIN_VALUE) print("") // keep `sink` observably used
    return best
}
