#include "original_raster_sink.h"
#include <sys/stat.h>
#include <unistd.h>
#include <map>
#include <set>
#include <array>
#include <stdexcept>
#include <sstream>
#include <algorithm>

namespace cinematic {
namespace {
[[noreturn]] void reject() { throw std::runtime_error("ORIGINAL_GEOMETRY_UNSUPPORTED"); }
struct Field { unsigned type, count; size_t data; };
class Tiff {
public:
    const std::vector<unsigned char>& bytes;
    bool little;
    explicit Tiff(const std::vector<unsigned char>& b) : bytes(b), little(b.size() >= 2 && b[0] == 'I' && b[1] == 'I') {
        if (b.size() < 8 || (!little && !(b[0] == 'M' && b[1] == 'M')) || number(2, 2) != 42) reject();
    }
    uint32_t number(size_t p, unsigned n) const {
        if (p > bytes.size() || n > bytes.size() - p) reject();
        uint32_t value = 0;
        for (unsigned i = 0; i < n; ++i) value = (value << 8) | bytes[p + (little ? n - i - 1 : i)];
        return value;
    }
    std::vector<unsigned> values(const Field& f, unsigned count) const {
        if (f.count != count || (f.type != 3 && f.type != 4 && f.type != 5) || count > 16) reject();
        const unsigned unit = f.type == 3 ? 2 : f.type == 4 ? 4 : 8;
        std::vector<unsigned> result;
        for (unsigned i = 0; i < count; ++i) {
            unsigned value = number(f.data + i * unit, f.type == 3 ? 2 : 4);
            if (f.type == 5) {
                const unsigned d = number(f.data + i * unit + 4, 4);
                if (!d || value % d) reject();
                value /= d;
            }
            result.push_back(value);
        }
        return result;
    }
    std::map<unsigned, Field> fields(size_t at) const {
        const unsigned count = number(at, 2);
        if (count > 512 || at > bytes.size() || uint64_t(count) * 12 + 6 > bytes.size() - at) reject();
        std::map<unsigned, Field> result;
        for (unsigned i = 0; i < count; ++i) {
            const size_t p = at + 2 + i * 12;
            const unsigned tag = number(p, 2), type = number(p + 2, 2), n = number(p + 4, 4);
            if (tag != 256 && tag != 257 && tag != 262 && tag != 274 && tag != 330 && tag != 50718 &&
                tag != 50719 && tag != 50720 && tag != 50829 && tag != 51125) continue;
            if (!n || n > 16 || (type != 3 && type != 4 && type != 5) || result.count(tag)) reject();
            const unsigned unit = type == 3 ? 2 : type == 4 ? 4 : 8;
            const size_t data = n * unit <= 4 ? p + 8 : number(p + 8, 4);
            if (data > bytes.size() || n * unit > bytes.size() - data) reject();
            result.emplace(tag, Field{type, n, data});
        }
        return result;
    }
};
}

void OriginalRasterSink::parse(const std::vector<unsigned char>& bytes, const LibRaw& raw) {
    const auto& s = raw.imgdata.sizes;
    rawWidth = s.raw_width; rawHeight = s.raw_height;
    if (!rawWidth || !rawHeight || rawWidth > 8192 || rawHeight > 8192 || uint64_t(rawWidth) * rawHeight > 32'000'000) reject();
    Tiff tiff(bytes);
    const unsigned root = tiff.number(4, 4);
    std::vector<unsigned> pending{root}; std::set<unsigned> visited;
    unsigned primaryOrientation = 1, matches = 0;
    std::map<unsigned, Field> selected;
    while (!pending.empty()) {
        const unsigned at = pending.back(); pending.pop_back();
        if (!at) continue;
        if (visited.size() >= 32 || !visited.insert(at).second) reject();
        const auto fields = tiff.fields(at);
        if (fields.count(51125)) reject(); // No silent DefaultUserCrop/fractional-crop policy.
        if (at == root && fields.count(274)) primaryOrientation = tiff.values(fields.at(274), 1)[0];
        if (fields.count(256) && fields.count(257) && fields.count(262) &&
            tiff.values(fields.at(256), 1)[0] == rawWidth && tiff.values(fields.at(257), 1)[0] == rawHeight &&
            tiff.values(fields.at(262), 1)[0] == 32803) { selected = fields; matches++; }
        if (fields.count(330)) for (unsigned child : tiff.values(fields.at(330), fields.at(330).count)) pending.push_back(child);
        pending.push_back(tiff.number(at + 2 + tiff.number(at, 2) * 12, 4));
    }
    if (matches != 1) reject();
    orientation = selected.count(274) ? tiff.values(selected.at(274), 1)[0] : primaryOrientation;
    if (orientation < 1 || orientation > 8) reject();
    static constexpr int flips[]{0, 0, 1, 3, 2, 4, 6, 7, 5};
    if (s.flip != flips[orientation]) reject();
    const auto active = selected.count(50829) ? tiff.values(selected.at(50829), 4) : std::vector<unsigned>{0, 0, rawHeight, rawWidth};
    if (active[0] >= active[2] || active[1] >= active[3] || active[2] > rawHeight || active[3] > rawWidth ||
        s.top_margin != active[0] || s.left_margin != active[1] || s.width != active[3] - active[1] || s.height != active[2] - active[0]) reject();
    const auto origin = selected.count(50719) ? tiff.values(selected.at(50719), 2) : std::vector<unsigned>{0, 0};
    const auto size = selected.count(50720) ? tiff.values(selected.at(50720), 2) : std::vector<unsigned>{s.width, s.height};
    if (!size[0] || !size[1] || uint64_t(origin[0]) + size[0] > s.width || uint64_t(origin[1]) + size[1] > s.height ||
        uint64_t(size[0]) * size[1] > 24'000'000) reject();
    if (selected.count(50718)) { const auto scale = tiff.values(selected.at(50718), 2); if (scale[0] != 1 || scale[1] != 1) reject(); }
    left = active[1] + origin[0]; top = active[0] + origin[1]; width = size[0]; height = size[1];
}

void OriginalRasterSink::applyCrop(LibRaw& raw) const {
    const auto& inset = raw.imgdata.sizes.raw_inset_crops[0];
    if (inset.cleft < 65535 && inset.ctop < 65535 && inset.cwidth && inset.cheight) {
        if (inset.cleft != left || inset.ctop != top || inset.cwidth != width || inset.cheight != height ||
            raw.adjust_to_raw_inset_crop(1, 0.f) != 1) reject();
    }
    const auto& s = raw.imgdata.sizes;
    if (s.left_margin != left || s.top_margin != top || s.width != width || s.height != height) reject();
}

void OriginalRasterSink::write(const libraw_processed_image_t& image, const std::function<void()>& checkpoint) const {
    const unsigned w = orientation >= 5 ? height : width, h = orientation >= 5 ? width : height;
    if (image.width != w || image.height != h || image.bits != 16 || image.colors != 3 || image.data_size != uint64_t(w) * h * 6) reject();
    const auto* values = reinterpret_cast<const uint16_t*>(image.data);
    std::vector<unsigned char> row(w * 6);
    for (unsigned y = 0; y < h; ++y) {
        checkpoint();
        for (unsigned i = 0; i < w * 3; ++i) { const auto v = values[(uint64_t(y) * w * 3) + i]; row[i * 2] = v & 255; row[i * 2 + 1] = v >> 8; }
        size_t at = 0;
        while (at < row.size()) {
            checkpoint();
            const auto n = pwrite(outputFd, row.data() + at, row.size() - at, uint64_t(y) * row.size() + at);
            if (n <= 0) throw std::runtime_error("ORIGINAL_RASTER_WRITE");
            at += n;
        }
    }
    struct stat actual{};
    if (fstat(outputFd, &actual) || actual.st_size != static_cast<off_t>(image.data_size)) throw std::runtime_error("ORIGINAL_RASTER_WRITE");
    checkpoint();
}

std::string OriginalRasterSink::report() const {
    std::ostringstream out;
    out << "{\"rawWidth\":" << rawWidth << ",\"rawHeight\":" << rawHeight << ",\"sourceWidth\":" << width
        << ",\"sourceHeight\":" << height << ",\"cropLeft\":" << left << ",\"cropTop\":" << top
        << ",\"orientation\":" << orientation << ",\"width\":" << (orientation >= 5 ? height : width)
        << ",\"height\":" << (orientation >= 5 ? width : height) << ",\"pixelsWritten\":" << uint64_t(width) * height
        << ",\"bits\":16,\"channels\":3,\"halfSize\":0,\"color\":\"linear-sRGB-D65\",\"scope\":\"PRELIMINARY_ORIGINAL_NO_FACE_NO_VERIFIED_EV\"}";
    return out.str();
}

std::array<int, 10> OriginalRasterSink::preflightGeometry() const {
    return {2, static_cast<int>(rawWidth), static_cast<int>(rawHeight), static_cast<int>(width),
            static_cast<int>(height), static_cast<int>(orientation),
            static_cast<int>(orientation >= 5 ? height : width), static_cast<int>(orientation >= 5 ? width : height),
            static_cast<int>(left), static_cast<int>(top)};
}
}
