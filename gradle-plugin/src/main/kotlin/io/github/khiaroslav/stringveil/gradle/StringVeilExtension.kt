package io.github.khiaroslav.stringveil.gradle

import org.gradle.api.provider.Property

/** Gradle configuration for String Veil. */
public abstract class StringVeilExtension {
    /** Enables String Veil for supported Kotlin compilations. */
    public abstract val enabled: Property<Boolean>

    init {
        enabled.convention(true)
    }
}
