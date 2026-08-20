# String Veil

Selective Kotlin/Android string obfuscation driven by `@Obfuscate` annotations.

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

For local development, publish all artifacts:

```bash
./gradlew publishToMavenLocal
```

Add `mavenLocal()` to both `pluginManagement.repositories` and
`dependencyResolutionManagement.repositories` in the consumer project's `settings.gradle.kts`.
Then apply the plugin:

```kotlin
plugins {
    id("io.github.khiaroslav.string-veil") version "0.1.0-SNAPSHOT"
}
```

The plugin supports Kotlin/JVM and Kotlin/Android compilations. It adds the annotations and
required runtime artifacts automatically. Android receives `native-runtime`; JVM does not.
String Veil is enabled by default and can be disabled:

```kotlin
stringVeil {
    enabled = false
}
```

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
./gradlew test
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
