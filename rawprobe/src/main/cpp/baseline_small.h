#pragma once
#include <jni.h>
#include <libraw/libraw.h>
#include <array>
#include <cstdint>
#include <functional>
#include <vector>

namespace cinematic {
// Diagnostic-only, explicit classic-LE/integer-crop subset. Never a product input policy.
class BaselineSmall {
public:
    explicit BaselineSmall(bool large = false) : large(large) {}
    static constexpr uint64_t maxBytes = 128 * 1024;
    static constexpr uint64_t maxPixels = 4096;
    uint64_t byteLimit() const { return large ? 64ULL * 1024 * 1024 : maxBytes; }
    uint64_t pixelLimit() const { return large ? 24'000'000 : maxPixels; }
    unsigned dimensionLimit() const { return large ? 8192 : 128; }
    bool isLarge() const { return large; }
    void parse(const std::vector<unsigned char>&);
    void validateOpen(const LibRaw&) const;
    void applyCrop(LibRaw&) const;
    jobject frame(JNIEnv*, jobject observer, const libraw_processed_image_t&, const std::function<void()>&) const;
private:
    bool large;
    uint32_t width = 0, height = 0, orientation = 0;
    std::array<uint32_t, 4> active{}; // top,left,bottom,right
    std::array<uint32_t, 2> origin{}, size{};
};
}
