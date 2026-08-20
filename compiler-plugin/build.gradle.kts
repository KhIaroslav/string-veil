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

    testImplementation(project(":annotations"))
    testImplementation(project(":runtime"))
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
