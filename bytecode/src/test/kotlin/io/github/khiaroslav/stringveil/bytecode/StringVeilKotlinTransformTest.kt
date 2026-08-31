package io.github.khiaroslav.stringveil.bytecode

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
import org.jetbrains.kotlin.config.Services

/**
 * Exercises the transform on real Kotlin bytecode, where property annotations land on Kotlin's
 * synthetic `get<Name>$annotations()` holder methods rather than on the JVM field. Compiles a
 * fixture with the real `@Obfuscate` / `@DoNotObfuscate` (BINARY-retained), transforms the classes,
 * and checks both the bytes and the decoded runtime values.
 */
class StringVeilKotlinTransformTest {
    @Test
    fun `obfuscates kotlin declarations and decodes at runtime`() {
        val root = createTempDirectory("kotlin-transform").toFile()
        try {
            val source = root.resolve("Fixture.kt").apply {
                writeText(
                    """
                    import io.github.khiaroslav.stringveil.annotations.DoNotObfuscate
                    import io.github.khiaroslav.stringveil.annotations.Obfuscate

                    @Obfuscate
                    val topSecret = "top-secret-value"

                    val topVisible = "top-visible-value"

                    @Obfuscate
                    fun secretFun(): String = "fun-secret-value"

                    @Obfuscate
                    class Holder {
                        val hidden = "class-secret-value"
                        @DoNotObfuscate val kept = "class-kept-value"
                    }
                    """.trimIndent(),
                )
            }
            val classes = compileKotlin(root, source)
            transformAll(classes)

            val fixtureKt = classes.resolve("FixtureKt.class").readBytes()
            val holder = classes.resolve("Holder.class").readBytes()

            assertFalse(fixtureKt.containsUtf8("top-secret-value"), "@Obfuscate val leaked")
            assertFalse(fixtureKt.containsUtf8("fun-secret-value"), "@Obfuscate fun leaked")
            assertTrue(fixtureKt.containsUtf8("top-visible-value"), "unannotated val wrongly hidden")
            assertFalse(holder.containsUtf8("class-secret-value"), "class-scoped field leaked")
            assertTrue(holder.containsUtf8("class-kept-value"), "@DoNotObfuscate field wrongly hidden")

            URLClassLoader(arrayOf(classes.toURI().toURL()), javaClass.classLoader).use { loader ->
                val fixture = loader.loadClass("FixtureKt")
                assertEquals("top-secret-value", fixture.getMethod("getTopSecret").invoke(null))
                assertEquals("fun-secret-value", fixture.getMethod("secretFun").invoke(null))
                val holderClass = loader.loadClass("Holder")
                val instance = holderClass.getDeclaredConstructor().newInstance()
                assertEquals("class-secret-value", holderClass.getMethod("getHidden").invoke(instance))
                assertEquals("class-kept-value", holderClass.getMethod("getKept").invoke(instance))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `obfuscates an instance property annotated directly, initialized in the constructor`() {
        val root = createTempDirectory("kotlin-instance-prop").toFile()
        try {
            val source = root.resolve("Config.kt").apply {
                writeText(
                    """
                    import io.github.khiaroslav.stringveil.annotations.Obfuscate

                    class Config {
                        @Obfuscate
                        val instanceSecret = "instance-secret-value"
                        val instanceVisible = "instance-visible-value"
                    }
                    """.trimIndent(),
                )
            }
            val classes = compileKotlin(root, source)
            transformAll(classes)

            val config = classes.resolve("Config.class").readBytes()
            assertFalse(config.containsUtf8("instance-secret-value"), "@Obfuscate instance property leaked")
            assertTrue(
                config.containsUtf8("instance-visible-value"),
                "unannotated instance property wrongly hidden",
            )

            URLClassLoader(arrayOf(classes.toURI().toURL()), javaClass.classLoader).use { loader ->
                val configClass = loader.loadClass("Config")
                val instance = configClass.getDeclaredConstructor().newInstance()
                assertEquals(
                    "instance-secret-value",
                    configClass.getMethod("getInstanceSecret").invoke(instance),
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun transformAll(classesDir: File) {
        val transformer = StringVeilTransformer()
        classesDir.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach { file ->
            val result = transformer.transform(file.readBytes())
            assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
            file.writeBytes(result.bytes)
        }
    }

    private fun compileKotlin(root: File, source: File): File {
        val output = root.resolve("classes").apply(File::mkdirs)
        val collector = object : MessageCollector {
            val messages = mutableListOf<String>()
            override fun clear() {}
            override fun hasErrors() = false
            override fun report(
                severity: CompilerMessageSeverity,
                message: String,
                location: CompilerMessageSourceLocation?,
            ) { messages += "${severity.name}: $message" }
        }
        val arguments = K2JVMCompilerArguments().apply {
            destination = output.absolutePath
            classpath = System.getProperty("java.class.path")
            freeArgs = listOf(source.absolutePath)
            jvmTarget = "17"
            noReflect = true
            noStdlib = true
            disableDefaultScriptingPlugin = true
        }
        val exit = K2JVMCompiler().exec(collector, Services.EMPTY, arguments)
        assertEquals(ExitCode.OK, exit, collector.messages.joinToString("\n"))
        return output
    }

    private fun ByteArray.containsUtf8(value: String): Boolean {
        val needle = value.encodeToByteArray()
        return indices.any { start ->
            start + needle.size <= size && needle.indices.all { this[start + it] == needle[it] }
        }
    }
}
