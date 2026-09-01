package io.github.khstov.stringveil.bytecode

import io.github.khstov.stringveil.encoder.EncryptionContext
import io.github.khstov.stringveil.encoder.LayeredStringCipher
import io.github.khstov.stringveil.encoder.ProtectionConfig
import io.github.khstov.stringveil.encoder.StringCipher
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
 * replaced with `StringDecoder.decode(<int[] container>)`. It operates on Kotlin or Java class files
 * without binding to Kotlin compiler internals; compatibility still depends on the emitted bytecode
 * and annotation layout and is verified against concrete tool versions.
 *
 * Scope (declaration-level, resolved from annotations that survive to bytecode):
 * - `@Obfuscate` on a class selects direct string constants in that class's methods.
 * - members marked `@DoNotObfuscate` are excluded from a supported enclosing class scope.
 * - `@Obfuscate` on a method selects direct string constants in that bytecode method.
 * - a selected String field is recognized when its literal is stored directly by the next real
 *   instruction.
 * - a `const`/`ConstantValue` field selected for obfuscation is reported as an error (its value is
 *   inlined into every use site and cannot be recovered from bytecode).
 *
 * Generated lambdas, nested classes, complex initializers, and other non-direct bytecode shapes
 * require separate coverage. Directly annotated computed-property getters are supported. The
 * annotation descriptors and decoder are injectable so tests and build integrations can select
 * their runtime without modifying the annotations module.
 */
