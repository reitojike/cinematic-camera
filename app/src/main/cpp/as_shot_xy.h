#pragma once

#include <libraw/libraw.h>
#include <array>
#include <cstddef>

namespace cinematic {
/** Bounded primary-IFD metadata for the explicitly preliminary dual/D65 WhiteXY route. */
class AsShotXyMetadata {
public:
    explicit AsShotXyMetadata(size_t size) : sourceSize(size) {}
    static void capture(void*, int, int, int, unsigned, void*, INT64) noexcept;
    std::array<float, 3> requestedWb(const libraw_colordata_t&) const;
    int unappliedTags() const { return observedTags; }
private:
    struct Entry { bool present = false; std::array<double, 9> values{}; };
    struct Signature { bool present = false; size_t length = 0; std::array<unsigned char, 256> bytes{}; };
    // Neutral, XY, CM1/2, illuminant1/2, AB, CC1/2, ColorimetricReference.
    std::array<Entry, 10> entries{};
    // Camera/ProfileCalibrationSignature, AsShotProfileName, primary ProfileName.
    std::array<Signature, 4> signatures{};
    size_t sourceSize;
    bool invalid = false;
    int observedTags = 0; // FM=1, Look=2, OpcodeList2=4, third calibration=8.
    void captureTag(int, int, int, unsigned, LibRaw_abstract_datastream*);
};
} // namespace cinematic
