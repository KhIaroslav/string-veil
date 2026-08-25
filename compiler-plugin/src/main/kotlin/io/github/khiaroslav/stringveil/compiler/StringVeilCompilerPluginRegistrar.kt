package io.github.khiaroslav.stringveil.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration

/** Registers String Veil extensions in the Kotlin compiler. */
@OptIn(ExperimentalCompilerApi::class)
public class StringVeilCompilerPluginRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String = PLUGIN_ID

    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val messageCollector =
            configuration.get(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)

        IrGenerationExtension.registerExtension(
            StringVeilIrGenerationExtension(
                messageCollector = messageCollector,
                nativeAvailable = configuration.get(NATIVE_AVAILABLE_KEY, false),
                failOnSecretLikeLiterals = configuration.get(FAIL_ON_SECRET_LIKE_KEY, false),
            ),
        )
    }

    private companion object {
        private const val PLUGIN_ID = "io.github.khiaroslav.stringveil"
    }
}
