package io.github.khiaroslav.stringveil.annotations

/** Excludes a declaration from an enclosing [Obfuscate] scope. */
@MustBeDocumented
// BINARY so the marker survives to the compiled class, where the bytecode transform reads it.
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
)
public annotation class DoNotObfuscate
