# Android instrumented test

An isolated Android build that verifies the one thing the host-only differential test cannot: that
the native JNI decoder (`libstring_veil_native.so`) actually loads for the device's ABI and decodes
an obfuscated string on a real Android runtime, rather than silently degrading to the JVM fallback.

It consumes String Veil from the **local Maven repository**, exactly like a real consumer, so publish
first:

```bash
./gradlew publishToMavenLocal
./gradlew -p android-test connectedDebugAndroidTest
```

The second command needs a running emulator or a connected device with an ABI the AAR ships
(`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`). CI runs it on an `x86_64` emulator.

The test asserts `NativeStringDecoder.isNativeAvailable()` (the `.so` loaded, so the NATIVE engine —
not the JVM fallback — is in use) and that the `@Obfuscate`-protected literal decodes to its original
value.
