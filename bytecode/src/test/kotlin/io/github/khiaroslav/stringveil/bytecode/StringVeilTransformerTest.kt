package io.github.khiaroslav.stringveil.bytecode

import java.io.File
import java.net.URLClassLoader
import javax.tools.ToolProvider
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proves the bytecode obfuscator end-to-end on real compiled classes: `@Obfuscate` string literals
 * are gone from the transformed `.class`, the excluded / unannotated ones stay, and the transformed
 * class still decodes to the original values at runtime.
 *
 * Uses test-local `@Obf` / `@Skip` annotations (BINARY-retained, so they survive to bytecode) so the
 * engine is exercised without touching the shipped annotations module.
 */
class StringVeilTransformerTest {
    private val transformer = StringVeilTransformer(
        obfuscateDescriptor = "Lveil/Obf;",
        doNotObfuscateDescriptor = "Lveil/Skip;",
    )

    @Test
    fun `hides scoped literals, keeps excluded, and decodes at runtime`() {
        val root = createTempDirectory("bytecode-proof").toFile()
        try {
            val classes = compile(
                root,
                "veil/Obf.java" to ANNOTATION_OBF,
                "veil/Skip.java" to ANNOTATION_SKIP,
                "sample/Fixture.java" to FIXTURE,
            )

            val fixtureFile = classes.resolve("sample/Fixture.class")
            val result = transformer.transform(fixtureFile.readBytes())
            assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))

            // Hidden: class-scoped fields, class-scoped method, one field-scoped.
            listOf("class-secret", "also-secret", "method-secret").forEach {
                assertFalse(result.bytes.containsUtf8(it), "leaked: $it")
            }
            // Kept: @Skip field, @Skip method.
            listOf("keep-me", "visible-method").forEach {
                assertTrue(result.bytes.containsUtf8(it), "wrongly hidden: $it")
            }

            // The transformed class must still produce the original values at runtime.
            fixtureFile.writeBytes(result.bytes)
            URLClassLoader(arrayOf(classes.toURI().toURL()), javaClass.classLoader).use { loader ->
                val fixture = loader.loadClass("sample.Fixture")
                val instance = fixture.getDeclaredConstructor().newInstance()
                assertEquals("class-secret", fixture.getField("classField").get(instance))
                assertEquals("also-secret", fixture.getField("plainInScope").get(instance))
                assertEquals("keep-me", fixture.getField("excluded").get(instance))
                assertEquals("method-secret", fixture.getMethod("greeting").invoke(instance))
                assertEquals("visible-method", fixture.getMethod("visible").invoke(instance))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `field-level obfuscation works without a class annotation`() {
        val root = createTempDirectory("bytecode-field").toFile()
        try {
            val classes = compile(
                root,
                "veil/Obf.java" to ANNOTATION_OBF,
                "veil/Skip.java" to ANNOTATION_SKIP,
                "sample/Plain.java" to PLAIN,
            )
            val result = transformer.transform(classes.resolve("sample/Plain.class").readBytes())
            assertTrue(result.errors.isEmpty())
            assertFalse(result.bytes.containsUtf8("prop-secret"), "field @Obf not hidden")
            assertTrue(result.bytes.containsUtf8("plain-value"), "unannotated field wrongly hidden")
            assertTrue(result.bytes.containsUtf8("plain-method"), "unannotated method wrongly hidden")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `const field is reported as an error`() {
        val root = createTempDirectory("bytecode-const").toFile()
        try {
            val classes = compile(
                root,
                "veil/Obf.java" to ANNOTATION_OBF,
                "veil/Skip.java" to ANNOTATION_SKIP,
                "sample/WithConst.java" to WITH_CONST,
            )
            val result = transformer.transform(classes.resolve("sample/WithConst.class").readBytes())
            assertTrue(
                result.errors.any { it.contains("constant") && it.contains("SECRET") },
                "expected a const error: ${result.errors}",
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun compile(root: File, vararg sources: Pair<String, String>): File {
        val sourceFiles = sources.map { (path, content) ->
            root.resolve(path).apply { parentFile.mkdirs(); writeText(content) }
        }
        val output = root.resolve("classes").apply { mkdirs() }
        val exit = ToolProvider.getSystemJavaCompiler().run(
            null, null, null,
            "-d", output.absolutePath,
            *sourceFiles.map { it.absolutePath }.toTypedArray(),
        )
        assertEquals(0, exit, "javac failed")
        return output
    }

    private fun ByteArray.containsUtf8(value: String): Boolean {
        val needle = value.encodeToByteArray()
        return indices.any { start ->
            start + needle.size <= size && needle.indices.all { this[start + it] == needle[it] }
        }
    }

    private companion object {
        const val ANNOTATION_OBF = """
            package veil;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS)
            @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
            public @interface Obf {}
        """

        const val ANNOTATION_SKIP = """
            package veil;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.CLASS)
            @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
            public @interface Skip {}
        """

        const val FIXTURE = """
            package sample;
            import veil.Obf;
            import veil.Skip;

            @Obf
            public class Fixture {
                public String classField = "class-secret";
                public String plainInScope = "also-secret";
                @Skip public String excluded = "keep-me";
                public String greeting() { return "method-secret"; }
                @Skip public String visible() { return "visible-method"; }
            }
        """

        const val PLAIN = """
            package sample;
            import veil.Obf;

            public class Plain {
                @Obf public String only = "prop-secret";
                public String plain = "plain-value";
                public String m() { return "plain-method"; }
            }
        """

        const val WITH_CONST = """
            package sample;
            import veil.Obf;

            public class WithConst {
                @Obf public static final String SECRET = "const-secret";
            }
        """
    }
}
