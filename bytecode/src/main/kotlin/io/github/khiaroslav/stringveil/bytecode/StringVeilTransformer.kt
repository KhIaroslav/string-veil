package io.github.khiaroslav.stringveil.bytecode

import io.github.khiaroslav.stringveil.encoder.EncryptionContext
import io.github.khiaroslav.stringveil.encoder.LayeredStringCipher
import io.github.khiaroslav.stringveil.encoder.ProtectionConfig
import io.github.khiaroslav.stringveil.encoder.StringCipher
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.FrameNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.MethodInsnNode

/**
 * Rewrites `@Obfuscate` string literals in a single compiled class: each selected `LDC "..."` is
 * replaced with `StringDecoder.decode(<int[] container>)`. Works on any JVM class file (Kotlin or
 * Java), independent of the Kotlin compiler version.
 *
 * Scope (declaration-level, resolved from annotations that survive to bytecode):
 * - `@Obfuscate` on the class hides every string in its fields and methods…
 * - …except members marked `@DoNotObfuscate`.
 * - `@Obfuscate` on a single field or method hides only that member.
 * - a `const`/`ConstantValue` field selected for obfuscation is reported as an error (its value is
 *   inlined into every use site and cannot be recovered from bytecode).
 *
 * The annotation descriptors are injectable so tests can drive the engine without touching the
 * shipped annotations module.
 */
public class StringVeilTransformer(
    private val cipher: StringCipher = LayeredStringCipher(),
    private val obfuscateDescriptor: String = OBFUSCATE_DESCRIPTOR,
    private val doNotObfuscateDescriptor: String = DO_NOT_OBFUSCATE_DESCRIPTOR,
    private val decoderInternalName: String = STRING_DECODER,
) {
    public data class Result(val bytes: ByteArray, val errors: List<String>)

    public fun transform(original: ByteArray): Result {
        val node = ClassNode()
        ClassReader(original).accept(node, 0)
        val errors = mutableListOf<String>()

        val classObfuscated = node.annotations().has(obfuscateDescriptor)

        // Which String fields' initializers should be hidden.
        val obfuscatedFields = HashSet<String>()
        for (field in node.fields) {
            if (field.desc != STRING_TYPE) continue
            val annotations = (field.visibleAnnotations to field.invisibleAnnotations)
            val excluded = annotations.has(doNotObfuscateDescriptor)
            val included = annotations.has(obfuscateDescriptor)
            if (excluded || !(included || classObfuscated)) continue
            if (field.value != null) {
                errors += "@Obfuscate cannot protect the compile-time constant '${field.name}' in " +
                    "${node.name.toDotted()} — its value is inlined into every use site. Remove `const`."
                continue
            }
            obfuscatedFields += "${field.name}:${field.desc}"
        }

        var changed = false
        var counter = 0
        for (method in node.methods) {
            val methodWide = when (method.name) {
                "<init>", "<clinit>" -> classObfuscated
                else -> {
                    val annotations = (method.visibleAnnotations to method.invisibleAnnotations)
                    !annotations.has(doNotObfuscateDescriptor) &&
                        (annotations.has(obfuscateDescriptor) || classObfuscated)
                }
            }

            for (insn in method.instructions.toArray()) {
                if (insn !is LdcInsnNode) continue
                val value = insn.cst as? String ?: continue
                if (value.isEmpty()) continue

                val next = insn.nextRealInsn()
                val hide = if (
                    next is FieldInsnNode &&
                    (next.opcode == Opcodes.PUTFIELD || next.opcode == Opcodes.PUTSTATIC)
                ) {
                    "${next.name}:${next.desc}" in obfuscatedFields
                } else {
                    methodWide
                }
                if (!hide) continue

                val container = cipher.encrypt(
                    value.toByteArray(Charsets.UTF_8),
                    EncryptionContext(node.name, counter++),
                    ProtectionConfig(),
                ).container
                method.instructions.insertBefore(insn, decodeInstructions(container))
                method.instructions.remove(insn)
                changed = true
            }
        }

        if (!changed) return Result(original, errors)
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        return Result(writer.toByteArray(), errors)
    }

    private fun decodeInstructions(container: IntArray): InsnList {
        val list = InsnList()
        list.add(pushInt(container.size))
        list.add(IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT))
        container.forEachIndexed { index, word ->
            list.add(InsnNode(Opcodes.DUP))
            list.add(pushInt(index))
            list.add(pushInt(word))
            list.add(InsnNode(Opcodes.IASTORE))
        }
        list.add(
            MethodInsnNode(
                Opcodes.INVOKESTATIC,
                decoderInternalName,
                "decode",
                "([I)Ljava/lang/String;",
                false,
            ),
        )
        return list
    }

    private companion object {
        const val STRING_TYPE = "Ljava/lang/String;"
        const val STRING_DECODER = "io/github/khiaroslav/stringveil/runtime/StringDecoder"
        const val OBFUSCATE_DESCRIPTOR = "Lio/github/khiaroslav/stringveil/annotations/Obfuscate;"
        const val DO_NOT_OBFUSCATE_DESCRIPTOR =
            "Lio/github/khiaroslav/stringveil/annotations/DoNotObfuscate;"
    }
}

private fun pushInt(value: Int): AbstractInsnNode = when (value) {
    in -1..5 -> InsnNode(Opcodes.ICONST_0 + value)
    in Byte.MIN_VALUE..Byte.MAX_VALUE -> IntInsnNode(Opcodes.BIPUSH, value)
    in Short.MIN_VALUE..Short.MAX_VALUE -> IntInsnNode(Opcodes.SIPUSH, value)
    else -> LdcInsnNode(value)
}

private fun AbstractInsnNode.nextRealInsn(): AbstractInsnNode? {
    var candidate = next
    while (candidate is LabelNode || candidate is LineNumberNode || candidate is FrameNode) {
        candidate = candidate.next
    }
    return candidate
}

private fun ClassNode.annotations(): Pair<List<AnnotationNode>?, List<AnnotationNode>?> =
    visibleAnnotations to invisibleAnnotations

private fun Pair<List<AnnotationNode>?, List<AnnotationNode>?>.has(descriptor: String): Boolean =
    first?.any { it.desc == descriptor } == true || second?.any { it.desc == descriptor } == true

private fun String.toDotted(): String = replace('/', '.')
