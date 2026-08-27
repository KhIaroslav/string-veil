package io.github.khiaroslav.stringveil.annotations

/** Available build-time transformations. */
public enum class ObfuscationMethod {
    BIT_SHIFT,
    BIT_XOR,
    BASE64,
    AES,
    RANDOM_ALL,
    RANDOM_SELECTED,
}

/** Selects where the protected container is decoded. */
public enum class ObfuscationEngine {
    /** Uses JNI for Android builds and the JVM runtime elsewhere. */
    AUTO,

    /** Uses the portable Kotlin/JVM decoder. */
    JVM,

    /** Uses the Android native library. Only valid for Android compilations. */
    NATIVE,
}

/**
 * Marks a declaration or string expression whose string literals must be obfuscated.
 *
 * [RANDOM_ALL] selects a fresh method for every repetition. [RANDOM_SELECTED] selects only from
 * [methods]. Every result is additionally wrapped in String Veil's hardened outer container.
 */
@MustBeDocumented
// BINARY so the marker survives to the compiled class, where the bytecode transform reads it.
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
)
public annotation class Obfuscate(
    public val method: ObfuscationMethod = ObfuscationMethod.RANDOM_ALL,
    public val methods: Array<ObfuscationMethod> = [],
    public val repetitions: Int = 3,
    public val engine: ObfuscationEngine = ObfuscationEngine.AUTO,
)
