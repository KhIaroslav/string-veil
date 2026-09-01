package io.github.khstov.stringveil.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer

/**
 * Applies String Veil by rewriting `@Obfuscate` string literals in the compiled bytecode after
 * compilation. It works on JVM class files rather than Kotlin compiler internals and covers supported
 * Kotlin and Java bytecode shapes; compatibility is verified against concrete tool versions.
 *
 * - JVM (`java` / `org.jetbrains.kotlin.jvm`): transforms the main source set's class output.
 * - Android (`com.android.application` / `com.android.library`): transforms project classes
 *   through AGP's `ScopedArtifacts` pipeline before dexing or AAR packaging and calls the native
 *   bridge, which has a JVM fallback.
 *
 * @suppress
 */
public class StringVeilGradlePlugin : Plugin<Project> {
    override fun apply(target: Project) {
        val extension = target.extensions.create(EXTENSION_NAME, StringVeilExtension::class.java)

        // The Kotlin JVM plugin applies the `java` plugin; Android does not, so these do not overlap.
        target.pluginManager.withPlugin("java") {
            addConsumerDependencies(target, native = false)
            wireJvm(target, extension)
        }
        target.pluginManager.withPlugin("com.android.application") {
            addConsumerDependencies(target, native = true)
            StringVeilAndroidWiring.apply(target, extension)
        }
        target.pluginManager.withPlugin("com.android.library") {
            addConsumerDependencies(target, native = true)
            StringVeilAndroidWiring.apply(target, extension)
        }
    }

    private fun wireJvm(target: Project, extension: StringVeilExtension) {
        val sourceSets = target.extensions.getByType(SourceSetContainer::class.java)
        val classesDirs = sourceSets.getByName("main").output.classesDirs
        val obfuscate = target.tasks.register(
            "stringVeilObfuscateJvm",
            StringVeilTransformJvmClassesTask::class.java,
        ) { task ->
            // classesDirs carries the compile task dependencies, so the task runs after compilation.
            task.classesDirs.from(classesDirs)
            task.obfuscationEnabled.set(extension.enabled)
            task.failOnSecretLike.set(extension.failOnSecretLikeLiterals)
            task.seed.set(extension.seed)
            task.includePackages.set(extension.includePackages)
            task.excludePackages.set(extension.excludePackages)
            task.minStringLength.set(extension.minStringLength)
        }
        // Everything that consumes `classes` (jar, test, run) then sees the transformed output.
        target.tasks.named("classes").configure { it.dependsOn(obfuscate) }
    }

    private fun addConsumerDependencies(target: Project, native: Boolean) {
        // @Obfuscate is BINARY-retained; consumers need it at compile time only.
        target.dependencies.add("compileOnly", module(StringVeilCoordinates.ANNOTATIONS_ARTIFACT))
        if (native) {
            target.dependencies.add(
                "implementation",
                module(StringVeilCoordinates.NATIVE_RUNTIME_ARTIFACT),
            )
        }
        // The transformed code calls the decoder, so the runtime is needed at runtime. On Android it
        // is also the fallback when the native library is unavailable for the device's ABI.
        target.dependencies.add("implementation", module(StringVeilCoordinates.RUNTIME_ARTIFACT))
    }

    private fun module(artifactId: String): String =
        "${StringVeilCoordinates.GROUP}:$artifactId:${StringVeilCoordinates.VERSION}"

    private companion object {
        const val EXTENSION_NAME = "stringVeil"
    }
}
