package io.github.khstov.stringveil.gradle

import org.gradle.api.provider.ListProperty
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

    /**
     * Only transform classes whose package matches one of these (exact or a parent prefix, e.g.
     * `com.example` covers `com.example.api`). Empty means every package is eligible.
     */
    public abstract val includePackages: ListProperty<String>

    /**
     * Never transform classes whose package matches one of these (exact or a parent prefix). Takes
     * precedence over [includePackages].
     */
    public abstract val excludePackages: ListProperty<String>

    /**
     * Skip annotation-scoped literals shorter than this. An explicit `obfuscate("...")` marker always
     * obfuscates regardless of length. Defaults to 0 (no minimum).
     */
    public abstract val minStringLength: Property<Int>

    /**
     * Android only: obfuscate only these build variants, by name (e.g. `release`). Empty means every
     * variant.
     */
    public abstract val includeVariants: ListProperty<String>

    init {
        enabled.convention(true)
        failOnSecretLikeLiterals.convention(false)
        minStringLength.convention(0)
    }
}
