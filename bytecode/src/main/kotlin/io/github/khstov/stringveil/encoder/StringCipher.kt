package io.github.khstov.stringveil.encoder

/** Build-time contract: turn a plaintext into a protected integer container. */
public interface StringCipher {
    fun encrypt(
        value: ByteArray,
        context: EncryptionContext,
        config: ProtectionConfig,
    ): EncryptedString
}

/** Per-literal context that seeds the outer envelope (so identical strings differ per site). */
public data class EncryptionContext(
    val fileName: String,
    val startOffset: Int,
)

/** The protected container that replaces the original literal in the generated code. */
public data class EncryptedString(
    val container: IntArray,
)

/** Resolved obfuscation configuration for a single literal. */
public data class ProtectionConfig(
    val method: ProtectionMethod = ProtectionMethod.RANDOM_ALL,
    val methods: Set<ProtectionMethod> = emptySet(),
    val repetitions: Int = DEFAULT_REPETITIONS,
    val engine: ProtectionEngine = ProtectionEngine.AUTO,
)

public enum class ProtectionEngine {
    AUTO,
    JVM,
    NATIVE,
}

public enum class ProtectionMethod {
    BIT_SHIFT,
    BIT_XOR,
    BASE64,
    AES,
    RANDOM_ALL,
    RANDOM_SELECTED,
}

public const val DEFAULT_REPETITIONS: Int = 3
