# String Veil

Build-time string obfuscation for Kotlin, Java, and Android. String Veil replaces selected
plaintext literals in compiled bytecode with randomized containers and restores them at runtime.

[![CI](https://github.com/khstov/string-veil/actions/workflows/ci.yml/badge.svg)](https://github.com/khstov/string-veil/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.khstov.stringveil/gradle-plugin.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.khstov.stringveil/gradle-plugin)
[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/io.github.khstov.string-veil)](https://plugins.gradle.org/plugin/io.github.khstov.string-veil)

> **Status: initial development (`0.2.0`).** The public API and container format may change between
> `0.x` releases.

## Quick start

Make the Plugin Portal and Maven Central available in `settings.gradle.kts` (`google()` is needed
for Android projects):

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

Apply the plugin:

```kotlin
plugins {
    id("io.github.khstov.string-veil") version "0.2.0"
}
```

The plugin adds the annotations and the appropriate runtime automatically.

## Usage

Use `@Obfuscate` for direct string literals owned by a class, function, property, or field.
`@DoNotObfuscate` excludes a member from an enclosing class scope:

```kotlin
import io.github.khstov.stringveil.annotations.DoNotObfuscate
import io.github.khstov.stringveil.annotations.Obfuscate

@Obfuscate
class BuildInfo {
    val endpoint = "https://internal.example.com/api"

    @DoNotObfuscate
    val publicLabel = "String Veil"
}
```

Use `obfuscate("literal")` when you need to select one exact call site, including a delegated
property, companion/static initializer, interpolation fragment, or sub-expression:

```kotlin
import io.github.khstov.stringveil.obfuscate

class DeferredConfig {
    val endpoint by lazy { obfuscate("https://internal.example.com/api") }
}
```

The marker accepts only a direct literal. `obfuscate(variable)` is reported and remains
unprotected. When the plugin is absent, the marker is an identity function.

Optional Gradle settings:

```kotlin
stringVeil {
    enabled = true
    failOnSecretLikeLiterals = false
    // seed = 1234L // set for reproducible, cacheable builds; omit to randomize every build
    // minStringLength = 8 // skip annotation-scoped literals shorter than this
    // includePackages = listOf("com.example.secret") // only these packages (empty = all)
    // excludePackages = listOf("com.example.generated") // never these packages
    // includeVariants = listOf("release") // Android only: only these build variants (empty = all)
}
```

`failOnSecretLikeLiterals` turns warnings for credential-shaped selected strings into build errors.
`seed`, when set, makes obfuscation a deterministic function of the source position, so identical
source produces identical containers; left unset, every build randomizes each container.
`includePackages` / `excludePackages` scope obfuscation to (or away from) whole packages by prefix,
`minStringLength` skips short annotation-scoped literals (an explicit `obfuscate("...")` always wins),
and `includeVariants` limits obfuscation to named Android build variants.
The annotation parameters `method`, `methods`, `repetitions`, and `engine` are currently reserved and
do not alter the transform.

## Security

String Veil is an **obfuscator, not encryption or secret storage**. The application ships everything
needed to decode each value, so a motivated attacker can recover it or read it from memory. Use the
plugin to discourage casual static inspection, never to protect passwords, private keys, OAuth
client secrets, signing material, or other high-value credentials.

Read the full [security model and vulnerability-reporting policy](SECURITY.md) before using the
plugin for sensitive-looking values.

## Limitations

- `const val` is not supported because the compiler inlines its value at use sites.
- Declaration annotations cover direct string constants in the selected class, method, property,
  custom getter, or field. Their scope does not cross into generated lambdas or nested/anonymous
  classes; use `obfuscate("literal")` at the exact call site instead.
- Android resources, manifests, assets, `BuildConfig`, and dependency classes are not transformed.
- Interpolation recipes may store text outside ordinary `LDC` instructions. Wrap the exact fragment
  that must be selected with `obfuscate("...")`.
- Decoding creates a new `String`; use value equality rather than reference equality.
- Obfuscation adds runtime and size overhead. Avoid hot loops and benchmark large deployments.
- Strings used as reflective names, JNI symbols, or resource identifiers may require R8/ProGuard
  keep rules.

Inspect the final JAR, AAR, or DEX when the absence of a particular plaintext matters. A missed
supported case is a bug; please report it with a minimal reproduction.

## How it works

1. Kotlin or Java compiles normally.
2. String Veil locates selected direct string constants in the resulting class files.
3. Each value is encoded into a randomized integer container.
4. The original literal is replaced with a runtime decoder call.
5. JVM classes are rewritten after compilation; Android project classes pass through AGP's
   `ScopedArtifacts` pipeline before packaging.

One reversible layer may use AES/CTR, but its key is stored in the same container. It adds format
diversity, not cryptographic confidentiality.

## Compatibility

| Component | Verified version |
|---|---|
| Gradle | 8.14.2 |
| Kotlin | 1.9.24 |
| Build JDK | 17 |
| Minimum consumer JDK | 8 |
| Android Gradle Plugin | 8.7.3 |
| Android compile SDK / minSdk | 34 / 21 |
| Android NDK for source builds | 27.3.13750724 |
| Native ABIs | `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` |

Consumers of the published Android AAR do not need the NDK.

## Project documentation

- [Sample project](sample) — runnable JVM example.
- [Benchmarks](BENCHMARKS.md) — container size, encode, and decode cost per protection config.
- [Contributing](CONTRIBUTING.md) — source build, tests, modules, and release process.
- [Changelog](CHANGELOG.md) — user-visible changes and migration notes.
- [Security policy](SECURITY.md) — threat model, supported versions, and private reporting.
- [Code of Conduct](CODE_OF_CONDUCT.md) — community expectations.

Use [GitHub Discussions](https://github.com/khstov/string-veil/discussions) for questions and the
[issue templates](https://github.com/khstov/string-veil/issues/new/choose) for reproducible bugs or
feature requests.

## License

String Veil is licensed under the [Apache License 2.0](LICENSE).
