plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Container encoder + shared format primitives (StringVeilFormat) live in :runtime.
    implementation(project(":runtime"))
    implementation(libs.asm)
    implementation(libs.asm.commons)
    implementation(libs.asm.tree)

    testImplementation(project(":annotations"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
