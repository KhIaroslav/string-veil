package io.github.khiaroslav.stringveil.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

/** Connects String Veil to Kotlin/JVM and Kotlin/Android compilations. */
public class StringVeilGradlePlugin : KotlinCompilerPluginSupportPlugin {
    private lateinit var extension: StringVeilExtension

    override fun apply(target: Project) {
        extension = target.extensions.create(
            EXTENSION_NAME,
            StringVeilExtension::class.java,
        )

        target.pluginManager.withPlugin(KOTLIN_JVM_PLUGIN_ID) {
            addConsumerDependencies(target, native = false)
        }
        target.pluginManager.withPlugin(KOTLIN_ANDROID_PLUGIN_ID) {
            addConsumerDependencies(target, native = true)
        }
    }

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean =
        extension.enabled.get() && kotlinCompilation.platformType in SUPPORTED_PLATFORMS

    override fun applyToCompilation(
        kotlinCompilation: KotlinCompilation<*>,
    ): Provider<List<SubpluginOption>> =
        kotlinCompilation.target.project.provider {
            listOf(
                SubpluginOption(
                    key = "nativeAvailable",
                    value = (kotlinCompilation.platformType == KotlinPlatformType.androidJvm)
                        .toString(),
                ),
            )
        }

    override fun getCompilerPluginId(): String = StringVeilCoordinates.COMPILER_PLUGIN_ID

    override fun getPluginArtifact(): SubpluginArtifact = SubpluginArtifact(
        groupId = StringVeilCoordinates.GROUP,
        artifactId = StringVeilCoordinates.COMPILER_PLUGIN_ARTIFACT,
        version = StringVeilCoordinates.VERSION,
    )

    private fun module(artifactId: String): String =
        "${StringVeilCoordinates.GROUP}:$artifactId:${StringVeilCoordinates.VERSION}"

    private fun addConsumerDependencies(target: Project, native: Boolean) {
        target.dependencies.add(
            COMPILE_ONLY_CONFIGURATION,
            module(StringVeilCoordinates.ANNOTATIONS_ARTIFACT),
        )
        if (native) {
            target.dependencies.add(
                IMPLEMENTATION_CONFIGURATION,
                module(StringVeilCoordinates.NATIVE_RUNTIME_ARTIFACT),
            )
        } else {
            target.dependencies.add(
                IMPLEMENTATION_CONFIGURATION,
                module(StringVeilCoordinates.RUNTIME_ARTIFACT),
            )
        }
    }

    private companion object {
        private const val EXTENSION_NAME = "stringVeil"
        private const val COMPILE_ONLY_CONFIGURATION = "compileOnly"
        private const val IMPLEMENTATION_CONFIGURATION = "implementation"

        private const val KOTLIN_JVM_PLUGIN_ID = "org.jetbrains.kotlin.jvm"
        private const val KOTLIN_ANDROID_PLUGIN_ID = "org.jetbrains.kotlin.android"

        private val SUPPORTED_PLATFORMS = setOf(
            KotlinPlatformType.jvm,
            KotlinPlatformType.androidJvm,
        )
    }
}
