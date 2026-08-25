package io.github.khiaroslav.stringveil.compiler

import java.io.File
import java.net.URLClassLoader
import javax.tools.ToolProvider
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import io.github.khiaroslav.stringveil.runtime.StringDecoder
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.Services

@OptIn(ExperimentalCompilerApi::class)
class StringVeilCompilerPluginTest {
    @Test
    fun `source resolver finds direct expression annotation`() {
        val root = createTempDirectory("string-veil-source-test").toFile()

        try {
            val sourceText = """
                import io.github.khiaroslav.stringveil.annotations.Obfuscate
                import io.github.khiaroslav.stringveil.annotations.ObfuscationEngine
                import io.github.khiaroslav.stringveil.annotations.ObfuscationMethod

                val direct = @Obfuscate(
                    method = ObfuscationMethod.RANDOM_SELECTED,
                    methods = [ObfuscationMethod.BIT_XOR, ObfuscationMethod.AES],
                    repetitions = 5,
                    engine = ObfuscationEngine.JVM,
                ) "expression-secret"
            """.trimIndent()
            val source = root.resolve("Source.kt").apply { writeText(sourceText) }
            val quoteOffset = sourceText.indexOf('"')
            val resolver = assertNotNull(SourceExpressionAnnotationResolver.fromFile(source.path))

            assertTrue(
                resolver.annotationsAt(quoteOffset, sourceText.length).any {
                    it.asString() == "io.github.khiaroslav.stringveil.annotations.Obfuscate"
                },
            )
            assertEquals(
                ProtectionConfig(
                    method = ProtectionMethod.RANDOM_SELECTED,
                    methods = setOf(ProtectionMethod.BIT_XOR, ProtectionMethod.AES),
                    repetitions = 5,
                    engine = ProtectionEngine.JVM,
                ),
                resolver.obfuscationConfigAt(quoteOffset, sourceText.length),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `native engine emits a call to the Java JNI bridge`() {
        val root = createTempDirectory("string-veil-native-compiler-test").toFile()

        try {
            val bridgeSource = root.resolve("NativeStringDecoder.java").apply {
                writeText(
                    """
                    package io.github.khiaroslav.stringveil.runtime;

                    public final class NativeStringDecoder {
                        private NativeStringDecoder() {}

                        public static String decode(int[] container) {
                            return StringDecoder.decode(container);
                        }
                    }
                    """.trimIndent(),
                )
            }
            val bridgeOutput = root.resolve("bridge-classes").apply(File::mkdirs)
            val javacExitCode = ToolProvider.getSystemJavaCompiler().run(
                null,
                null,
                null,
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                bridgeOutput.absolutePath,
                bridgeSource.absolutePath,
            )
            assertEquals(0, javacExitCode, "Failed to compile the Java native bridge stub")

            val source = root.resolve("NativeSample.kt").apply {
                writeText(
                    """
                    import io.github.khiaroslav.stringveil.annotations.Obfuscate
                    import io.github.khiaroslav.stringveil.annotations.ObfuscationEngine
                    import io.github.khiaroslav.stringveil.annotations.ObfuscationMethod

                    @Obfuscate(
                        method = ObfuscationMethod.BIT_XOR,
                        repetitions = 2,
                        engine = ObfuscationEngine.NATIVE,
                    )
                    fun nativeSecret(): String = "native-engine-secret"
                    """.trimIndent(),
                )
            }
            val output = root.resolve("classes").apply(File::mkdirs)
            val collector = RecordingMessageCollector()
            val runtimeLocation = File(
                StringDecoder::class.java.protectionDomain.codeSource.location.toURI(),
            ).canonicalFile
            val nativeOnlyClasspath = System.getProperty("java.class.path")
                .split(File.pathSeparator)
                .filterNot { entry -> File(entry).canonicalFile == runtimeLocation }
                .joinToString(File.pathSeparator)
            val arguments = K2JVMCompilerArguments().apply {
                destination = output.absolutePath
                classpath = nativeOnlyClasspath +
                    File.pathSeparator + bridgeOutput.absolutePath
                freeArgs = listOf(source.absolutePath)
                jvmTarget = "17"
                noReflect = true
                noStdlib = true
                disableDefaultScriptingPlugin = true
                pluginClasspaths = arrayOf(
                    requireNotNull(System.getProperty("stringVeil.compilerPluginJar")),
                )
                pluginOptions = arrayOf(
                    "plugin:io.github.khiaroslav.string-veil:nativeAvailable=true",
                )
            }

            val exitCode = K2JVMCompiler().exec(collector, Services.EMPTY, arguments)

            assertEquals(ExitCode.OK, exitCode, collector.messages.joinToString("\n"))
            assertTrue(
                collector.messages.any { it.contains("engines=NATIVE:1") },
                collector.messages.joinToString("\n"),
            )
            val classBytes = output.resolve("NativeSampleKt.class").readBytes()
            assertTrue(!classBytes.containsUtf8("native-engine-secret"))
            assertTrue(
                classBytes.containsUtf8(
                    "io/github/khiaroslav/stringveil/runtime/NativeStringDecoder",
                ),
            )
            assertTrue(
                !classBytes.containsUtf8(
                    "io/github/khiaroslav/stringveil/runtime/StringDecoder",
                ),
            )

            URLClassLoader(
                arrayOf(output.toURI().toURL(), bridgeOutput.toURI().toURL()),
                javaClass.classLoader,
            ).use { loader ->
                val sample = loader.loadClass("NativeSampleKt")
                assertEquals("native-engine-secret", sample.getMethod("nativeSecret").invoke(null))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `all configurable methods preserve UTF-8 values`() {
        val expected = "Секрет 🔐 / https://internal.example"
        val cipher = LayeredStringCipher()
        val configs = listOf(
            ProtectionConfig(ProtectionMethod.BIT_SHIFT, repetitions = 2),
            ProtectionConfig(ProtectionMethod.BIT_XOR, repetitions = 2),
            ProtectionConfig(ProtectionMethod.BASE64, repetitions = 2),
            ProtectionConfig(ProtectionMethod.AES, repetitions = 2),
            ProtectionConfig(ProtectionMethod.RANDOM_ALL, repetitions = 5),
            ProtectionConfig(
                method = ProtectionMethod.RANDOM_SELECTED,
                methods = setOf(ProtectionMethod.BIT_XOR, ProtectionMethod.AES),
                repetitions = 5,
            ),
        )

        configs.forEachIndexed { index, config ->
            val encrypted = cipher.encrypt(
                expected.encodeToByteArray(),
                EncryptionContext("Methods.kt", index),
                config,
            )
            assertEquals(expected, StringDecoder.decode(encrypted.container), config.toString())
        }
    }

    @Test
    fun `transforms scoped literals while preserving runtime values`() {
        val root = createTempDirectory("string-veil-compiler-test").toFile()

        try {
            val source = root.resolve("Sample.kt").apply {
                writeText(
                    """
                    import io.github.khiaroslav.stringveil.annotations.DoNotObfuscate
                    import io.github.khiaroslav.stringveil.annotations.Obfuscate as Veil
                    import io.github.khiaroslav.stringveil.annotations.ObfuscationMethod

                    @Veil
                    class SecretHolder {
                        val hidden = "class-secret"

                        @DoNotObfuscate
                        fun visible(): String = "visible"
                    }

                    val direct = @Veil "expression-secret"

                    @Veil
                    fun functionSecret(): String = "function-secret"

                    @Veil(method = ObfuscationMethod.AES, repetitions = 2)
                    fun aesSecret(): String = "aes-secret"

                    val selected = @Veil(
                        method = ObfuscationMethod.RANDOM_SELECTED,
                        methods = [ObfuscationMethod.BIT_XOR, ObfuscationMethod.AES],
                        repetitions = 5,
                    ) "selected-secret"

                    @Veil
                    fun excludedExpression(): String = @DoNotObfuscate "expression-visible"

                    fun ordinary(): String = "ordinary"
                    """.trimIndent(),
                )
            }
            val output = root.resolve("classes").apply(File::mkdirs)
            val collector = RecordingMessageCollector()
            val arguments = K2JVMCompilerArguments().apply {
                destination = output.absolutePath
                classpath = System.getProperty("java.class.path")
                freeArgs = listOf(source.absolutePath)
                jvmTarget = "17"
                noReflect = true
                noStdlib = true
                disableDefaultScriptingPlugin = true
                pluginClasspaths = arrayOf(
                    requireNotNull(System.getProperty("stringVeil.compilerPluginJar")),
                )
            }

            val exitCode = K2JVMCompiler().exec(collector, Services.EMPTY, arguments)

            assertEquals(ExitCode.OK, exitCode, collector.messages.joinToString("\n"))
            assertTrue(
                collector.messages.any {
                    it.contains(
                        "string-veil: transformed=5, skipped=2, layers=16, " +
                            "methods=AES:1|RANDOM_ALL:3|RANDOM_SELECTED:1",
                    )
                },
                collector.messages.joinToString("\n"),
            )

            val classFiles = output.walkTopDown().filter { it.extension == "class" }.toList()
            listOf(
                "class-secret",
                "expression-secret",
                "function-secret",
                "aes-secret",
                "selected-secret",
            ).forEach { secret ->
                assertTrue(
                    classFiles.none { it.readBytes().containsUtf8(secret) },
                    "Plaintext secret remains in compiled output: $secret",
                )
            }
            listOf("visible", "expression-visible", "ordinary").forEach { plain ->
                assertTrue(
                    classFiles.any { it.readBytes().containsUtf8(plain) },
                    "Expected unobfuscated string is missing: $plain",
                )
            }

            URLClassLoader(arrayOf(output.toURI().toURL()), javaClass.classLoader).use { loader ->
                val sample = loader.loadClass("SampleKt")
                assertEquals("expression-secret", sample.getMethod("getDirect").invoke(null))
                assertEquals("function-secret", sample.getMethod("functionSecret").invoke(null))
                assertEquals("aes-secret", sample.getMethod("aesSecret").invoke(null))
                assertEquals("selected-secret", sample.getMethod("getSelected").invoke(null))
                assertEquals(
                    "expression-visible",
                    sample.getMethod("excludedExpression").invoke(null),
                )
                assertEquals("ordinary", sample.getMethod("ordinary").invoke(null))

                val holderClass = loader.loadClass("SecretHolder")
                val holder = holderClass.getConstructor().newInstance()
                assertEquals("class-secret", holderClass.getMethod("getHidden").invoke(holder))
                assertEquals("visible", holderClass.getMethod("visible").invoke(holder))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `expression @Obfuscate on a compound expression fails the build`() {
        val root = createTempDirectory("string-veil-expression-probe").toFile()
        try {
            val source = root.resolve("Expression.kt").apply {
                writeText(
                    """
                    import io.github.khiaroslav.stringveil.annotations.Obfuscate

                    val conditional = @Obfuscate if (System.currentTimeMillis() > 0) {
                        "conditional-expression-secret"
                    } else {
                        "conditional-expression-public"
                    }
                    val template = @Obfuscate "template-expression-head-${'$'}{System.nanoTime()}-tail"
                    """.trimIndent(),
                )
            }
            val output = root.resolve("classes").apply(File::mkdirs)
            val collector = RecordingMessageCollector()
            val arguments = K2JVMCompilerArguments().apply {
                destination = output.absolutePath
                classpath = System.getProperty("java.class.path")
                freeArgs = listOf(source.absolutePath)
                jvmTarget = "17"
                noReflect = true
                noStdlib = true
                disableDefaultScriptingPlugin = true
                pluginClasspaths = arrayOf(
                    requireNotNull(System.getProperty("stringVeil.compilerPluginJar")),
                )
            }

            val exitCode = K2JVMCompiler().exec(collector, Services.EMPTY, arguments)

            // @Obfuscate on the `if`-expression and on the interpolated template maps to no literal.
            // Rather than silently leaking the branch/segment literals as plaintext, the build fails.
            assertEquals(
                ExitCode.COMPILATION_ERROR,
                exitCode,
                collector.messages.joinToString("\n"),
            )
            val unapplied = collector.messages.count {
                it.startsWith("ERROR:") && it.contains("was not applied to any string literal")
            }
            assertEquals(
                2,
                unapplied,
                "both compound-expression @Obfuscate annotations should be reported:\n" +
                    collector.messages.joinToString("\n"),
            )
        } finally {
            root.deleteRecursively()
        }
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

    private fun ByteArray.containsUtf8(value: String): Boolean {
        val expected = value.encodeToByteArray()
        if (expected.size > size) return false

        return indices.any { start ->
            start + expected.size <= size &&
                expected.indices.all { offset -> this[start + offset] == expected[offset] }
        }
    }
}
