#pragma once
#include <cstddef>
#include <cstdint>
#include <vector>

namespace cinematic::embedded {
constexpr size_t maxInput = 64 * 1024 * 1024;
constexpr size_t maxJpeg = 8 * 1024 * 1024;
enum class Status { Found, Absent, Unsupported, Invalid, Cancelled, Memory };
enum class OrientationEvidence { Unknown, Thumbnail, ThumbnailAndExif };
struct Result {
    Status status;
    std::vector<uint8_t> jpeg;
    unsigned width = 0, height = 0;
    unsigned orientation = 0; // EXIF 1..8; 0 means unknown. Pixels are not rotated.
    OrientationEvidence evidence = OrientationEvidence::Unknown;
};
using Cancel = bool (*)(void*) noexcept;
// Caller owns an immutable snapshot for the entire call, including opaque LibRaw work.
// No FD, JNI, global admission, UI, RAW unpack, decode or fingerprint verification here.
Result extract(const uint8_t* snapshot, size_t size, Cancel cancel = nullptr,
               void* context = nullptr) noexcept;
unsigned exifOrientation(unsigned flip) noexcept;
} // namespace cinematic::embedded
