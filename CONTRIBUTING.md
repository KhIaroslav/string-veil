# Contributing to String Veil

Thanks for your interest in improving String Veil. This document explains how to build the project,
what we expect from changes, and how to get a pull request merged.

By participating you agree to abide by our [Code of Conduct](CODE_OF_CONDUCT.md).

## Reporting issues

- **Bugs and features:** open a [GitHub issue](https://github.com/KhIaroslav/string-veil/issues)
  using the provided templates. Include your Kotlin, Gradle, JDK, and (for Android) AGP/`minSdk`
  versions, plus a minimal reproduction where possible.
- **Security vulnerabilities:** do **not** open a public issue. Follow the private process in
  [SECURITY.md](SECURITY.md).

## Development setup

Requirements:

- JDK 17 (the build uses a Kotlin toolchain pinned to 17).
- Kotlin **2.3.21** — the compiler plugin binds to internal K2 IR APIs, so this version must match.
- Gradle is provided by the wrapper (`./gradlew`); do not rely on a system Gradle.
- Android NDK **only** if you build `native-runtime` from source. Set `STRING_VEIL_NDK_HOME` or
  `stringVeilNdkDir` if it is not discoverable via `local.properties`.

Clone and run the full check:

```bash
git clone https://github.com/KhIaroslav/string-veil.git
cd string-veil
./gradlew test :native-runtime:assembleRelease :gradle-plugin:validatePlugins publishToMavenLocal
```

To iterate against a real consumer project, `./gradlew publishToMavenLocal` and add `mavenLocal()`
to the consumer's `settings.gradle.kts` (see the README's *Local development* section).

## Module layout

| Module           | What lives here                                                        |
|------------------|-----------------------------------------------------------------------|
| `annotations`    | Public `@Obfuscate` / `@DoNotObfuscate` API.                          |
| `compiler-plugin`| K2 registration, scope resolution, the encode pipeline, the container.|
| `runtime`        | Portable Kotlin/JVM decoder.                                          |
| `native-runtime` | Android JNI bridge (`.java`) and native decoder (`.cpp`).            |
| `gradle-plugin`  | Build integration and dependency wiring.                             |

> The `runtime` (Kotlin) and `native-runtime` (C++) decoders implement the **same container
> format**. Any change to the format in `compiler-plugin` must be mirrored in **both** decoders, and
> both must stay byte-for-byte compatible. This is the most error-prone area of the project — please
> add or update tests that exercise the change on both paths.

## Coding standards

- **Style:** follow the existing code and the [`.editorconfig`](.editorconfig) (4-space indent, LF,
  UTF-8, final newline; 2-space indent for YAML). Match the surrounding code's naming and idioms.
- **Public API:** the Kotlin modules use `explicitApi()`. Every public declaration needs an explicit
  visibility modifier and a KDoc comment.
- **No secrets, ever:** do not commit real credentials, keys, or tokens — not in code, tests,
  fixtures, or history. `local.properties` is git-ignored and must stay that way.
- **Keep it honest:** String Veil is an obfuscation tool. Do not describe it, in code or docs, as
  encryption or as a secrets store. See [SECURITY.md](SECURITY.md).

## Tests

- Add tests for every behavioral change. Prefer tests that would fail without your change.
- Compiler-plugin changes should be covered by the integration tests that compile Kotlin through the
  plugin and assert both the runtime value and the **absence** of the plaintext in generated output.
- Run `./gradlew test` before pushing. If you touched the container format or the native decoder,
  run `:native-runtime:assembleRelease` and validate both decoders.

## Commit and PR conventions

- Write focused commits with clear messages. We use
  [Conventional Commits](https://www.conventionalcommits.org/) prefixes (`feat:`, `fix:`, `docs:`,
  `ci:`, `build:`, `refactor:`, `test:`, `chore:`).
- Keep pull requests small and single-purpose. Fill in the PR template.
- Add a `CHANGELOG.md` entry under **Unreleased** for any user-visible change.
- CI (build, tests, plugin validation, wrapper validation) must be green before review.

## License

By contributing, you agree that your contributions are licensed under the
[Apache License 2.0](LICENSE), the same license that covers the project.
