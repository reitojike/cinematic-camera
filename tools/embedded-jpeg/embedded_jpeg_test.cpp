#include "embedded_jpeg.h"
#include "synthetic_jpeg.h"
#include <algorithm>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>
using namespace cinematic::embedded;
namespace {
using Bytes = std::vector<uint8_t>;
void require(bool value, const char* name) { if (!value) throw std::runtime_error(name); }
void put16(Bytes& b, size_t at, unsigned v) { b.at(at) = uint8_t(v); b.at(at + 1) = uint8_t(v >> 8); }
void put32(Bytes& b, size_t at, unsigned v) { put16(b, at, v); put16(b, at + 2, v >> 16); }
struct Entry { unsigned tag, type, count; Bytes bytes; };
Bytes numbers(std::initializer_list<unsigned> values, unsigned width) {
    Bytes b(values.size() * width); size_t at = 0;
    for (auto v : values) { if (width == 2) put16(b, at, v); else put32(b, at, v); at += width; }
    return b;
}
Entry s(unsigned tag, unsigned value) { return {tag, 3, 1, numbers({value}, 2)}; }
Entry l(unsigned tag, unsigned value) { return {tag, 4, 1, numbers({value}, 4)}; }
unsigned ifd(Bytes& b, std::vector<Entry> entries) {
    std::sort(entries.begin(), entries.end(), [](const Entry& a, const Entry& c) { return a.tag < c.tag; });
    const size_t start = b.size(); b.resize(start + 2 + 12 * entries.size() + 4);
    put16(b, start, unsigned(entries.size()));
    size_t at = start + 2;
    for (const auto& e : entries) {
        put16(b, at, e.tag); put16(b, at + 2, e.type); put32(b, at + 4, e.count);
        if (e.bytes.size() <= 4) std::copy(e.bytes.begin(), e.bytes.end(), b.begin() + at + 8);
        else { put32(b, at + 8, unsigned(b.size())); b.insert(b.end(), e.bytes.begin(), e.bytes.end()); }
        at += 12;
    }
    return unsigned(start);
}
void patch(Bytes& b, unsigned start, unsigned tag, unsigned value) {
    const unsigned count = unsigned(b[start]) | unsigned(b[start + 1]) << 8;
    for (unsigned i = 0; i < count; ++i) {
        size_t at = start + 2 + i * 12;
        if ((unsigned(b[at]) | unsigned(b[at + 1]) << 8) == tag) { put32(b, at + 8, value); return; }
    }
    throw std::runtime_error("missing fixture tag");
}
void retag(Bytes& b, unsigned start, unsigned oldTag, unsigned newTag) {
    const unsigned count = unsigned(b[start]) | unsigned(b[start + 1]) << 8;
    for (unsigned i = 0; i < count; ++i) {
        const size_t at = start + 2 + i * 12;
        if ((unsigned(b[at]) | unsigned(b[at + 1]) << 8) == oldTag) { put16(b,at,newTag); return; }
    }
    throw std::runtime_error("missing tag to replace");
}
struct Fixture { Bytes bytes; unsigned thumbIfd, jpegOffset; };
Fixture fixture(Bytes jpeg = Bytes(std::begin(syntheticJpeg), std::end(syntheticJpeg)), unsigned direction = 1,
                unsigned width = 16, unsigned height = 8, bool thumbnail = true) {
    Bytes b{'I','I',42,0,8,0,0,0};
    std::vector<Entry> entries{l(256,32),l(257,24),s(258,16),s(259,1),s(262,32803),l(273,0),s(274,1),
        s(277,1),l(278,24),l(279,32*24*2),{33421,3,2,numbers({2,2},2)},{33422,1,4,{0,1,1,2}},
        {50706,1,4,{1,4,0,0}},s(50713,1),l(50714,64),l(50717,4095),
        {50721,10,9,numbers({1,1,0,1,0,1,0,1,1,1,0,1,0,1,0,1,1,1},4)},
        {50728,5,3,numbers({1,1,1,1,1,1},4)},s(50778,21)};
    const auto rawIfd = ifd(b, entries);
    const unsigned rawOffset = unsigned(b.size()); b.resize(b.size() + 32*24*2, 17);
    patch(b, rawIfd, 273, rawOffset);
    unsigned thumbIfd = 0, jpegOffset = 0;
    if (thumbnail) {
        thumbIfd = ifd(b, {l(254,1),l(256,width),l(257,height),s(258,8),s(259,6),s(262,6),
                          l(273,0),s(274,direction),s(277,3),l(278,height),l(279,unsigned(jpeg.size()))});
        put32(b, rawIfd + 2 + 12 * unsigned(entries.size()), thumbIfd);
        jpegOffset = unsigned(b.size()); patch(b, thumbIfd, 273, jpegOffset);
        b.insert(b.end(), jpeg.begin(), jpeg.end());
    }
    // LibRaw identifies TIFF only after a minimum input window.
    b.resize(std::max<size_t>(b.size(), 4096));
    return {b,thumbIfd,jpegOffset};
}
Result run(const Fixture& f) { return extract(f.bytes.data(), f.bytes.size()); }
Bytes withExif(unsigned orientation) {
    Bytes jpeg(std::begin(syntheticJpeg), std::end(syntheticJpeg));
    Bytes app{255,225,0,34,'E','x','i','f',0,0,'I','I',42,0,8,0,0,0,1,0,18,1,3,0,1,0,0,0,0,0,0,0,0,0,0,0};
    app[28] = uint8_t(orientation);
    jpeg.insert(jpeg.begin() + 2, app.begin(), app.end()); return jpeg;
}
Bytes sizedJpeg(unsigned width, unsigned height) {
    Bytes jpeg(std::begin(syntheticJpeg), std::end(syntheticJpeg));
    for (size_t i = 0; i + 8 < jpeg.size(); ++i) if (jpeg[i] == 255 && jpeg[i + 1] == 0xc0) {
        jpeg[i + 5] = uint8_t(height >> 8); jpeg[i + 6] = uint8_t(height);
        jpeg[i + 7] = uint8_t(width >> 8); jpeg[i + 8] = uint8_t(width);
        return jpeg;
    }
    throw std::runtime_error("missing SOF");
}
void addThumbnail(Fixture& f, Bytes jpeg, unsigned width, unsigned height, unsigned direction) {
    const unsigned count = unsigned(f.bytes[f.thumbIfd]) | unsigned(f.bytes[f.thumbIfd + 1]) << 8;
    const unsigned next = ifd(f.bytes,{l(254,1),l(256,width),l(257,height),s(258,8),s(259,6),s(262,6),
        l(273,0),s(274,direction),s(277,3),l(278,height),l(279,unsigned(jpeg.size()))});
    put32(f.bytes,f.thumbIfd + 2 + 12 * count,next);
    patch(f.bytes,next,273,unsigned(f.bytes.size()));
    f.bytes.insert(f.bytes.end(),jpeg.begin(),jpeg.end());
}
}
int main() {
    try {
        for (unsigned i = 1; i <= 8; ++i) {
            auto f = fixture(withExif(i), i); const auto before = f.bytes;
            auto r = run(f);
            if (r.status != Status::Found) std::cerr << "status " << int(r.status) << '\n';
            require(r.status == Status::Found, "found synthetic embedded JPEG");
            require(r.jpeg == withExif(i), "JPEG bytes unchanged");
            require(f.bytes == before, "snapshot unchanged");
            require(r.width == 16 && r.height == 8, "dimensions");
            require(r.orientation == i && r.evidence == OrientationEvidence::ThumbnailAndExif, "orientation 1..8");
        }
        auto plain = run(fixture());
        require(plain.status == Status::Found && plain.orientation == 1 && plain.evidence == OrientationEvidence::Thumbnail, "thumbnail orientation");
        auto conflict = run(fixture(withExif(3), 6));
        require(conflict.status == Status::Found && !conflict.orientation, "conflicting orientation unknown");
        auto invalidExif = run(fixture(withExif(9)));
        require(invalidExif.status == Status::Found && !invalidExif.orientation, "invalid EXIF orientation unknown");
        require(exifOrientation(0xffff) == 0 && exifOrientation(8) == 0, "unknown flip");
        const auto invalidDirection = run(fixture(Bytes(std::begin(syntheticJpeg),std::end(syntheticJpeg)),9));
        require(invalidDirection.status == Status::Found && invalidDirection.orientation == 0, "invalid thumbnail orientation unknown");
        auto missing = fixture();
        // Replace Orientation tag by an unrelated private tag without borrowing RAW IFD orientation.
        retag(missing.bytes,missing.thumbIfd,274,65000);
        require(run(missing).status == Status::Found && run(missing).orientation == 0, "missing thumbnail orientation unknown");
        auto subIfd = fixture();
        patch(subIfd.bytes,8,50714,subIfd.thumbIfd); retag(subIfd.bytes,8,50714,330);
        const unsigned rawEntries = unsigned(subIfd.bytes[8]) | unsigned(subIfd.bytes[9]) << 8;
        put32(subIfd.bytes,8 + 2 + rawEntries * 12,0);
        require(run(subIfd).status == Status::Found && run(subIfd).orientation == 1, "SubIFD thumbnail orientation");
        auto bigExif = withExif(6);
        const Bytes tiff{'M','M',0,42,0,0,0,8,0,1,1,18,0,3,0,0,0,1,0,6,0,0,0,0,0,0};
        std::copy(tiff.begin(),tiff.end(),bigExif.begin()+12);
        const auto big = run(fixture(bigExif,6));
        require(big.status == Status::Found && big.orientation == 6 && big.evidence == OrientationEvidence::ThumbnailAndExif, "big endian EXIF orientation");
        auto absentFixture = fixture({},1,16,8,false);
        require(run(absentFixture).status == Status::Absent, "absent");
        auto corrupt = fixture(); corrupt.bytes[corrupt.jpegOffset] = 0;
        require(run(corrupt).status == Status::Invalid, "do not repair SOI");
        auto mismatch = fixture(); patch(mismatch.bytes,mismatch.thumbIfd,256,17);
        require(run(mismatch).status == Status::Invalid, "JPEG versus TIFF dimensions");
        auto outside = fixture(); patch(outside.bytes,outside.thumbIfd,273,0xfffffff0);
        require(run(outside).status != Status::Found, "out of bounds offset");
        auto truncated = fixture(); patch(truncated.bytes,truncated.thumbIfd,279,0xffffffff);
        require(run(truncated).status != Status::Found, "out of bounds length");
        require(run(fixture(sizedJpeg(4096,8),1,4096,8)).status == Status::Found, "dimension exact limit");
        require(run(fixture(sizedJpeg(4097,8),1,4097,8)).status == Status::Unsupported, "dimension over limit");
        require(run(fixture(sizedJpeg(2000,2000),1,2000,2000)).status == Status::Found, "pixels exact limit");
        require(run(fixture(sizedJpeg(2001,2000),1,2001,2000)).status == Status::Unsupported, "pixels over limit");
        auto multiple = fixture(); addThumbnail(multiple,sizedJpeg(32,16),32,16,3);
        require(run(multiple).width == 32 && run(multiple).orientation == 3, "largest valid candidate");
        auto tied = fixture(); addThumbnail(tied,withExif(3),16,8,3);
        require(run(tied).orientation == 1, "equal candidates prefer first");
        auto badLarge = fixture(); addThumbnail(badLarge,withExif(3),32,16,3);
        require(run(badLarge).status == Status::Found && run(badLarge).width == 16, "invalid large candidate ignored");
        auto badScan = fixture();
        for (size_t i = badScan.jpegOffset; i + 4 < badScan.bytes.size(); ++i)
            if (badScan.bytes[i] == 255 && badScan.bytes[i+1] == 0xda) { badScan.bytes[i+2] = 0; badScan.bytes[i+3] = 2; break; }
        require(run(badScan).status == Status::Invalid, "broken scan header");
        auto large = fixture(); large.bytes.resize(maxInput + 1);
        require(run(large).status == Status::Unsupported, "input limit");
        large.bytes.resize(maxInput);
        require(run(large).status == Status::Found, "input exact limit");
        Bytes jpeg(std::begin(syntheticJpeg), std::end(syntheticJpeg));
        jpeg.resize(maxJpeg);
        require(run(fixture(jpeg)).status == Status::Found, "JPEG exact limit");
        jpeg.push_back(0);
        require(run(fixture(jpeg)).status == Status::Unsupported, "JPEG over limit");
        auto nonJpeg = fixture(); patch(nonJpeg.bytes,nonJpeg.thumbIfd,259,1);
        require(run(nonJpeg).status != Status::Found, "non-JPEG not decoded");
        require(extract(nullptr,0).status == Status::Invalid, "empty input");
        auto f = fixture();
        auto cancel = [](void* p) noexcept { return --*static_cast<int*>(p) <= 0; };
        int calls = 0;
        auto count = [](void* p) noexcept { ++*static_cast<int*>(p); return false; };
        require(extract(f.bytes.data(),f.bytes.size(),count,&calls).status == Status::Found, "count cancellation boundaries");
        for (int i = 1; i <= calls; ++i) {
            int left = i;
            require(extract(f.bytes.data(),f.bytes.size(),cancel,&left).status == Status::Cancelled, "cancel every boundary");
            require(run(f).status == Status::Found, "retry after release");
        }
        std::cout << "PASS embedded JPEG: orientation 1..8, exact bytes, bounds, absent, cancellation " << calls << " boundaries\n";
    } catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
