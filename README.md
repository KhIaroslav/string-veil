# String Veil

Selective, build-time string obfuscation for Kotlin and Android, driven by `@Obfuscate`
annotations. String Veil rewrites annotated string literals at compile time so they no longer
appear as readable text in your compiled artifacts, then restores them at runtime.

[![CI](https://github.com/KhIaroslav/string-veil/actions/workflows/ci.yml/badge.svg)](https://github.com/KhIaroslav/string-veil/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/kotlin-2.3.21-blue.svg?logo=kotlin)](https://kotlinlang.org)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.khiaroslav.stringveil/gradle-plugin.svg?label=Maven%20Central)](https://central.sonatype.com/namespace/io.github.khiaroslav.stringveil)

> **Status: early development (`0.1.0-alpha01`, unreleased).** The container format is not yet
> stable and may change without a compatibility shim between pre-release versions.

## What String Veil is — and what it is not

String Veil is an **obfuscation** tool. It makes strings harder to find and read through casual
inspection — `strings`, `grep`, a decompiler's constant pool, a quick scan of an APK. That is its
entire job, and it does it well.

String Veil is **not encryption** and **not a secrets vault**. There is no external key. By design,
everything required to reconstruct a protected string travels inside the artifact, because the code
must be able to decode it at runtime with no secret input from you. A determined attacker who has
your artifact — and especially anyone reading these open sources — can recover the plaintext. Treat
it as a **speed bump**, not a lock.

**Do not use String Veil to store long-lived secrets** — API keys, passwords, OAuth client secrets,
signing material. Those belong on a server, in a platform secrets manager, or injected at runtime
from a trusted source. See [SECURITY.md](SECURITY.md) for the full threat model.

**Good fits**

- Raising the cost of static analysis and automated string scraping of your app.
- Hiding internal endpoints, feature flags, log markers, and other low-sensitivity strings you
  simply would rather not advertise in plaintext.
- A complement to R8/ProGuard, not a replacement for it.

**Bad fits**

- Anything you would be harmed by if a motivated attacker read it. If leaking the value is a real
  incident, keep it off the client entirely.

## Modules

| Module           | Purpose                                                                          | Published as |
|------------------|----------------------------------------------------------------------------------|--------------|
| `annotations`    | `@Obfuscate` and `@DoNotObfuscate` markers.                                       | JAR          |
| `compiler-plugin`| K2 compiler plugin: scope resolution, transform pipeline, randomized container.  | JAR          |
| `runtime`        | Portable Kotlin/JVM container decoder.                                            | JAR          |
| `native-runtime` | Android AAR with a JNI bridge and a native C++ decoder for four ABIs.             | AAR          |
| `gradle-plugin`  | Wires the compiler plugin, annotations, and the right decoder into your build.   | JAR / Plugin |

## Requirements

String Veil's compiler plugin binds to K2 IR APIs that are specific to a Kotlin version, so the
Kotlin version below is a hard requirement, not a lower bound.

| Component        | Version / value                                              |
|------------------|-------------------------------------------------------------|
| Kotlin           | **2.3.21** (must match; the plugin uses internal K2 IR APIs)|
| Gradle           | 8.14+ (developed and tested against 8.14.2)                 |
| JDK (toolchain)  | 17                                                          |
| Platforms        | Kotlin/JVM and Kotlin/Android                               |
| Android `minSdk` | 21 (native ABIs: arm64-v8a, armeabi-v7a, x86, x86_64)       |
| Android NDK      | Only to build `native-runtime` from source (27.3.13750724).|

Consumers of the published Android AAR do **not** need the NDK.

## Installation

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

No manual runtime dependency is required. The plugin adds the compiler plugin, annotations, and the
appropriate decoder automatically: Android compilations receive `native-runtime`; JVM compilations
receive `runtime`.

String Veil is enabled by default. Disable it (for example in debug builds) with:

```kotlin
stringVeil {
    enabled = false
}
```

## Quickstart

```kotlin
import io.github.khiaroslav.stringveil.annotations.Obfuscate

@Obfuscate
val internalEndpoint = "https://internal.example.com/v3/telemetry"
```

At compile time the literal is replaced with a call equivalent to
`StringDecoder.decode(container)` (or `NativeStringDecoder.decode(container)` on Android), and the
plaintext no longer appears in the compiled class or DEX. At runtime the value is restored
transparently — you use the property exactly as before.

For a complete, runnable example that consumes the plugin from source, see [`sample/`](sample) and
run `./gradlew -p sample run`.

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
val aesLayer = "internal-value"

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

`@Obfuscate` defaults to `RANDOM_ALL` with three independently randomized layers. `repetitions` is
limited to `1..16`; more layers increase build output and runtime work. Each layer is one of the
methods above, and the whole pipeline is then sealed inside a per-string randomized container with
random padding, masked key material, a sparse permutation, decoy words, and an ARX block transform.

Under the random methods (`RANDOM_ALL`, `RANDOM_SELECTED`), `BASE64` is applied at most once per
pipeline: it only inflates size by ~4/3 and adds no obfuscation when stacked. An explicit
`method = BASE64` (or a `BASE64`-only selection) is honored for every layer. See
[`:compiler-plugin:benchmark`](#building-and-testing) for the resulting size and decode costs.

> **A note on `AES`.** The `AES` method applies AES/CTR as one reversible layer. Because String Veil
> has no external key, the AES key is randomly generated at build time and stored — masked —
> **inside the same container** as the ciphertext. It adds variety to the obfuscation pipeline; it
> does **not** add confidentiality in the cryptographic sense. See [SECURITY.md](SECURITY.md).

### Scoping with `@DoNotObfuscate`

`@Obfuscate` on a class, function, or property applies to the string literals in its scope.
`@DoNotObfuscate` excludes a nested declaration or expression from an enclosing `@Obfuscate` scope.

### Secret-like literal warnings

Because String Veil is obfuscation and not encryption, the plugin warns at compile time when an
annotated literal looks like a real secret (AWS/GitHub/Google/Slack tokens, private-key blocks,
JWTs, or high-entropy tokens). Treat the warning as a prompt to move the value server-side. To
hard-fail such builds instead:

```kotlin
stringVeil {
    failOnSecretLikeLiterals = true
}
```

## Decoder engines

- `AUTO` (default) — JNI on Android, the Kotlin/JVM decoder elsewhere.
- `NATIVE` — requires an Android compilation and fails the build if `native-runtime` is unavailable.
- `JVM` — forces the portable Kotlin decoder everywhere.

The Android AAR packages `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`, and the plugin always adds
the `runtime` artifact on Android as well. If the native library cannot be loaded for the device's
ABI, `NativeStringDecoder.decode` transparently falls back to the JVM decoder instead of crashing.
The native decoder raises the cost of static analysis further, but an attacker can still hook the
JNI bridge or read the decoded value from memory — the threat model above still applies.

## How it works

1. The K2 compiler plugin resolves which string literals fall inside an `@Obfuscate` scope (and are
   not excluded by `@DoNotObfuscate`).
2. Each selected literal is encoded through a randomized pipeline of reversible transforms and
   sealed into an integer container. The container carries its own layer metadata, a
   **non-cryptographic checksum** (guards against accidental corruption, not tampering), and decoy
   material.
3. The literal in the IR is replaced with a call to the decoder, so the plaintext never reaches the
   compiled class file / DEX.
4. At runtime the decoder (`runtime` on JVM, `native-runtime` on Android) reverses the pipeline and
   materializes the original string.

> K2 does not retain `AnnotationTarget.EXPRESSION` annotations in IR. String Veil recovers direct
> expression markers from Kotlin lexer tokens and IR source offsets, and **fails the build** for any
> `@Obfuscate` it cannot apply to a string literal — an unreadable source, or an annotation on a
> compound expression — rather than silently shipping the literal as plaintext.

## Limitations

- **`const val` cannot be obfuscated.** Its value must remain a compile-time constant, so it cannot
  be replaced with a decoder call. Annotating one is a compile error — drop `const`, or exclude it
  with `@DoNotObfuscate`.
- **`@Obfuscate` must reach a string literal.** Annotating a compound expression — an `if`/`when`, an
  interpolated template (`"...${x}..."`), or a call — is a compile error, because String Veil rewrites
  literals, not runtime values. Annotate the literal directly, or the enclosing declaration.
- **Only Kotlin string literals in scope are protected.** String Veil rewrites Kotlin IR, so it does
  not touch Android resources, `AndroidManifest.xml`, `strings.xml`, assets, `BuildConfig`, or
  non-Kotlin sources. Keep sensitive strings out of those surfaces yourself.

## Local development

To test an unpublished checkout, publish all artifacts to your local Maven repository:

```bash
./gradlew publishToMavenLocal
```

Add `mavenLocal()` to both `pluginManagement.repositories` and
`dependencyResolutionManagement.repositories` in the consumer project's `settings.gradle.kts`, and
apply the plugin using the version from `VERSION_NAME` in `gradle.properties`.

## Building and testing

```bash
./gradlew test :native-differential:nativeDifferentialTest :native-runtime:assembleRelease :gradle-plugin:validatePlugins publishToMavenLocal
```

Building `native-runtime` from source requires an installed Android NDK. Set `STRING_VEIL_NDK_HOME`
(or `stringVeilNdkDir`) when the NDK cannot be found through `local.properties`.

### Differential decoder testing

The Kotlin (`runtime`) and C++ (`native-runtime`) decoders are two independent implementations of
the same container format, so they are tested to agree byte-for-byte:

- `StringCipherRoundTripTest` (in `compiler-plugin`) round-trips a broad corpus — every method,
  `RANDOM_ALL`/`RANDOM_SELECTED`, repetitions `1..16`, ASCII/Unicode/emoji/control bytes, plus 500
  randomized fuzz cases — through the build-time cipher and the JVM decoder.
- `:native-differential:nativeDifferentialTest` host-compiles the C++ decoder into a shared library
  and decodes the *same* containers with it, asserting they match the JVM result. It runs
  automatically wherever a C++ toolchain and JNI headers are present (including CI) and is skipped
  gracefully elsewhere — no Android device or emulator required.

### Fuzzing the native decoder

The C++ decoder parses a raw `int[]` container, so its parser is fuzzed under AddressSanitizer and
UndefinedBehaviorSanitizer to prove it rejects malformed or hostile input without out-of-bounds
reads, hangs, or undefined behavior (the JVM decoder throws on the same input):

```bash
./gradlew :native-differential:nativeFuzzTest -PstringVeil.fuzzRuns=2000000
```

The harness (`native-runtime/src/test/cpp/fuzz_open_container.cpp`) replays the valid differential
corpus and then runs a bounded random-mutation loop. It needs only a C++17 compiler with
`-fsanitize=address,undefined` (no libFuzzer runtime). It is a dedicated task rather than part of
`check` — CI runs it on Linux, where the sanitizer runtimes are reliable.

### Benchmarks

To see the size and speed cost of obfuscation — container-size overhead per method and configuration,
plus decode throughput — run:

```bash
./gradlew :compiler-plugin:benchmark
```

Size figures are exact; timing is a rough steady-state estimate and machine-dependent, so this is a
report rather than a `check` gate.

### API documentation

Generate aggregated HTML API docs for the consumer-facing modules (`annotations`, `runtime`,
`gradle-plugin`) with [Dokka](https://kotlinlang.org/docs/dokka-introduction.html):

```bash
./gradlew dokkaGenerate
```

The docs land in `build/dokka/html`.

## Publishing

Release tags publish the Maven artifacts to Maven Central, the Gradle plugin to the Plugin Portal,
and create a GitHub release. The tag must exactly match `VERSION_NAME`, prefixed with `v`:

```bash
git tag v0.1.0-alpha01
git push origin v0.1.0-alpha01
```

The release workflow runs in a manually approved `release` environment and uploads to the Central
Portal in `USER_MANAGED` mode, so the deployment is held for a final manual review before it goes
live. See [`.github/workflows/release.yml`](.github/workflows/release.yml) for the required secrets
and the `io.github.khiaroslav` namespace verification steps.

### Supply chain

- **Reproducible artifacts.** Published jars and the AAR strip embedded timestamps and pin entry
  order, so a given source revision builds byte-for-byte identical archives on any machine.
- **Signed build provenance.** The release attests every published binary with
  [SLSA build provenance](https://slsa.dev/) via GitHub's attestation service. Because the archives
  are reproducible, the attested digests match what you can rebuild yourself. Verify with:

  ```bash
  gh attestation verify <artifact.jar> --repo KhIaroslav/string-veil
  ```

- **SBOM.** Each release attaches an SPDX software bill of materials
  (`string-veil-sbom.spdx.json`) as a GitHub release asset.
- **Pinned CI actions.** Every GitHub Action is pinned to a commit SHA.

## Contributing

Contributions are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for build, style, and PR
conventions, and [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) for community expectations. Security
issues have a separate, private disclosure process in [SECURITY.md](SECURITY.md) — please do not
open a public issue for them.

## License

String Veil is licensed under the [Apache License 2.0](LICENSE).
