#pragma once
#include <libraw/libraw.h>
#include <vector>
#include <string>
#include <functional>
#include <cstdint>
#include <array>

namespace cinematic {
/** Separate preliminary original-size entry. Never a completed/publishable result. */
class OriginalRasterSink {
public:
    explicit OriginalRasterSink(int output) : outputFd(output) {}
    void parse(const std::vector<unsigned char>&, const LibRaw&);
    void applyCrop(LibRaw&) const;
    void validateProcessedGeometry(const LibRaw&) const;
    void write(const libraw_processed_image_t&, const std::function<void()>&) const;
    std::string report() const;
    // Expected geometry from the existing parser, not processed-pixel observations.
    std::array<int, 10> preflightGeometry() const;
private:
    bool needsCropbox(const LibRaw&) const;
    int outputFd;
    unsigned rawWidth = 0, rawHeight = 0, orientation = 1;
    unsigned left = 0, top = 0, width = 0, height = 0;
};
}
