plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `maven-publish`
}

kotlin {
    jvmToolchain(17)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

dependencies {
    // Container encoder + shared format primitives (StringVeilFormat) live in :runtime.
    implementation(project(":runtime"))
    implementation(libs.asm)
    implementation(libs.asm.commons)
    implementation(libs.asm.tree)

    testImplementation(project(":annotations"))
    testImplementation(kotlin("test"))
    // Test-only: compiles Kotlin fixtures in-process to exercise the transform on real Kotlin
    // bytecode (property annotations land on synthetic methods, not fields). Not used by the plugin.
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:${libs.versions.kotlin.get()}")
}

tasks.test {
    useJUnitPlatform()
}

// Encodes the JVM<->native differential corpus consumed by :native-differential.
val generateDifferentialCorpus by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Encodes the differential corpus consumed by :native-differential."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.khstov.stringveil.encoder.DifferentialCorpusGeneratorKt")

    val corpusFile = layout.buildDirectory.file("differential/corpus.bin").get().asFile
    outputs.file(corpusFile)
    args(corpusFile.absolutePath)
}

// Reports container-size overhead and decode throughput. On-demand, not part of `check`.
tasks.register<JavaExec>("benchmark") {
    group = "verification"
    description = "Writes String Veil size overhead, encode, and decode cost to BENCHMARKS.md."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.khstov.stringveil.encoder.BenchmarkReportKt")
    systemProperty("stringVeil.benchmarkOutput", rootProject.file("BENCHMARKS.md").absolutePath)
}