public class StringVeilTransformer(
    private val cipher: StringCipher = LayeredStringCipher(),
    private val obfuscateDescriptor: String = OBFUSCATE_DESCRIPTOR,
    private val doNotObfuscateDescriptor: String = DO_NOT_OBFUSCATE_DESCRIPTOR,
    private val decoderInternalName: String = STRING_DECODER,
    private val markerInternalName: String = MARKER,
    private val failOnSecretLike: Boolean = false,
) {
    public data class Result(
        val bytes: ByteArray,
        val errors: List<String>,
        val warnings: List<String>,
    )

    public data class Outcome(
        val changed: Boolean,
        val errors: List<String>,
        val warnings: List<String>,
    )

    public fun transform(original: ByteArray): Result {
        val node = ClassNode()
        ClassReader(original).accept(node, 0)
        val outcome = transformNode(node)
        if (!outcome.changed) return Result(original, outcome.errors, outcome.warnings)
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        return Result(writer.toByteArray(), outcome.errors, outcome.warnings)
    }

    /** Mutates [node] in place. Shared by the byte-array API and build integrations. */
    public fun transformNode(node: ClassNode): Outcome {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val classObfuscated = (node.visibleAnnotations to node.invisibleAnnotations)
            .has(obfuscateDescriptor)

        // Kotlin does not put property annotations on the JVM field; it emits a synthetic
        // `get<Name>$annotations()` method that carries them. Fold those back onto the property name.
        val propertyObfuscated = HashSet<String>()
        val propertyExcluded = HashSet<String>()
        // A computed property (custom getter, no backing field) keeps its literal in the accessor
        // method body rather than a field initializer, so record the accessor to obfuscate directly.
        // `get<Name>$annotations()` mirrors the accessor `get<Name>()` exactly, so drop the suffix.
        val obfuscatedAccessors = HashSet<String>()
        for (method in node.methods) {
            val property = kotlinPropertyName(method.name) ?: continue
            val annotations = method.visibleAnnotations to method.invisibleAnnotations
            when {
                annotations.has(doNotObfuscateDescriptor) -> propertyExcluded += property
                annotations.has(obfuscateDescriptor) -> {
                    propertyObfuscated += property
                    obfuscatedAccessors += method.name.removeSuffix("\$annotations")
                }
            }
        }

        val obfuscatedFields = HashSet<String>()
        for (field in node.fields) {
            if (field.desc != STRING_TYPE) continue
            val annotations = field.visibleAnnotations to field.invisibleAnnotations
            val excluded = annotations.has(doNotObfuscateDescriptor) || field.name in propertyExcluded
            val included = annotations.has(obfuscateDescriptor) || field.name in propertyObfuscated
            if (excluded) continue
            if (!(included || classObfuscated)) continue
            if (field.value != null) {
                errors += "@Obfuscate cannot protect the compile-time constant '${field.name}' in " +
                    "${node.name.toDotted()} — its value is inlined into every use site. Remove `const`."
                continue
            }
            obfuscatedFields += "${field.name}:${field.desc}"
        }

        var changed = false
        var counter = 0

        // Replaces a string literal with the decode over its randomized container, reporting
        // secret-looking values. Shared by the annotation-scope path and the `obfuscate(...)` marker.
        fun encodeLiteral(value: String): InsnList {
            SecretLiteralHeuristics.detect(value)?.let { reason ->
                val message = "'${node.name.toDotted()}' obfuscates a literal that looks like " +
                    "$reason. String Veil is obfuscation, not encryption; move real secrets " +
                    "server-side or inject them at runtime (see SECURITY.md)."
                if (failOnSecretLike) errors += message else warnings += message
            }
            val container = cipher.encrypt(
                value.toByteArray(Charsets.UTF_8),
                EncryptionContext(node.name, counter++),
                ProtectionConfig(),
            ).container
            return decodeInstructions(container)
        }

        for (method in node.methods) {
            val methodWide = when (method.name) {
                "<init>", "<clinit>" -> classObfuscated
                else -> {
                    val annotations = method.visibleAnnotations to method.invisibleAnnotations
                    !annotations.has(doNotObfuscateDescriptor) &&
                        (annotations.has(obfuscateDescriptor) ||
                            method.name in obfuscatedAccessors ||
                            classObfuscated)
                }
            }

            for (insn in method.instructions.toArray()) {
                if (insn !is LdcInsnNode) continue
                val value = insn.cst as? String ?: continue
                val next = insn.nextRealInsn()

                // `obfuscate("literal")` — a self-contained opt-in, independent of annotation scope.
                // Replace the literal and drop the marker call; an empty literal keeps only the `LDC`.
                if (next is MethodInsnNode && next.isMarkerCall()) {
                    if (value.isNotEmpty()) {
                        method.instructions.insertBefore(insn, encodeLiteral(value))
                        method.instructions.remove(insn)
                    }
                    method.instructions.remove(next)
                    changed = true
                    continue
                }

                if (value.isEmpty()) continue
                val hide = if (
                    next is FieldInsnNode &&
                    (next.opcode == Opcodes.PUTFIELD || next.opcode == Opcodes.PUTSTATIC)
                ) {
                    "${next.name}:${next.desc}" in obfuscatedFields
                } else {
                    methodWide
                }
                if (!hide) continue

                method.instructions.insertBefore(insn, encodeLiteral(value))
                method.instructions.remove(insn)
                changed = true
            }

            // Fail-closed: any marker call still present was not applied to a string literal.
            for (insn in method.instructions.toArray()) {
                if (insn is MethodInsnNode && insn.isMarkerCall()) {
                    warnings += "obfuscate() in '${node.name.toDotted()}' was not called on a string " +
                        "literal, so its value is not obfuscated. Pass a direct \"literal\"."
                }
            }
        }

        return Outcome(changed, errors, warnings)
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

    private fun MethodInsnNode.isMarkerCall(): Boolean =
        opcode == Opcodes.INVOKESTATIC &&
            owner == markerInternalName &&
            name == MARKER_METHOD &&
            desc == MARKER_DESC

    private companion object {
        const val STRING_TYPE = "Ljava/lang/String;"
        const val STRING_DECODER = "io/github/khstov/stringveil/runtime/StringDecoder"
        const val MARKER = "io/github/khstov/stringveil/StringVeil"
        const val MARKER_METHOD = "obfuscate"
        const val MARKER_DESC = "(Ljava/lang/String;)Ljava/lang/String;"
        const val OBFUSCATE_DESCRIPTOR = "Lio/github/khstov/stringveil/annotations/Obfuscate;"
        const val DO_NOT_OBFUSCATE_DESCRIPTOR =
            "Lio/github/khstov/stringveil/annotations/DoNotObfuscate;"
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

private fun Pair<List<AnnotationNode>?, List<AnnotationNode>?>.has(descriptor: String): Boolean =
    first?.any { it.desc == descriptor } == true || second?.any { it.desc == descriptor } == true

/** Recovers the property name from Kotlin's synthetic `get<Name>$annotations()` holder method. */
private fun kotlinPropertyName(methodName: String): String? {
    val base = methodName.removeSuffix("\$annotations")
    if (base == methodName) return null
    val name = when {
        base.startsWith("get") && base.length > 3 -> base.substring(3)
        base.startsWith("is") && base.length > 2 -> base.substring(2)
        else -> base
    }
    return name.replaceFirstChar { it.lowercaseChar() }
}

private fun String.toDotted(): String = replace('/', '.')
