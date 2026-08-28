# Security Policy

## Threat model — read this first

String Veil is a **build-time string obfuscator**. Understanding what it does and does not protect
against is essential to using it safely.

### What String Veil protects against

- **Casual and automated static inspection.** For supported declaration and bytecode shapes, selected
  literals are replaced in the final transformed class or DEX, so they are not exposed to
  `strings`, `grep`, a decompiler's constant-pool view, or bulk APK/JAR scraping.
- **Low-effort scraping at scale.** Recovering selected strings requires locating the generated
  containers and applying the shipped decoder rather than reading the original literals directly.

See the README's **Current limitations** section before relying on a declaration shape. Complex
property initializers, custom getters, generated lambdas, nested classes, and other compiler-generated
forms are not all covered yet. Inspect the final artifact when absence of a particular plaintext
matters.

### What String Veil does NOT protect against

- **A motivated attacker who has your artifact.** String Veil has **no external key**. Everything
  required to reconstruct a protected string ships inside the artifact, because the runtime must be
  able to decode it with no secret input. Given the artifact — and especially given these open
  sources — the plaintext is recoverable. This is inherent to any client-side "hide a string"
  technique and is not a defect.
- **Runtime extraction.** An attacker can hook the decoder (including the JNI bridge), attach a
  debugger, or read the decoded value out of process memory once it has been materialized.
- **Tampering.** The container carries a **non-cryptographic checksum** that detects accidental
  corruption. It is **not** a MAC and does not detect deliberate modification.

### It is not encryption, and not a secrets store

The `ObfuscationMethod.AES` layer applies AES/CTR, but the key is randomly generated at build time
and stored (masked) inside the same container as the ciphertext. It contributes obfuscation
diversity, **not confidentiality**. Do not read the presence of "AES" as a claim of cryptographic
protection.

**Never place long-lived or high-value secrets in obfuscated strings** — API keys, passwords, OAuth
client secrets, private keys, or signing material. If disclosure of a value would be a security
incident, keep it off the client entirely:

- Keep secrets on a server and expose narrow, authenticated endpoints instead.
- Use a platform secrets manager or key-management service.
- Inject deployment-specific values at runtime from a trusted source, so the secret never lands in
  the artifact.

### Recommended usage

- Treat String Veil as a **speed bump** that raises the cost of static analysis, layered with — not
  instead of — R8/ProGuard shrinking and obfuscation on Android release builds.
- Reserve it for low-sensitivity strings: internal endpoints, feature flags, log markers, and
  similar values you would simply rather not publish in plaintext.

## Supply-chain and release controls

Published artifacts are signed and distributed by a release pipeline with the following controls:

- The Gradle wrapper JAR is validated against Gradle's published checksums in CI
  (`validate-wrappers`).
- Publishing runs only in a manually approved `release` GitHub Environment. Configure required
  reviewers under **Settings → Environments → release**, and scope the signing and portal secrets
  to that environment.
- Central Portal deployments use `USER_MANAGED` mode, so a release is uploaded but held for a final
  manual review before it goes live.
- Third-party GitHub Actions are pinned to full commit SHAs.
- Binary artifacts produced by the release job receive GitHub build-provenance attestations, and the
  GitHub release includes an SPDX SBOM.
- Archive tasks omit entry timestamps and use reproducible entry ordering. Full byte-for-byte
  reproducibility across operating systems and native toolchains is not currently guaranteed.

## Supported versions

String Veil is in early development. Only the latest published version receives security fixes.

| Version        | Supported |
|----------------|-----------|
| `0.1.0-alphaNN`| ✅ latest pre-release only |
| older          | ❌         |

## Reporting a vulnerability

**Please do not report security vulnerabilities through public GitHub issues, discussions, or pull
requests.**

Report privately through GitHub's **[private vulnerability reporting](https://github.com/KhIaroslav/string-veil/security/advisories/new)**
("Report a vulnerability" on the repository's Security tab). If that channel is unavailable, open a
minimal public issue that says only "requesting a private security contact" — without any details —
so a maintainer can reach out.

Please include, where possible:

- the affected version(s) and module(s),
- a description of the issue and its impact,
- reproduction steps or a proof of concept,
- any suggested remediation.

### What to expect

- **Acknowledgement:** within 7 days.
- **Assessment and triage:** we will confirm the report, determine severity, and share a remediation
  plan.
- **Disclosure:** we prefer coordinated disclosure. We will agree on a timeline with you and credit
  you in the release notes and advisory unless you prefer to remain anonymous.

Because String Veil is explicitly an obfuscation tool and not a cryptographic control, reports that
amount to "the obfuscation can be reversed given the artifact" fall inside the documented threat
model and are not treated as vulnerabilities. Reports of supported annotated literals remaining in
the final transformed output, container-format flaws that cause crashes, memory-safety issues in the
native decoder, incorrect decoding, or build-time information leakage are in scope and very welcome.
