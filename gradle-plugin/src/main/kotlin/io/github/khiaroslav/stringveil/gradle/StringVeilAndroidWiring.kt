package io.github.khiaroslav.stringveil.gradle

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.AndroidComponentsExtension
import io.github.khiaroslav.stringveil.bytecode.StringVeilTransformer
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode

/**
 * Wires String Veil into an Android build through AGP's ASM instrumentation, so classes are
 * transformed before dexing. Loaded only when an AGP plugin is applied, so JVM consumers never touch
 * the Android API.
 */
internal object StringVeilAndroidWiring {
    fun apply(project: Project, extension: StringVeilExtension) {
        val components = project.extensions
            .findByType(AndroidComponentsExtension::class.java) ?: return
        components.onVariants { variant ->
            if (!extension.enabled.getOrElse(true)) return@onVariants
            variant.instrumentation.transformClassesWith(
                StringVeilAsmClassVisitorFactory::class.java,
                InstrumentationScope.PROJECT,
            ) { parameters ->
                parameters.failOnSecretLike.set(extension.failOnSecretLikeLiterals)
            }
            variant.instrumentation.setAsmFramesComputationMode(FramesComputationMode.COPY_FRAMES)
        }
    }
}

/** Parameters passed from the build into each per-class instrumentation worker. */
public interface StringVeilInstrumentationParameters : InstrumentationParameters {
    @get:Input
    public val failOnSecretLike: Property<Boolean>
}

/** AGP entry point: builds the per-class visitor that runs the String Veil transform. */
public abstract class StringVeilAsmClassVisitorFactory :
    AsmClassVisitorFactory<StringVeilInstrumentationParameters> {

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor,
    ): ClassVisitor = StringVeilBridgeClassVisitor(
        nextClassVisitor,
        parameters.get().failOnSecretLike.getOrElse(false),
    )

    override fun isInstrumentable(classData: ClassData): Boolean = true
}

private class StringVeilBridgeClassVisitor(
    private val downstream: ClassVisitor,
    private val failOnSecretLike: Boolean,
) : ClassNode(Opcodes.ASM9) {
    override fun visitEnd() {
        super.visitEnd()
        val outcome = StringVeilTransformer(
            decoderInternalName = NATIVE_DECODER,
            failOnSecretLike = failOnSecretLike,
        ).transformNode(this)
        outcome.warnings.forEach { System.err.println("string-veil: $it") }
        if (outcome.errors.isNotEmpty()) {
            throw IllegalStateException("string-veil: " + outcome.errors.joinToString("\n"))
        }
        accept(downstream)
    }

    private companion object {
        const val NATIVE_DECODER = "io/github/khiaroslav/stringveil/runtime/NativeStringDecoder"
    }
}
