import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.JavaExec
import java.io.File

/**
 * Test-only module (not published) that proves the JVM `runtime` decoder and the `native-runtime`
 * C++ decoder agree on the exact same containers.
 *
 * It host-compiles `native-runtime/src/main/cpp/native_decoder.cpp` into a shared library, then runs
 * every container from `:compiler-plugin:generateDifferentialCorpus` through it in a forked JVM.
 * When no C++ toolchain (or no JNI headers) is available, both tasks are disabled at configuration
 * time rather than failing, so a plain `./gradlew check` still works on hosts without a compiler.
 *
 * The tasks are written to be Gradle configuration-cache compatible: all host detection happens at
 * configuration time and no task action closes over the build script.
 */
plugins {
    java
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    // NativeStringDecoder falls back to StringDecoder, so the runtime decoder must be on the
    // classpath for both compilation and the forked runner.
    implementation(project(":runtime"))
}

// Reuse the real JNI bridge so the class name matches the native RegisterNatives binding exactly.
sourceSets {
    main {
        java.srcDir(rootProject.file("native-runtime/src/main/java"))
    }
}

// ---- Host-native build configuration (all resolved at configuration time) ------------------------

val osName = providers.systemProperty("os.name").get().lowercase()
val javaHome = File(providers.systemProperty("java.home").get())

data class HostTarget(val platformIncludeDir: String, val libraryFileName: String)

val hostTarget: HostTarget? = when {
    osName.contains("mac") || osName.contains("darwin") ->
        HostTarget("darwin", "libstring_veil_native.dylib")
    osName.contains("linux") ->
        HostTarget("linux", "libstring_veil_native.so")
    else -> null // Windows/MSVC is not wired up; the native leg is simply skipped.
}

fun locateCxxCompiler(): String? {
    val candidates = buildList {
        providers.environmentVariable("CXX").orNull?.let(::add)
        add("clang++")
        add("g++")
        add("c++")
    }
    val pathEntries = providers.environmentVariable("PATH").getOrElse("").split(File.pathSeparator)
    for (candidate in candidates) {
        val direct = File(candidate)
        if (direct.isAbsolute && direct.canExecute()) return direct.absolutePath
        for (dir in pathEntries) {
            val resolved = File(dir, candidate)
            if (resolved.canExecute()) return resolved.absolutePath
        }
    }
    return null
}

val cxxCompiler: String? = locateCxxCompiler()
val jniHeadersPresent = File(javaHome, "include/jni.h").isFile
val nativeBuildable = hostTarget != null && cxxCompiler != null && jniHeadersPresent

val nativeOutputDir: File = layout.buildDirectory.dir("nativeHost").get().asFile
val nativeSource: File = rootProject.file("native-runtime/src/main/cpp/native_decoder.cpp")
val nativeLibraryFile: File? = hostTarget?.let { File(nativeOutputDir, it.libraryFileName) }
// Resolve the corpus path to a plain String at configuration time so no cross-project provider is
// captured by a task action (configuration cache cannot serialize script/project references).
val corpusPath: String = project(":compiler-plugin").layout.buildDirectory
    .file("differential/corpus.bin").get().asFile.absolutePath

if (!nativeBuildable) {
    logger.lifecycle(
        "native-differential: no C++ toolchain / JNI headers on this host " +
            "(compiler=${cxxCompiler ?: "none"}, jniHeaders=$jniHeadersPresent, os=$osName); " +
            "the native differential test will be skipped.",
    )
}

val compileHostNativeDecoder by tasks.registering(Exec::class) {
    group = "verification"
    description = "Host-compiles the native String Veil decoder into a shared library for testing."
    enabled = nativeBuildable
    inputs.file(nativeSource)
    inputs.property("os", osName)
    inputs.property("compiler", cxxCompiler ?: "none")

    if (nativeBuildable) {
        val output = nativeLibraryFile!!
        outputs.file(output)
        val includeDir = javaHome.resolve("include")
        val platformInclude = includeDir.resolve(hostTarget!!.platformIncludeDir)
        // mkdir is folded into the command so the task needs no doFirst closure (CC-safe).
        val script = buildString {
            append("mkdir -p '").append(output.parentFile.absolutePath).append("' && ")
            append("'").append(cxxCompiler).append("' ")
            append("-std=c++17 -O2 -fPIC -shared -fno-exceptions -fno-rtti ")
            append("-I'").append(includeDir.absolutePath).append("' ")
            append("-I'").append(platformInclude.absolutePath).append("' ")
            append("'").append(nativeSource.absolutePath).append("' ")
            append("-o '").append(output.absolutePath).append("'")
        }
        commandLine("sh", "-c", script)
    } else {
        commandLine("true")
    }
}

val nativeDifferentialTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Decodes the differential corpus natively and checks it against the JVM decoder."
    enabled = nativeBuildable
    dependsOn(compileHostNativeDecoder, ":compiler-plugin:generateDifferentialCorpus")
    inputs.file(corpusPath)

    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.khiaroslav.stringveil.differential.NativeDifferentialRunner")
    args(corpusPath)

    if (nativeBuildable) {
        jvmArgs("-Djava.library.path=${nativeOutputDir.absolutePath}")
    }
}

tasks.named("check") {
    dependsOn(nativeDifferentialTest)
}
