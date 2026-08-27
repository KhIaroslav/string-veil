package io.github.khiaroslav.stringveil.bytecode.gradle

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

/**
 * Proves the Gradle wiring: a JVM consumer that applies the plugin has its `@Obfuscate` string
 * literals rewritten in the compiled `.class` files, while unannotated classes are untouched. The
 * consumer defines its own BINARY-retained `@Obfuscate` (matching the real fully-qualified name) so
 * the test needs nothing published.
 */
class StringVeilBytecodePluginTest {
    @Test
    fun `obfuscates annotated classes in a consumer build`() {
        val projectDir = createTempDirectory("string-veil-bytecode-consumer").toFile()
        try {
            write(projectDir, "settings.gradle.kts", """rootProject.name = "consumer"""")
            write(
                projectDir,
                "build.gradle.kts",
                """
                plugins {
                    java
                    id("io.github.khiaroslav.string-veil.bytecode")
                }
                """.trimIndent(),
            )
            write(
                projectDir,
                "src/main/java/io/github/khiaroslav/stringveil/annotations/Obfuscate.java",
                """
                package io.github.khiaroslav.stringveil.annotations;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.CLASS)
                @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
                public @interface Obfuscate {}
                """.trimIndent(),
            )
            write(
                projectDir,
                "src/main/java/sample/Secret.java",
                """
                package sample;
                import io.github.khiaroslav.stringveil.annotations.Obfuscate;
                @Obfuscate
                public class Secret {
                    public String value = "consumer-secret";
                }
                """.trimIndent(),
            )
            write(
                projectDir,
                "src/main/java/sample/Plain.java",
                """
                package sample;
                public class Plain {
                    public String keep = "kept-value";
                }
                """.trimIndent(),
            )

            val result = GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments("classes", "--stacktrace")
                .build()

            assertEquals(TaskOutcome.SUCCESS, result.task(":classes")?.outcome, result.output)

            val classes = projectDir.resolve("build/classes/java/main")
            assertFalse(
                classes.resolve("sample/Secret.class").readBytes().containsUtf8("consumer-secret"),
                "annotated literal was not obfuscated:\n${result.output}",
            )
            assertTrue(
                classes.resolve("sample/Plain.class").readBytes().containsUtf8("kept-value"),
                "unannotated literal was wrongly obfuscated",
            )
        } finally {
            projectDir.deleteRecursively()
        }
    }

    private fun write(root: File, path: String, content: String) {
        root.resolve(path).apply { parentFile.mkdirs(); writeText(content) }
    }

    private fun ByteArray.containsUtf8(value: String): Boolean {
        val needle = value.encodeToByteArray()
        return indices.any { start ->
            start + needle.size <= size && needle.indices.all { this[start + it] == needle[it] }
        }
    }
}
