package io.github.khiaroslav.stringveil.differential;

import io.github.khiaroslav.stringveil.runtime.NativeStringDecoder;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

/**
 * Decodes every container in the differential corpus with the native C++ decoder and checks it
 * against the plaintext recorded when the corpus was produced by the JVM cipher.
 *
 * <p>Because the JVM {@code StringDecoder} already validated every one of these containers when the
 * corpus was written, native-result == recorded-plaintext is exactly the JVM<->native agreement we
 * want to check. This runs in a forked JVM so that {@code NativeStringDecoder} is loaded by the
 * system class loader, which is the loader {@code JNI_OnLoad}'s {@code FindClass} uses to bind the
 * native method.
 *
 * <p>Exit code 0 on full agreement, 1 on any mismatch, 2 on a corpus/IO error.
 */
public final class NativeDifferentialRunner {
    private static final int MAGIC = 0x53564454; // "SVDT"

    private NativeDifferentialRunner() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: NativeDifferentialRunner <corpus-file>");
            System.exit(2);
        }
        File corpus = new File(args[0]);
        if (!corpus.isFile()) {
            System.err.println("corpus file not found: " + corpus.getAbsolutePath());
            System.exit(2);
        }

        // Guard against a false pass: if the native library did not load, decode() would silently
        // fall back to the JVM decoder and this test would compare the JVM against itself.
        if (!NativeStringDecoder.isNativeAvailable()) {
            System.err.println(
                    "native library did not load; refusing to run the differential test against the "
                            + "JVM fallback. Check -Djava.library.path and the compiled library.");
            System.exit(2);
        }

        int count;
        int failures = 0;
        try (DataInputStream in =
                new DataInputStream(new BufferedInputStream(new FileInputStream(corpus)))) {
            int magic = in.readInt();
            if (magic != MAGIC) {
                System.err.println("bad corpus magic: 0x" + Integer.toHexString(magic));
                System.exit(2);
            }
            count = in.readInt();
            for (int i = 0; i < count; i++) {
                String label = in.readUTF();
                byte[] plaintextBytes = new byte[in.readInt()];
                in.readFully(plaintextBytes);
                String expected = new String(plaintextBytes, StandardCharsets.UTF_8);

                int[] container = new int[in.readInt()];
                for (int j = 0; j < container.length; j++) {
                    container[j] = in.readInt();
                }

                String actual;
                try {
                    actual = NativeStringDecoder.decode(container);
                } catch (Throwable t) {
                    failures++;
                    System.err.println("FAIL [" + label + "] native decoder threw: " + t);
                    continue;
                }
                if (!expected.equals(actual)) {
                    failures++;
                    System.err.println(
                            "FAIL ["
                                    + label
                                    + "] native/JVM mismatch: expected "
                                    + describe(expected)
                                    + " but native produced "
                                    + describe(actual));
                }
            }
        }

        System.out.println(
                "native differential: " + (count - failures) + "/" + count + " containers agreed");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static String describe(String value) {
        String escaped = value.length() > 64 ? value.substring(0, 64) + "..." : value;
        return "\"" + escaped.replace("\n", "\\n").replace("\r", "\\r") + "\" (len=" + value.length() + ")";
    }
}
