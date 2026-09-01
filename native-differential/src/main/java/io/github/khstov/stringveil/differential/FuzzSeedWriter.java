package io.github.khstov.stringveil.differential;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Explodes the differential corpus into one seed file per container for the libFuzzer harness
 * ({@code fuzz_open_container.cpp}). Seeding with valid containers lets the fuzzer get past the
 * header check and mutate real, deeply-parsed inputs instead of wasting runs on early rejects.
 *
 * <p>Each container word is written little-endian, matching how the harness reinterprets its input
 * bytes as 32-bit words.
 *
 * <p>Usage: {@code FuzzSeedWriter <corpus-file> <seeds-dir>}. Exit code 0 on success, 2 on IO error.
 */
public final class FuzzSeedWriter {
    private static final int MAGIC = 0x53564454; // "SVDT"

    private FuzzSeedWriter() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: FuzzSeedWriter <corpus-file> <seeds-dir>");
            System.exit(2);
        }
        File corpus = new File(args[0]);
        Path seedsDir = new File(args[1]).toPath();
        if (!corpus.isFile()) {
            System.err.println("corpus file not found: " + corpus.getAbsolutePath());
            System.exit(2);
        }
        Files.createDirectories(seedsDir);

        int written = 0;
        try (DataInputStream in =
                new DataInputStream(new BufferedInputStream(new FileInputStream(corpus)))) {
            int magic = in.readInt();
            if (magic != MAGIC) {
                System.err.println("bad corpus magic: 0x" + Integer.toHexString(magic));
                System.exit(2);
            }
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                in.readUTF(); // label, unused
                in.skipBytes(in.readInt()); // plaintext bytes, unused
                int[] container = new int[in.readInt()];
                byte[] seed = new byte[container.length * 4];
                for (int j = 0; j < container.length; j++) {
                    int word = in.readInt();
                    seed[j * 4] = (byte) word;
                    seed[j * 4 + 1] = (byte) (word >>> 8);
                    seed[j * 4 + 2] = (byte) (word >>> 16);
                    seed[j * 4 + 3] = (byte) (word >>> 24);
                }
                Files.write(seedsDir.resolve(String.format("seed_%04d.bin", i)), seed);
                written++;
            }
        } catch (IOException e) {
            System.err.println("failed to read corpus: " + e);
            System.exit(2);
        }

        System.out.println("wrote " + written + " fuzz seeds to " + seedsDir.toAbsolutePath());
    }
}
