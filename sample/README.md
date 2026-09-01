# String Veil sample

A tiny, runnable example that consumes String Veil **straight from source** — no publishing step.
Its [`settings.gradle.kts`](settings.gradle.kts) uses `includeBuild("..")`, so the
`io.github.khstov.string-veil` plugin and its runtime are substituted from the surrounding
project.

Run it from the repository root:

```bash
./gradlew -p sample run
```

It prints a few `@Obfuscate`-protected strings that decode at runtime. To confirm the plaintext is
gone from the compiled bytecode:

```bash
./gradlew -p sample classes
javap -c -p sample/build/classes/kotlin/main/io/github/khstov/stringveil/sample/MainKt.class
```

You will see calls to `StringDecoder.decode(int[])` in place of the string literals. The only
plaintext that remains is the one literal marked `@DoNotObfuscate`.

See [`src/main/kotlin/.../Main.kt`](src/main/kotlin/io/github/khstov/stringveil/sample/Main.kt)
for property-level, function-level, class-level, and opt-out usage.
