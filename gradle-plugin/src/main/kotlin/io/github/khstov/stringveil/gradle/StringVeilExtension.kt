package io.github.khstov.stringveil.gradle

import org.gradle.api.provider.Property

/** Gradle configuration for String Veil. */
public abstract class StringVeilExtension {
    /** Enables String Veil for supported JVM and Android projects. */
    public abstract val enabled: Property<Boolean>

    /**
     * Escalates the "this literal looks like a secret" warning to a build error. String Veil is
     * obfuscation, not a secrets store; enable this to hard-fail builds that try to protect
     * credential-shaped literals.
     */
    public abstract val failOnSecretLikeLiterals: Property<Boolean>

    /**
     * Makes obfuscation deterministic. Left unset, each build randomizes every container (maximum
     * diversity). Set to any fixed value for reproducible, cacheable builds: identical source then
     * produces identical containers.
     */
    public abstract val seed: Property<Long>

    init {
        enabled.convention(true)
        failOnSecretLikeLiterals.convention(false)
    }
}
