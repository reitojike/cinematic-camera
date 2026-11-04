#pragma once

#include <libraw/libraw.h>
#include <array>
#include <cstddef>

namespace cinematic {
// Reject arithmetic outside LibRaw's signed-int scale/RGB conversion range.
void validateW1Processing(const libraw_colordata_t&, const std::array<float, 3>&);
struct W1Entry {
    bool present = false;
    std::array<double, 9> values{};
};

/** Fixed-size, primary-profile-only callback state. Never owns image data or parses IFD offsets. */
class W1Metadata {
public:
    explicit W1Metadata(size_t size) : sourceSize(size) {}
    static void capture(void*, int tag, int type, int count, unsigned order, void* stream, INT64 base) noexcept;
    std::array<float, 3> requestedWb() const;
    int unappliedTags() const { return observedTags; }
private:
    // Neutral, XY, CM1, CM2, illuminant1, illuminant2, AB, CC1, CC2.
    std::array<W1Entry, 9> entries{};
    size_t sourceSize;
    bool invalid = false;
    int observedTags = 0; // FM=1, LookTable=2, OpcodeList2=4. Presence, NOT applied processing.
    void captureTag(int tag, int type, int count, unsigned order, LibRaw_abstract_datastream* stream);
};
} // namespace cinematic
