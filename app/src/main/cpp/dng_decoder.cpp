#include <jni.h>
#include <libraw/libraw.h>
#include <sys/stat.h>
#include <unistd.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <memory>
#include <new>
#include <stdexcept>
#include <vector>
#ifdef CINEMATIC_ENABLE_RAW_PROBE
#include <sstream>
#include <string>
#include "baseline_small.h"
#endif
#include "w1_trial.h"
#include "as_shot_xy.h"
#include "original_raster_sink.h"
#include <fcntl.h>
#ifdef CINEMATIC_ENABLE_DECODE_TIMING
#include <time.h>
#endif

#ifdef CINEMATIC_ENABLE_DECODE_PARITY
#include <sys/system_properties.h>
#include <cerrno>
#include <fstream>
#include <mutex>
#include <optional>
#include <string>
#include <utility>
#endif

namespace {
#ifndef CINEMATIC_DECODE_THREADS
#define CINEMATIC_DECODE_THREADS 4
#endif

void applyDecodeOpenMpSettings(int threadCount) {
    // Control build (ahd-control): the AHD/OpenMP changes are removed, so the
    // configured thread count is recorded but never applied.
    (void)threadCount;
}

#ifdef CINEMATIC_ENABLE_DECODE_PARITY
std::mutex decodeConfigPathMutex;
std::string decodeConfigPath;

struct DecodeThreadOverride {
    int threads = 0;
    int restoreThreads = 0;
};
thread_local DecodeThreadOverride nextDecodeOverride;

struct RestoreDecodeThreads {
    int threads;
    ~RestoreDecodeThreads() { /* Control build: no OpenMP setting to restore. */ }
};

std::string trimConfigField(const std::string& text) {
    const auto start = text.find_first_not_of(" \t\r\n");
    if (start == std::string::npos) return {};
    return text.substr(start, text.find_last_not_of(" \t\r\n") - start + 1);
}

int parseThreadCount(const std::string& text) {
    return text.size() == 1 && text.find_first_not_of("1248") == std::string::npos
        ? text[0] - '0' : 0;
}

cinematic::DecodeConfig resolveDecodeConfig(int forcedThreads) {
    cinematic::DecodeConfig config{CINEMATIC_DECODE_THREADS};
    if (forcedThreads > 0) {
        config.threads = forcedThreads;
        return config;
    }
    char threadsProperty[PROP_VALUE_MAX]{};
    char parityProperty[PROP_VALUE_MAX]{};
    const bool hasThreads = __system_property_get("debug.cinematic.decode.threads", threadsProperty) > 0;
    const bool hasParity = __system_property_get("debug.cinematic.decode.parity", parityProperty) > 0;
    const int propThreads = parseThreadCount(threadsProperty);
    const std::string propParity(parityProperty);
    const bool validParity = propParity == "1" || propParity == "0";
    if (propThreads) {
        config.threads = propThreads;
        config.threadSource = "prop";
    }
    if (validParity) config.parity = propParity == "1";
    config.threadInvalid = hasThreads && !propThreads;
    config.parityInvalid = hasParity && !validParity;
    if (propThreads && validParity) return config;

    std::string path;
    {
        const std::lock_guard<std::mutex> lock(decodeConfigPathMutex);
        path = decodeConfigPath;
    }
    if (path.empty()) return config;

    errno = 0;
    std::ifstream file(path);
    const int openError = errno;
    if (!file.is_open() && openError == ENOENT) return config;
    std::optional<std::string> fileThreads;
    std::optional<std::string> fileParity;
    for (std::string line; std::getline(file, line);) {
        line = trimConfigField(line);
        if (line.empty() || line.front() == '#') continue;
        const auto separator = line.find('=');
        const auto key = trimConfigField(line.substr(0, separator));
        auto* target = key == "threads" ? &fileThreads : key == "parity" ? &fileParity : nullptr;
        if (target) {
            // Empty, missing and duplicate values for a present key are invalid.
            *target = target->has_value() || separator == std::string::npos
                ? std::string{} : trimConfigField(line.substr(separator + 1));
        }
    }
    const bool readFailed = !file.is_open() || file.bad() || !file.eof();
    if (!propThreads) {
        if (readFailed) config.threadInvalid = true;
        else if (fileThreads) {
            const int threads = parseThreadCount(*fileThreads);
            if (threads) {
                config.threads = threads;
                config.threadSource = "file";
            } else config.threadInvalid = true;
        }
    }
    if (!validParity) {
        if (readFailed) config.parityInvalid = true;
        else if (fileParity) {
            if (*fileParity == "on" || *fileParity == "off") config.parity = *fileParity == "on";
            else config.parityInvalid = true;
        }
    }
    return config;
}
#endif

constexpr uint64_t kMaxFileBytes = 64ULL * 1024 * 1024;
constexpr uint64_t kMaxRawPixels = 16'000'000;

#ifdef CINEMATIC_ENABLE_DECODE_TIMING
int64_t monotonicNowNs() {
    struct timespec ts{};
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return -1;
    if (ts.tv_sec < 0 || ts.tv_nsec < 0) return -1;
    return static_cast<int64_t>(ts.tv_sec) * 1'000'000'000LL + static_cast<int64_t>(ts.tv_nsec);
}

int64_t elapsedNs(int64_t start, int64_t end) {
    return (start >= 0 && end >= start) ? (end - start) : -1;
}

struct DecodeTimingMarks {
    int64_t preadStart = -1;
    int64_t preadEnd = -1;
    int64_t unpackStart = -1;
    int64_t processStart = -1;
    int64_t scaleColorsStart = -1;
    int64_t scaleColorsEnd = -1;
    int64_t preInterpStart = -1;
    int64_t preInterpEnd = -1;
    int64_t convertRgbStart = -1;
    int64_t convertRgbEnd = -1;
    int64_t makeImageStart = -1;
    int64_t makeImageEnd = -1;
    int64_t writeStart = -1;
    int64_t writeEnd = -1;
};
#endif

struct Cancellation {
    JNIEnv* env;
    jobject signal;
    jmethodID isCancelled;
#ifdef CINEMATIC_ENABLE_DECODE_TIMING
    DecodeTimingMarks* timing = nullptr;
#endif
    bool cancelled() const {
        return env->ExceptionCheck() || env->CallBooleanMethod(signal, isCancelled);
    }
};

#ifdef CINEMATIC_ENABLE_DECODE_TIMING
int progress(void* state, LibRaw_progress stage, int iteration, int) {
    auto* cancel = static_cast<Cancellation*>(state);
    if (auto* marks = cancel->timing) {
        int64_t* slot = nullptr;
        if (stage == LIBRAW_PROGRESS_SCALE_COLORS) {
            if (iteration == 0) slot = &marks->scaleColorsStart;
            else if (iteration == 1) slot = &marks->scaleColorsEnd;
        } else if (stage == LIBRAW_PROGRESS_PRE_INTERPOLATE) {
            if (iteration == 0) slot = &marks->preInterpStart;
            else if (iteration == 1) slot = &marks->preInterpEnd;
        } else if (stage == LIBRAW_PROGRESS_CONVERT_RGB) {
            if (iteration == 0) slot = &marks->convertRgbStart;
            else if (iteration == 1) slot = &marks->convertRgbEnd;
        }
        if (slot && *slot < 0) *slot = monotonicNowNs();
    }
    return cancel->cancelled() ? 1 : 0;
}
#else
int progress(void* state, LibRaw_progress, int, int) {
    return static_cast<Cancellation*>(state)->cancelled() ? 1 : 0;
}
#endif

void fail(const char* code) { throw std::runtime_error(code); }
void check(int result) {
    if (result == LIBRAW_SUCCESS) return;
    if (result == LIBRAW_CANCELLED_BY_CALLBACK) fail("DNG_CANCELLED");
    if (result == LIBRAW_UNSUFFICIENT_MEMORY || result == LIBRAW_MEMPOOL_OVERFLOW) fail("DNG_MEMORY");
    if (result == LIBRAW_TOO_BIG) fail("DNG_LIMIT");
    fail("DNG_UNSUPPORTED");
}

#ifdef CINEMATIC_ENABLE_RAW_PROBE
// Compiled only into the separate diagnostic APK. No production admission or limit changes.
struct ProbeReport {
    JNIEnv* env;
    jobject observer;
    jmethodID onStage;
    std::string json;
    cinematic::BaselineSmall* baseline = nullptr;
    void stage(const char* name, const Cancellation& cancel) const {
        jstring text = env->NewStringUTF(name);
        if (!text) fail("DNG_MEMORY");
        env->CallVoidMethod(observer, onStage, text);
        env->DeleteLocalRef(text);
        if (cancel.cancelled()) fail("DNG_CANCELLED");
    }
};
#endif

jobject decodeDng(JNIEnv* env, jint fd, jint longEdge, jobject signal, jint wbMode
#ifdef CINEMATIC_ENABLE_RAW_PROBE
                 , ProbeReport* probe = nullptr
#endif
                 , cinematic::OriginalRasterSink* original = nullptr, bool geometryOnly = false
#ifdef CINEMATIC_ENABLE_DECODE_PARITY
                 , int forcedThreads = 0
#endif
) {
    if (fd < 0 || longEdge < 1 || longEdge > 1024 || !signal || (wbMode != 0 && wbMode != 1)) fail("DNG_UNSUPPORTED");
    if (geometryOnly && !original) fail("DNG_UNSUPPORTED");
#ifdef CINEMATIC_ENABLE_DECODE_PARITY
    auto decodeConfig = geometryOnly ? cinematic::DecodeConfig{CINEMATIC_DECODE_THREADS}
                                     : resolveDecodeConfig(forcedThreads);
#elif defined(CINEMATIC_ENABLE_DECODE_TIMING)
    cinematic::DecodeConfig decodeConfig{CINEMATIC_DECODE_THREADS};
#endif
#ifdef CINEMATIC_ENABLE_DECODE_TIMING
    DecodeTimingMarks timingMarks{};
    const bool recordTiming = (original != nullptr && !original->isHalfPreview() && !geometryOnly);
    Cancellation cancel{env, signal, env->GetMethodID(env->GetObjectClass(signal), "isCanceled", "()Z"),
                        recordTiming ? &timingMarks : nullptr};
#else
    Cancellation cancel{env, signal, env->GetMethodID(env->GetObjectClass(signal), "isCanceled", "()Z")};
#endif
    if (!cancel.isCancelled || cancel.cancelled()) fail("DNG_CANCELLED");
#ifdef CINEMATIC_ENABLE_RAW_PROBE
    if (probe) probe->stage("snapshot", cancel);
#endif
#ifdef CINEMATIC_ENABLE_DECODE_TIMING
    if (recordTiming) timingMarks.preadStart = monotonicNowNs();
#endif
    struct stat file{};
    if (fstat(fd, &file) || !S_ISREG(file.st_mode) || file.st_size <= 0) fail("DNG_SOURCE");
    if (static_cast<uint64_t>(file.st_size) > kMaxFileBytes) fail("DNG_LIMIT");
#ifdef CINEMATIC_ENABLE_RAW_PROBE
    if (probe && probe->baseline && static_cast<uint64_t>(file.st_size) > probe->baseline->byteLimit()) fail("DNG_LIMIT");
#endif
    // A bounded in-memory snapshot avoids SIGBUS if an external provider truncates the file.
    // pread also preserves the provider's descriptor offset; no persistent DNG copy is made.
    std::vector<unsigned char> encoded(static_cast<size_t>(file.st_size));
    for (size_t offset = 0; offset < encoded.size();) {
        if (cancel.cancelled()) fail("DNG_CANCELLED");
        const auto read = pread(fd, encoded.data() + offset, std::min<size_t>(65536, encoded.size() - offset), offset);
        if (read <= 0) fail("DNG_SOURCE");
        offset += static_cast<size_t>(read);
    }
#ifdef CINEMATIC_ENABLE_DECODE_TIMING
    if (recordTiming) timingMarks.preadEnd = monotonicNowNs();
#endif

#ifdef CINEMATIC_ENABLE_RAW_PROBE
    if (probe && probe->baseline) probe->baseline->parse(encoded);
#endif
    // Construct after encoded: LibRaw releases all references before the snapshot is destroyed.
    cinematic::W1Metadata metadata(encoded.size());
    cinematic::AsShotXyMetadata asShotMetadata(encoded.size());
    LibRaw raw(LIBRAW_OPTIONS_NO_DATAERR_CALLBACK);
    if (wbMode == 1) {
        raw.set_exifparser_handler(cinematic::W1Metadata::capture, &metadata);
        raw.imgdata.params.use_camera_matrix = 1;
    } else {
        raw.set_exifparser_handler(cinematic::AsShotXyMetadata::capture, &asShotMetadata);
    }
    raw.imgdata.rawparams.max_raw_memory_mb = 128;
    if (original) raw.imgdata.rawparams.max_raw_memory_mb = 512; // Separate, fixed original-trial policy only.
#ifdef CINEMATIC_ENABLE_RAW_PROBE
    // A fixed diagnostic ceiling, not increased automatically after a failure.
    if (probe) { raw.imgdata.rawparams.max_raw_memory_mb = probe->baseline && !probe->baseline->isLarge() ? 128 : 512; probe->stage("open", cancel); }
#endif
    raw.set_progress_handler(progress, &cancel);
    const int opened = raw.open_buffer(encoded.data(), encoded.size());
    check(opened); // Preserve LibRaw cancellation/memory/source failures over partial metadata errors.
    if (original) original->parse(encoded, raw);
#ifdef CINEMATIC_ENABLE_RAW_PROBE
    if (probe && probe->baseline) probe->baseline->validateOpen(raw);
#endif
    std::array<float, 3> requestedWb{};
    int observedTags = -1; // UNKNOWN unless a metadata-derived user WB is actually used.
    bool whiteXyCompat = false;
    if (wbMode == 1) {
        requestedWb = metadata.requestedWb();
        observedTags = metadata.unappliedTags();
    }
    if (cancel.cancelled()) fail("DNG_CANCELLED");
    const auto& identity = raw.imgdata.idata;
    const auto& sizes = raw.imgdata.sizes;
    const int sourceWidth = sizes.width, sourceHeight = sizes.height;
    const uint64_t count = static_cast<uint64_t>(sizes.raw_width) * sizes.raw_height;
    uint64_t pixelLimit = original ? 32'000'000 : kMaxRawPixels;
#ifdef CINEMATIC_ENABLE_RAW_PROBE
    if (probe) pixelLimit = probe->baseline ? probe->baseline->pixelLimit() : 24'000'000;
#endif
    if (!count || count > pixelLimit) fail("DNG_LIMIT");
    // DefaultScale can otherwise amplify an accepted RAW during LibRaw's stretch().
    // First preview route supports square pixels and no Fuji diagonal-layout expansion.
    if (!std::isfinite(sizes.pixel_aspect) || sizes.pixel_aspect != 1.0 || raw.is_fuji_rotated() ||
        !sizes.width || !sizes.height || sizes.width > sizes.raw_width || sizes.height > sizes.raw_height)
        fail("DNG_UNSUPPORTED");
    // First route is single-frame three-colour Bayer DNG; no thumbnails or arbitrary RAW formats.
    if (!identity.dng_version || identity.raw_count != 1 || identity.colors != 3 ||
        identity.filters == 0 || identity.filters == 9 || identity.is_foveon) fail("DNG_UNSUPPORTED");
    if (geometryOnly) {
        // Reuse the exact original TIFF/LibRaw geometry gates; no unpack, processing or image allocation.
        // applyCrop and actual image dimensions remain mandatory later in the full decode path.
        if (cancel.cancelled()) fail("DNG_CANCELLED");
        return nullptr;
    }
    if (wbMode == 0) {
        // Missing camera WB may trigger LibRaw's automatic fallback even with use_auto_wb=0.
        for (int c = 0; c < 3; ++c)
            if (!std::isfinite(raw.imgdata.color.cam_mul[c]) || raw.imgdata.color.cam_mul[c] <= .00001f)
                whiteXyCompat = true;
        if (whiteXyCompat) {
            requestedWb = asShotMetadata.requestedWb(raw.imgdata.color);
            observedTags = asShotMetadata.unappliedTags();
        }
    }

    auto& params = raw.imgdata.params;
    params.half_size = 1;
    if (original) params.half_size = original->isHalfPreview() ? 1 : 0;
#ifdef CINEMATIC_ENABLE_RAW_PROBE
    if (probe) params.half_size = 0;
#endif
    params.use_fuji_rotate = 0; // No geometry expansion; EXIF orientation is applied by make_mem_image.
    params.output_color = 1; // LibRaw linear sRGB/D65, not camera RGB or R7 base.
    params.output_bps = 16;
    params.gamm[0] = params.gamm[1] = 1.0;
    params.use_camera_wb = wbMode == 0 && !whiteXyCompat ? 1 : 0;
    params.use_auto_wb = 0;
    if (wbMode == 1 || whiteXyCompat) {
        params.user_mul[0] = requestedWb[0];
        params.user_mul[1] = 1.f;
        params.user_mul[2] = requestedWb[2];
        params.user_mul[3] = 1.f;
    }
    params.no_auto_bright = 1;
    params.no_auto_scale = 0;
    params.bright = 1.f;
    params.exp_shift = 1.f;
    params.exp_preser = 0.f;
    params.adjust_maximum_thr = 0.f;
    params.highlight = 0;
    params.fbdd_noiserd = 0;
    params.threshold = 0.f;
    params.med_passes = 0;
#ifdef CINEMATIC_ENABLE_RAW_PROBE
    if (probe) probe->stage("unpack", cancel);
#endif
#ifdef CINEMATIC_ENABLE_DECODE_TIMING
    if (recordTiming) timingMarks.unpackStart = monotonicNowNs();
#endif
    check(raw.unpack());
    if (raw.error_count()) fail("DNG_UNSUPPORTED");
    if (original) original->applyCrop(raw);
#ifdef CINEMATIC_ENABLE_RAW_PROBE
    if (probe && probe->baseline) probe->baseline->applyCrop(raw);
#endif
    if (wbMode == 1) cinematic::validateW1Processing(raw.imgdata.rawdata.color, requestedWb);
    if (whiteXyCompat) {
        // raw2image_start restores this exact colour state; attest the matrix that will be used.
        asShotMetadata.requestedWb(raw.imgdata.rawdata.color);
        try { cinematic::validateW1Processing(raw.imgdata.rawdata.color, requestedWb); }
        catch (const std::runtime_error&) { fail("DNG_UNSUPPORTED"); }
    }
#if defined(CINEMATIC_ENABLE_DECODE_TIMING) || defined(CINEMATIC_ENABLE_DECODE_PARITY)
    applyDecodeOpenMpSettings(decodeConfig.threads);
    if (original && !original->isHalfPreview()) {
        // Control build: no OpenMP runtime, so blocktime stays at its default
        // and is recorded as UNAVAILABLE.
        original->setDecodeConfig(decodeConfig);
    }
#else
    applyDecodeOpenMpSettings(CINEMATIC_DECODE_THREADS);
#endif
#ifdef CINEMATIC_ENABLE_RAW_PROBE
    if (probe) probe->stage("process", cancel);
#endif
#ifdef CINEMATIC_ENABLE_DECODE_TIMING
    if (recordTiming) timingMarks.processStart = monotonicNowNs();
#endif
    check(raw.dcraw_process());
    if (raw.error_count()) fail("DNG_UNSUPPORTED");
    if (original) original->validateProcessedGeometry(raw);
    int error = 0;
#ifdef CINEMATIC_ENABLE_RAW_PROBE
    if (probe) probe->stage("make_image", cancel);
#endif
#ifdef CINEMATIC_ENABLE_DECODE_TIMING
    if (recordTiming) timingMarks.makeImageStart = monotonicNowNs();
#endif
    std::unique_ptr<libraw_processed_image_t, decltype(&LibRaw::dcraw_clear_mem)> image(
        raw.dcraw_make_mem_image(&error), LibRaw::dcraw_clear_mem);
#ifdef CINEMATIC_ENABLE_DECODE_TIMING
    if (recordTiming) timingMarks.makeImageEnd = monotonicNowNs();
#endif
    check(error);
    if (!image || image->type != LIBRAW_IMAGE_BITMAP || image->bits != 16 || image->colors != 3 ||
        !image->width || !image->height ||
        image->data_size != static_cast<uint64_t>(image->width) * image->height * 6) fail("DNG_UNSUPPORTED");
    if (original) {
        // File output keeps full RGB outside Java. Free RAW/snapshot before row writes.
#ifdef CINEMATIC_ENABLE_DECODE_TIMING
        if (recordTiming) timingMarks.writeStart = monotonicNowNs();
#endif
        raw.recycle();
        std::vector<unsigned char>().swap(encoded);
        original->write(*image, [&] { if (cancel.cancelled()) fail("DNG_CANCELLED"); });
        image.reset();
#ifdef CINEMATIC_ENABLE_DECODE_TIMING
        if (recordTiming) {
            timingMarks.writeEnd = monotonicNowNs();
            original->setDecodeTiming(cinematic::DecodeTimingNs{
                elapsedNs(timingMarks.preadStart, timingMarks.preadEnd),
                elapsedNs(timingMarks.preadEnd, timingMarks.unpackStart),
                elapsedNs(timingMarks.unpackStart, timingMarks.processStart),
                elapsedNs(timingMarks.processStart, timingMarks.scaleColorsStart),
                elapsedNs(timingMarks.scaleColorsStart, timingMarks.scaleColorsEnd),
                elapsedNs(timingMarks.preInterpStart, timingMarks.preInterpEnd),
                elapsedNs(timingMarks.preInterpEnd, timingMarks.convertRgbStart),
                elapsedNs(timingMarks.convertRgbStart, timingMarks.convertRgbEnd),
                elapsedNs(timingMarks.makeImageStart, timingMarks.makeImageEnd),
                elapsedNs(timingMarks.writeStart, timingMarks.writeEnd),
            });
        }
#endif
        return nullptr;
    }
#ifdef CINEMATIC_ENABLE_RAW_PROBE
    if (probe && probe->baseline) {
        raw.recycle();
        std::vector<unsigned char>().swap(encoded);
        jobject result = probe->baseline->frame(env, probe->observer, *image, [&] { probe->stage("pack_row", cancel); });
        image.reset();
        if (!result || env->ExceptionCheck()) return nullptr;
        probe->stage("released", cancel);
        return result;
    }
    if (probe) {
        if (static_cast<uint64_t>(image->width) * image->height > pixelLimit) fail("DNG_LIMIT");
        probe->stage("summarize", cancel);
        const auto* values = reinterpret_cast<const uint16_t*>(image->data);
        uint64_t digest = 14695981039346656037ULL;
        for (size_t i = 0; i < image->data_size; ++i) {
            if ((i & 65535) == 0 && cancel.cancelled()) fail("DNG_CANCELLED");
            digest = (digest ^ image->data[i]) * 1099511628211ULL;
        }
        std::ostringstream result;
        result << "{\"sourceWidth\":" << sourceWidth << ",\"sourceHeight\":" << sourceHeight
               << ",\"width\":" << image->width << ",\"height\":" << image->height
               << ",\"halfSize\":0,\"bits\":16,\"colors\":3,\"dataBytes\":" << image->data_size
               << ",\"checksumKind\":\"fnv1a64-diagnostic-not-security\",\"checksum\":\""
               << std::hex << digest << std::dec << "\",\"samples\":[";
        bool first = true;
        for (unsigned y : {0U, static_cast<unsigned>(image->height / 2), static_cast<unsigned>(image->height - 1)})
            for (unsigned x : {0U, static_cast<unsigned>(image->width / 2), static_cast<unsigned>(image->width - 1)}) {
                if (!first) result << ',';
                first = false;
                const size_t i = (static_cast<size_t>(y) * image->width + x) * 3;
                result << '[' << x << ',' << y << ',' << values[i] << ',' << values[i + 1] << ',' << values[i + 2] << ']';
            }
        result << "]}";
        // No full-sized Java output; free every native image/snapshot before the final measurement.
        image.reset();
        raw.recycle();
        std::vector<unsigned char>().swap(encoded);
        probe->json = result.str();
        probe->stage("released", cancel);
        return nullptr;
    }
#endif


    const double scale = std::min(1.0, static_cast<double>(longEdge) / std::max(image->width, image->height));
    const int width = std::max(1, static_cast<int>(std::floor(image->width * scale)));
    const int height = std::max(1, static_cast<int>(std::floor(image->height * scale)));
    const auto* samples = reinterpret_cast<const uint16_t*>(image->data);
    std::vector<float> pixels(static_cast<size_t>(width) * height * 3);
    // Area average in linear light, including fractional edge pixels. Never upscale.
    for (int y = 0; y < height; ++y) {
        if (cancel.cancelled()) fail("DNG_CANCELLED");
        const double top = static_cast<double>(y) * image->height / height;
        const double bottom = static_cast<double>(y + 1) * image->height / height;
        for (int x = 0; x < width; ++x) {
            const double left = static_cast<double>(x) * image->width / width;
            const double right = static_cast<double>(x + 1) * image->width / width;
            double sum[3]{};
            for (int sy = static_cast<int>(top); sy < std::ceil(bottom); ++sy) {
                const double wy = std::min(bottom, sy + 1.0) - std::max(top, static_cast<double>(sy));
                for (int sx = static_cast<int>(left); sx < std::ceil(right); ++sx) {
                    const double weight = wy * (std::min(right, sx + 1.0) - std::max(left, static_cast<double>(sx)));
                    const size_t offset = (static_cast<size_t>(sy) * image->width + sx) * 3;
                    for (int c = 0; c < 3; ++c) sum[c] += samples[offset + c] * weight;
                }
            }
            for (int c = 0; c < 3; ++c) pixels[(static_cast<size_t>(y) * width + x) * 3 + c] =
                static_cast<float>(sum[c] / ((right - left) * (bottom - top) * 65535.0));
        }
    }
    // Release RAW/native intermediates before allocating Java's packed RGB array.
    image.reset();
    raw.recycle();
    std::vector<unsigned char>().swap(encoded);
    if (cancel.cancelled()) fail("DNG_CANCELLED");
    jfloatArray output = env->NewFloatArray(static_cast<jsize>(pixels.size()));
    if (!output) return nullptr;
    env->SetFloatArrayRegion(output, 0, static_cast<jsize>(pixels.size()), pixels.data());
    if (env->ExceptionCheck()) return nullptr;
    jclass result = env->FindClass("dev/cinematic/p0/raw/NativeDngPixels");
    if (!result) return nullptr;
    jmethodID constructor = env->GetMethodID(result, "<init>", "(IIII[FIFFFI)V");
    if (!constructor) return nullptr;
    return env->NewObject(result, constructor, width, height, sourceWidth, sourceHeight, output,
                          whiteXyCompat ? 2 : wbMode, requestedWb[0], requestedWb[1], requestedWb[2], observedTags);
}
} // namespace

