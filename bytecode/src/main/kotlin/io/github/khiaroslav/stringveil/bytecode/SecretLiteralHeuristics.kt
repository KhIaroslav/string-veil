package io.github.khiaroslav.stringveil.bytecode

import kotlin.math.ln

/**
 * Cheap, best-effort detection of string literals that look like real secrets.
 *
 * String Veil is obfuscation, not encryption or a secrets store (see SECURITY.md). Protecting a
 * genuine credential with it gives a false sense of safety, so the plugin warns when an annotated
 * literal matches a well-known secret shape or looks like a high-entropy token.
 *
 * These are heuristics: they may miss secrets and may occasionally flag a benign string. The signal
 * is a warning by default, escalated to an error only when the consumer opts in.
 */
internal object SecretLiteralHeuristics {
    private val NAMED_PATTERNS: List<Pair<String, Regex>> = listOf(
        "an AWS access key id" to Regex("(?:AKIA|ASIA|AGPA|AIDA|AROA|AIPA|ANPA|ANVA)[A-Z0-9]{16}"),
        "a GitHub token" to Regex("gh[pousr]_[A-Za-z0-9]{20,}"),
        "a Google API key" to Regex("AIza[0-9A-Za-z_-]{35}"),
        "a Slack token" to Regex("xox[baprs]-[A-Za-z0-9-]{10,}"),
        "a private key block" to Regex("-----BEGIN (?:[A-Z ]+ )?PRIVATE KEY-----"),
        "a JSON Web Token" to Regex("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"),
    )

    private const val MIN_ENTROPY_LENGTH = 40
    private const val MIN_ENTROPY_BITS = 4.2

    /** Returns a short human description of why the literal looks secret-like, or null. */
    fun detect(value: String): String? {
        for ((label, regex) in NAMED_PATTERNS) {
            if (regex.containsMatchIn(value)) return label
        }
        if (looksHighEntropy(value)) return "a high-entropy token"
        return null
    }

    private fun looksHighEntropy(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.length < MIN_ENTROPY_LENGTH) return false
        // Restrict to token-like charsets so prose, URLs, and paths are not flagged.
        if (!trimmed.all { it.isLetterOrDigit() || it in TOKEN_SYMBOLS }) return false
        return shannonEntropyBits(trimmed) >= MIN_ENTROPY_BITS
    }

    private fun shannonEntropyBits(value: String): Double {
        val counts = HashMap<Char, Int>()
        value.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        val length = value.length.toDouble()
        var entropy = 0.0
        for (count in counts.values) {
            val probability = count / length
            entropy -= probability * (ln(probability) / LN2)
        }
        return entropy
    }

    private const val TOKEN_SYMBOLS = "+/=_-"
    private val LN2 = ln(2.0)
}
