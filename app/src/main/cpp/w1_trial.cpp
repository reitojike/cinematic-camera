#include "w1_trial.h"
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <limits>
#include <stdexcept>

namespace cinematic {
namespace {
using Vec = std::array<double, 3>;
using Pair = std::array<double, 2>;
using Mat = std::array<double, 9>;
constexpr Pair kD50{.3457, .3585};
constexpr int kTags[]{50728, 50729, 50721, 50722, 50778, 50779, 50727, 50723, 50724};

[[noreturn]] void reject() { throw std::runtime_error("DNG_W1_METADATA"); }
bool finite(double value) { return std::isfinite(value); }

uint32_t u32(const unsigned char* data, bool little) {
    uint32_t value = 0;
    for (int i = 0; i < 4; ++i) value = (value << 8) | data[little ? 3 - i : i];
    return value;
}
int64_t signed32(uint32_t value) {
    return value & 0x80000000U ? static_cast<int64_t>(value) - 0x100000000LL : value;
}
double readNumber(LibRaw_abstract_datastream* stream, int type, bool little) {
    unsigned char bytes[8]{};
    const int size = type == 3 ? 2 : 8;
    if (stream->read(bytes, 1, size) != size) reject();
    if (type == 3) return little ? bytes[0] + (bytes[1] << 8) : (bytes[0] << 8) + bytes[1];
    const uint32_t numerator = u32(bytes, little), denominator = u32(bytes + 4, little);
    if (!denominator) reject();
    return type == 10 ? static_cast<double>(signed32(numerator)) / signed32(denominator)
                      : static_cast<double>(numerator) / denominator;
}

Vec multiply(const Mat& matrix, const Vec& value) {
    Vec result{};
    for (int row = 0; row < 3; ++row)
        for (int col = 0; col < 3; ++col) result[row] += matrix[row * 3 + col] * value[col];
    for (double x : result) if (!finite(x)) reject();
    return result;
}

Mat inverse(const Mat& matrix) {
    double work[3][6]{};
    double scale = 0;
    for (int row = 0; row < 3; ++row) {
        for (int col = 0; col < 3; ++col) {
            work[row][col] = matrix[row * 3 + col];
            scale = std::max(scale, std::abs(work[row][col]));
        }
        work[row][row + 3] = 1;
    }
    if (!finite(scale) || scale == 0) reject();
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
    Mat result{};
    for (int row = 0; row < 3; ++row) for (int col = 0; col < 3; ++col) {
        const double value = work[row][col + 3];
        if (!finite(value)) reject();
        result[row * 3 + col] = value;
    }
    return result;
}

Vec xyz(const Pair& xy) {
    if (!finite(xy[0]) || !finite(xy[1]) || xy[0] <= 0 || xy[1] <= 0 || xy[0] + xy[1] >= 1) reject();
    return {xy[0] / xy[1], 1., (1 - xy[0] - xy[1]) / xy[1]};
}
double cct(const Pair& xy) {
    xyz(xy);
    if (std::abs(.1858 - xy[1]) < 1e-12) reject();
    const double n = (xy[0] - .3320) / (.1858 - xy[1]);
    const double temperature = 449 * n * n * n + 3525 * n * n + 6823.3 * n + 5520.33;
    if (!finite(temperature) || temperature <= 0) reject();
    return temperature;
}
double illuminant(double code) {
    if (code == 17) return 2856.;
    if (code == 20) return 5503.;
    if (code == 21) return 6504.;
    if (code == 22) return 7504.;
    if (code == 23) return 5003.;
    reject();
}
double weight(double temperature, double first, double second) {
    if (first == second) return 1.;
    const double result = (1 / temperature - 1 / second) / (1 / first - 1 / second);
    if (!finite(result)) reject();
    return std::clamp(result, 0., 1.);
}
Mat mix(const Mat& first, const Mat& second, double amount) {
    Mat result{};
    for (size_t i = 0; i < result.size(); ++i) result[i] = amount * first[i] + (1 - amount) * second[i];
    return result;
}

Pair planck(double temperature) {
    const double t = temperature;
    return {(.860117757 + 1.54118254e-4 * t + 1.28641212e-7 * t * t) /
                (1 + 8.42420235e-4 * t + 7.08145163e-7 * t * t),
            (.317398726 + 4.22806245e-5 * t + 4.20481691e-8 * t * t) /
                (1 - 2.89741816e-5 * t + 1.61456053e-7 * t * t)};
}
Pair normal(double temperature) {
    const auto high = planck(temperature * 1.001), low = planck(temperature * .999);
    Pair result{-(high[1] - low[1]), high[0] - low[0]};
    const double length = std::hypot(result[0], result[1]);
    if (!finite(length) || length == 0) reject();
    const double sign = result[1] > 0 ? 1. : -1.;
    for (double& value : result) value *= sign / length;
    return result;
}
Pair shiftedWhite(const Pair& xy) {
    xyz(xy);
    const double divisor = -2 * xy[0] + 12 * xy[1] + 3;
    if (!finite(divisor) || std::abs(divisor) < 1e-12) reject();
    const Pair uv{4 * xy[0] / divisor, 6 * xy[1] / divisor};
    double bestDistance = std::numeric_limits<double>::infinity(), sourceTemperature = 0;
    int bestIndex = -1;
    for (int i = 0; i < 4000; ++i) {
        const double mired = 1e6 / 15000 + i * ((1e6 / 1700 - 1e6 / 15000) / 3999);
        const double temperature = 1e6 / mired;
        const auto point = planck(temperature);
        const double distance = std::hypot(uv[0] - point[0], uv[1] - point[1]);
        if (distance < bestDistance) { bestDistance = distance; sourceTemperature = temperature; bestIndex = i; }
    }
    // Domain restriction is deliberate: do not hide an out-of-range white by clipping the search.
    if (bestIndex <= 0 || bestIndex >= 3999) reject();
    const auto base = planck(sourceTemperature), sourceNormal = normal(sourceTemperature);
    const double tint = ((uv[0] - base[0]) * sourceNormal[0] + (uv[1] - base[1]) * sourceNormal[1]) * 3000;
    const double targetTemperature = 1e6 / (1e6 / sourceTemperature + 22.);
    if (!finite(tint) || !finite(targetTemperature) || targetTemperature < 1700 || targetTemperature > 15000) reject();
    const auto target = planck(targetTemperature), targetNormal = normal(targetTemperature);
    const Pair shiftedUv{target[0] + targetNormal[0] * tint / 3000, target[1] + targetNormal[1] * tint / 3000};
    const double d = 2 * shiftedUv[0] - 8 * shiftedUv[1] + 4;
    if (!finite(d) || std::abs(d) < 1e-12) reject();
    const Pair result{3 * shiftedUv[0] / d, 2 * shiftedUv[1] / d};
    xyz(result);
    return result;
}
} // namespace

void W1Metadata::capture(void* context, int tag, int type, int count, unsigned order, void* stream, INT64) noexcept {
    auto* metadata = static_cast<W1Metadata*>(context);
    if (!metadata || metadata->invalid) return;
    try { metadata->captureTag(tag, type, count, order, static_cast<LibRaw_abstract_datastream*>(stream)); }
    catch (...) { metadata->invalid = true; }
}

void W1Metadata::captureTag(int qualifiedTag, int type, int count, unsigned order, LibRaw_abstract_datastream* stream) {
    const int tag = qualifiedTag & 0xffff;
    const int nameSpace = qualifiedTag & ~0xffff;
    if ((qualifiedTag >> 20) > 0) {
        if (tag == 50964 || tag == 50965) observedTags |= 1;
        if (tag == 50981 || tag == 50982 || tag == 51108) observedTags |= 2;
        if (tag == 51009) observedTags |= 4;
        if (tag == 50933 || (tag >= 52529 && tag <= 52532)) reject(); // Extra/third profiles unsupported.
    }
    int index = -1;
    for (int i = 0; i < 9; ++i) if (tag == kTags[i]) index = i;
    if (index < 0) return;
    if (nameSpace != 0x100000 || entries[index].present || !stream) reject();
    const int expectedCount = index == 0 || index == 6 ? 3 : index == 1 ? 2 : index == 4 || index == 5 ? 1 : 9;
    const bool correctType = index == 0 ? (type == 3 || type == 5) : index == 1 || index == 6 ? type == 5 :
                             index == 4 || index == 5 ? type == 3 : type == 10;
    if (count != expectedCount || !correctType || (order != 0x4949 && order != 0x4d4d)) reject();
    const auto position = stream->tell(), size = stream->size();
    const uint64_t bytes = static_cast<uint64_t>(count) * (type == 3 ? 2 : 8);
    if (position < 0 || size < 0 || static_cast<uint64_t>(size) != sourceSize ||
        static_cast<uint64_t>(position) > sourceSize || bytes > sourceSize - static_cast<uint64_t>(position)) reject();
    auto& entry = entries[index];
    entry.present = true;
    for (int i = 0; i < count; ++i) {
        entry.values[i] = readNumber(stream, type, order == 0x4949);
        if (!finite(entry.values[i])) reject();
    }
}

void validateW1Processing(const libraw_colordata_t& color, const std::array<float, 3>& wb) {
    // raw2image_start restores rawdata.color. With adjust_maximum_thr=0, exp_shift=1,
    // threshold=0 and no user black/saturation override, subtracting the sum below
    // is a conservative lower bound on the maximum reaching scale_colors().
    if (!color.maximum || color.maximum > 65535 || color.as_shot_wb_applied) reject();
    const auto& black = color.cblack;
    const uint64_t gridSize = static_cast<uint64_t>(black[4]) * black[5];
    if ((black[4] == 0) != (black[5] == 0) || gridSize > LIBRAW_CBLACK_SIZE - 6) reject();
    uint64_t gridMax = 0;
    for (uint64_t i = 0; i < gridSize; ++i) gridMax = std::max<uint64_t>(gridMax, black[6 + i]);
    const uint64_t totalBlack = static_cast<uint64_t>(color.black) +
        *std::max_element(black, black + 4) + gridMax;
    if (totalBlack >= color.maximum) reject();
    const auto [minimum, maximum] = std::minmax_element(wb.begin(), wb.end());
    const double ratio = static_cast<double>(*maximum) / *minimum;
    // At most 65535 in absolute value before scale, including negative black subtraction.
    // Half of INT_MAX leaves ample room for Float32 division/multiplication rounding.
    constexpr double safeInt = 1073741823.5;
    if (!finite(ratio) || 65535. * 65535. * ratio / (color.maximum - totalBlack) > safeInt) reject();
    for (const auto& row : color.rgb_cam) {
        double magnitude = 0;
        for (int c = 0; c < 3; ++c) {
            if (!std::isfinite(row[c])) reject();
            magnitude += std::abs(static_cast<double>(row[c]));
        }
        // output_color=1 uses the identity sRGB output matrix; RGB conversion has no offset.
        if (magnitude * 65535. > safeInt) reject();
    }
}

std::array<float, 3> W1Metadata::requestedWb() const {
    if (invalid || entries[0].present == entries[1].present || !entries[2].present || !entries[4].present ||
        entries[3].present != entries[5].present) reject();
    for (int index : {6, 7, 8}) if (entries[index].present) {
        const int count = index == 6 ? 3 : 9;
        for (int i = 0; i < count; ++i)
            if (entries[index].values[i] != (index == 6 || i % 4 == 0 ? 1. : 0.)) reject();
    }
    const Mat first = entries[2].values, second = entries[3].present ? entries[3].values : first;
    inverse(first); inverse(second);
    const double t1 = illuminant(entries[4].values[0]);
    const double t2 = entries[5].present ? illuminant(entries[5].values[0]) : t1;
    Pair white{};
    if (entries[1].present) white = {entries[1].values[0], entries[1].values[1]};
    else {
        Vec neutral{entries[0].values[0], entries[0].values[1], entries[0].values[2]};
        for (double value : neutral) if (value <= 0 || !finite(value)) reject();
        const double maximum = *std::max_element(neutral.begin(), neutral.end());
        for (double& value : neutral) value /= maximum;
        white = kD50;
        bool converged = false;
        for (int i = 0; i < 30; ++i) {
            const auto camera = mix(first, second, weight(cct(white), t1, t2));
            const auto value = multiply(inverse(camera), neutral);
            const double sum = value[0] + value[1] + value[2];
            if (!finite(sum) || sum <= 0) reject();
            const Pair next{value[0] / sum, value[1] / sum};
            xyz(next);
            if (std::max(std::abs(next[0] - white[0]), std::abs(next[1] - white[1])) < 1e-7) { converged = true; break; }
            white = next;
        }
        if (!converged) reject();
    }
    const auto shifted = shiftedWhite(white);
    const auto camera = mix(first, second, weight(cct(shifted), t1, t2));
    inverse(camera);
    const auto neutral = multiply(camera, xyz(shifted));
    for (double value : neutral) if (!finite(value) || value <= 0) reject();
    const std::array<float, 3> result{static_cast<float>(neutral[1] / neutral[0]), 1.f, static_cast<float>(neutral[1] / neutral[2])};
    for (float value : result) if (!std::isfinite(value) || value <= .00001f) reject();
    return result;
}
} // namespace cinematic
