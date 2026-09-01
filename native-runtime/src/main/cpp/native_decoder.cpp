#include <jni.h>

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <vector>

namespace {

constexpr uint32_t kHeaderA = 0x6D2B79F5u;
constexpr uint32_t kHeaderB = 0xA5B35705u;
constexpr uint32_t kHeaderC = 0x7F4A7C15u;
constexpr uint32_t kGolden = 0x9E3779B9u;
constexpr uint32_t kMurmur1 = 0x85EBCA6Bu;
constexpr uint32_t kMurmur2 = 0xC2B2AE35u;
constexpr uint32_t kOdd = 0x27D4EB2Du;
constexpr uint32_t kBase64Mask = 0x346D2A11u;
constexpr uint32_t kShiftMask = 0x51ED270Bu;
constexpr uint32_t kPipelineMagic = 0x53564C32u;
constexpr uint8_t kPipelineVersion = 1;
constexpr size_t kHeaderWords = 4;
constexpr size_t kKeyWords = 4;
constexpr size_t kMetadataWords = 6;
constexpr size_t kContainerOverhead = 7;
constexpr size_t kMinContainerWords = 23;
constexpr size_t kMaxRepetitions = 16;
constexpr size_t kAesKeyBytes = 16;
constexpr size_t kAesIvBytes = 16;

constexpr uint32_t kDeltas[] = {
    kGolden,
    0x7F4A7C15u,
    0x6A09E667u,
    0xBB67AE85u,
};
constexpr int kRounds[] = {32, 36, 40, 44};

uint32_t rotate_left(uint32_t value, int distance) {
    const unsigned shift = static_cast<unsigned>(distance) & 31u;
    return shift == 0 ? value : (value << shift) | (value >> (32u - shift));
}

uint32_t rotate_right(uint32_t value, int distance) {
    const unsigned shift = static_cast<unsigned>(distance) & 31u;
    return shift == 0 ? value : (value >> shift) | (value << (32u - shift));
}

uint32_t mix32(uint32_t value) {
    value = (value ^ (value >> 16u)) * kMurmur1;
    value = (value ^ (value >> 13u)) * kMurmur2;
    return value ^ (value >> 16u);
}

int floor_mod(uint32_t value, int modulus) {
    int remainder = static_cast<int32_t>(value) % modulus;
    return remainder < 0 ? remainder + modulus : remainder;
}

int greatest_common_divisor(int left, int right) {
    while (right != 0) {
        const int remainder = left % right;
        left = right;
        right = remainder;
    }
    return left;
}

int coprime_step(int modulus, uint32_t seed) {
    int candidate = floor_mod(mix32(seed), modulus - 1) + 1;
    while (greatest_common_divisor(candidate, modulus) != 1) {
        candidate = candidate % (modulus - 1) + 1;
    }
    return candidate;
}

int outer_variant(uint32_t seed_a, uint32_t seed_b) {
    return static_cast<int>(((seed_a ^ rotate_left(seed_b, 9)) >> 1u) & 3u);
}

uint32_t mask_word(uint32_t seed_a, uint32_t seed_b, int index, int variant) {
    const uint32_t unsigned_index = static_cast<uint32_t>(index);
    const uint32_t base = mix32(seed_a + unsigned_index * kGolden) ^
        rotate_left(seed_b, index * 7 + variant * 3);
    switch (variant) {
        case 0:
            return mix32(base ^ kOdd);
        case 1:
            return rotate_left(mix32(base + kMurmur1), 9);
        case 2:
            return mix32(base ^ rotate_right(seed_a, index + 11));
        default:
            return mix32(base + rotate_left(seed_b, index + 17)) ^ kMurmur2;
    }
}

uint32_t header_check(uint32_t seed_a, uint32_t seed_b, size_t size) {
    return mix32(seed_a ^ rotate_left(seed_b, 7) ^ static_cast<uint32_t>(size)) ^ kHeaderC;
}

uint32_t key_mask(uint32_t seed_a, uint32_t seed_b, int index, int variant) {
    return mask_word(seed_a, seed_b, index * 19 + 5, variant) ^
        rotate_left(seed_b + kOdd * static_cast<uint32_t>(index + 1), index * 7 + 3);
}

uint32_t storage_mask(
    uint32_t seed_a,
    uint32_t seed_b,
    int index,
    int position,
    int variant
) {
    return mask_word(
        seed_a ^ static_cast<uint32_t>(position + 1) * kMurmur2,
        seed_b + static_cast<uint32_t>(index + 1) * kOdd,
        index ^ position,
        variant
    );
}

uint32_t checksum(const std::vector<uint8_t>& value, uint32_t seed_a, uint32_t seed_b) {
    uint32_t hash = seed_a ^ rotate_left(seed_b, 11) ^ static_cast<uint32_t>(value.size());
    for (uint8_t byte : value) {
        hash = (hash ^ byte) * 0x01000193u;
        hash = rotate_left(hash, 5) + kGolden;
    }
    return mix32(hash);
}

uint32_t round_function(uint32_t value, uint32_t sum, uint32_t key, int variant) {
    switch (variant) {
        case 0:
            return (((value << 4u) ^ (value >> 5u)) + value) ^ (sum + key);
        case 1:
            return (((value << 5u) ^ (value >> 3u)) + rotate_left(value, 1)) ^ (sum + key);
        case 2:
            return (rotate_left(value, 4) + (value ^ (value >> 7u))) ^ (sum + key);
        default:
            return ((rotate_left(value, 3) ^ rotate_right(value, 6)) + value) ^ (sum + key);
    }
}

void decrypt_words(std::vector<uint32_t>& words, const uint32_t key[4], int variant) {
    const uint32_t delta = kDeltas[variant];
    const int rounds = kRounds[variant];
    for (size_t offset = 0; offset < words.size(); offset += 2) {
        uint32_t left = words[offset];
        uint32_t right = words[offset + 1];
        uint32_t sum = delta * static_cast<uint32_t>(rounds);
        for (int round = 0; round < rounds; ++round) {
            const uint32_t right_key_index =
                (((sum >> 11u) & 3u) ^ static_cast<uint32_t>((variant + 1) & 3)) & 3u;
            right -= round_function(left, sum, key[right_key_index], variant);
            sum -= delta;
            const uint32_t left_key_index = ((sum & 3u) ^ static_cast<uint32_t>(variant)) & 3u;
            left -= round_function(right, sum, key[left_key_index], variant);
        }
        words[offset] = left;
        words[offset + 1] = right;
    }
}

template <typename T>
void secure_wipe(std::vector<T>& values) {
    volatile T* pointer = values.data();
    for (size_t index = 0; index < values.size(); ++index) pointer[index] = 0;
}

void secure_wipe(uint32_t values[4]) {
    volatile uint32_t* pointer = values;
    for (size_t index = 0; index < 4; ++index) pointer[index] = 0;
}

bool open_outer_container(const std::vector<uint32_t>& container, std::vector<uint8_t>& pipeline) {
    if (
        container.size() < kMinContainerWords ||
        (container.size() - kContainerOverhead) % 2 != 0
    ) {
        return false;
    }

    const uint32_t seed_a = container[0] ^ kHeaderA;
    const uint32_t seed_b = rotate_right(container[1] ^ seed_a, 13) ^ kHeaderB;
    if (container[2] != header_check(seed_a, seed_b, container.size())) return false;

    const int variant = outer_variant(seed_a, seed_b);
    const size_t logical_size = (container.size() - kContainerOverhead) / 2;
    const int body_size = static_cast<int>(container.size() - kHeaderWords);
    const int start = floor_mod(mix32(seed_a + rotate_left(seed_b, 3)), body_size);
    const int step = coprime_step(body_size, seed_a ^ seed_b);
    std::vector<uint32_t> logical(logical_size);
    uint32_t key[4] = {};

    for (size_t index = 0; index < logical_size; ++index) {
        const int position = static_cast<int>(
            (static_cast<uint64_t>(start) + static_cast<uint64_t>(index) * step) % body_size
        );
        logical[index] = container[kHeaderWords + position] ^
            storage_mask(seed_a, seed_b, static_cast<int>(index), position, variant);
    }

    const int32_t length = static_cast<int32_t>(
        logical[0] ^ mask_word(seed_a, seed_b, -7, variant)
    );
    const uint32_t expected_checksum = logical[1] ^ mask_word(seed_a, seed_b, -11, variant);
    for (size_t index = 0; index < kKeyWords; ++index) {
        key[index] = logical[2 + index] ^
            key_mask(seed_a, seed_b, static_cast<int>(index), variant);
    }

    const size_t encrypted_word_count = logical_size - kMetadataWords;
    if (
        length <= 0 ||
        encrypted_word_count == 0 ||
        encrypted_word_count % 2 != 0 ||
        static_cast<size_t>(length) > encrypted_word_count * 4
    ) {
        secure_wipe(logical);
        secure_wipe(key);
        return false;
    }

    std::vector<uint32_t> words(encrypted_word_count);
    uint32_t chain = seed_a ^ rotate_left(seed_b, 7);
    for (size_t index = 0; index < encrypted_word_count; ++index) {
        const uint32_t word = logical[kMetadataWords + index] ^
            rotate_left(chain, static_cast<int>(index) + variant * 5) ^
            mask_word(seed_b, seed_a, static_cast<int>(index) + 37, variant);
        words[index] = word;
        chain = word + kGolden;
    }

    decrypt_words(words, key, variant);
    std::vector<uint8_t> decoded(words.size() * 4);
    for (size_t index = 0; index < words.size(); ++index) {
        decoded[index * 4] = static_cast<uint8_t>(words[index]);
        decoded[index * 4 + 1] = static_cast<uint8_t>(words[index] >> 8u);
        decoded[index * 4 + 2] = static_cast<uint8_t>(words[index] >> 16u);
        decoded[index * 4 + 3] = static_cast<uint8_t>(words[index] >> 24u);
    }
    pipeline.assign(decoded.begin(), decoded.begin() + length);
    const bool valid = checksum(pipeline, seed_a, seed_b) == expected_checksum;

    secure_wipe(logical);
    secure_wipe(key);
    secure_wipe(words);
    secure_wipe(decoded);
    if (!valid) secure_wipe(pipeline);
    return valid;
}

class ByteCursor {
public:
    explicit ByteCursor(const std::vector<uint8_t>& bytes) : bytes_(bytes) {}

