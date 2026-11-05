#include "as_shot_xy.h"
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <stdexcept>

namespace cinematic {
namespace {
using Matrix = std::array<double, 9>;
using Vector = std::array<double, 3>;
constexpr int kTags[]{50728, 50729, 50721, 50722, 50778, 50779, 50727, 50723, 50724, 50879};
constexpr Matrix kIdentity{1, 0, 0, 0, 1, 0, 0, 0, 1};
// Linear sRGB/D65 primaries, matching the fixed LibRaw colour conversion.
constexpr Matrix kXyzFromSrgb{.4124564, .3575761, .1804375, .2126729, .7151522, .0721750, .0193339, .1191920, .9503041};

[[noreturn]] void reject() { throw std::runtime_error("DNG_UNSUPPORTED"); }
uint32_t u32(const unsigned char* bytes, bool little) {
    uint32_t result = 0;
    for (int i = 0; i < 4; ++i) result = (result << 8) | bytes[little ? 3 - i : i];
    return result;
}
int64_t signed32(uint32_t value) { return value & 0x80000000U ? static_cast<int64_t>(value) - 0x100000000LL : value; }
double number(LibRaw_abstract_datastream* stream, int type, bool little) {
    unsigned char bytes[8]{};
    const int size = type == 3 ? 2 : 8;
    if (stream->read(bytes, 1, size) != size) reject();
    if (type == 3) return little ? bytes[0] + (bytes[1] << 8) : (bytes[0] << 8) + bytes[1];
    const auto numerator = u32(bytes, little), denominator = u32(bytes + 4, little);
    if (!denominator) reject();
    const double result = type == 10 ? static_cast<double>(signed32(numerator)) / signed32(denominator)
                                     : static_cast<double>(numerator) / denominator;
    if (!std::isfinite(result)) reject();
    return result;
}
Matrix product(const Matrix& left, const Matrix& right) {
    Matrix result{};
    for (int row = 0; row < 3; ++row) for (int col = 0; col < 3; ++col)
        for (int k = 0; k < 3; ++k) result[row * 3 + col] += left[row * 3 + k] * right[k * 3 + col];
    for (double value : result) if (!std::isfinite(value)) reject();
    return result;
}
Matrix inverse(const Matrix& input) {
    double work[3][6]{};
    double scale = 0;
    for (int row = 0; row < 3; ++row) {
        for (int col = 0; col < 3; ++col) {
            work[row][col] = input[row * 3 + col];
            scale = std::max(scale, std::abs(work[row][col]));
        }
        work[row][row + 3] = 1;
    }
    if (!std::isfinite(scale) || !scale) reject();
    for (int col = 0; col < 3; ++col) {
        int pivot = col;
        for (int row = col + 1; row < 3; ++row)
            if (std::abs(work[row][col]) > std::abs(work[pivot][col])) pivot = row;
        if (std::abs(work[pivot][col]) <= scale * 1e-12) reject();
        for (int j = 0; j < 6; ++j) std::swap(work[col][j], work[pivot][j]);
        const double divisor = work[col][col];
        for (double& value : work[col]) value /= divisor;
        for (int row = 0; row < 3; ++row) if (row != col) {
            const double factor = work[row][col];
            for (int j = 0; j < 6; ++j) work[row][j] -= factor * work[col][j];
        }
    }
    Matrix result{};
    for (int row = 0; row < 3; ++row) for (int col = 0; col < 3; ++col) {
        const double value = work[row][col + 3];
        if (!std::isfinite(value)) reject();
        result[row * 3 + col] = value;
    }
    return result;
}

// Robertson CIE 1960 table: Wyszecki & Stiles, Color Science, 2nd ed., p.228.
// Numerical table only: Colour v0.4.6, Copyright 2013 Colour Developers, BSD-3-Clause.
// Provenance/notices: third_party/dng_temperature/README.md and APK SOURCE.txt.
struct Isotherm { double mired, u, v, slope; };
constexpr Isotherm kIsotherms[]{
    {0,.18006,.26352,-.24341}, {10,.18066,.26589,-.25479}, {20,.18133,.26846,-.26876},
    {30,.18208,.27119,-.28539}, {40,.18293,.27407,-.30470}, {50,.18388,.27709,-.32675},
    {60,.18494,.28021,-.35156}, {70,.18611,.28342,-.37915}, {80,.18740,.28668,-.40955},
    {90,.18880,.28997,-.44278}, {100,.19032,.29326,-.47888}, {125,.19462,.30141,-.58204},
    {150,.19962,.30921,-.70471}, {175,.20525,.31647,-.84901}, {200,.21142,.32312,-1.0182},
    {225,.21807,.32909,-1.2168}, {250,.22511,.33439,-1.4512}, {275,.23247,.33904,-1.7298},
    {300,.24010,.34308,-2.0637}, {325,.24792,.34655,-2.4681}, {350,.25591,.34951,-2.9641},
    {375,.26400,.35200,-3.5814}, {400,.27218,.35407,-4.3633}, {425,.28039,.35577,-5.3762},
    {450,.28863,.35714,-6.7262}, {475,.29685,.35823,-8.5955}, {500,.30505,.35907,-11.324},
    {525,.31320,.35968,-15.628}, {550,.32129,.36011,-23.325}, {575,.32931,.36038,-40.770},
    {600,.33724,.36051,-116.45},
};
double temperature(double x, double y) {
    if (!std::isfinite(x) || !std::isfinite(y) || x <= 0 || y <= 0 || x + y >= 1) reject();
    const double divisor = 1.5 - x + 6 * y, u = 2 * x / divisor, v = 3 * y / divisor;
    double previous = 0;
    for (int i = 1; i < 31; ++i) {
        const auto& point = kIsotherms[i];
        const double distance = (v - point.v - (u - point.u) * point.slope) / std::hypot(1., point.slope);
        if (!std::isfinite(distance)) reject();
        if (distance <= 0) {
            // Reject table endpoints instead of silently substituting an extreme white point.
            if (i == 1 || previous <= 0) reject();
            const double fraction = -distance / (previous - distance);
            const double mired = fraction * kIsotherms[i - 1].mired + (1 - fraction) * point.mired;
            const double value = 1e6 / mired;
            if (!std::isfinite(value) || value < 1700 || value > 15000) reject();
            return value;
        }
        previous = distance;
    }
    reject();
}
double illuminant(double code) {
    // Standard illuminant temperatures; unknown/custom sources are unsupported.
    if (code == 17) return 2850;
    if (code == 20) return 5500;
    if (code == 21) return 6500;
    if (code == 22) return 7500;
    if (code == 23) return 5000;
    reject();
}
bool utf8(const unsigned char* bytes, size_t count) {
    for (size_t i = 0; i < count;) {
        const unsigned char first = bytes[i++];
        if (first == 0) return false;
        if (first < 0x80) continue;
        const int tail = first >= 0xc2 && first <= 0xdf ? 1 : first >= 0xe0 && first <= 0xef ? 2 : first >= 0xf0 && first <= 0xf4 ? 3 : -1;
        if (tail < 0 || i + tail > count) return false;
        if ((first == 0xe0 && bytes[i] < 0xa0) || (first == 0xed && bytes[i] >= 0xa0) ||
            (first == 0xf0 && bytes[i] < 0x90) || (first == 0xf4 && bytes[i] >= 0x90)) return false;
        for (int k = 0; k < tail; ++k) if ((bytes[i++] & 0xc0) != 0x80) return false;
    }
    return true;
}
} // namespace

void AsShotXyMetadata::capture(void* state, int tag, int type, int count, unsigned order, void* stream, INT64) noexcept {
    auto* self = static_cast<AsShotXyMetadata*>(state);
    if (!self || self->invalid) return;
    try { self->captureTag(tag, type, count, order, static_cast<LibRaw_abstract_datastream*>(stream)); }
    catch (...) { self->invalid = true; }
}
void AsShotXyMetadata::captureTag(int qualified, int type, int count, unsigned order, LibRaw_abstract_datastream* stream) {
    const int tag = qualified & 0xffff;
    if ((qualified >> 20) > 0) {
        if (tag == 50964 || tag == 50965 || tag == 52532) observedTags |= 1;
        if (tag == 50981 || tag == 50982 || tag == 51108) observedTags |= 2;
        if (tag == 51009) observedTags |= 4;
        if (tag >= 52529 && tag <= 52532) observedTags |= 8;
        if (tag == 50933) reject(); // An alternate camera profile is not a third calibration.
    }
    int index = -1;
    for (int i = 0; i < 10; ++i) if (tag == kTags[i]) index = i;
    const int signature = tag == 50931 ? 0 : tag == 50932 ? 1 : tag == 50934 ? 2 : tag == 50936 ? 3 : -1;
    if (index < 0 && signature < 0) return;
    if ((qualified & ~0xffff) != 0x100000 || !stream || (order != 0x4949 && order != 0x4d4d)) reject();
    const bool signatureTag = signature >= 0;
    const int expected = index == 0 || index == 6 ? 3 : index == 1 ? 2 : index == 4 || index == 5 || index == 9 ? 1 : 9;
    if (signatureTag) {
        if (signatures[signature].present || (type != 2 && type != 1) || count < 1 || count > 256) reject();
    } else {
        const bool correctType = index == 0 ? type == 3 || type == 5 : index == 1 || index == 6 ? type == 5 :
                                 index == 4 || index == 5 || index == 9 ? type == 3 : type == 10;
        if (entries[index].present || !correctType || count != expected) reject();
    }
    const auto offset = stream->tell(), size = stream->size();
    const uint64_t bytes = static_cast<uint64_t>(count) * (signatureTag ? 1 : type == 3 ? 2 : 8);
    if (offset < 0 || size < 0 || static_cast<uint64_t>(size) != sourceSize ||
        static_cast<uint64_t>(offset) > sourceSize || bytes > sourceSize - static_cast<uint64_t>(offset)) reject();
    if (signatureTag) {
        auto& value = signatures[signature];
        value.present = true;
        value.length = static_cast<size_t>(count - 1);
        if (stream->read(value.bytes.data(), 1, count) != count || value.bytes[count - 1] != 0 || !utf8(value.bytes.data(), value.length)) reject();
    } else {
        auto& value = entries[index];
        value.present = true;
        for (int i = 0; i < count; ++i) value.values[i] = number(stream, type, order == 0x4949);
    }
}

std::array<float, 3> AsShotXyMetadata::requestedWb(const libraw_colordata_t& color) const {
    if (invalid || entries[0].present || !entries[1].present) reject();
    // The unchanged LibRaw path does not implement output-referred DNG colourimetry.
    if (entries[9].present && entries[9].values[0] != 0) reject();
    const auto& shotProfile = signatures[2];
    const auto& primaryProfile = signatures[3];
    if (shotProfile.length && (shotProfile.length != primaryProfile.length ||
        !std::equal(shotProfile.bytes.begin(), shotProfile.bytes.begin() + shotProfile.length, primaryProfile.bytes.begin()))) reject();
    for (int index : {2, 3, 4, 5}) if (!entries[index].present) reject();
    const double firstTemperature = illuminant(entries[4].values[0]), secondTemperature = illuminant(entries[5].values[0]);
    if (firstTemperature == secondTemperature || (entries[4].values[0] != 21 && entries[5].values[0] != 21)) reject();
    const auto& firstSignature = signatures[0];
    const auto& secondSignature = signatures[1];
    const bool matching = firstSignature.length == secondSignature.length &&
        std::equal(firstSignature.bytes.begin(), firstSignature.bytes.begin() + firstSignature.length, secondSignature.bytes.begin());
    Matrix matrices[2]{};
    for (int endpoint = 0; endpoint < 2; ++endpoint) {
        const Matrix calibration = entries[7 + endpoint].present ? entries[7 + endpoint].values : kIdentity;
        if (!matching && calibration != kIdentity) reject();
        inverse(calibration); inverse(entries[2 + endpoint].values);
        matrices[endpoint] = product(calibration, entries[2 + endpoint].values);
        for (int row = 0; row < 3; ++row) {
            const double analog = entries[6].present ? entries[6].values[row] : 1;
            if (!std::isfinite(analog) || analog <= 0) reject();
            for (int col = 0; col < 3; ++col) matrices[endpoint][row * 3 + col] *= analog;
        }
        inverse(matrices[endpoint]);
    }
    // Attest the actual LibRaw dual-profile D65 replacement, not the TIFF WhiteXY-folded branch.
    // Its metadata fields are Float32; reconstruct those rounding points before comparing.
    const int selected = entries[4].values[0] == 21 ? 0 : 1;
    Matrix selectedCm{}, selectedCc{};
    for (int i = 0; i < 9; ++i) {
        selectedCm[i] = static_cast<float>(entries[2 + selected].values[i]);
        const double cc = entries[7 + selected].present ? entries[7 + selected].values[i] : kIdentity[i];
        const double ab = entries[6].present ? static_cast<float>(entries[6].values[i / 3]) : 1;
        selectedCc[i] = static_cast<float>(cc) * ab;
    }
    Matrix normalized = product(product(selectedCc, selectedCm), kXyzFromSrgb);
    for (int row = 0; row < 3; ++row) {
        const double sum = normalized[row * 3] + normalized[row * 3 + 1] + normalized[row * 3 + 2];
        if (!std::isfinite(sum) || sum <= .00001) reject();
        for (int col = 0; col < 3; ++col) normalized[row * 3 + col] /= sum;
    }
    const auto expected = inverse(normalized);
    for (int row = 0; row < 3; ++row) for (int col = 0; col < 4; ++col) {
        const double actual = color.rgb_cam[row][col], reference = col == 3 ? 0 : expected[row * 3 + col];
        if (!std::isfinite(actual) || std::abs(actual - reference) > 2e-5 * (1 + std::abs(reference))) reject();
    }
    const double x = entries[1].values[0], y = entries[1].values[1], shotTemperature = temperature(x, y);
    const double weight = std::clamp((1 / shotTemperature - 1 / secondTemperature) /
                                   (1 / firstTemperature - 1 / secondTemperature), 0., 1.);
    Matrix blended{};
    for (int i = 0; i < 9; ++i) blended[i] = weight * matrices[0][i] + (1 - weight) * matrices[1][i];
    inverse(blended);
    const Vector xyz{x / y, 1, (1 - x - y) / y};
    Vector neutral{};
    for (int row = 0; row < 3; ++row) {
        for (int col = 0; col < 3; ++col) neutral[row] += blended[row * 3 + col] * xyz[col];
        if (!std::isfinite(neutral[row]) || neutral[row] <= 0) reject();
    }
    const std::array<float, 3> wb{static_cast<float>(neutral[1] / neutral[0]), 1, static_cast<float>(neutral[1] / neutral[2])};
    for (float value : wb) if (!std::isfinite(value) || value <= .00001f) reject();
    return wb;
}
} // namespace cinematic
