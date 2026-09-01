package io.github.khstov.stringveil.gradle

import io.github.khstov.stringveil.bytecode.StringVeilTransformer
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.BufferedOutputStream
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

/**
 * Android CLASSES transform: rewrites `@Obfuscate` literals across the project's compiled classes by
 * running the shared [StringVeilTransformer] and re-serializing each class through a fresh
 * `ClassWriter`.
 *
 * The task owns the final project-classes archive for the variant. Each selected class is passed
 * through the shared byte-array transformer, whose fresh, non-reader-backed `ClassWriter` rebuilds
 * the constant pool from live references. Integration tests must inspect this output archive or its
 * packaged AAR/DEX rather than infer absence of plaintext from visitor behavior alone.
 *
 * @suppress
 */
public abstract class StringVeilTransformClassesTask : DefaultTask() {
    @get:InputFiles
    @get:Optional
    public abstract val inputJars: ListProperty<RegularFile>

    @get:InputFiles
    @get:Optional
    public abstract val inputDirectories: ListProperty<Directory>

    @get:OutputFile
    public abstract val output: RegularFileProperty

    @get:Input
    public abstract val failOnSecretLike: Property<Boolean>

    @get:Input
    @get:Optional
    public abstract val seed: Property<Long>

    @get:Input
    public abstract val includePackages: ListProperty<String>

    @get:Input
    public abstract val excludePackages: ListProperty<String>

    @get:Input
    public abstract val minStringLength: Property<Int>

    @TaskAction
    public fun transform() {
        val transformer = StringVeilTransformer(
            seed = seed.orNull,
            decoderInternalName = NATIVE_DECODER,
            failOnSecretLike = failOnSecretLike.get(),
            includePackages = includePackages.get(),
            excludePackages = excludePackages.get(),
            minStringLength = minStringLength.get(),
        )
        val errors = mutableListOf<String>()
        val written = HashSet<String>()

        JarOutputStream(BufferedOutputStream(output.get().asFile.outputStream())).use { jar ->
            fun emit(name: String, bytes: ByteArray) {
                // PROJECT scope should not collide, but guard against duplicate jar entries defensively.
                if (!written.add(name)) return
                jar.putNextEntry(JarEntry(name))
                jar.write(bytes)
                jar.closeEntry()
            }

            fun rewrite(name: String, bytes: ByteArray): ByteArray {
                if (!name.endsWith(".class")) return bytes
                val result = transformer.transform(bytes)
                result.warnings.forEach { logger.warn("string-veil: $it") }
                errors += result.errors
                return result.bytes
            }

            inputDirectories.get().forEach { directory ->
                val root = directory.asFile
                root.walkTopDown().filter { it.isFile }.forEach { file ->
                    val name = file.relativeTo(root).invariantSeparatorsPath
                    emit(name, rewrite(name, file.readBytes()))
                }
            }
            inputJars.get().forEach { regularFile ->
                JarFile(regularFile.asFile).use { jarFile ->
                    jarFile.entries().asSequence().filter { !it.isDirectory }.forEach { entry ->
                        val bytes = jarFile.getInputStream(entry).use { it.readBytes() }
                        emit(entry.name, rewrite(entry.name, bytes))
                    }
                }
            }
        }

        if (errors.isNotEmpty()) {
            throw GradleException("string-veil:\n" + errors.joinToString("\n"))
        }
    }

    private companion object {
        const val NATIVE_DECODER = "io/github/khstov/stringveil/runtime/NativeStringDecoder"
    }
}
