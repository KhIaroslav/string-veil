package io.github.khiaroslav.stringveil.compiler

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CliOptionProcessingException
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

@OptIn(ExperimentalCompilerApi::class)
public class StringVeilCommandLineProcessor : CommandLineProcessor {
    override val pluginId: String = PLUGIN_ID

    override val pluginOptions: Collection<AbstractCliOption> =
        listOf(NATIVE_AVAILABLE_OPTION, FAIL_ON_SECRET_LIKE_OPTION)

    override fun processOption(
        option: AbstractCliOption,
        value: String,
        configuration: CompilerConfiguration,
    ) {
        when (option.optionName) {
            NATIVE_AVAILABLE_OPTION_NAME -> configuration.put(
                NATIVE_AVAILABLE_KEY,
                value.toBooleanStrictOrNull()
                    ?: throw CliOptionProcessingException("nativeAvailable must be true or false"),
            )
            FAIL_ON_SECRET_LIKE_OPTION_NAME -> configuration.put(
                FAIL_ON_SECRET_LIKE_KEY,
                value.toBooleanStrictOrNull()
                    ?: throw CliOptionProcessingException(
                        "failOnSecretLikeLiterals must be true or false",
                    ),
            )
            else -> throw CliOptionProcessingException("Unknown String Veil option: ${option.optionName}")
        }
    }

    private companion object {
        private const val PLUGIN_ID = "io.github.khiaroslav.stringveil"
        private const val NATIVE_AVAILABLE_OPTION_NAME = "nativeAvailable"
        private val NATIVE_AVAILABLE_OPTION = CliOption(
            optionName = NATIVE_AVAILABLE_OPTION_NAME,
            valueDescription = "<true|false>",
            description = "Whether the Android JNI runtime is available",
            required = false,
        )
        private const val FAIL_ON_SECRET_LIKE_OPTION_NAME = "failOnSecretLikeLiterals"
        private val FAIL_ON_SECRET_LIKE_OPTION = CliOption(
            optionName = FAIL_ON_SECRET_LIKE_OPTION_NAME,
            valueDescription = "<true|false>",
            description = "Escalate secret-like literal warnings to build errors",
            required = false,
        )
    }
}

internal val NATIVE_AVAILABLE_KEY: CompilerConfigurationKey<Boolean> =
    CompilerConfigurationKey.create("string-veil native runtime availability")

internal val FAIL_ON_SECRET_LIKE_KEY: CompilerConfigurationKey<Boolean> =
    CompilerConfigurationKey.create("string-veil fail on secret-like literals")
