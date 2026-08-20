package io.github.khiaroslav.stringveil.annotations

/** Excludes a declaration or expression from an enclosing [Obfuscate] scope. */
@MustBeDocumented
@Retention(AnnotationRetention.SOURCE)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.EXPRESSION,
)
public annotation class DoNotObfuscate
