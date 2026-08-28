package io.github.khiaroslav.stringveil.annotations

/**
 * Transformation identifiers reserved by the annotation API.
 *
 * The current bytecode transform uses its default randomized pipeline and does not read the
 * per-annotation value yet.
 */
public enum class ObfuscationMethod {
    BIT_SHIFT,
    BIT_XOR,
    BASE64,
    AES,
    RANDOM_ALL,
    RANDOM_SELECTED,
}

/**
 * Decoder preferences reserved by the annotation API.
 *
 * The current Gradle integration selects the decoder from the project type and does not read this
 * per-annotation value yet.
 */
public enum class ObfuscationEngine {
    /** Intended to use JNI for Android builds and the JVM runtime elsewhere. */
    AUTO,

    /** Intended to request the portable Kotlin/JVM decoder. */
    JVM,

    /** Intended to request the Android native library. */
    NATIVE,
}

/**
 * Marks a declaration whose directly emitted string constants are candidates for obfuscation.
 *
 * The current bytecode transform supports direct constants in specific class, function, property,
 * and field bytecode shapes. Compiler-generated lambdas, nested classes, custom getters, and complex
 * property initializers may not belong to the annotated declaration in bytecode; see the README's
 * limitations before relying on those forms.
 *
 * [method], [methods], [repetitions], and [engine] are retained for source compatibility with the
 * earlier compiler-plugin prototype but are not read by the current bytecode transform.
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
