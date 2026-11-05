#include "baseline_small.h"
#include <android/bitmap.h>
#include <algorithm>
#include <cmath>
#include <map>
#include <sstream>
#include <stdexcept>

namespace cinematic {
namespace {
[[noreturn]] void reject() { throw std::runtime_error("BASELINE_GEOMETRY"); }
uint32_t read(const std::vector<unsigned char>& b, size_t p, size_t n) {
    if (p > b.size() || n > b.size() - p) reject();
    uint32_t value = 0;
    for (size_t i = 0; i < n; ++i) value |= uint32_t(b[p + i]) << (i * 8);
    return value;
}
struct Entry { unsigned type, count; size_t data; };
struct Unlock {
    JNIEnv* env; jobject bitmap; bool locked = true;
    ~Unlock() { if (locked) AndroidBitmap_unlockPixels(env, bitmap); }
};
unsigned srgb(uint16_t sample) {
    const double linear = sample / 65535.0;
    const double encoded = linear <= .0031308 ? 12.92 * linear : 1.055 * std::pow(linear, 1.0 / 2.4) - .055;
    return static_cast<unsigned>(std::clamp(std::floor(encoded * 255 + .5), 0.0, 255.0));
}
}

void BaselineSmall::parse(const std::vector<unsigned char>& bytes) {
    if (bytes.size() > maxBytes || read(bytes, 0, 4) != 0x002a4949) reject();
    const size_t ifd = read(bytes, 4, 4), count = read(bytes, ifd, 2);
    if (count > 64 || read(bytes, ifd + 2 + count * 12, 4) != 0) reject();
    std::map<unsigned, Entry> fields;
    for (size_t i = 0; i < count; ++i) {
        const size_t p = ifd + 2 + i * 12;
        const unsigned tag = read(bytes, p, 2), type = read(bytes, p + 2, 2), n = read(bytes, p + 4, 4);
        if (tag == 51125) reject(); // No implicit user crop in this diagnostic contract.
        if (tag != 256 && tag != 257 && tag != 274 && tag != 50829 && tag != 50719 && tag != 50720 && tag != 50718) continue;
        if (!n || n > 4 || (type != 3 && type != 4 && type != 5)) reject();
        const size_t unit = type == 3 ? 2 : type == 4 ? 4 : 8;
        const size_t data = n * unit <= 4 ? p + 8 : read(bytes, p + 8, 4);
        if (data > bytes.size() || n * unit > bytes.size() - data || fields.count(tag)) reject();
        fields.emplace(tag, Entry{type, n, data});
    }
    auto values = [&](unsigned tag, unsigned n) {
        const auto found = fields.find(tag);
        if (found == fields.end() || found->second.count != n) reject();
        const auto& e = found->second;
        const size_t unit = e.type == 3 ? 2 : e.type == 4 ? 4 : 8;
        std::array<uint32_t, 4> v{};
        for (unsigned i = 0; i < n; ++i) {
            v[i] = read(bytes, e.data + i * unit, e.type == 3 ? 2 : 4);
            if (e.type == 5) {
                const auto denominator = read(bytes, e.data + i * unit + 4, 4);
                if (!denominator || v[i] % denominator) reject();
                v[i] /= denominator;
            }
        }
        return v;
    };
    width = values(256, 1)[0]; height = values(257, 1)[0]; orientation = values(274, 1)[0];
    active = values(50829, 4);
    const auto o = values(50719, 2), s = values(50720, 2);
    origin = {o[0], o[1]}; size = {s[0], s[1]};
    if (!width || !height || width > 128 || height > 128 || uint64_t(width) * height > maxPixels || orientation < 1 || orientation > 8) reject();
    if (active[0] >= active[2] || active[1] >= active[3] || active[2] > height || active[3] > width || !size[0] || !size[1] ||
        uint64_t(origin[0]) + size[0] > active[3] - active[1] || uint64_t(origin[1]) + size[1] > active[2] - active[0]) reject();
    if (fields.count(50718)) { const auto scale = values(50718, 2); if (scale[0] != 1 || scale[1] != 1) reject(); }
}

void BaselineSmall::validateOpen(const LibRaw& raw) const {
    static constexpr int flips[]{0, 0, 1, 3, 2, 4, 6, 7, 5};
    const auto& s = raw.imgdata.sizes;
    if (s.raw_width != width || s.raw_height != height || s.left_margin != active[1] || s.top_margin != active[0] ||
        s.width != active[3] - active[1] || s.height != active[2] - active[0] || s.flip != flips[orientation]) reject();
}

void BaselineSmall::applyCrop(LibRaw& raw) const {
    // unpack first: retain masked-area black processing. Explicit bounds replace maxcrop's heuristic.
    const int adjusted = raw.adjust_to_raw_inset_crop(1, 0.f);
    // LibRaw does not promote an identity DefaultCrop (strict '<' in identify.cpp).
    // A no-op is accepted only when the actual rectangle below already equals the explicit crop.
    if (adjusted != 0 && adjusted != 1) reject();
    const auto& s = raw.imgdata.sizes;
    if (s.left_margin != active[1] + origin[0] || s.top_margin != active[0] + origin[1] || s.width != size[0] || s.height != size[1]) reject();
}

jobject BaselineSmall::frame(JNIEnv* env, jobject observer, const libraw_processed_image_t& image,
                            const std::function<void()>& checkpoint) const {
    const unsigned w = orientation >= 5 ? size[1] : size[0], h = orientation >= 5 ? size[0] : size[1];
    if (image.width != w || image.height != h || image.bits != 16 || image.colors != 3 || image.data_size != uint64_t(w) * h * 6) reject();
    jmethodID allocate = env->GetMethodID(env->GetObjectClass(observer), "allocate", "(II)Landroid/graphics/Bitmap;");
    if (!allocate) return nullptr;
    jobject bitmap = env->CallObjectMethod(observer, allocate, static_cast<jint>(w), static_cast<jint>(h));
    if (!bitmap || env->ExceptionCheck()) return nullptr;
    AndroidBitmapInfo info{}; void* address = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &info) || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width != w || info.height != h || info.stride < w * 4 ||
        AndroidBitmap_lockPixels(env, bitmap, &address)) throw std::runtime_error("BASELINE_BITMAP");
    Unlock unlock{env, bitmap};
    const auto* rgb = reinterpret_cast<const uint16_t*>(image.data);
    uint64_t packed = 0;
    for (unsigned y = 0; y < h; ++y) {
        checkpoint();
        auto* row = static_cast<unsigned char*>(address) + y * info.stride;
        for (unsigned x = 0; x < w; ++x) {
            const size_t i = (size_t(y) * w + x) * 3;
            for (int c = 0; c < 3; ++c) row[x * 4 + c] = srgb(rgb[i + c]);
            row[x * 4 + 3] = 255; ++packed;
        }
    }
    if (AndroidBitmap_unlockPixels(env, bitmap)) throw std::runtime_error("BASELINE_BITMAP");
    unlock.locked = false;
    jbyteArray reference = env->NewByteArray(static_cast<jsize>(image.data_size));
    if (!reference) return nullptr;
    env->SetByteArrayRegion(reference, 0, image.data_size, reinterpret_cast<const jbyte*>(image.data));
    if (env->ExceptionCheck()) return nullptr;
    std::ostringstream geometry;
    geometry << "{\"scope\":\"DIAGNOSTIC_BASELINE_NO_R7_NO_STAGE_CLEAR\",\"policy\":\"probe-integer-standard-crop-v1\",\"rawWidth\":" << width
             << ",\"rawHeight\":" << height << ",\"active\":[" << active[1] << ',' << active[0] << ',' << active[3] - active[1] << ',' << active[2] - active[0]
             << "],\"crop\":[" << active[1] + origin[0] << ',' << active[0] + origin[1] << ',' << size[0] << ',' << size[1]
             << "],\"orientation\":" << orientation << ",\"width\":" << w << ",\"height\":" << h << ",\"halfSize\":0,\"resized\":false,\"packedPixels\":" << packed << '}';
    jstring details = env->NewStringUTF(geometry.str().c_str());
    if (!details) return nullptr;
    jclass result = env->FindClass("dev/cinematic/rawprobe/BaselineFrame");
    if (!result) return nullptr;
    jmethodID ctor = env->GetMethodID(result, "<init>", "(Landroid/graphics/Bitmap;Ljava/lang/String;[B)V");
    if (!ctor) return nullptr;
    return env->NewObject(result, ctor, bitmap, details, reference);
}
}
