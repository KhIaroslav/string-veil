package io.github.khiaroslav.stringveil.runtime;

/**
 * Android JNI bridge for String Veil.
 *
 * <p>{@link #decode(int[])} uses the native decoder in {@code libstring_veil_native.so} when it is
 * available for the current ABI, and otherwise falls back to the portable {@link StringDecoder}.
 * The native library is loaded defensively: a missing or unloadable library degrades to the JVM
 * decoder instead of crashing class initialization with an {@link UnsatisfiedLinkError} (which
 * would surface as an {@code ExceptionInInitializerError} at the first use of a protected string).
 */
public final class NativeStringDecoder {
    private static final boolean NATIVE_AVAILABLE = loadNativeLibrary();

    private static boolean loadNativeLibrary() {
        try {
            System.loadLibrary("string_veil_native");
            return true;
        } catch (Throwable failure) {
            // No native library for this ABI: fall back to the JVM decoder instead of crashing.
            return false;
        }
    }

    private NativeStringDecoder() {}

    /** Materializes one protected UTF-8 string, using the native decoder when it is available. */
    public static String decode(int[] container) {
        if (NATIVE_AVAILABLE) {
            return nativeDecode(container);
        }
        return StringDecoder.decode(container);
    }

    /** Whether the native library loaded for the current ABI. Exposed for diagnostics and tests. */
    public static boolean isNativeAvailable() {
        return NATIVE_AVAILABLE;
    }

    private static native String nativeDecode(int[] container);
}
