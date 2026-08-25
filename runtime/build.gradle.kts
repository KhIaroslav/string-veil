plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dokka)
    `java-library`
    `maven-publish`
}

kotlin {
    explicitApi()
    jvmToolchain(17)
}

dependencies {
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
    useJUnitPlatform()
}
