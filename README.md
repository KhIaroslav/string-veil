# String Veil

Selective Kotlin/Android string obfuscation driven by `@Obfuscate` annotations.

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

> Early development: configurable multi-layer protection, compiler registration, scope resolution,
> IR replacement, Kotlin Gradle integration, and an Android JNI decoder are implemented.

## Modules

- `annotations` — `@Obfuscate` and `@DoNotObfuscate`.
- `compiler-plugin` — K2 registration, scope resolution, configurable transforms, and a hardened
  randomized outer container.
- `runtime` — pipeline and container decoder.
- `native-runtime` — Android AAR with the JNI bridge and native decoder for four common ABIs.
- `gradle-plugin` — automatic Kotlin compiler, annotations, and runtime integration.

## Usage

Declare the standard public repositories in the consumer project's `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}
```

Apply String Veil in the application or library module:

```kotlin
plugins {
    id("io.github.khiaroslav.string-veil") version "0.1.0-alpha01"
}
```

No manual runtime dependency is required. The plugin adds the compiler plugin, annotations, and
the appropriate decoder automatically. Android receives `native-runtime`; JVM receives `runtime`.

String Veil is enabled by default and can be disabled:

```kotlin
stringVeil {
    enabled = false
}
```

### Local development

To test an unpublished checkout, publish all artifacts locally:

```bash
./gradlew publishToMavenLocal
```

Add `mavenLocal()` to both `pluginManagement.repositories` and
`dependencyResolutionManagement.repositories` in the consumer project's `settings.gradle.kts`.
Use the version from `VERSION_NAME` in `gradle.properties` when applying the plugin.

The plugin supports Kotlin/JVM and Kotlin/Android compilations.

## Obfuscation methods

```kotlin
import io.github.khiaroslav.stringveil.annotations.Obfuscate
import io.github.khiaroslav.stringveil.annotations.ObfuscationEngine
import io.github.khiaroslav.stringveil.annotations.ObfuscationMethod

@Obfuscate(method = ObfuscationMethod.BIT_SHIFT)
val shifted = "internal-value"

@Obfuscate(method = ObfuscationMethod.BIT_XOR)
val xored = "internal-value"

@Obfuscate(method = ObfuscationMethod.BASE64)
val encoded = "internal-value"

@Obfuscate(method = ObfuscationMethod.AES)
val encrypted = "internal-value"

@Obfuscate(method = ObfuscationMethod.RANDOM_ALL, repetitions = 5)
val mixed = "internal-value"

@Obfuscate(
    method = ObfuscationMethod.RANDOM_SELECTED,
    methods = [ObfuscationMethod.BIT_XOR, ObfuscationMethod.AES],
    repetitions = 5,
)
val selected = "internal-value"

@Obfuscate(
    method = ObfuscationMethod.RANDOM_ALL,
    repetitions = 5,
    engine = ObfuscationEngine.NATIVE,
)
val native = "android-only-value"
```

`@Obfuscate` defaults to `RANDOM_ALL` with three independently randomized layers. Repetitions are
limited to `1..16`; more layers increase build output and runtime work. Every method, including the
simple bit/base64 modes, is sealed inside the same per-string outer container with random padding,
masked key material, sparse permutation, decoy words, an ARX block transform, and integrity checks.

## Decoder engines

- `AUTO` (default) uses JNI for Android and the Kotlin/JVM decoder for JVM projects.
- `NATIVE` requires an Android compilation and fails the build if `native-runtime` is unavailable.
- `JVM` forces the portable Kotlin decoder. On Android, add the `runtime` artifact explicitly;
  it is intentionally not packaged by default so the Kotlin decoder is absent from native APKs.

The Android AAR currently packages `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`. Building the
AAR from source requires an installed Android NDK. Consumer applications do not need an NDK when
they use a published AAR.

## Current check

```bash
./gradlew test :native-runtime:assembleRelease :gradle-plugin:validatePlugins publishToMavenLocal
```

Building `native-runtime` from source requires Android NDK. Set `STRING_VEIL_NDK_HOME` when the NDK
cannot be found through `local.properties`.

## Publishing

Release tags publish the five Maven artifacts to Maven Central, the Gradle plugin to the Plugin
Portal, and create a GitHub release. The tag must exactly match `VERSION_NAME`, prefixed with `v`.

Repository secrets required by `.github/workflows/release.yml`:

- `CENTRAL_PORTAL_USERNAME` and `CENTRAL_PORTAL_PASSWORD`
- `SIGNING_KEY` and `SIGNING_PASSWORD`
- `GRADLE_PUBLISH_KEY` and `GRADLE_PUBLISH_SECRET`

The `io.github.khiaroslav` namespace must be verified in Maven Central before the first release.
The first Gradle Plugin Portal publication is subject to its manual review.

Example after preparing the release commit on the repository's default branch:

```bash
git tag v0.1.0-alpha01
git push origin v0.1.0-alpha01
```

Client-side secrets can always be recovered by a sufficiently determined attacker at runtime.
String Veil raises static-analysis cost; it is not a replacement for keeping long-lived credentials
on a server. Android release builds should also enable R8 shrinking and obfuscation.

K2 does not retain `AnnotationTarget.EXPRESSION` annotations in IR. For Android/JVM MVP,
String Veil recovers direct expression markers from Kotlin lexer tokens and IR source offsets.

Selected Android literals are replaced with a call equivalent to:

```kotlin
NativeStringDecoder.decode(protectedIntContainer)
```

JVM mode uses:

```kotlin
StringDecoder.decode(protectedIntContainer)
```

The compiler integration tests compile Kotlin through both engines, verify runtime values, and
scan generated class files to ensure selected plaintext strings are absent. Native decoding raises
the cost of static analysis, but an attacker can still hook the JNI bridge or inspect the decoded
value in memory; long-lived credentials must remain server-side.

## License

String Veil is licensed under the [Apache License 2.0](LICENSE).
