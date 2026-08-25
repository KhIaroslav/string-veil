package io.github.khiaroslav.stringveil.compiler

/** Build-time contract: turn a plaintext into a protected integer container. */
internal interface StringCipher {
    fun encrypt(
        value: ByteArray,
        context: EncryptionContext,
        config: ProtectionConfig,
    ): EncryptedString
}

/** Per-literal context that seeds the outer envelope (so identical strings differ per site). */
internal data class EncryptionContext(
    val fileName: String,
    val startOffset: Int,
)

/** The protected container that replaces the original literal in the generated code. */
internal data class EncryptedString(
    val container: IntArray,
)

/** Resolved obfuscation configuration for a single literal. */
internal data class ProtectionConfig(
    val method: ProtectionMethod = ProtectionMethod.RANDOM_ALL,
    val methods: Set<ProtectionMethod> = emptySet(),
    val repetitions: Int = DEFAULT_REPETITIONS,
    val engine: ProtectionEngine = ProtectionEngine.AUTO,
)

internal enum class ProtectionEngine {
    AUTO,
    JVM,
    NATIVE,
}

internal enum class ProtectionMethod {
    BIT_SHIFT,
    BIT_XOR,
    BASE64,
    AES,
    RANDOM_ALL,
    RANDOM_SELECTED,
}

internal const val DEFAULT_REPETITIONS: Int = 3
