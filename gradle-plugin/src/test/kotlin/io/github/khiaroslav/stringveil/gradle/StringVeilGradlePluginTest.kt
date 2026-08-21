package io.github.khiaroslav.stringveil.gradle

import java.net.URLClassLoader
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

class StringVeilGradlePluginTest {
    @Test
    fun `consumer project receives compiler annotations and runtime automatically`() {
        val projectDir = Files.createTempDirectory("string-veil-consumer")
        try {
            val repositoryDir = projectDir.resolve("repository")
            installTestArtifacts(repositoryDir)
            writeConsumerProject(projectDir, repositoryDir)

            val result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("classes", "--stacktrace")
                .build()

            assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlin")?.outcome)

            val outputDirectory = projectDir.resolve("build/classes/kotlin/main")
            val classBytes = outputDirectory.resolve("Consumer.class").readBytes()
            assertFalse(
                classBytes.containsUtf8("gradle-plugin-secret"),
                result.output,
            )
            assertTrue(classBytes.containsUtf8("visible-value"))

            URLClassLoader(
                arrayOf(
                    outputDirectory.toUri().toURL(),
                    requiredJar("stringVeil.runtimeJar").toUri().toURL(),
                ),
                javaClass.classLoader,
            ).use { classLoader ->
                val consumerClass = classLoader.loadClass("Consumer")
                val consumer = consumerClass.getDeclaredConstructor().newInstance()

                assertEquals(
                    "gradle-plugin-secret",
                    consumerClass.getMethod("getSecret").invoke(consumer),
                )
                assertEquals(
                    "visible-value",
                    consumerClass.getMethod("getVisible").invoke(consumer),
                )
            }
        } finally {
            projectDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `disabled extension leaves annotated literal unchanged`() {
        val projectDir = Files.createTempDirectory("string-veil-disabled-consumer")
        try {
            val repositoryDir = projectDir.resolve("repository")
            installTestArtifacts(repositoryDir)
            writeConsumerProject(projectDir, repositoryDir, enabled = false)

            val result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("classes", "--stacktrace")
                .build()

            assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlin")?.outcome)

            val classBytes = projectDir
                .resolve("build/classes/kotlin/main/Consumer.class")
                .readBytes()
            assertTrue(classBytes.containsUtf8("gradle-plugin-secret"))
        } finally {
            projectDir.toFile().deleteRecursively()
        }
    }

    private fun installTestArtifacts(repositoryDir: Path) {
        installArtifact(
            repositoryDir,
            StringVeilCoordinates.ANNOTATIONS_ARTIFACT,
            requiredJar("stringVeil.annotationsJar"),
        )
        installArtifact(
            repositoryDir,
            StringVeilCoordinates.COMPILER_PLUGIN_ARTIFACT,
            requiredJar("stringVeil.compilerPluginJar"),
            dependencies = listOf(
                MavenDependency(
                    groupId = StringVeilCoordinates.GROUP,
                    artifactId = StringVeilCoordinates.RUNTIME_ARTIFACT,
                    version = StringVeilCoordinates.VERSION,
                ),
            ),
        )
        installArtifact(
            repositoryDir,
            StringVeilCoordinates.GRADLE_PLUGIN_ARTIFACT,
            requiredJar("stringVeil.gradlePluginJar"),
            dependencies = listOf(
                MavenDependency(
                    groupId = "org.jetbrains.kotlin",
                    artifactId = "kotlin-gradle-plugin-api",
                    version = KOTLIN_VERSION,
                ),
            ),
        )
        installArtifact(
            repositoryDir,
            StringVeilCoordinates.RUNTIME_ARTIFACT,
            requiredJar("stringVeil.runtimeJar"),
        )
        installPluginMarker(repositoryDir)
    }

    private fun installArtifact(
        repositoryDir: Path,
        artifactId: String,
        jar: Path,
        dependencies: List<MavenDependency> = emptyList(),
    ) {
        val version = StringVeilCoordinates.VERSION
        val artifactDirectory = repositoryDir
            .resolve(StringVeilCoordinates.GROUP.replace('.', '/'))
            .resolve(artifactId)
            .resolve(version)
            .createDirectories()
        val baseName = "$artifactId-$version"

        Files.copy(jar, artifactDirectory.resolve("$baseName.jar"))
        artifactDirectory.resolve("$baseName.pom").writeText(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>${StringVeilCoordinates.GROUP}</groupId>
              <artifactId>$artifactId</artifactId>
              <version>$version</version>
              ${dependencies.toPomXml()}
            </project>
            """.trimIndent(),
        )
    }

    private fun installPluginMarker(repositoryDir: Path) {
        val pluginId = "io.github.khiaroslav.string-veil"
        val markerArtifact = "$pluginId.gradle.plugin"
        val version = StringVeilCoordinates.VERSION
        val markerDirectory = repositoryDir
            .resolve(pluginId.replace('.', '/'))
            .resolve(markerArtifact)
            .resolve(version)
            .createDirectories()

        markerDirectory.resolve("$markerArtifact-$version.pom").writeText(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>$pluginId</groupId>
              <artifactId>$markerArtifact</artifactId>
              <version>$version</version>
              <packaging>pom</packaging>
              ${listOf(
                MavenDependency(
                    groupId = StringVeilCoordinates.GROUP,
                    artifactId = StringVeilCoordinates.GRADLE_PLUGIN_ARTIFACT,
                    version = version,
                ),
            ).toPomXml()}
            </project>
            """.trimIndent(),
        )
    }

    private fun writeConsumerProject(
        projectDir: Path,
        repositoryDir: Path,
        enabled: Boolean = true,
    ) {
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    maven { url = uri("${repositoryDir.toUri()}") }
                    gradlePluginPortal()
                    mavenCentral()
                }
            }

            dependencyResolutionManagement {
                repositories {
                    maven { url = uri("${repositoryDir.toUri()}") }
                    mavenCentral()
                }
            }

            rootProject.name = "string-veil-consumer"
            """.trimIndent(),
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                kotlin("jvm") version "$KOTLIN_VERSION"
                id("io.github.khiaroslav.string-veil") version "${StringVeilCoordinates.VERSION}"
            }

            kotlin {
                jvmToolchain(17)
            }

            stringVeil {
                enabled = $enabled
            }
            """.trimIndent(),
        )
        val sourceDirectory = projectDir.resolve("src/main/kotlin").createDirectories()
        sourceDirectory.resolve("Consumer.kt").writeText(
            """
            import io.github.khiaroslav.stringveil.annotations.Obfuscate

            class Consumer {
                @Obfuscate
                val secret = "gradle-plugin-secret"

                val visible = "visible-value"
            }
            """.trimIndent(),
        )
    }

    private fun requiredJar(property: String): Path =
        Path.of(requireNotNull(System.getProperty(property)) { "Missing $property" })

    private fun List<MavenDependency>.toPomXml(): String {
        if (isEmpty()) return ""

        return joinToString(
            prefix = "<dependencies>\n",
            postfix = "\n</dependencies>",
            separator = "\n",
        ) { dependency ->
            """
            <dependency>
              <groupId>${dependency.groupId}</groupId>
              <artifactId>${dependency.artifactId}</artifactId>
              <version>${dependency.version}</version>
            </dependency>
            """.trimIndent()
        }
    }

    private fun ByteArray.containsUtf8(value: String): Boolean {
        val needle = value.encodeToByteArray()
        if (needle.isEmpty() || needle.size > size) return false

        return indices
            .take(size - needle.size + 1)
            .any { start ->
                needle.indices.all { index -> this[start + index] == needle[index] }
            }
    }

    private data class MavenDependency(
        val groupId: String,
        val artifactId: String,
        val version: String,
    )

    private companion object {
        private const val KOTLIN_VERSION = "2.3.21"
    }
}
