import java.util.Properties
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    base
    `maven-publish`
}

val localProperties = Properties().apply {
    rootProject.file("local.properties")
        .takeIf(File::isFile)
        ?.inputStream()
        ?.use(::load)
}
val sdkDirectory = localProperties.getProperty("sdk.dir")?.let(::file)
val ndkDirectory = providers.provider {
    listOfNotNull(
        providers.gradleProperty("stringVeilNdkDir").orNull?.let(::file),
        providers.environmentVariable("STRING_VEIL_NDK_HOME").orNull?.let(::file),
        providers.environmentVariable("ANDROID_NDK_HOME").orNull?.let(::file),
        sdkDirectory?.resolve("ndk-bundle"),
    ).firstOrNull { it.resolve("ndk-build").isFile }
        ?: throw GradleException(
            "Android NDK not found. Set stringVeilNdkDir or STRING_VEIL_NDK_HOME.",
        )
}

val nativeLibraries = layout.buildDirectory.dir("ndk/libs")
val nativeObjects = layout.buildDirectory.dir("ndk/obj")
val bridgeClasses = layout.buildDirectory.dir("classes/java")

val compileNative by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds String Veil JNI libraries for all Android ABIs"
    inputs.files(fileTree("src/main/cpp"))
    outputs.dir(nativeLibraries)

    val ndkBuild = ndkDirectory.get().resolve("ndk-build").absolutePath
    val buildArguments = listOf(
        "NDK_PROJECT_PATH=null",
        "APP_BUILD_SCRIPT=${file("src/main/cpp/Android.mk").absolutePath}",
        "NDK_APPLICATION_MK=${file("src/main/cpp/Application.mk").absolutePath}",
        "NDK_OUT=${nativeObjects.get().asFile.absolutePath}",
        "NDK_LIBS_OUT=${nativeLibraries.get().asFile.absolutePath}",
        "-j${Runtime.getRuntime().availableProcessors().coerceAtMost(8)}",
    )
    if (System.getProperty("os.name").startsWith("Mac") &&
        System.getProperty("os.arch") in setOf("aarch64", "arm64")
    ) {
        commandLine(listOf("/usr/bin/arch", "-x86_64", ndkBuild) + buildArguments)
    } else {
        commandLine(listOf(ndkBuild) + buildArguments)
    }
}

val compileBridgeJava by tasks.registering(JavaCompile::class) {
    source(fileTree("src/main/java") { include("**/*.java") })
    classpath = files()
    destinationDirectory.set(bridgeClasses)
    sourceCompatibility = "17"
    targetCompatibility = "17"
    options.release.set(17)
}

val classesJar by tasks.registering(Jar::class) {
    dependsOn(compileBridgeJava)
    archiveFileName.set("classes.jar")
    destinationDirectory.set(layout.buildDirectory.dir("intermediates/aar"))
    from(bridgeClasses)
}

val sourcesJar by tasks.registering(Jar::class) {
    archiveClassifier.set("sources")
    from("src/main/java")
    from("src/main/cpp")
}

val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
    from(rootProject.file("README.md"))
}

val releaseAar by tasks.registering(Zip::class) {
    dependsOn(classesJar, compileNative)
    archiveBaseName.set(project.name)
    archiveVersion.set(project.version.toString())
    archiveExtension.set("aar")
    destinationDirectory.set(layout.buildDirectory.dir("outputs/aar"))

    from("src/main/AndroidManifest.xml")
    from("src/main/aar/R.txt")
    from(classesJar)
    from("consumer-rules.pro") {
        rename { "proguard.txt" }
    }
    from(nativeLibraries) {
        include("**/*.so")
        into("jni")
    }
}

tasks.named("assemble") {
    dependsOn(releaseAar)
}

tasks.register("assembleRelease") {
    group = "build"
    dependsOn(releaseAar)
}

publishing {
    publications {
        create<MavenPublication>("release") {
            artifact(releaseAar) {
                extension = "aar"
            }
            artifact(sourcesJar)
            artifact(javadocJar)
            pom {
                packaging = "aar"
                name.set("String Veil Native Runtime")
                description.set("Android JNI runtime for String Veil")
            }
        }
    }
}
