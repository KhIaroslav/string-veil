package io.github.khiaroslav.stringveil.gradle

import org.gradle.api.provider.Property

/** Gradle configuration for String Veil. */
public abstract class StringVeilExtension {
    /** Enables String Veil for supported Kotlin compilations. */
    public abstract val enabled: Property<Boolean>

    /**
     * Escalates the "this literal looks like a secret" warning to a build error. String Veil is
     * obfuscation, not a secrets store; enable this to hard-fail builds that try to protect
     * credential-shaped literals.
     */
    public abstract val failOnSecretLikeLiterals: Property<Boolean>

    init {
        enabled.convention(true)
        failOnSecretLikeLiterals.convention(false)
    }
}
