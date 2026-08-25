<!--
Thanks for contributing to String Veil!
Please read CONTRIBUTING.md before opening this PR.
Do not report security vulnerabilities here — see SECURITY.md.
-->

## Summary

<!-- What does this change do, and why? -->

## Related issues

<!-- e.g. Closes #123 -->

## Type of change

- [ ] Bug fix
- [ ] New feature
- [ ] Documentation
- [ ] Build / CI
- [ ] Refactor (no behavior change)

## Checklist

- [ ] I read [CONTRIBUTING.md](../CONTRIBUTING.md).
- [ ] `./gradlew test :gradle-plugin:validatePlugins` passes locally.
- [ ] I added or updated tests for my change (tests that would fail without it).
- [ ] If I touched the container format or native decoder, I updated **both** the Kotlin (`runtime`)
      and C++ (`native-runtime`) decoders and verified they stay compatible.
- [ ] Public API changes keep `explicitApi()` happy and are documented with KDoc.
- [ ] I updated `CHANGELOG.md` under **Unreleased** for any user-visible change.
- [ ] I did not commit any secrets, credentials, or real keys.
- [ ] Docs still describe String Veil honestly (obfuscation, not encryption / secret storage).