#ifdef CINEMATIC_ENABLE_RAW_PROBE
extern "C" JNIEXPORT jobject JNICALL
Java_dev_cinematic_rawprobe_NativeBaselineProbe_decodeLarge(JNIEnv* env, jobject, jint fd, jobject signal, jobject observer) {
    try {
        if (!observer) fail("DNG_UNSUPPORTED");
        jmethodID onStage = env->GetMethodID(env->GetObjectClass(observer), "onStage", "(Ljava/lang/String;)V");
        if (!onStage) return nullptr;
        cinematic::BaselineSmall baseline(true);
        ProbeReport report{env, observer, onStage, {}, &baseline};
        return decodeDng(env, fd, 1024, signal, 0, &report);
    } catch (const std::bad_alloc&) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), "DNG_MEMORY");
    } catch (const std::exception& error) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), error.what());
    } catch (...) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), "DNG_UNSUPPORTED");
    }
    return nullptr;
}

extern "C" JNIEXPORT jobject JNICALL
Java_dev_cinematic_rawprobe_NativeBaselineProbe_decode(JNIEnv* env, jobject, jint fd, jobject signal, jobject observer) {
    try {
        if (!observer) fail("DNG_UNSUPPORTED");
        jmethodID onStage = env->GetMethodID(env->GetObjectClass(observer), "onStage", "(Ljava/lang/String;)V");
        if (!onStage) return nullptr;
        cinematic::BaselineSmall baseline;
        ProbeReport report{env, observer, onStage, {}, &baseline};
        return decodeDng(env, fd, 1024, signal, 0, &report);
    } catch (const std::bad_alloc&) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), "DNG_MEMORY");
    } catch (const std::exception& error) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), error.what());
    } catch (...) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), "DNG_UNSUPPORTED");
    }
    return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_cinematic_rawprobe_NativeFullResolutionProbe_inspect(JNIEnv* env, jobject, jint fd,
                                                            jobject signal, jobject observer) {
    try {
        if (!observer) fail("DNG_UNSUPPORTED");
        jmethodID onStage = env->GetMethodID(env->GetObjectClass(observer), "onStage", "(Ljava/lang/String;)V");
        if (!onStage) return nullptr;
        ProbeReport report{env, observer, onStage, {}};
        decodeDng(env, fd, 1024, signal, 0, &report);
        return env->NewStringUTF(report.json.c_str());
    } catch (const std::bad_alloc&) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), "DNG_MEMORY");
    } catch (const std::exception& error) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), error.what());
    } catch (...) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), "DNG_UNSUPPORTED");
    }
    return nullptr;
}
#else
extern "C" JNIEXPORT jobject JNICALL
Java_dev_cinematic_p0_raw_NativeDngDecoder_decode(JNIEnv* env, jobject, jint fd, jint longEdge, jobject signal, jint wbMode) {
    try { return decodeDng(env, fd, longEdge, signal, wbMode); }
    catch (const std::bad_alloc&) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), "DNG_MEMORY");
    } catch (const std::exception& error) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), error.what());
    } catch (...) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), "DNG_UNSUPPORTED");
    }
    return nullptr;
}
#endif

