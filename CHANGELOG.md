# Changelog

All notable user-visible changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project
follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0-alpha02] — 2026-08-31

### Changed

- Android project classes are transformed through AGP's `ScopedArtifacts` classes pipeline, producing
  one rebuilt class archive for the selected variant.
- Local Android integration tests resolve `mavenLocal()` before public repositories, ensuring a
  locally published build is tested even when the same version already exists remotely.
- Documentation and API comments now distinguish implemented bytecode behavior from reserved
  per-annotation settings and list the currently unsupported bytecode shapes.
- Maven and Gradle Plugin Portal descriptions now include Java, JVM, and Android support.
- Gradle Plugin Portal metadata no longer claims configuration-cache compatibility while the JVM
  integration still mutates the `classes` output in a task action.
- The release workflow validates the signing key/passphrase pair before the full build and marks
  SemVer pre-release tags as GitHub pre-releases.
- The published artifacts are built with Kotlin 1.9.24 instead of 2.3.21, lowering the
  `kotlin-stdlib` version consumers inherit through the POM.
- The published `annotations`, `runtime`, and native-bridge classes now target Java 8 bytecode
  (down from Java 17), and the published module metadata declares `org.gradle.jvm.version` 8, so
  consumers on JDK 8 or 11 are no longer forced onto JDK 17. The build still uses a JDK 17 toolchain.

### Fixed

- Obfuscated string literals no longer remain in the compiled Android class files or a published
  AAR. AGP's ASM instrumentation writes through a reader-backed `ClassWriter` that copies the
  original constant pool wholesale, so removing the `LDC` left the plaintext orphaned but still
  present in the pool; the `ScopedArtifacts` classes transform re-serializes through a fresh writer
  that rebuilds the pool from live references. (A dexed APK was already clean because D8 rebuilds the
  string pool, but a published AAR was not.) A build-time check fails the Android build if the
  literal is ever found in the packaged AAR.

## [0.1.0-alpha01] — 2026-08-28

First public pre-release. The container format and public API may change between pre-release
versions.

### Added

- Selective declaration annotations: `@Obfuscate` and `@DoNotObfuscate`.
- An ASM bytecode transform for direct string constants in supported Kotlin and Java classes.
- A randomized reversible pipeline using bit-shift, XOR, Base64, and AES/CTR layers, sealed in a
  per-literal integer container with masked metadata, padding, permutation, decoy words, and a
  non-cryptographic corruption checksum.
- JVM and Android Gradle integration. JVM projects use the portable decoder; Android projects call a
  JNI decoder with a JVM fallback when the native library is unavailable.
- Native Android decoder binaries for `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`.
- Warnings for annotated literals that resemble common credentials, with
  `stringVeil.failOnSecretLikeLiterals` to turn warnings into build errors.
- JVM round-trip tests, JVM/C++ differential tests, Android emulator coverage, and a native
  ASan/UBSan mutation harness for the outer-container parser.
- A runnable JVM sample, aggregated Dokka API documentation, issue/PR templates, a security policy,
  and contribution guidelines.
- Signed Maven Central and Gradle Plugin Portal publication, build-provenance attestations, an SPDX
  SBOM, pinned GitHub Actions, and reproducible archive metadata.
- The `:bytecode:benchmark` report for container-size overhead and JVM decode timing.

### Fixed

- Android class initialization falls back to the JVM decoder instead of failing with
  `UnsatisfiedLinkError` when the native library cannot be loaded.

### Known limitations

- The bytecode transform does not yet read the annotation's `method`, `methods`, `repetitions`, or
  `engine` values; it uses the default pipeline and selects the decoder from the project type.
- `const val`, complex property initializers, custom getters, delegated properties, generated
  lambdas, nested classes, interpolated-template recipes, resources, assets, and `BuildConfig` are
  not all covered. See the README for the exact current limitations.

[Unreleased]: https://github.com/KhIaroslav/string-veil/compare/v0.1.0-alpha02...HEAD
[0.1.0-alpha02]: https://github.com/KhIaroslav/string-veil/compare/v0.1.0-alpha01...v0.1.0-alpha02
[0.1.0-alpha01]: https://github.com/KhIaroslav/string-veil/releases/tag/v0.1.0-alpha01
