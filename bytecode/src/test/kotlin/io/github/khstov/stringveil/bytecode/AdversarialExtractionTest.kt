package io.github.khstov.stringveil.bytecode

import io.github.khstov.stringveil.runtime.StringDecoder
import java.io.File
import javax.tools.ToolProvider
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode

/**
 * Honest adversarial baseline. String Veil defeats off-the-shelf static extraction — a plain byte
 * scan finds no plaintext — but the container is a constant `int[]` built inline and the decoder
 * ships with the app, so a purpose-built extractor that knows the single `StringDecoder.decode(int[])`
 * shape reconstructs every container and decodes it offline: no runtime, no hooking. This documents
 * that obfuscation is cost against automated tooling, not secrecy.
 *
 * It is also the baseline the decoder-diversification work is measured against: a fixed
 * single-shape extractor must recover fewer strings once decoders diversify.
 */
class AdversarialExtractionTest {
    @Test
    fun `off-the-shelf scan finds nothing but a shape-aware extractor recovers every container`() {
        val root = createTempDirectory("adversarial").toFile()
        try {
            val secrets = listOf(
                "https://internal.example.com/api",
                "internal-feature-flag-checkout",
                "telemetry.internal.example.com/v3",
                "another-internal-secret-value",
            )
            val original = compile(root, secrets).resolve("sample/Secrets.class").readBytes()
            val transformed = StringVeilTransformer(obfuscateDescriptor = "Lveil/Obf;").transform(original).bytes

            // 1) Off-the-shelf: a plain byte scan (strings/grep-style) recovers nothing.
            secrets.forEach { secret ->
                assertFalse(transformed.containsUtf8(secret), "off-the-shelf scan leaked: $secret")
            }

            // 2) Shape-aware extractor: rebuild each container and run the published decoder offline.
            val recovered = extractAndDecode(transformed).toSet()
            val hits = secrets.count { it in recovered }
            println("Adversarial extraction — off-the-shelf: 0/${secrets.size}; shape-aware: $hits/${secrets.size}")
            assertEquals(secrets.toSet(), recovered, "a shape-aware extractor recovers all containers offline")
        } finally {
            root.deleteRecursively()
        }
    }

    private fun extractAndDecode(classBytes: ByteArray): List<String> {
        val node = ClassNode().also { ClassReader(classBytes).accept(it, 0) }
        val out = mutableListOf<String>()
        for (method in node.methods) {
            for (insn in method.instructions) {
                if (insn is MethodInsnNode &&
                    insn.opcode == Opcodes.INVOKESTATIC &&
                    insn.name == "decode" &&
                    insn.desc == "([I)Ljava/lang/String;"
                ) {
                    reconstructContainer(insn)?.let { out += StringDecoder.decode(it) }
                }
            }
        }
        return out
    }

    /** Rebuild the inline `int[]` argument by replaying the `NEWARRAY` … `IASTORE` sequence. */
    private fun reconstructContainer(decodeCall: AbstractInsnNode): IntArray? {
        var newArray: AbstractInsnNode? = decodeCall.previous
        while (newArray != null && !(newArray is IntInsnNode && newArray.opcode == Opcodes.NEWARRAY)) {
            newArray = newArray.previous
        }
        newArray ?: return null
        val size = intValue(newArray.previous) ?: return null
        val array = IntArray(size)
        val stack = ArrayDeque<Int>()
        var cur: AbstractInsnNode? = newArray.next
        while (cur != null && cur != decodeCall) {
            intValue(cur)?.let { stack.addLast(it) }
            if (cur.opcode == Opcodes.IASTORE) {
                val value = stack.removeLast()
                val index = stack.removeLast()
                array[index] = value
            }
            cur = cur.next
        }
        return array
    }

    private fun intValue(insn: AbstractInsnNode?): Int? = when {
        insn is InsnNode && insn.opcode in Opcodes.ICONST_M1..Opcodes.ICONST_5 -> insn.opcode - Opcodes.ICONST_0
        insn is IntInsnNode && (insn.opcode == Opcodes.BIPUSH || insn.opcode == Opcodes.SIPUSH) -> insn.operand
        insn is LdcInsnNode && insn.cst is Int -> insn.cst as Int
        else -> null
    }

    private fun compile(root: File, secrets: List<String>): File {
        val fields = secrets.mapIndexed { i, s -> "    public String s$i = \"$s\";" }.joinToString("\n")
        val sources = listOf(
            "veil/Obf.java" to ANNOTATION_OBF,
            "sample/Secrets.java" to "package sample;\nimport veil.Obf;\n@Obf public class Secrets {\n$fields\n}\n",
        )
        val files = sources.map { (path, content) ->
            root.resolve(path).apply { parentFile.mkdirs(); writeText(content) }
        }
        val output = root.resolve("classes").apply { mkdirs() }
        val exit = ToolProvider.getSystemJavaCompiler()
            .run(null, null, null, "-d", output.absolutePath, *files.map { it.absolutePath }.toTypedArray())
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
        const val ANNOTATION_OBF =
            "package veil;\nimport java.lang.annotation.*;\n" +
                "@Retention(RetentionPolicy.CLASS)\n" +
                "@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})\n" +
                "public @interface Obf {}\n"
    }
}