#ifndef CINEMATIC_ENABLE_RAW_PROBE
extern "C" JNIEXPORT jintArray JNICALL
Java_dev_cinematic_p0_raw_original_NativeOriginalRaster_preflight24(JNIEnv* env, jobject, jint fd, jobject signal) {
    try {
        cinematic::OriginalRasterSink sink(-1); // Metadata-only: no output descriptor or raster owner.
        decodeDng(env, fd, 1024, signal, 0, &sink, true);
        if (env->ExceptionCheck()) return nullptr;
        const auto geometry = sink.preflightGeometry();
        jintArray result = env->NewIntArray(static_cast<jsize>(geometry.size()));
        if (!result) return nullptr;
        env->SetIntArrayRegion(result, 0, static_cast<jsize>(geometry.size()), geometry.data());
        return env->ExceptionCheck() ? nullptr : result;
    } catch (const std::bad_alloc&) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), "DNG_MEMORY");
    } catch (const std::exception& error) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), error.what());
    } catch (...) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), "DNG_UNSUPPORTED");
    }
    return nullptr;
}

namespace {
jstring decodeRaster(JNIEnv* env, jint fd, jint outputFd, jobject signal, cinematic::OriginalRasterSink::Output mode) {
#ifdef CINEMATIC_ENABLE_DECODE_PARITY
    const auto forced = mode == cinematic::OriginalRasterSink::Output::Full
        ? std::exchange(nextDecodeOverride, {}) : DecodeThreadOverride{};
    const RestoreDecodeThreads restore{forced.restoreThreads};
#endif
    try {
        struct stat source{}, target{};
        const int flags = fcntl(outputFd, F_GETFL);
        if (fd < 0 || outputFd < 0 || flags < 0 || (flags & O_ACCMODE) == O_RDONLY ||
            fstat(fd, &source) || fstat(outputFd, &target) || !S_ISREG(target.st_mode) || target.st_size != 0 ||
            (source.st_dev == target.st_dev && source.st_ino == target.st_ino)) fail("ORIGINAL_RASTER_TARGET");
        cinematic::OriginalRasterSink sink(outputFd, mode);
        decodeDng(env, fd, 1024, signal, 0, &sink, false
#ifdef CINEMATIC_ENABLE_DECODE_PARITY
                  , forced.threads
#endif
        );
        if (env->ExceptionCheck()) return nullptr;
        return env->NewStringUTF(sink.report().c_str());
    } catch (const std::bad_alloc&) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), "DNG_MEMORY");
    } catch (const std::exception& error) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), error.what());
    } catch (...) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), "DNG_UNSUPPORTED");
    }
    return nullptr;
}
} // namespace

