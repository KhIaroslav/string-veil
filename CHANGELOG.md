# Changelog

All notable user-visible changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project
follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/KhIaroslav/string-veil/compare/v0.1.0-alpha01...HEAD
[0.1.0-alpha01]: https://github.com/KhIaroslav/string-veil/releases/tag/v0.1.0-alpha01
