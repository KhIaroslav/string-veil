# Contributing to String Veil

Thanks for helping improve String Veil. This guide describes the current bytecode-based architecture,
the checks expected for changes, and the release workflow.

By participating, you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## Report an issue

- Use the provided [GitHub issue templates](https://github.com/KhIaroslav/string-veil/issues) for bugs
  and feature requests. Include the String Veil, Gradle, JDK, Kotlin or Java, and—when applicable—AGP,
  Android SDK, minSdk, and ABI versions.
- Report vulnerabilities privately through [SECURITY.md](SECURITY.md). Do not include vulnerability
  details in a public issue, discussion, or pull request.

## Development requirements

- JDK 17.
- The Gradle wrapper included in the repository.
- Android SDK and NDK 27.3.13750724 only when building or testing `native-runtime` and Android
  integration.
- A C++17 compiler with ASan/UBSan support for the native mutation harness.

The transform operates on compiled class files and does not bind to internal K2 IR APIs. The current
development matrix is recorded in [README.md](README.md); compatibility claims should be expanded
only after the corresponding versions are tested.

Set `STRING_VEIL_NDK_HOME` or `stringVeilNdkDir` when the NDK cannot be discovered through
`local.properties`.

## Build

```bash
git clone https://github.com/KhIaroslav/string-veil.git
cd string-veil

./gradlew test \
  :gradle-plugin:validatePlugins \
  :native-differential:nativeDifferentialTest \
  :native-runtime:assembleRelease
```

Useful additional checks:

```bash
./gradlew :native-differential:nativeFuzzTest -PstringVeil.fuzzRuns=2000000
./gradlew :bytecode:benchmark
./gradlew -p sample run
./gradlew dokkaGenerate
```

To test a local publication in another project:

```bash
./gradlew publishToMavenLocal
```

Put `mavenLocal()` before public repositories in both `pluginManagement.repositories` and
`dependencyResolutionManagement.repositories`. Repository order matters when the same version is
already published remotely.

## Modules

| Module | Responsibility |
|---|---|
| `annotations` | Consumer-facing `@Obfuscate` and `@DoNotObfuscate` API |
| `bytecode` | ASM transform, build-time encoder, randomized container |
| `runtime` | Portable JVM decoder and shared format definitions |
| `native-runtime` | Android JNI bridge and C++ decoder |
| `gradle-plugin` | JVM and Android task/dependency wiring |
| `native-differential` | JVM/C++ corpus comparison and native mutation harness |
| `sample` | Included-build JVM smoke test |
| `android-test` | Standalone Maven Local Android emulator test |

The JVM and C++ decoders implement the same container format independently. Any change to
`StringVeilFormat`, the encoder, the outer container, or the pipeline must be reflected in both
decoders and covered by the shared differential corpus.

## Coding standards

- Follow [`.editorconfig`](.editorconfig): UTF-8, LF, final newline, four-space code indentation, and
  two-space YAML indentation.
- Consumer-facing Kotlin modules use `explicitApi()`. Public declarations require explicit
  visibility, explicit types where required, and accurate KDoc.
- Keep comments focused on current behavior. Historical rationale belongs in Git history or an
  architecture document, not in a comment that can become false.
- Use “obfuscation”, “encode”, and “decode” in user-facing documentation. Do not present String Veil
  as cryptographic protection or secret storage.
- Never commit real credentials, signing keys, tokens, or private material. `local.properties` must
  remain ignored.

## Tests

- Every behavior change should have a test that fails without the change.
- Bytecode-transform tests must check both the runtime value and absence of the selected plaintext
  from the final transformed class.
- Add Kotlin and Java fixtures when compiler output can differ.
- Cover complex initializer, custom getter, lambda, nested-class, and opt-out behavior explicitly;
  never infer support from a nearby simple case.
- Android integration changes must inspect the final AAR or DEX as well as run the emulator test.
- Container or decoder changes require `:native-differential:nativeDifferentialTest`.
- Parser changes require the native ASan/UBSan task on a supported host.
- Fuzzing and finite corpora are bug-finding checks, not proofs of correctness or memory safety.

## Commit and pull-request conventions

- Use focused [Conventional Commits](https://www.conventionalcommits.org/) prefixes such as `feat:`,
  `fix:`, `docs:`, `test:`, `refactor:`, `build:`, `ci:`, and `chore:`.
- Use conventional branch prefixes such as `feat/`, `fix/`, `docs/`, `refactor/`, or `chore/`.
- Keep pull requests single-purpose and complete the PR template.
- Add user-visible changes under **Unreleased** in [CHANGELOG.md](CHANGELOG.md).
- Do not claim support, security guarantees, or reproducibility beyond what automated checks
  demonstrate.

## Maintainer release process

1. Update `VERSION_NAME`, README examples, and the changelog together.
2. Run the full JVM, native, Android, plugin-validation, sample, and publication checks.
3. Create a signed annotated tag whose name is `v` followed by the exact `VERSION_NAME`.
4. Push the tag. The release workflow validates the version and publication secrets, builds and
   signs the artifacts, uploads to Maven Central and the Plugin Portal, creates provenance
   attestations, marks pre-release versions appropriately, creates the GitHub release, and attaches
   the SPDX SBOM.
5. Review the user-managed Central Portal deployment before publishing it.

Do not move or recreate an already published release tag.

## License

Contributions are licensed under the [Apache License 2.0](LICENSE).
