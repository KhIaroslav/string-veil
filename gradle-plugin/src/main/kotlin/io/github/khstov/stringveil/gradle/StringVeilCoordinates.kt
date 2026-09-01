package io.github.khstov.stringveil.gradle

internal object StringVeilCoordinates {
    const val GROUP: String = "io.github.khstov.stringveil"
    val VERSION: String =
        StringVeilCoordinates::class.java.`package`.implementationVersion
            ?: "0.1.0"

    const val ANNOTATIONS_ARTIFACT: String = "annotations"
    const val GRADLE_PLUGIN_ARTIFACT: String = "gradle-plugin"
    const val RUNTIME_ARTIFACT: String = "runtime"
    const val NATIVE_RUNTIME_ARTIFACT: String = "native-runtime"
}
