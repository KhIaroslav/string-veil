plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `maven-publish`
}

kotlin {
    explicitApi()
    jvmToolchain(17)
}

dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:${libs.versions.kotlin.get()}")

    // The build-time cipher shares StringVeilFormat with the runtime decoder, so the plugin needs
    // :runtime on the compiler classpath (resolved transitively through the plugin artifact).
    implementation(project(":runtime"))

    testImplementation(project(":annotations"))
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:${libs.versions.kotlin.get()}")
    testImplementation(kotlin("test"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

tasks.test {
    val compilerPluginJar = tasks.named<Jar>("jar")

    dependsOn(compilerPluginJar)
    systemProperty(
        "stringVeil.compilerPluginJar",
        compilerPluginJar.get().archiveFile.get().asFile.absolutePath,
    )
    useJUnitPlatform()
}

// Encodes the JVM<->native differential corpus. Runs the build-time cipher over every
// DifferentialCorpus case, self-checks each container with the JVM decoder, and serializes the
// containers so the `native-differential` module can decode the very same bytes with the C++ library.
val generateDifferentialCorpus by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Encodes the differential corpus consumed by :native-differential."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.khiaroslav.stringveil.compiler.DifferentialCorpusGeneratorKt")

    val corpusFile = layout.buildDirectory.file("differential/corpus.bin").get().asFile
    outputs.file(corpusFile)
    args(corpusFile.absolutePath)
}
