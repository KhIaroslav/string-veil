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

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
