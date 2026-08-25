// Fuzz / sanitizer harness for the untrusted-input parser of the native String Veil decoder.
//
// `open_outer_container` is the first stage that consumes a raw, potentially hostile `int[]`
// container (embedded in the compiled app) and does all the size/offset/permutation/ARX arithmetic.
// It is pure C++ (no JNI), which makes it the right target to fuzz for out-of-bounds reads, hangs,
// and undefined behavior on malformed input: the JVM decoder rejects bad containers with an
// exception, and the C++ decoder must likewise reject them without crashing or looping forever.
//
// Two front-ends share one `run_one`:
//   * default: a self-contained ASan/UBSan driver (`main`) that replays a seed corpus and then runs
//     a bounded, deterministic random-mutation loop. It needs only `-fsanitize=address,undefined`,
//     so it runs under any clang/gcc — no libFuzzer runtime required.
//   * `-DSV_LIBFUZZER`: a libFuzzer `LLVMFuzzerTestOneInput` entry for coverage-guided fuzzing on
//     toolchains where libFuzzer is available.
//
// The production translation unit is included directly so the harness can reach functions in its
// anonymous namespace without changing production code.

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <vector>

#include "native_decoder.cpp"

namespace {

// Interpret input bytes as a little-endian sequence of 32-bit container words and run the parser.
// The return value is intentionally ignored: we only care that the parser never reads out of
// bounds, loops forever, or triggers undefined behavior, whatever it decides about validity.
void run_one(const uint8_t* data, size_t size) {
    const size_t word_count = size / 4;
    std::vector<uint32_t> container(word_count);
    for (size_t index = 0; index < word_count; ++index) {
        const size_t offset = index * 4;
        container[index] = static_cast<uint32_t>(data[offset]) |
            (static_cast<uint32_t>(data[offset + 1]) << 8) |
            (static_cast<uint32_t>(data[offset + 2]) << 16) |
            (static_cast<uint32_t>(data[offset + 3]) << 24);
    }
    std::vector<uint8_t> pipeline;
    (void) open_outer_container(container, pipeline);
}

}  // namespace

#ifdef SV_LIBFUZZER

extern "C" int LLVMFuzzerTestOneInput(const uint8_t* data, size_t size) {
    run_one(data, size);
    return 0;
}

#else

#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <string>

namespace {

std::vector<uint8_t> read_file(const std::filesystem::path& path) {
    std::ifstream stream(path, std::ios::binary);
    return std::vector<uint8_t>(
        std::istreambuf_iterator<char>(stream),
        std::istreambuf_iterator<char>());
}

}  // namespace

// Usage: fuzz_open_container <seeds-dir> [iterations] [rng-seed]
//
// Replays every seed once (deterministic sanitizer coverage of the valid decode path), then runs
// `iterations` random mutations of the seeds. A fixed rng-seed makes the whole run reproducible.
int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: %s <seeds-dir> [iterations] [rng-seed]\n", argv[0]);
        return 2;
    }
    const std::filesystem::path seeds_dir = argv[1];
    const uint64_t iterations = argc > 2 ? std::strtoull(argv[2], nullptr, 10) : 200000ULL;
    uint64_t rng = argc > 3 ? std::strtoull(argv[3], nullptr, 10) : 0x9E3779B97F4A7C15ULL;

    std::vector<std::vector<uint8_t>> seeds;
    if (std::filesystem::is_directory(seeds_dir)) {
        for (const auto& entry : std::filesystem::directory_iterator(seeds_dir)) {
            if (entry.is_regular_file()) seeds.push_back(read_file(entry.path()));
        }
    }
    // Guarantee at least one base input so an empty/missing seed dir still fuzzes something.
    if (seeds.empty()) seeds.emplace_back(92, 0);

    // Deterministic replay of every valid container.
    for (const auto& seed : seeds) run_one(seed.data(), seed.size());

    auto next_random = [&rng]() -> uint64_t {
        rng ^= rng << 13;
        rng ^= rng >> 7;
        rng ^= rng << 17;
        return rng;
    };

    std::vector<uint8_t> buffer;
    for (uint64_t iteration = 0; iteration < iterations; ++iteration) {
        buffer = seeds[next_random() % seeds.size()];
        const int mutations = 1 + static_cast<int>(next_random() % 8);
        for (int mutation = 0; mutation < mutations; ++mutation) {
            const uint64_t choice = next_random();
            switch (choice % 4) {
                case 0:
                    if (!buffer.empty()) {
                        buffer[choice % buffer.size()] ^= static_cast<uint8_t>(next_random());
                    }
                    break;
                case 1:
                    if (!buffer.empty()) {
                        buffer[choice % buffer.size()] = static_cast<uint8_t>(next_random());
                    }
                    break;
                case 2:
                    if (buffer.size() > 4) {
                        const size_t drop = 4 * (1 + next_random() % (buffer.size() / 4));
                        buffer.resize(buffer.size() - std::min(drop, buffer.size()));
                    }
                    break;
                default:
                    buffer.push_back(static_cast<uint8_t>(next_random()));
                    break;
            }
        }
        run_one(buffer.data(), buffer.size());
    }

    std::printf(
        "native fuzz: replayed %zu seeds, ran %llu mutations, no sanitizer error\n",
        seeds.size(),
        static_cast<unsigned long long>(iterations));
    return 0;
}

#endif  // SV_LIBFUZZER
