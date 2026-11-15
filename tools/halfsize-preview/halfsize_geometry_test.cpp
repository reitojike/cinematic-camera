// Synthetic, host-only geometry exploration. Never used by the Android product.
#include <libraw/libraw.h>
#include "original_raster_sink.h"
#include <algorithm>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <vector>
#include <cstring>
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

Bytes fixture(unsigned originX, unsigned originY, unsigned w, unsigned h, unsigned orientation,
              unsigned pattern, bool isolated) {
    static const unsigned cfas[4][4]{{0,1,1,2},{1,0,2,1},{1,2,0,1},{2,1,1,0}};
    const unsigned ox = isolated ? 4 + originX : 0, oy = isolated ? 6 + originY : 0;
    const unsigned width = isolated ? w : 80, height = isolated ? h : 64;
    auto color = [&](unsigned x, unsigned y) { return cfas[pattern][(y % 2)*2 + x % 2]; };
    Bytes b{'I','I',42,0,8,0,0,0};
    const unsigned id = ifd(b, {l(256,width),l(257,height),s(258,16),s(259,1),s(262,32803),l(273,0),
        s(274,orientation),s(277,1),l(278,height),l(279,width*height*2),
        {33421,3,2,numbers({2,2},2)},
        {33422,1,4,{uint8_t(color(ox,oy)),uint8_t(color(ox+1,oy)),uint8_t(color(ox,oy+1)),uint8_t(color(ox+1,oy+1))}},
        {50706,1,4,{1,4,0,0}},{50713,3,2,numbers({1,1},2)},l(50714,64),l(50717,4095),
        {50721,10,9,numbers({1,1,0,1,0,1,0,1,1,1,0,1,0,1,0,1,1,1},4)},
        {50728,5,3,numbers({1,1,1,1,1,1},4)},s(50778,21),
        {50719,4,2,numbers({isolated?0:originX,isolated?0:originY},4)},
        {50720,4,2,numbers({w,h},4)},
        {50829,4,4,numbers({isolated?0u:6u,isolated?0u:4u,isolated?height:58u,isolated?width:76u},4)}});
    unsigned offset = unsigned(b.size()); patch(b,id,273,offset); b.resize(offset + width*height*2);
    for (unsigned y=0;y<height;++y) for (unsigned x=0;x<width;++x) {
        const unsigned levels[]{700,1700,2700};
        put16(b,offset+2*(y*width+x),levels[color(x+ox,y+oy)]+((x+ox)*7+(y+oy)*11)%220);
    }
    b.resize(std::max<size_t>(4096,b.size())); return b;
}
struct Frame { unsigned width, height; std::vector<uint16_t> rgb; };
Frame decodeFrame(const Bytes& bytes, bool half) {
    LibRaw raw(LIBRAW_OPTIONS_NO_DATAERR_CALLBACK);
    auto check = [](int code) { if (code) throw std::runtime_error(libraw_strerror(code)); };
    check(raw.open_buffer(const_cast<uint8_t*>(bytes.data()),bytes.size()));
    cinematic::OriginalRasterSink geometry(-1);
    geometry.parse(bytes,raw);
    auto& p = raw.imgdata.params;
    p.half_size=half?1:0; p.use_fuji_rotate=0; p.output_color=1; p.output_bps=16;
    p.gamm[0]=p.gamm[1]=1.; p.use_camera_wb=1; p.use_auto_wb=0;
    p.no_auto_bright=1; p.no_auto_scale=0; p.bright=1; p.exp_shift=1; p.exp_preser=0;
    p.adjust_maximum_thr=0; p.highlight=0; p.fbdd_noiserd=0; p.threshold=0; p.med_passes=0;
    check(raw.unpack()); geometry.applyCrop(raw); check(raw.dcraw_process());
    if (!half) geometry.validateProcessedGeometry(raw);
    else {
        // Exploratory HALF postcondition only. The full-size product validator must not be weakened.
        const auto expected=geometry.preflightGeometry(); const auto& s=raw.imgdata.sizes;
        static constexpr int flips[]{0,0,1,3,2,4,6,7,5};
        require(s.left_margin==expected[8] && s.top_margin==expected[9],"half crop origin");
        require(s.width==(expected[3]+1)/2 && s.height==(expected[4]+1)/2,"half processed extent");
        require(s.flip==flips[expected[5]],"half orientation metadata");
    }
    int error=0;
    std::unique_ptr<libraw_processed_image_t, decltype(&LibRaw::dcraw_clear_mem)> image(
        raw.dcraw_make_mem_image(&error), LibRaw::dcraw_clear_mem);
    check(error); require(bool(image),"image");
    require(!raw.error_count(),"LibRaw data error");
    require(image->bits==16 && image->colors==3,"RGB16 output");
    Frame result{image->width,image->height,std::vector<uint16_t>(image->data_size/2)};
    std::memcpy(result.rgb.data(),image->data,image->data_size); return result;
}
}
int main() {
    try {
        unsigned cases=0;
        for (unsigned pattern=0;pattern<4;++pattern) for(unsigned orientation=1;orientation<=8;++orientation)
        for (unsigned ox : {4u,5u}) for (unsigned oy : {4u,5u})
        for (unsigned oddW : {0u,1u}) for (unsigned oddH : {0u,1u}) {
            const unsigned w=48+oddW,h=32+oddH;
            auto input=fixture(ox,oy,w,h,orientation,pattern,false); const auto before=input;
            auto half=decodeFrame(input,true); auto full=decodeFrame(input,false);
            require(input==before,"snapshot unchanged");
            const unsigned rw=orientation>=5?h:w,rh=orientation>=5?w:h;
            require(full.width==rw && full.height==rh,"full dimensions unchanged");
            require(half.width==(rw+1)/2 && half.height==(rh+1)/2,"half dimensions match ceil crop");
            auto isolated=decodeFrame(fixture(ox,oy,w,h,orientation,pattern,true),true);
            require(half.width==isolated.width && half.height==isolated.height,"isolated dimensions");
            require(half.rgb==isolated.rgb,"isolated crop pixel oracle");
            auto upright=decodeFrame(fixture(ox,oy,w,h,1,pattern,false),true);
            // Independent EXIF orientation oracle; no LibRaw flip helper used.
            for(unsigned y=0;y<half.height;++y) for(unsigned x=0;x<half.width;++x) {
                unsigned sx=x,sy=y;
                switch(orientation) {
                    case 2: sx=upright.width-1-x; break;
                    case 3: sx=upright.width-1-x; sy=upright.height-1-y; break;
                    case 4: sy=upright.height-1-y; break;
                    case 5: sx=y; sy=x; break;
                    case 6: sx=y; sy=upright.height-1-x; break;
                    case 7: sx=upright.width-1-y; sy=upright.height-1-x; break;
                    case 8: sx=upright.width-1-y; sy=x; break;
                }
                for(unsigned c=0;c<3;++c) require(half.rgb[(y*half.width+x)*3+c]==upright.rgb[(sy*upright.width+sx)*3+c],"orientation pixel oracle");
            }
            ++cases;
        }
        std::cout << "PASS " << cases << " half crop/CFA/orientation cases; full geometry unchanged\n";
        return 0;
    } catch(const std::exception& e) { std::cerr << "FAIL " << e.what() << '\n'; return 1; }
}
