package io.github.khstov.stringveil.format

/**
 * Marks internal String Veil container-format details that are shared between the build-time cipher
 * and the runtime decoders.
 *
 * These declarations are `public` only so the bytecode encoder and runtime can share a single
 * definition of the format; they are **not** a stable API and may change between releases. Consumer
 * code should never opt in.
 *
 * @suppress
 */
@RequiresOptIn(
    message = "Internal String Veil container-format detail; not a stable public API.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
)
public annotation class InternalStringVeilApi
