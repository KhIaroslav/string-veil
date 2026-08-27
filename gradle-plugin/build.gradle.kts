import org.gradle.plugin.compatibility.compatibility

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.plugin.publish)
    alias(libs.plugins.dokka)
    `java-gradle-plugin`
    `maven-publish`
}

kotlin {
    explicitApi()
    jvmToolchain(17)
}

dependencies {
    // The transform engine runs inside the consumer build, so it (and its ASM/encoder/runtime deps)
    // must be on the plugin's runtime classpath.
    implementation(project(":bytecode"))
    // The Android instrumentation bridge uses the ASM tree API directly.
    implementation(libs.asm)
    implementation(libs.asm.tree)

    // compileOnly: AGP is present in the consumer's build only when they apply an Android plugin, and
    // the Android code path is loaded lazily there. Declaring it `implementation` would drag AGP into
    // every JVM consumer.
    compileOnly(libs.agp.api)

    testImplementation(gradleTestKit())
    testImplementation(kotlin("test"))
}

gradlePlugin {
    website.set("https://github.com/KhIaroslav/string-veil")
    vcsUrl.set("https://github.com/KhIaroslav/string-veil.git")

    plugins {
        create("stringVeil") {
            id = "io.github.khiaroslav.string-veil"
            implementationClass =
                "io.github.khiaroslav.stringveil.gradle.StringVeilGradlePlugin"
            displayName = "String Veil"
            description = "Selective Kotlin string obfuscation"
            tags.set(listOf("kotlin", "android", "obfuscation", "security"))
            compatibility {
                features {
                    configurationCache = true
                }
            }
        }
    }
}

tasks.jar {
    manifest.attributes["Implementation-Version"] = project.version
}

tasks.test {
    // The plugin (and its bytecode/asm/runtime deps) is injected via withPluginClasspath(); only the
    // consumer-facing artifacts the plugin adds — annotations and runtime — need a resolvable repo.
    val annotationsJar = project(":annotations").tasks.named<Jar>("jar")
    val runtimeJar = project(":runtime").tasks.named<Jar>("jar")

    dependsOn(annotationsJar, runtimeJar)
    systemProperty(
        "stringVeil.annotationsJar",
        annotationsJar.get().archiveFile.get().asFile.absolutePath,
    )
    systemProperty(
        "stringVeil.runtimeJar",
        runtimeJar.get().archiveFile.get().asFile.absolutePath,
    )
    useJUnitPlatform()
}
