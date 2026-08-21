import org.gradle.plugin.compatibility.compatibility

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.plugin.publish)
    `java-gradle-plugin`
    `maven-publish`
}

kotlin {
    explicitApi()
    jvmToolchain(17)
}

dependencies {
    // compileOnly: the Kotlin Gradle plugin already contributes kotlin-gradle-plugin-api to
    // the consumer's buildscript classpath at runtime. Declaring it as `implementation` would
    // leak the dependency into the published POM and pin the consumer's KGP version.
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin-api:${libs.versions.kotlin.get()}")

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
    val annotationsJar = project(":annotations").tasks.named<Jar>("jar")
    val compilerPluginJar = project(":compiler-plugin").tasks.named<Jar>("jar")
    val gradlePluginJar = tasks.named<Jar>("jar")
    val runtimeJar = project(":runtime").tasks.named<Jar>("jar")

    dependsOn(annotationsJar, compilerPluginJar, gradlePluginJar, runtimeJar)
    systemProperty(
        "stringVeil.annotationsJar",
        annotationsJar.get().archiveFile.get().asFile.absolutePath,
    )
    systemProperty(
        "stringVeil.compilerPluginJar",
        compilerPluginJar.get().archiveFile.get().asFile.absolutePath,
    )
    systemProperty(
        "stringVeil.gradlePluginJar",
        gradlePluginJar.get().archiveFile.get().asFile.absolutePath,
    )
    systemProperty(
        "stringVeil.runtimeJar",
        runtimeJar.get().archiveFile.get().asFile.absolutePath,
    )
    useJUnitPlatform()
}
