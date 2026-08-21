package io.github.khiaroslav.stringveil.compiler

import io.github.khiaroslav.stringveil.runtime.StringDecoder
import java.io.BufferedOutputStream
import java.io.File
import java.security.SecureRandom

/**
 * Standalone entry point that produces the differential corpus consumed by the `native-differential`
 * module. It encodes every [DifferentialCorpus] case with [LayeredStringCipher], self-checks each
 * container with the JVM [StringDecoder] (so a broken corpus fails here, loudly), and serializes the
 * `[label, plaintext, container]` triples to the path given as the first argument.
 *
 * Run via the `:compiler-plugin:generateDifferentialCorpus` Gradle task, which puts the compiler and
 * runtime classes on the classpath.
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: DifferentialCorpusGenerator <output-file>" }
    val output = File(args[0])
    output.parentFile?.mkdirs()

    val cipher = LayeredStringCipher()
    val random = SecureRandom()
    val cases = DifferentialCorpus.deterministicCases() +
        DifferentialCorpus.randomCases(count = 200, random = random)

    val entries = cases.map { case ->
        val container = cipher.encrypt(
            value = case.plaintext.toByteArray(Charsets.UTF_8),
            context = EncryptionContext(
                fileName = "${case.label}.kt",
                startOffset = case.label.length,
            ),
            config = case.config,
        ).container

        // Self-check: the JVM decoder must already agree, otherwise the corpus is meaningless.
        val jvm = StringDecoder.decode(container)
        check(jvm == case.plaintext) {
            "JVM decode disagreed while building corpus for ${case.label}"
        }
        case to container
    }

    BufferedOutputStream(output.outputStream()).use { stream ->
        DifferentialCorpus.write(stream, entries)
    }
    println("Wrote ${entries.size} differential cases to ${output.absolutePath}")
}
