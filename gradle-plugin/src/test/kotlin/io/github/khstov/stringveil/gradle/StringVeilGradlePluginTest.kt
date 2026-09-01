package io.github.khstov.stringveil.gradle

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

/**
 * Verifies the Gradle plugin end to end: a consumer that applies it has its `@Obfuscate` string
 * literals rewritten in the compiled bytecode after compilation, `@DoNotObfuscate` members are kept,
 * and `enabled = false` turns everything off. The plugin itself is injected with
 * `withPluginClasspath()`; the consumer-facing `annotations` and `runtime` artifacts are installed
 * into a throwaway Maven repo.
 */
class StringVeilGradlePluginTest {
    @Test
    fun `obfuscates annotated literals and keeps excluded ones`() {
        withConsumer(enabled = true) { projectDir, result ->
            assertEquals(TaskOutcome.SUCCESS, result.task(":classes")?.outcome, result.output)
            val consumer = projectDir.resolve("build/classes/java/main/Consumer.class").readBytes()
            assertFalse(consumer.containsUtf8("gradle-plugin-secret"), result.output)
            assertTrue(consumer.containsUtf8("visible-value"))
        }
    }

    @Test
    fun `disabled extension leaves literals untouched`() {
        withConsumer(enabled = false) { projectDir, result ->
            assertEquals(TaskOutcome.SUCCESS, result.task(":classes")?.outcome, result.output)
            val consumer = projectDir.resolve("build/classes/java/main/Consumer.class").readBytes()
            assertTrue(consumer.containsUtf8("gradle-plugin-secret"))
        }
    }

    private fun withConsumer(
        enabled: Boolean,
        assertions: (Path, org.gradle.testkit.runner.BuildResult) -> Unit,
    ) {
        val projectDir = Files.createTempDirectory("string-veil-consumer")
        try {
            val repo = projectDir.resolve("repo")
            install(repo, "annotations", requiredJar("stringVeil.annotationsJar"))
            install(repo, "runtime", requiredJar("stringVeil.runtimeJar"))

            projectDir.resolve("settings.gradle.kts").writeText("""rootProject.name = "consumer"""")
            projectDir.resolve("build.gradle.kts").writeText(
                """
                plugins {
                    java
                    id("io.github.khstov.string-veil")
                }
                repositories {
                    maven { url = uri("${repo.toUri()}") }
                    mavenCentral()
                }
                stringVeil {
                    enabled.set($enabled)
                }
                """.trimIndent(),
            )
            write(
                projectDir,
                "src/main/java/Consumer.java",
                """
                import io.github.khstov.stringveil.annotations.Obfuscate;
                import io.github.khstov.stringveil.annotations.DoNotObfuscate;
                @Obfuscate
                public class Consumer {
                    public String secret = "gradle-plugin-secret";
                    @DoNotObfuscate public String visible = "visible-value";
                }
                """.trimIndent(),
            )

            val result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments("classes", "--stacktrace")
                .build()

            assertions(projectDir, result)
        } finally {
            projectDir.toFile().deleteRecursively()
        }
    }

    private fun install(repo: Path, artifactId: String, jar: Path) {
        val version = StringVeilCoordinates.VERSION
        val dir = repo
            .resolve(StringVeilCoordinates.GROUP.replace('.', '/'))
            .resolve(artifactId)
            .resolve(version)
            .createDirectories()
        Files.copy(jar, dir.resolve("$artifactId-$version.jar"))
        dir.resolve("$artifactId-$version.pom").writeText(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>${StringVeilCoordinates.GROUP}</groupId>
              <artifactId>$artifactId</artifactId>
              <version>$version</version>
            </project>
            """.trimIndent(),
        )
    }

    private fun write(root: Path, path: String, content: String) {
        root.resolve(path).apply { parent.createDirectories(); writeText(content) }
    }

    private fun requiredJar(property: String): Path =
        Path.of(requireNotNull(System.getProperty(property)) { "missing system property $property" })

    private fun ByteArray.containsUtf8(value: String): Boolean {
        val needle = value.encodeToByteArray()
        return indices.any { start ->
            start + needle.size <= size && needle.indices.all { this[start + it] == needle[it] }
        }
    }
}
