plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // The transform engine runs inside the consumer build, so it (and its ASM/encoder deps) must be
    // on the plugin's runtime classpath.
    implementation(project(":bytecode"))

    testImplementation(gradleTestKit())
    testImplementation(kotlin("test"))
}

gradlePlugin {
    plugins {
        create("stringVeilBytecode") {
            id = "io.github.khiaroslav.string-veil.bytecode"
            implementationClass =
                "io.github.khiaroslav.stringveil.bytecode.gradle.StringVeilBytecodePlugin"
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
