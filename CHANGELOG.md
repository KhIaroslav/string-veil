# Changelog

All notable user-visible changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project
follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- The JVM integration runs in a dedicated `stringVeilObfuscateJvm` task instead of a `classes.doLast`
  action, so JVM consumer builds now support Gradle's configuration cache.

## [0.1.0] — 2026-09-01

First release. The container format and public API may change between `0.x` releases.

### Added

- Selective, build-time string obfuscation for Kotlin, Java, and Android: an ASM bytecode transform
  rewrites selected string literals in the compiled classes and restores them at runtime, independent
  of the Kotlin compiler version.
- `@Obfuscate` and `@DoNotObfuscate` declaration annotations for direct string literals owned by a
  class, function, property (including custom getters), or field.
- An `obfuscate("...")` marker function for one exact call site — including forms an annotation cannot
  reach: `by lazy { }` / delegated properties, `companion object` / static initializers, interpolation
  fragments, and sub-expressions. Only a direct literal is supported; the build warns (fail-closed)
  when it is applied to a non-literal, and it is an identity function when the plugin is absent.
- A randomized reversible container pipeline (bit-shift, XOR, Base64, and AES/CTR layers) sealed in a
  per-literal integer container with masked metadata, padding, permutation, decoy words, and a
  corruption checksum.
- Gradle integration for JVM and Android modules: JVM classes are rewritten after compilation, and
  Android project classes pass through AGP's `ScopedArtifacts` pipeline before packaging. The plugin
  adds the annotations and the appropriate runtime automatically.
- A native Android decoder for `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`, with a JVM fallback
  when the native library is unavailable.
- A compile-time warning for credential-shaped literals, with `stringVeil.failOnSecretLikeLiterals`
  to turn it into a build error.
- Published `annotations` and `runtime` target Java 8 bytecode and are built with Kotlin 1.9.24, so
  consumers on JDK 8 or newer and any Kotlin version are supported.
- Signed Maven Central and Gradle Plugin Portal publication, SLSA build-provenance attestations, an
  SPDX SBOM, pinned GitHub Actions, and reproducible archive metadata.

[Unreleased]: https://github.com/khstov/string-veil/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/khstov/string-veil/releases/tag/v0.1.0
