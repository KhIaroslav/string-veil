package io.github.khstov.stringveil.gradle

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ScopedArtifacts
import org.gradle.api.Project

/**
 * Wires String Veil into an Android build through AGP's `ScopedArtifacts` CLASSES transform, so the
 * project's compiled classes are rewritten before they are dexed or packaged into an AAR. Loaded only
 * when an AGP plugin is applied, so JVM consumers never touch the Android API.
 *
 * Owning the transformed classes archive lets [StringVeilTransformClassesTask] validate and emit the
 * exact class bytes passed to later packaging stages, using the shared transformer's fresh
 * `ClassWriter`.
 */
internal object StringVeilAndroidWiring {
    fun apply(project: Project, extension: StringVeilExtension) {
        val components = project.extensions
            .findByType(AndroidComponentsExtension::class.java) ?: return
        components.onVariants { variant ->
            if (!extension.enabled.getOrElse(true)) return@onVariants
            val obfuscate = project.tasks.register(
                "stringVeilObfuscate" + variant.name.replaceFirstChar { it.uppercase() },
                StringVeilTransformClassesTask::class.java,
            ) { task ->
                task.failOnSecretLike.set(extension.failOnSecretLikeLiterals)
            }
            variant.artifacts
                .forScope(ScopedArtifacts.Scope.PROJECT)
                .use(obfuscate)
                .toTransform(
                    ScopedArtifact.CLASSES,
                    StringVeilTransformClassesTask::inputJars,
                    StringVeilTransformClassesTask::inputDirectories,
                    StringVeilTransformClassesTask::output,
                )
        }
    }
}
