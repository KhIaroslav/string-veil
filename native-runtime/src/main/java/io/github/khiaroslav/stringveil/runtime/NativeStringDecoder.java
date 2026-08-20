package io.github.khiaroslav.stringveil.runtime;

/** Android JNI bridge. The decoder implementation lives in libstring_veil_native.so. */
public final class NativeStringDecoder {
    static {
        System.loadLibrary("string_veil_native");
    }

    private NativeStringDecoder() {}

    /** Materializes a protected UTF-8 string in native code. */
    public static native String decode(int[] container);
}