    bool read_byte(uint8_t& value) {
        if (position_ >= bytes_.size()) return false;
        value = bytes_[position_++];
        return true;
    }

    bool read_short(uint16_t& value) {
        uint8_t high;
        uint8_t low;
        if (!read_byte(high) || !read_byte(low)) return false;
        value = static_cast<uint16_t>((high << 8u) | low);
        return true;
    }

    bool read_int(uint32_t& value) {
        uint8_t a;
        uint8_t b;
        uint8_t c;
        uint8_t d;
        if (!read_byte(a) || !read_byte(b) || !read_byte(c) || !read_byte(d)) return false;
        value = (static_cast<uint32_t>(a) << 24u) |
            (static_cast<uint32_t>(b) << 16u) |
            (static_cast<uint32_t>(c) << 8u) |
            d;
        return true;
    }

    bool read_bytes(size_t length, std::vector<uint8_t>& output) {
        if (length > remaining()) return false;
        output.assign(bytes_.begin() + position_, bytes_.begin() + position_ + length);
        position_ += length;
        return true;
    }

    size_t remaining() const { return bytes_.size() - position_; }

private:
    const std::vector<uint8_t>& bytes_;
    size_t position_ = 0;
};

enum class Method : uint8_t {
    kBitShift = 1,
    kBitXor = 2,
    kBase64 = 3,
    kAes = 4,
};

struct Layer {
    Method method;
    size_t input_length;
    std::vector<uint8_t> parameters;
};

size_t parameter_size(Method method) {
    return method == Method::kAes ? kAesKeyBytes + kAesIvBytes : 4;
}

uint32_t parameter_seed(const std::vector<uint8_t>& parameters) {
    return (static_cast<uint32_t>(parameters[0]) << 24u) |
        (static_cast<uint32_t>(parameters[1]) << 16u) |
        (static_cast<uint32_t>(parameters[2]) << 8u) |
        parameters[3];
}

uint8_t stream_byte(uint32_t seed, size_t index) {
    const uint32_t value = seed + static_cast<uint32_t>(index + 1) * kGolden +
        rotate_left(static_cast<uint32_t>(index), static_cast<int>(index & 15u));
    return static_cast<uint8_t>(mix32(value) >> 24u);
}

void xor_stream(std::vector<uint8_t>& bytes, uint32_t seed) {
    for (size_t index = 0; index < bytes.size(); ++index) {
        bytes[index] ^= stream_byte(seed, index);
    }
}

void undo_shift(std::vector<uint8_t>& bytes, uint32_t seed) {
    for (size_t index = 0; index < bytes.size(); ++index) {
        const int shift = stream_byte(seed, index) % 7 + 1;
        const uint8_t rotated = bytes[index] ^ stream_byte(seed ^ kShiftMask, index);
        bytes[index] = static_cast<uint8_t>((rotated >> shift) | (rotated << (8 - shift)));
    }
}

int base64_value(uint8_t byte) {
    if (byte >= 'A' && byte <= 'Z') return byte - 'A';
    if (byte >= 'a' && byte <= 'z') return byte - 'a' + 26;
    if (byte >= '0' && byte <= '9') return byte - '0' + 52;
    if (byte == '+') return 62;
    if (byte == '/') return 63;
    return -1;
}

bool decode_base64(const std::vector<uint8_t>& encoded, std::vector<uint8_t>& output) {
    if (encoded.empty() || encoded.size() % 4 != 0) return false;
    size_t padding = 0;
    if (encoded.back() == '=') {
        padding = 1;
        if (encoded[encoded.size() - 2] == '=') padding = 2;
    }
    output.resize(encoded.size() / 4 * 3 - padding);
    size_t target = 0;
    for (size_t source = 0; source < encoded.size(); source += 4) {
        const int a = base64_value(encoded[source]);
        const int b = base64_value(encoded[source + 1]);
        const int c = encoded[source + 2] == '=' ? 0 : base64_value(encoded[source + 2]);
        const int d = encoded[source + 3] == '=' ? 0 : base64_value(encoded[source + 3]);
        if (a < 0 || b < 0 || c < 0 || d < 0) return false;
        if (target < output.size()) output[target++] = static_cast<uint8_t>((a << 2) | (b >> 4));
        if (target < output.size()) output[target++] = static_cast<uint8_t>((b << 4) | (c >> 2));
        if (target < output.size()) output[target++] = static_cast<uint8_t>((c << 6) | d);
    }
    return true;
}

jbyteArray byte_array(JNIEnv* env, const uint8_t* bytes, size_t length) {
    jbyteArray result = env->NewByteArray(static_cast<jsize>(length));
    if (result != nullptr && length > 0) {
        env->SetByteArrayRegion(
            result,
            0,
            static_cast<jsize>(length),
            reinterpret_cast<const jbyte*>(bytes)
        );
    }
    return result;
}

bool aes_ctr_decrypt(
    JNIEnv* env,
    const std::vector<uint8_t>& input,
    const std::vector<uint8_t>& parameters,
    std::vector<uint8_t>& output
) {
    jclass cipher_class = env->FindClass("javax/crypto/Cipher");
    jclass key_class = env->FindClass("javax/crypto/spec/SecretKeySpec");
    jclass iv_class = env->FindClass("javax/crypto/spec/IvParameterSpec");
    if (cipher_class == nullptr || key_class == nullptr || iv_class == nullptr) return false;

    jmethodID get_instance = env->GetStaticMethodID(
        cipher_class,
        "getInstance",
        "(Ljava/lang/String;)Ljavax/crypto/Cipher;"
    );
    jmethodID key_constructor = env->GetMethodID(key_class, "<init>", "([BLjava/lang/String;)V");
    jmethodID iv_constructor = env->GetMethodID(iv_class, "<init>", "([B)V");
    jmethodID init = env->GetMethodID(
        cipher_class,
        "init",
        "(ILjava/security/Key;Ljava/security/spec/AlgorithmParameterSpec;)V"
    );
    jmethodID do_final = env->GetMethodID(cipher_class, "doFinal", "([B)[B");
    if (
        get_instance == nullptr || key_constructor == nullptr || iv_constructor == nullptr ||
        init == nullptr || do_final == nullptr
    ) {
        return false;
    }

    jstring transformation = env->NewStringUTF("AES/CTR/NoPadding");
    jstring aes = env->NewStringUTF("AES");
    jbyteArray key_bytes = byte_array(env, parameters.data(), kAesKeyBytes);
    jbyteArray iv_bytes = byte_array(env, parameters.data() + kAesKeyBytes, kAesIvBytes);
    jbyteArray input_bytes = byte_array(env, input.data(), input.size());
    if (
        transformation == nullptr || aes == nullptr || key_bytes == nullptr ||
        iv_bytes == nullptr || input_bytes == nullptr
    ) {
        return false;
    }

    jobject cipher = env->CallStaticObjectMethod(cipher_class, get_instance, transformation);
    jobject key = env->NewObject(key_class, key_constructor, key_bytes, aes);
    jobject iv = env->NewObject(iv_class, iv_constructor, iv_bytes);
    if (env->ExceptionCheck() || cipher == nullptr || key == nullptr || iv == nullptr) return false;

    env->CallVoidMethod(cipher, init, 2, key, iv);
    jbyteArray result = static_cast<jbyteArray>(env->CallObjectMethod(cipher, do_final, input_bytes));
    if (env->ExceptionCheck() || result == nullptr) return false;
    const jsize result_size = env->GetArrayLength(result);
    output.resize(static_cast<size_t>(result_size));
    env->GetByteArrayRegion(
        result,
        0,
        result_size,
        reinterpret_cast<jbyte*>(output.data())
    );
    return !env->ExceptionCheck();
}

bool decode_layer(
    JNIEnv* env,
    const Layer& layer,
    const std::vector<uint8_t>& input,
    std::vector<uint8_t>& output
) {
    switch (layer.method) {
        case Method::kBitShift:
            output = input;
            undo_shift(output, parameter_seed(layer.parameters));
            return true;
        case Method::kBitXor:
            output = input;
            xor_stream(output, parameter_seed(layer.parameters));
            return true;
        case Method::kBase64: {
            std::vector<uint8_t> unmasked = input;
            xor_stream(unmasked, parameter_seed(layer.parameters) ^ kBase64Mask);
            const bool valid = decode_base64(unmasked, output);
            secure_wipe(unmasked);
            return valid;
        }
        case Method::kAes:
            return aes_ctr_decrypt(env, input, layer.parameters, output);
    }
    return false;
}

bool decode_pipeline(
    JNIEnv* env,
    const std::vector<uint8_t>& pipeline,
    std::vector<uint8_t>& plaintext
) {
    ByteCursor cursor(pipeline);
    uint32_t magic;
    uint8_t version;
    uint8_t layer_count;
    if (
        !cursor.read_int(magic) || magic != kPipelineMagic ||
        !cursor.read_byte(version) || version != kPipelineVersion ||
        !cursor.read_byte(layer_count) || layer_count == 0 || layer_count > kMaxRepetitions
    ) {
        return false;
    }

    std::vector<Layer> layers;
    layers.reserve(layer_count);
    for (uint8_t index = 0; index < layer_count; ++index) {
        uint8_t method_id;
        uint32_t input_length;
        uint16_t parameters_length;
        if (
            !cursor.read_byte(method_id) || method_id < 1 || method_id > 4 ||
            !cursor.read_int(input_length) ||
            !cursor.read_short(parameters_length)
        ) {
            return false;
        }
        Layer layer{
            static_cast<Method>(method_id),
            static_cast<size_t>(input_length),
            {},
        };
        if (
            parameters_length != parameter_size(layer.method) ||
            !cursor.read_bytes(parameters_length, layer.parameters)
        ) {
            return false;
        }
        layers.push_back(std::move(layer));
    }

    uint32_t transformed_length;
    if (
        !cursor.read_int(transformed_length) ||
        transformed_length != cursor.remaining() ||
        !cursor.read_bytes(transformed_length, plaintext)
    ) {
        return false;
    }

    for (auto layer = layers.rbegin(); layer != layers.rend(); ++layer) {
        std::vector<uint8_t> previous;
        if (!decode_layer(env, *layer, plaintext, previous) || previous.size() != layer->input_length) {
            secure_wipe(previous);
            secure_wipe(plaintext);
            return false;
        }
        secure_wipe(plaintext);
        plaintext.swap(previous);
        secure_wipe(previous);
        secure_wipe(layer->parameters);
    }
    return true;
}

void throw_invalid(JNIEnv* env) {
    if (env->ExceptionCheck()) return;
    jclass exception = env->FindClass("java/lang/IllegalArgumentException");
    if (exception != nullptr) env->ThrowNew(exception, "Invalid protected string");
}

jstring new_utf8_string(JNIEnv* env, const std::vector<uint8_t>& plaintext) {
    jclass string_class = env->FindClass("java/lang/String");
    if (string_class == nullptr) return nullptr;
    jmethodID constructor = env->GetMethodID(string_class, "<init>", "([BLjava/lang/String;)V");
    if (constructor == nullptr) return nullptr;
    jbyteArray bytes = byte_array(env, plaintext.data(), plaintext.size());
    jstring charset = env->NewStringUTF("UTF-8");
    if (bytes == nullptr || charset == nullptr) return nullptr;
    return static_cast<jstring>(env->NewObject(string_class, constructor, bytes, charset));
}

jstring native_decode(JNIEnv* env, jclass, jintArray source) {
    if (source == nullptr) {
        throw_invalid(env);
        return nullptr;
    }

    const jsize length = env->GetArrayLength(source);
    if (length < static_cast<jsize>(kMinContainerWords)) {
        throw_invalid(env);
        return nullptr;
    }
    std::vector<jint> signed_container(static_cast<size_t>(length));
    env->GetIntArrayRegion(source, 0, length, signed_container.data());
    if (env->ExceptionCheck()) return nullptr;
    std::vector<uint32_t> container(signed_container.size());
    for (size_t index = 0; index < container.size(); ++index) {
        container[index] = static_cast<uint32_t>(signed_container[index]);
    }

    std::vector<uint8_t> pipeline;
    std::vector<uint8_t> plaintext;
    const bool valid = open_outer_container(container, pipeline) &&
        decode_pipeline(env, pipeline, plaintext);
    secure_wipe(signed_container);
    secure_wipe(container);
    secure_wipe(pipeline);
    if (!valid) {
        secure_wipe(plaintext);
        throw_invalid(env);
        return nullptr;
    }

    jstring result = new_utf8_string(env, plaintext);
    secure_wipe(plaintext);
    if (result == nullptr) throw_invalid(env);
    return result;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass decoder = env->FindClass(
        "io/github/khstov/stringveil/runtime/NativeStringDecoder"
    );
    if (decoder == nullptr) return JNI_ERR;
    JNINativeMethod method = {
        const_cast<char*>("nativeDecode"),
        const_cast<char*>("([I)Ljava/lang/String;"),
        reinterpret_cast<void*>(native_decode),
    };
    if (env->RegisterNatives(decoder, &method, 1) != JNI_OK) return JNI_ERR;
    return JNI_VERSION_1_6;
}
