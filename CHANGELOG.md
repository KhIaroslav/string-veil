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
- Compile-time warning when an `@Obfuscate` literal looks like a real secret (AWS/GitHub/Google/Slack
  tokens, private-key blocks, JWTs, high-entropy tokens), with `stringVeil.failOnSecretLikeLiterals`
  to escalate it to a build error.
- Fail-closed behavior so a literal is never silently emitted as plaintext: expression-level
  `@Obfuscate` recovery now errors the build if it cannot read the source (instead of skipping the
  literal), and fail-closed regression tests cover string templates, raw strings, and `const val`.
- Native decoder fuzzing under AddressSanitizer/UndefinedBehaviorSanitizer
  (`:native-differential:nativeFuzzTest`): a standalone harness replays the valid container corpus
  and runs a bounded random-mutation loop over `open_outer_container`, so malformed or hostile
  containers cannot cause out-of-bounds reads, hangs, or undefined behavior. It runs on Linux in CI.

### Fixed

- `@Obfuscate` on a `const val` no longer crashes the compiler backend with an `INTERNAL_ERROR`. A
  `const val` must keep a compile-time-constant initializer, so it now reports a clear compile error
  telling you to drop `const` or exclude it with `@DoNotObfuscate`.

- The Android JNI bridge no longer crashes class initialization with `UnsatisfiedLinkError` when the
  native library is unavailable for the device's ABI: `NativeStringDecoder.decode` now loads the
  library defensively and falls back to the JVM `StringDecoder`. The `runtime` artifact is therefore
  always added on Android as well.

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
