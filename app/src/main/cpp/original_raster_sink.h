#pragma once
#include <libraw/libraw.h>
#include <vector>
#include <string>
#include <functional>
#include <cstdint>
#include <array>

namespace cinematic {
#ifdef CINEMATIC_ENABLE_DECODE_TIMING
struct DecodeTimingNs {
    int64_t pread = -1;
    int64_t openParse = -1;
    int64_t unpack = -1;
    int64_t raw2image = -1;
    int64_t scaleColors = -1;
    int64_t preInterp = -1;
    int64_t ahd = -1;
    int64_t convertRgb = -1;
    int64_t makeImage = -1;
    int64_t write = -1;
};
#endif

#if defined(CINEMATIC_ENABLE_DECODE_TIMING) || defined(CINEMATIC_ENABLE_DECODE_PARITY)
struct DecodeConfig {
    int threads;
    const char* threadSource = "default";
    bool threadInvalid = false;
    int blocktime = -1;
    bool parity = true;
    bool parityInvalid = false;
};
#endif

/** Separate preliminary original-size entry. Never a completed/publishable result. */
class OriginalRasterSink {
public:
    enum class Output { Full, HalfPreview };
    explicit OriginalRasterSink(int output, Output mode = Output::Full) : outputFd(output), mode(mode) {}
    bool isHalfPreview() const { return mode == Output::HalfPreview; }
    void parse(const std::vector<unsigned char>&, const LibRaw&);
    void applyCrop(LibRaw&) const;
    void validateProcessedGeometry(const LibRaw&) const;
    void write(const libraw_processed_image_t&, const std::function<void()>&) const;
    std::string report() const;
#ifdef CINEMATIC_ENABLE_DECODE_TIMING
    void setDecodeTiming(const DecodeTimingNs& value) { decodeTiming = value; hasDecodeTiming = true; }
#endif
#if defined(CINEMATIC_ENABLE_DECODE_TIMING) || defined(CINEMATIC_ENABLE_DECODE_PARITY)
    void setDecodeConfig(const DecodeConfig& value) { decodeConfig = value; }
#endif
    // Expected geometry from the existing parser, not processed-pixel observations.
    std::array<int, 10> preflightGeometry() const;
private:
    bool needsCropbox(const LibRaw&) const;
#if defined(CINEMATIC_ENABLE_DECODE_TIMING) || defined(CINEMATIC_ENABLE_DECODE_PARITY)
    DecodeConfig decodeConfig{0};
#endif
    int outputFd;
    const Output mode;
    unsigned outputWidth() const;
    unsigned outputHeight() const;
    unsigned rawWidth = 0, rawHeight = 0, orientation = 1;
    unsigned left = 0, top = 0, width = 0, height = 0;
#ifdef CINEMATIC_ENABLE_DECODE_TIMING
    DecodeTimingNs decodeTiming{};
    bool hasDecodeTiming = false;
#endif
};
}
