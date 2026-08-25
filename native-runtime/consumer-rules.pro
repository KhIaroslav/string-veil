# The native method name is resolved by JNI RegisterNatives and must not be renamed or removed.
-keep class io.github.khiaroslav.stringveil.runtime.NativeStringDecoder {
    public static java.lang.String decode(int[]);
    native <methods>;
}

# The JVM decoder is the runtime fallback when the native library is unavailable for an ABI.
-keep class io.github.khiaroslav.stringveil.runtime.StringDecoder {
    public static java.lang.String decode(int[]);
}
