# String Veil

Selective, build-time string obfuscation for Kotlin, Java, and Android. String Veil rewrites
annotated literals in compiled JVM bytecode and restores them at runtime.

[![CI](https://github.com/KhIaroslav/string-veil/actions/workflows/ci.yml/badge.svg)](https://github.com/KhIaroslav/string-veil/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Kotlin | Java](https://img.shields.io/badge/kotlin%20%7C%20java-JVM%20%7C%20Android-blue.svg?logo=kotlin)](https://kotlinlang.org)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.khiaroslav.stringveil/gradle-plugin.svg?label=Maven%20Central)](https://central.sonatype.com/namespace/io.github.khiaroslav.stringveil)
[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/io.github.khiaroslav.string-veil)](https://plugins.gradle.org/plugin/io.github.khiaroslav.string-veil)

> **Status: early development (`0.1.0-alpha01`, first public pre-release).** The container format and
> public API may change between pre-release versions.

## Install

Declare the public repositories in the consumer project's `settings.gradle.kts`:

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

Apply String Veil in an application or library module:

```kotlin
plugins {
    id("io.github.khiaroslav.string-veil") version "0.1.0-alpha01"
}
```

The plugin adds the annotations and appropriate runtime automatically. No manual runtime dependency
is required.

## Use

Annotate a supported declaration:

```kotlin
import io.github.khiaroslav.stringveil.annotations.Obfuscate

@Obfuscate
val internalEndpoint = "https://internal.example.com/v3/telemetry"

@Obfuscate
fun internalMarker(): String = "internal-marker"
```

For supported bytecode shapes, the final transformed class contains a decoder call and a randomized
integer container instead of the selected plaintext literal. The runtime reconstructs the original
value when the declaration is used.

`@Obfuscate` can be placed on classes, functions, properties, and fields. `@DoNotObfuscate` excludes
a member from an enclosing class-level scope:

```kotlin
import io.github.khiaroslav.stringveil.annotations.DoNotObfuscate

@Obfuscate
class BuildInfo {
    val internalFlag = "internal-feature-flag"

    @DoNotObfuscate
    val publicLabel = "String Veil sample"
}
```

The annotation currently uses one default randomized, three-layer pipeline. The declared
`method`, `methods`, `repetitions`, and `engine` arguments are reserved for compatibility with the
earlier compiler-plugin prototype and are **not read by the bytecode transform**. Do not rely on them
until a release explicitly documents their support.

Android builds call `NativeStringDecoder`, which uses JNI when its library loads and falls back to the
portable JVM decoder otherwise. JVM builds call `StringDecoder` directly. Per-annotation engine
selection is not implemented yet.

## Gradle options

String Veil is enabled by default:

```kotlin
stringVeil {
    enabled = true
    failOnSecretLikeLiterals = false
}
```

- `enabled` enables the transform for supported JVM and Android projects.
- `failOnSecretLikeLiterals` turns warnings for credential-shaped annotated literals into build
  errors.

The warning is deliberately conservative and may produce false positives or miss an unknown secret
format. Its purpose is to remind you that client-side obfuscation is not secret storage.

## Security model

String Veil is an **obfuscator**, not encryption and not a secrets vault. Everything required to
decode a string is shipped in the application. A motivated attacker can extract the decoder, invoke
it, hook the JVM/JNI boundary, or read the materialized value from memory.

Use it to raise the cost of casual static inspection and bulk string scraping. Do not use it for
passwords, private keys, OAuth client secrets, signing material, or long-lived high-value API keys.
Keep those values off the client.

See [SECURITY.md](SECURITY.md) for the complete threat model and private vulnerability-reporting
process.

## Current limitations

- **`const val` is not supported.** Its value is inlined at use sites. Selecting a `ConstantValue`
  field produces a build error.
- **Only direct string constants in supported declaration bytecode are transformed.** Complex
  property initializers such as `if`/`when`, collection initializers, delegated properties, custom
  getters, compiler-generated lambdas, and nested or anonymous classes may fall outside the selected
  declaration. Do not assume those forms are protected without inspecting the final output.
- **Interpolated template recipes are not transformed.** Some template fragments compile to
  `invokedynamic` metadata rather than `LDC` string instructions.
- **Resources are outside the transform.** Android resources, `AndroidManifest.xml`, assets,
  `BuildConfig`, and strings in dependencies are not processed.
- **String identity can change.** A decoded value is a new `String`, so Java reference equality and
  Kotlin `===` may differ from an interned literal. Use value equality.
- **Runtime and size overhead are expected.** Each use builds an integer container and decodes it.
  Avoid protecting literals in hot loops, and benchmark applications with many or very long strings.
- **JVM consumer builds do not currently support Gradle's configuration cache.** The JVM integration
  mutates the main `classes` output after compilation and must be moved to a dedicated task before
  configuration-cache compatibility can be advertised.
- **Reflection and shrinking need care.** If a protected literal names a class, method, JNI symbol, or
  resource, configure R8/ProGuard so that the referenced element is retained and named as expected.
- **Per-annotation transform and engine settings are not implemented.** Every selected literal uses
  the current default pipeline; the project type selects the decoder path.

Until fail-closed coverage exists for all supported language shapes, inspect the final JAR, AAR, or
DEX when the absence of a particular plaintext matters. A missed supported case is a bug and should
be reported with a minimal reproduction.

## How it works

1. Kotlin or Java compiles normally.
2. String Veil reads declaration annotations from the compiled classes and selects supported `LDC`
   string instructions.
3. Each selected value is passed through a randomized reversible pipeline and placed in an integer
   container with masked metadata, padding, decoy words, a sparse permutation, and a
   non-cryptographic corruption checksum.
4. The literal instruction is replaced with a runtime decoder call.
5. JVM builds transform the main class output; Android builds transform project classes through AGP's
   `ScopedArtifacts` classes pipeline before dexing or AAR packaging.

AES/CTR may appear as one reversible layer, but its key is stored in the same container. It adds
format diversity, not cryptographic confidentiality.

## Modules

| Module | Purpose | Artifact |
|---|---|---|
| `annotations` | `@Obfuscate` and `@DoNotObfuscate` | JAR |
| `bytecode` | ASM transform, pipeline encoder, randomized container | JAR |
| `runtime` | Portable Kotlin/JVM decoder and shared format definitions | JAR |
| `native-runtime` | Android AAR with JNI bridge and C++ decoder | AAR |
| `gradle-plugin` | JVM and Android build integration | Gradle plugin/JAR |
| `native-differential` | JVM/native compatibility and fuzz-test harnesses | development only |

## Compatibility

The transform operates on class files rather than Kotlin compiler internals, so it is not pinned to a
specific K2 IR API. Compatibility is still verified against concrete tool versions rather than
claimed for every past or future compiler.

| Component | Currently verified |
|---|---|
| Gradle | 8.14.2 |
| Kotlin | 1.9.24 |
| JDK toolchain (to build) | 17 |
| Published bytecode target / minimum consumer JDK | 8 |
| Android Gradle Plugin | 8.7.3 |
| Android compile SDK | 34 |
| Android AAR `minSdk` | 21 |
| Native ABIs | `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` |
| Android NDK for source builds | 27.3.13750724 |

Consumers of the published Android AAR do not need the NDK.

## Build and test

```bash
./gradlew test \
  :gradle-plugin:validatePlugins \
  :native-differential:nativeDifferentialTest \
  :native-runtime:assembleRelease
```

Additional checks:

```bash
./gradlew :native-differential:nativeFuzzTest -PstringVeil.fuzzRuns=2000000
./gradlew :bytecode:benchmark
./gradlew -p sample run
```

The differential suite compares the JVM and C++ decoders over the same corpus. The native fuzz task
replays valid seeds and random mutations under ASan/UBSan; it is a bounded bug-finding check, not a
proof that every possible input is safe.

Building `native-runtime` from source requires an installed Android NDK. Set
`STRING_VEIL_NDK_HOME` or `stringVeilNdkDir` when it cannot be discovered through
`local.properties`.

For local consumer testing, run `./gradlew publishToMavenLocal` and put `mavenLocal()` **before**
public repositories in both repository blocks. The [sample](sample) uses an included build and needs
no local publication. The [Android instrumented test](android-test) uses Maven Local and a connected
device or emulator.

Generate API documentation with:

```bash
./gradlew dokkaGenerate
```

The output is written to `build/dokka/html`.

## Releases and supply chain

Release tags publish signed Maven artifacts, the Gradle plugin, a GitHub release, build-provenance
attestations, and an SPDX SBOM. Archive tasks remove entry timestamps and use reproducible entry
ordering. Full byte-for-byte reproducibility across different operating systems and native
toolchains is not currently claimed.

Maintainer release instructions live in [CONTRIBUTING.md](CONTRIBUTING.md); supply-chain controls are
described in [SECURITY.md](SECURITY.md).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for the development workflow and
[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) for community expectations. Report vulnerabilities through
the private process in [SECURITY.md](SECURITY.md), not a public issue.

## License

String Veil is licensed under the [Apache License 2.0](LICENSE).
