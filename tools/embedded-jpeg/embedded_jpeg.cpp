#include "embedded_jpeg.h"
#include <libraw/libraw.h>
#include <cstring>
#include <new>
#include <array>
#include <memory>

namespace cinematic::embedded {
namespace {
struct Cancellation {
    Cancel callback;
    void* context;
    bool stopped() const noexcept { return callback && callback(context); }
};
int progress(void* context, LibRaw_progress, int, int) {
    return static_cast<Cancellation*>(context)->stopped() ? 1 : 0;
}
Result result(Status status) { return {status, {}}; }
Status error(int rc) {
    if (rc == LIBRAW_CANCELLED_BY_CALLBACK) return Status::Cancelled;
    if (rc == LIBRAW_UNSUFFICIENT_MEMORY || rc == LIBRAW_MEMPOOL_OVERFLOW) return Status::Memory;
    if (rc == LIBRAW_FILE_UNSUPPORTED || rc == LIBRAW_UNSUPPORTED_THUMBNAIL) return Status::Unsupported;
    return Status::Invalid;
}
bool dimensions(unsigned w, unsigned h) {
    return w && h && w <= 4096 && h <= 4096 && uint64_t(w) * h <= 4'000'000;
}
// LibRaw normalizes invalid/missing TIFF Orientation into valid-looking tflip values.
// Corroborate only classic TIFF IFDs whose single JPEG range matches the selected item.
// Other containers or ambiguous/malformed metadata deliberately keep orientation unknown.
unsigned sourceOrientation(const uint8_t* p, size_t n, uint64_t offset, unsigned length) {
    if (n < 8) return 0;
    const bool le = p[0] == 'I' && p[1] == 'I';
    if (!le && !(p[0] == 'M' && p[1] == 'M')) return 0;
    auto u16 = [le](const uint8_t* q) -> uint32_t {
        return le ? uint32_t(q[0]) | uint32_t(q[1]) << 8 : uint32_t(q[0]) << 8 | q[1];
    };
    auto u32 = [le](const uint8_t* q) -> uint32_t {
        return le ? uint32_t(q[0]) | uint32_t(q[1]) << 8 | uint32_t(q[2]) << 16 | uint32_t(q[3]) << 24
                  : uint32_t(q[0]) << 24 | uint32_t(q[1]) << 16 | uint32_t(q[2]) << 8 | q[3];
    };
    if (u16(p + 2) != 42) return 0;
    std::array<uint32_t, 64> queue{};
    queue[0] = u32(p + 4);
    size_t queued = 1;
    unsigned found = 0;
    bool matched = false;
    auto enqueue = [&](uint32_t value) {
        if (!value) return true;
        if (queued == queue.size()) return false;
        for (size_t i = 0; i < queued; ++i) if (queue[i] == value) return false;
        queue[queued++] = value;
        return true;
    };
    for (size_t index = 0; index < queued; ++index) {
        size_t at = queue[index];
        if (at < 8 || at > n - 2) return 0;
        const size_t count = u16(p + at);
        if (count > 4096 || count > (n - at - 2) / 12) return 0;
        const size_t end = at + 2 + count * 12;
        if (n - end < 4 || !enqueue(u32(p + end))) return 0;
        uint64_t dataOffset = 0, dataLength = 0;
        unsigned direction = 0;
        bool offsetSeen = false, lengthSeen = false, directionSeen = false, ambiguous = false;
        for (size_t i = 0; i < count; ++i) {
            const auto* entry = p + at + 2 + i * 12;
            const auto tag = u16(entry), type = u16(entry + 2), amount = u32(entry + 4);
            if (tag == 330) {
                if (type != 4 || !amount || amount > 64) return 0;
                const size_t base = amount == 1 ? size_t(entry + 8 - p) : u32(entry + 8);
                if (base > n || amount > (n - base) / 4) return 0;
                for (unsigned j = 0; j < amount; ++j) if (!enqueue(u32(p + base + j * 4))) return 0;
            }
            if (tag == 273 || tag == 513) {
                if (offsetSeen || amount != 1 || (type != 3 && type != 4)) ambiguous = true;
                dataOffset = type == 3 ? u16(entry + 8) : u32(entry + 8); offsetSeen = true;
            }
            if (tag == 279 || tag == 514) {
                if (lengthSeen || amount != 1 || (type != 3 && type != 4)) ambiguous = true;
                dataLength = type == 3 ? u16(entry + 8) : u32(entry + 8); lengthSeen = true;
            }
            if (tag == 274) {
                if (directionSeen || type != 3 || amount != 1) ambiguous = true;
                direction = u16(entry + 8); directionSeen = true;
            }
        }
        if (offsetSeen && lengthSeen && dataOffset == offset && dataLength == length) {
            if (matched || ambiguous || !directionSeen || direction < 1 || direction > 8) return 0;
            found = direction; matched = true;
        }
    }
    return found;
}
// Only bounded TIFF IFD0 orientation parsing. Unknown/malformed EXIF never borrows RAW orientation.
int orientation(const uint8_t* p, size_t n) {
    if (n < 8) return -1;
    const bool le = p[0] == 'I' && p[1] == 'I';
    if (!le && !(p[0] == 'M' && p[1] == 'M')) return -1;
    auto u16 = [le](const uint8_t* q) -> uint32_t {
        return le ? uint32_t(q[0]) | uint32_t(q[1]) << 8 : uint32_t(q[0]) << 8 | q[1];
    };
    auto u32 = [le](const uint8_t* q) -> uint32_t {
        return le ? uint32_t(q[0]) | uint32_t(q[1]) << 8 | uint32_t(q[2]) << 16 | uint32_t(q[3]) << 24
                  : uint32_t(q[0]) << 24 | uint32_t(q[1]) << 16 | uint32_t(q[2]) << 8 | q[3];
    };
    if (u16(p + 2) != 42) return -1;
    const size_t offset = u32(p + 4);
    if (offset < 8 || offset > n - 2) return -1;
    const size_t count = u16(p + offset);
    if (count > (n - offset - 2) / 12) return -1;
    int value = 0;
    for (size_t i = 0; i < count; ++i) {
        const auto* entry = p + offset + 2 + i * 12;
        if (u16(entry) != 274) continue;
        if (value || u16(entry + 2) != 3 || u32(entry + 4) != 1) return -1;
        value = static_cast<int>(u16(entry + 8));
        if (value < 1 || value > 8) return -1;
    }
    return value;
}
struct Header { unsigned width = 0, height = 0; int exif = 0; };
bool jpegHeader(const uint8_t* p, size_t n, Header& header) {
    if (n < 4 || p[0] != 255 || p[1] != 216) return false;
    size_t at = 2;
    while (at < n) {
        if (p[at++] != 255) return false;
        while (at < n && p[at] == 255) ++at;
        if (at == n) return false;
        const auto marker = p[at++];
        if (marker == 0xd9 || marker == 0 || marker == 0xd8) return false;
        if (marker == 1 || (marker >= 0xd0 && marker <= 0xd7)) return false;
        if (n - at < 2) return false;
        const size_t length = size_t(p[at]) * 256 + p[at + 1];
        if (length < 2 || length > n - at) return false;
        const auto* data = p + at + 2;
        const size_t bytes = length - 2;
        if (marker == 0xda) {
            // Validate the complete scan header, but leave entropy decoding to the caller.
            return header.width && header.height && bytes >= 6 && data[0] >= 1 && data[0] <= 4 &&
                   bytes == size_t(4 + 2 * data[0]);
        }
        if (marker == 0xe1 && bytes >= 6 && !std::memcmp(data, "Exif\0\0", 6)) {
            const int value = orientation(data + 6, bytes - 6);
            if (value < 0 || (value && header.exif)) header.exif = -1;
            else if (header.exif >= 0 && value) header.exif = value;
        }
        if ((marker >= 0xc0 && marker <= 0xcf) && marker != 0xc4 && marker != 0xc8 && marker != 0xcc) {
            if (bytes < 6 || header.width || data[0] != 8 || data[5] < 1 || data[5] > 4 ||
                bytes != size_t(6 + 3 * data[5])) return false;
            header.height = unsigned(data[1]) * 256 + data[2];
            header.width = unsigned(data[3]) * 256 + data[4];
            if (!dimensions(header.width, header.height)) return false;
        }
        at += length;
    }
    return false;
}
} // namespace

unsigned exifOrientation(unsigned flip) noexcept {
    constexpr unsigned values[] = {1, 2, 4, 3, 5, 8, 6, 7};
    return flip < 8 ? values[flip] : 0;
}

Result extract(const uint8_t* snapshot, size_t size, Cancel callback, void* context) noexcept {
    try {
        Cancellation cancel{callback, context};
        if (cancel.stopped()) return result(Status::Cancelled);
        if (!snapshot || !size) return result(Status::Invalid);
        if (size > maxInput) return result(Status::Unsupported);
        auto owner = std::make_unique<LibRaw>(LIBRAW_OPTIONS_NO_DATAERR_CALLBACK);
        auto& raw = *owner;
        raw.set_progress_handler(progress, &cancel);
        // open_buffer borrows the memory; fixed LibRaw does not write the source buffer.
        int rc = raw.open_buffer(const_cast<uint8_t*>(snapshot), size);
        if (cancel.stopped()) return result(Status::Cancelled);
        if (rc) return result(error(rc));
        const auto& list = raw.imgdata.thumbs_list;
        if (!list.thumbcount) return result(Status::Absent);
        if (list.thumbcount < 0 || list.thumbcount > LIBRAW_THUMBNAIL_MAXCOUNT) return result(Status::Invalid);
        int selected = -1;
        uint64_t largest = 0;
        bool invalid = false, present = false;
        for (int i = 0; i < list.thumbcount; ++i) {
            const auto& item = list.thumblist[i];
            // LibRaw creates a zero-range placeholder even for a DNG with no thumbnail.
            if (!item.toffset && !item.tlength) continue;
            present = true;
            if (item.tformat != LIBRAW_INTERNAL_THUMBNAIL_JPEG) continue;
            if (item.toffset < 0 || uint64_t(item.toffset) > size || item.tlength > size - size_t(item.toffset)) {
                invalid = true;
                continue;
            }
            if (item.tlength < 64 || item.tlength > maxJpeg || !dimensions(item.twidth, item.theight)) continue;
            Header header;
            if (!jpegHeader(snapshot + size_t(item.toffset), item.tlength, header) ||
                header.width != item.twidth || header.height != item.theight) {
                invalid = true;
                continue;
            }
            const uint64_t pixels = uint64_t(item.twidth) * item.theight;
            if (pixels > largest) { selected = i; largest = pixels; }
        }
        if (cancel.stopped()) return result(Status::Cancelled);
        if (!present) return result(Status::Absent);
        if (selected < 0) return result(invalid ? Status::Invalid : Status::Unsupported);
        const auto item = list.thumblist[selected];
        if (cancel.stopped()) return result(Status::Cancelled);
        rc = raw.unpack_thumb_ex(selected);
        if (cancel.stopped()) return result(Status::Cancelled);
        if (rc) return result(error(rc));
        const auto& thumb = raw.imgdata.thumbnail;
        if (thumb.tformat != LIBRAW_THUMBNAIL_JPEG || !thumb.thumb || thumb.tlength != item.tlength ||
            thumb.twidth != item.twidth || thumb.theight != item.theight ||
            std::memcmp(thumb.thumb, snapshot + size_t(item.toffset), item.tlength)) return result(Status::Invalid);
        Header header;
        if (!jpegHeader(reinterpret_cast<const uint8_t*>(thumb.thumb), thumb.tlength, header)) return result(Status::Invalid);
        Result out = result(Status::Found);
        out.width = header.width;
        out.height = header.height;
        const unsigned flip = exifOrientation(item.tflip);
        const unsigned source = sourceOrientation(snapshot, size, uint64_t(item.toffset), item.tlength);
        if (source && source == flip && header.exif >= 0 && (!header.exif || unsigned(header.exif) == flip)) {
            out.orientation = flip;
            out.evidence = header.exif ? OrientationEvidence::ThumbnailAndExif : OrientationEvidence::Thumbnail;
        }
        if (cancel.stopped()) return result(Status::Cancelled);
        out.jpeg.assign(thumb.thumb, thumb.thumb + thumb.tlength);
        if (cancel.stopped()) return result(Status::Cancelled);
        return out;
    } catch (const std::bad_alloc&) { return result(Status::Memory); }
      catch (...) { return result(Status::Invalid); }
}
} // namespace cinematic::embedded
