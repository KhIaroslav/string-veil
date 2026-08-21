# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Project documentation and open-source scaffolding: threat-model-first `README`, `SECURITY.md`
  (threat model + private vulnerability disclosure), `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, issue
  and pull-request templates, `CODEOWNERS`, and Dependabot configuration.
- Differential decoder testing that proves the JVM (`runtime`) and native (`native-runtime`)
  decoders agree on identical containers: a broad JVM round-trip corpus (`StringCipherRoundTripTest`)
  plus a new `native-differential` module that host-compiles the C++ decoder and cross-checks the
  same containers without an Android device. Both run in CI.

### Changed

- Release pipeline hardened: the publish job now runs in a manually approved `release` GitHub
  Environment, the Gradle wrapper is validated in CI (`validate-wrappers`), and Central Portal
  deployments use `USER_MANAGED` mode so releases are held for a final manual review.
- `gradle-plugin` now declares `kotlin-gradle-plugin-api` as `compileOnly` so it no longer leaks
  into the published POM or pins the consumer's Kotlin Gradle plugin version.

## [0.1.0-alpha01] — unreleased

Initial pre-release. The container format is not yet stable.

### Added

- `@Obfuscate` / `@DoNotObfuscate` annotations with configurable methods, repetitions, and engine.
- K2 compiler plugin: scope resolution, a randomized multi-layer encode pipeline, and a per-string
  randomized container.
- Portable Kotlin/JVM decoder (`runtime`) and Android JNI/native decoder (`native-runtime`) for
  arm64-v8a, armeabi-v7a, x86, and x86_64.
- Gradle plugin that wires the compiler plugin, annotations, and the appropriate decoder into
  Kotlin/JVM and Kotlin/Android builds.

[Unreleased]: https://github.com/KhIaroslav/string-veil/compare/v0.1.0-alpha01...HEAD
[0.1.0-alpha01]: https://github.com/KhIaroslav/string-veil/releases/tag/v0.1.0-alpha01
