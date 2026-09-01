package io.github.khstov.stringveil.gradle

import io.github.khstov.stringveil.bytecode.StringVeilTransformer
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * JVM path: rewrites `@Obfuscate` / `obfuscate(...)` literals in the main source set's compiled
 * classes, in place, after compilation.
 *
 * This is a real task rather than a `classes.doLast` action so it carries only serializable inputs
 * (no `Project` capture) and is compatible with Gradle's configuration cache. Re-running is a no-op:
 * once a class is transformed there are no matching literals left for a second pass, so the
 * transformer reports no change and the file is not rewritten.
 */
public abstract class StringVeilTransformJvmClassesTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val classesDirs: ConfigurableFileCollection

    @get:Input
    public abstract val obfuscationEnabled: Property<Boolean>

    @get:Input
    public abstract val failOnSecretLike: Property<Boolean>

    @TaskAction
    public fun transform() {
        if (!obfuscationEnabled.get()) return
        val transformer = StringVeilTransformer(failOnSecretLike = failOnSecretLike.get())
        val errors = mutableListOf<String>()
        classesDirs.files.filter { it.isDirectory }.forEach { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach { classFile ->
                val original = classFile.readBytes()
                val result = transformer.transform(original)
                result.warnings.forEach { logger.warn("string-veil: $it") }
                errors += result.errors
                if (result.bytes !== original) classFile.writeBytes(result.bytes)
            }
        }
        if (errors.isNotEmpty()) {
            throw GradleException("string-veil:\n" + errors.joinToString("\n"))
        }
    }
}
