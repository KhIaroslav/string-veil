import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.JavaExec
import java.io.File

/**
 * Test-only module (not published) that checks the JVM `runtime` decoder and the `native-runtime`
 * C++ decoder agree on the exact same containers.
 *
 * It host-compiles `native-runtime/src/main/cpp/native_decoder.cpp` into a shared library, then runs
 * every container from `:bytecode:generateDifferentialCorpus` through it in a forked JVM.
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
val corpusPath: String = project(":bytecode").layout.buildDirectory
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
    dependsOn(compileHostNativeDecoder, ":bytecode:generateDifferentialCorpus")
    inputs.file(corpusPath)

    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.khiaroslav.stringveil.differential.NativeDifferentialRunner")
    args(corpusPath)

    if (nativeBuildable) {
        jvmArgs("-Djava.library.path=${nativeOutputDir.absolutePath}")
    }
}

// ---- Fuzzing (AddressSanitizer + UndefinedBehaviorSanitizer) -------------------------------------
//
// `open_outer_container` parses a raw, potentially hostile `int[]` container with no JNI. The
// standalone harness (native-runtime/src/test/cpp/fuzz_open_container.cpp) replays the valid corpus
// and then runs a bounded random-mutation loop, all under ASan/UBSan, so out-of-bounds reads, hangs,
// or undefined behavior on malformed input surface as a hard failure. It needs only a C++17 compiler
// with -fsanitize=address,undefined (no libFuzzer runtime), so it runs anywhere the differential
// decoder builds. It is a dedicated task, not wired into `check`, because some sanitizer runtimes
// hang at process start on certain hosts; CI runs it explicitly on a known-good toolchain.

val fuzzSource: File = rootProject.file("native-runtime/src/test/cpp/fuzz_open_container.cpp")
val fuzzDir: File = layout.buildDirectory.dir("fuzz").get().asFile
val fuzzBinary = File(fuzzDir, "fuzz_open_container")
val fuzzSeedsDir = File(fuzzDir, "seeds")
val fuzzRuns: String = (project.findProperty("stringVeil.fuzzRuns") as String?) ?: "200000"

val nativeFuzzSeeds by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Explodes the differential corpus into fuzz seed files (valid containers)."
    enabled = nativeBuildable
    dependsOn(":bytecode:generateDifferentialCorpus")
    inputs.file(corpusPath)
    outputs.dir(fuzzSeedsDir)
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.khiaroslav.stringveil.differential.FuzzSeedWriter")
    args(corpusPath, fuzzSeedsDir.absolutePath)
}

val compileNativeFuzzer by tasks.registering(Exec::class) {
    group = "verification"
    description = "Compiles the ASan/UBSan fuzz harness for the native container parser."
    enabled = nativeBuildable
    inputs.file(fuzzSource)
    inputs.file(nativeSource)
    inputs.property("compiler", cxxCompiler ?: "none")

    if (nativeBuildable) {
        outputs.file(fuzzBinary)
        val includeDir = javaHome.resolve("include")
        val platformInclude = includeDir.resolve(hostTarget!!.platformIncludeDir)
        val mainCppDir = nativeSource.parentFile
        val script = buildString {
            append("mkdir -p '").append(fuzzBinary.parentFile.absolutePath).append("' && ")
            append("'").append(cxxCompiler).append("' ")
            append("-std=c++17 -g -O1 -fno-omit-frame-pointer ")
            append("-fsanitize=address,undefined -fno-sanitize-recover=all ")
            append("-I'").append(includeDir.absolutePath).append("' ")
            append("-I'").append(platformInclude.absolutePath).append("' ")
            append("-I'").append(mainCppDir.absolutePath).append("' ")
            append("'").append(fuzzSource.absolutePath).append("' ")
            append("-o '").append(fuzzBinary.absolutePath).append("'")
        }
        commandLine("sh", "-c", script)
    } else {
        commandLine("true")
    }
}

val nativeFuzzTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Fuzzes the native container parser under ASan/UBSan (-PstringVeil.fuzzRuns=N)."
    enabled = nativeBuildable
    dependsOn(compileNativeFuzzer, nativeFuzzSeeds)

    if (nativeBuildable) {
        inputs.file(fuzzBinary)
        inputs.dir(fuzzSeedsDir)
        environment("ASAN_OPTIONS", "abort_on_error=1:detect_leaks=0")
        environment("UBSAN_OPTIONS", "halt_on_error=1:abort_on_error=1:print_stacktrace=1")
        commandLine(fuzzBinary.absolutePath, fuzzSeedsDir.absolutePath, fuzzRuns)
    } else {
        commandLine("true")
    }
}

tasks.named("check") {
    dependsOn(nativeDifferentialTest)
}