#ifdef CINEMATIC_ENABLE_DECODE_PARITY
extern "C" JNIEXPORT void JNICALL
Java_dev_cinematic_p0_raw_original_NativeOriginalRaster_registerDecodeConfigPath(
    JNIEnv* env, jobject, jbyteArray path
) {
    try {
        std::string value(path ? env->GetArrayLength(path) : 0, '\0');
        if (!value.empty()) {
            env->GetByteArrayRegion(path, 0, static_cast<jsize>(value.size()), reinterpret_cast<jbyte*>(value.data()));
            if (env->ExceptionCheck()) return;
        }
        const std::lock_guard<std::mutex> lock(decodeConfigPathMutex);
        decodeConfigPath = std::move(value);
    } catch (...) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), "DNG_DECODE_CONFIG");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cinematic_p0_raw_original_NativeOriginalRaster_forceNextDecodeThreads(
    JNIEnv*, jobject, jint threads, jint restoreThreads
) {
    nextDecodeOverride = threads > 0 && restoreThreads > 0
        ? DecodeThreadOverride{threads, restoreThreads} : DecodeThreadOverride{};
}
#endif

extern "C" JNIEXPORT jstring JNICALL
Java_dev_cinematic_p0_raw_original_NativeOriginalRaster_decode24(JNIEnv* env, jobject, jint fd, jint outputFd, jobject signal) {
    return decodeRaster(env, fd, outputFd, signal, cinematic::OriginalRasterSink::Output::Full);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_cinematic_p0_raw_original_NativeOriginalRaster_decodeHalfPreview(JNIEnv* env, jobject, jint fd, jint outputFd, jobject signal) {
    return decodeRaster(env, fd, outputFd, signal, cinematic::OriginalRasterSink::Output::HalfPreview);
}
#endif
