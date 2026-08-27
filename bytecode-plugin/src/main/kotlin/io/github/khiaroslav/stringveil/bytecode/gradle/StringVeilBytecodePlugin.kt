package io.github.khiaroslav.stringveil.bytecode.gradle

import io.github.khiaroslav.stringveil.bytecode.StringVeilTransformer
import java.io.File
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer

/**
 * Applies String Veil to a JVM (Kotlin or Java) project by rewriting `@Obfuscate` string literals in
 * the compiled `.class` files after compilation. Because it works on JVM bytecode — not the Kotlin
 * compiler — it is independent of the Kotlin version and also covers Java sources.
 *
 * (Android support is added separately through AGP's ASM instrumentation API.)
 */
public class StringVeilBytecodePlugin : Plugin<Project> {
    override fun apply(target: Project) {
        // The Kotlin JVM plugin applies the `java` plugin too, so this covers both.
        target.pluginManager.withPlugin("java") {
            val sourceSets = target.extensions.getByType(SourceSetContainer::class.java)
            val classesDirs = sourceSets.getByName("main").output.classesDirs
            target.tasks.named("classes").configure { classes ->
                classes.doLast {
                    val transformer = StringVeilTransformer()
                    classesDirs.files.forEach { dir -> obfuscate(transformer, dir) }
                }
            }
        }
    }

    private fun obfuscate(transformer: StringVeilTransformer, dir: File) {
        if (!dir.isDirectory) return
        dir.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach { classFile ->
            val original = classFile.readBytes()
            val result = transformer.transform(original)
            if (result.errors.isNotEmpty()) {
                throw GradleException("string-veil: " + result.errors.joinToString("\n"))
            }
            // The transformer returns the same array when nothing changed, so this only rewrites
            // classes that actually had literals hidden.
            if (result.bytes !== original) classFile.writeBytes(result.bytes)
        }
    }
}
