import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.plugins.signing.SigningExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.nmcp) apply false
    alias(libs.plugins.nmcp.aggregation)
    alias(libs.plugins.plugin.publish) apply false
}

allprojects {
    group = "io.github.khiaroslav.stringveil"
    version = providers.gradleProperty("VERSION_NAME").get()
}

subprojects {
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
                description.set("Selective Kotlin and Android string obfuscation")
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
    add("nmcpAggregation", project(":compiler-plugin"))
    add("nmcpAggregation", project(":gradle-plugin"))
    add("nmcpAggregation", project(":native-runtime"))
    add("nmcpAggregation", project(":runtime"))
}

nmcpAggregation {
    centralPortal {
        username = providers.environmentVariable("CENTRAL_PORTAL_USERNAME")
            .orElse(providers.gradleProperty("centralPortalUsername"))
            .getOrElse("")
        password = providers.environmentVariable("CENTRAL_PORTAL_PASSWORD")
            .orElse(providers.gradleProperty("centralPortalPassword"))
            .getOrElse("")
        publishingType = "AUTOMATIC"
        publicationName = "string-veil:${project.version}"
    }
}
