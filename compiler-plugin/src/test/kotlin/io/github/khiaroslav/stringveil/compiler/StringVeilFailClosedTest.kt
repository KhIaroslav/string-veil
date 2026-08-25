package io.github.khiaroslav.stringveil.compiler

import java.io.File
import java.net.URLClassLoader
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.Services

/**
 * Guards String Veil's fail-closed behavior: literals that cannot be obfuscated must produce a clear
 * diagnostic (never a silent plaintext leak and never a compiler crash), and the literal forms that
 * *can* be obfuscated must keep working so a future change cannot silently start leaking them.
 */
@OptIn(ExperimentalCompilerApi::class)
class StringVeilFailClosedTest {
    private class Compilation(
        val exitCode: ExitCode,
        val messages: List<String>,
        val classDir: File,
    ) {
        fun leaks(value: String): Boolean {
            val expected = value.encodeToByteArray()
            return classDir.walkTopDown().filter { it.extension == "class" }.any { file ->
                val bytes = file.readBytes()
                bytes.indices.any { start ->
                    start + expected.size <= bytes.size &&
                        expected.indices.all { offset -> bytes[start + offset] == expected[offset] }
                }
            }
        }
    }

    private fun compile(fileName: String, source: String): Compilation {
        val root = createTempDirectory("string-veil-fail-closed").toFile()
        val src = root.resolve(fileName).apply { writeText(source) }
        val output = root.resolve("classes").apply(File::mkdirs)
        val collector = RecordingMessageCollector()
        val arguments = K2JVMCompilerArguments().apply {
            destination = output.absolutePath
            classpath = System.getProperty("java.class.path")
            freeArgs = listOf(src.absolutePath)
            jvmTarget = "17"
            noReflect = true
            noStdlib = true
            disableDefaultScriptingPlugin = true
            pluginClasspaths = arrayOf(
                requireNotNull(System.getProperty("stringVeil.compilerPluginJar")),
            )
        }
        val exitCode = K2JVMCompiler().exec(collector, Services.EMPTY, arguments)
        return Compilation(exitCode, collector.messages, output)
    }

    @Test
    fun `const val is rejected with a clear error instead of crashing or leaking`() {
        val result = compile(
            "ConstSecret.kt",
            """
            import io.github.khiaroslav.stringveil.annotations.Obfuscate

            @Obfuscate
            const val SECRET = "const-should-not-leak"
            """.trimIndent(),
        )

        assertEquals(
            ExitCode.COMPILATION_ERROR,
            result.exitCode,
            result.messages.joinToString("\n"),
        )
        assertFalse(
            result.messages.any { it.contains("INTERNAL_ERROR", ignoreCase = true) },
            "const val must not crash the backend: ${result.messages.joinToString("\n")}",
        )
        assertTrue(
            result.messages.any {
                it.startsWith("ERROR:") && it.contains("cannot protect a `const val`")
            },
            "Expected a clear const-val diagnostic: ${result.messages.joinToString("\n")}",
        )
        assertFalse(result.leaks("const-should-not-leak"), "const value leaked into output")
    }

    @Test
    fun `string template literal parts are obfuscated and preserved`() {
        val result = compile(
            "Template.kt",
            """
            import io.github.khiaroslav.stringveil.annotations.Obfuscate

            @Obfuscate
            fun greet(name: String): String = "template-head-secret ${'$'}name template-tail-secret"
            """.trimIndent(),
        )

        assertEquals(ExitCode.OK, result.exitCode, result.messages.joinToString("\n"))
        assertFalse(result.leaks("template-head-secret"), "template head leaked")
        assertFalse(result.leaks("template-tail-secret"), "template tail leaked")

        URLClassLoader(arrayOf(result.classDir.toURI().toURL()), javaClass.classLoader).use { loader ->
            val sample = loader.loadClass("TemplateKt")
            assertEquals(
                "template-head-secret Ada template-tail-secret",
                sample.getMethod("greet", String::class.java).invoke(null, "Ada"),
            )
        }
    }

    @Test
    fun `raw string expression annotation is obfuscated and preserved`() {
        val result = compile(
            "Raw.kt",
            """
            import io.github.khiaroslav.stringveil.annotations.Obfuscate

            val raw = @Obfuscate ${"\"\"\""}raw-should-not-leak${"\"\"\""}
            """.trimIndent(),
        )

        assertEquals(ExitCode.OK, result.exitCode, result.messages.joinToString("\n"))
        assertFalse(result.leaks("raw-should-not-leak"), "raw string leaked")

        URLClassLoader(arrayOf(result.classDir.toURI().toURL()), javaClass.classLoader).use { loader ->
            val sample = loader.loadClass("RawKt")
            assertEquals("raw-should-not-leak", sample.getMethod("getRaw").invoke(null))
        }
    }

    @Test
    fun `expression @Obfuscate on a call expression fails the build`() {
        val result = compile(
            "Call.kt",
            """
            import io.github.khiaroslav.stringveil.annotations.Obfuscate

            fun provide(): String = "runtime-value"

            val called = @Obfuscate provide()
            """.trimIndent(),
        )

        assertEquals(ExitCode.COMPILATION_ERROR, result.exitCode, result.messages.joinToString("\n"))
        assertTrue(
            result.messages.any {
                it.startsWith("ERROR:") && it.contains("was not applied to any string literal")
            },
            "expected an unapplied-@Obfuscate error: ${result.messages.joinToString("\n")}",
        )
    }

    @Test
    fun `valid declaration and literal annotations do not trip the cross-check`() {
        val result = compile(
            "Valid.kt",
            """
            import io.github.khiaroslav.stringveil.annotations.Obfuscate
            import io.github.khiaroslav.stringveil.annotations.Obfuscate as Veil

            @Obfuscate
            val declared = "declared-secret"

            val direct = @Veil "direct-secret"
            """.trimIndent(),
        )

        assertEquals(ExitCode.OK, result.exitCode, result.messages.joinToString("\n"))
        assertFalse(
            result.messages.any { it.contains("was not applied") },
            "the cross-check must not flag valid annotations: ${result.messages.joinToString("\n")}",
        )
        assertFalse(result.leaks("declared-secret"), "declaration-annotated value leaked")
        assertFalse(result.leaks("direct-secret"), "expression-annotated value leaked")
    }

    private class RecordingMessageCollector : MessageCollector {
        val messages = mutableListOf<String>()
        private var errors = false

        override fun clear() {
            messages.clear()
            errors = false
        }

        override fun hasErrors(): Boolean = errors

        override fun report(
            severity: CompilerMessageSeverity,
            message: String,
            location: CompilerMessageSourceLocation?,
        ) {
            errors = errors || severity.isError
            messages += "${severity.name}: $message"
        }
    }
}
