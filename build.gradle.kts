import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.plugins.signing.SigningExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.nmcp) apply false
    alias(libs.plugins.nmcp.aggregation)
    alias(libs.plugins.plugin.publish) apply false
    alias(libs.plugins.dokka)
}

allprojects {
    group = "io.github.khiaroslav.stringveil"
    version = providers.gradleProperty("VERSION_NAME").get()
}

subprojects {
    // Make archive metadata reproducible by omitting file timestamps and fixing entry order.
    // Cross-machine reproducibility, especially for native binaries, is verified separately.
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    pluginManager.withPlugin("java") {
        extensions.configure<JavaPluginExtension> {
            withSourcesJar()
            withJavadocJar()
        }
    }

    pluginManager.withPlugin("maven-publish") {
        apply(plugin = "com.gradleup.nmcp")
        apply(plugin = "signing")

        val publishingExtension = extensions.getByType<PublishingExtension>()
        publishingExtension.publications.withType<MavenPublication>().configureEach {
            pom {
                name.set("String Veil ${artifactId.replace('-', ' ').replaceFirstChar(Char::uppercase)}")
                description.set(
                    "Selective build-time string obfuscation for Kotlin, Java, JVM, and Android",
                )
                url.set("https://github.com/KhIaroslav/string-veil")
                inceptionYear.set("2026")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("KhIaroslav")
                        name.set("Iaroslav")
                        email.set("KhIaroslav@users.noreply.github.com")
                        organization.set("KhIaroslav")
                        organizationUrl.set("https://github.com/KhIaroslav")
                        url.set("https://github.com/KhIaroslav")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/KhIaroslav/string-veil.git")
                    developerConnection.set("scm:git:ssh://git@github.com/KhIaroslav/string-veil.git")
                    url.set("https://github.com/KhIaroslav/string-veil")
                }
                issueManagement {
                    system.set("GitHub")
                    url.set("https://github.com/KhIaroslav/string-veil/issues")
                }
            }
        }

        val signingKey = providers.environmentVariable("SIGNING_KEY")
            .orElse(providers.gradleProperty("signingKey"))
        val signingPassword = providers.environmentVariable("SIGNING_PASSWORD")
            .orElse(providers.gradleProperty("signingPassword"))

        extensions.configure<SigningExtension> {
            isRequired = signingKey.isPresent
            if (signingKey.isPresent) {
                useInMemoryPgpKeys(signingKey.get(), signingPassword.orNull)
            }
            sign(publishingExtension.publications)
        }
    }
}

dependencies {
    add("nmcpAggregation", project(":annotations"))
    add("nmcpAggregation", project(":bytecode"))
    add("nmcpAggregation", project(":gradle-plugin"))
    add("nmcpAggregation", project(":native-runtime"))
    add("nmcpAggregation", project(":runtime"))

    // Aggregated API documentation for the consumer-facing modules. `./gradlew dokkaGenerate`
    // renders HTML into build/dokka/html.
    dokka(project(":annotations"))
    dokka(project(":runtime"))
    dokka(project(":gradle-plugin"))
}

nmcpAggregation {
    centralPortal {
        username = providers.environmentVariable("CENTRAL_PORTAL_USERNAME")
            .orElse(providers.gradleProperty("centralPortalUsername"))
            .getOrElse("")
        password = providers.environmentVariable("CENTRAL_PORTAL_PASSWORD")
            .orElse(providers.gradleProperty("centralPortalPassword"))
            .getOrElse("")
        // USER_MANAGED uploads the deployment to the Central Portal but holds it for a
        // manual review and release in the web UI. Switch to "AUTOMATIC" only once the
        // release pipeline is trusted end to end.
        publishingType = "USER_MANAGED"
        publicationName = "string-veil:${project.version}"
    }
}
