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
#include "w1_trial.h"

namespace {
constexpr uint64_t kMaxFileBytes = 64ULL * 1024 * 1024;
constexpr uint64_t kMaxRawPixels = 16'000'000;

struct Cancellation {
    JNIEnv* env;
    jobject signal;
    jmethodID isCancelled;
    bool cancelled() const {
        return env->ExceptionCheck() || env->CallBooleanMethod(signal, isCancelled);
    }
};

int progress(void* state, LibRaw_progress, int, int) {
    return static_cast<Cancellation*>(state)->cancelled() ? 1 : 0;
}

void fail(const char* code) { throw std::runtime_error(code); }
void check(int result) {
    if (result == LIBRAW_SUCCESS) return;
    if (result == LIBRAW_CANCELLED_BY_CALLBACK) fail("DNG_CANCELLED");
    if (result == LIBRAW_UNSUFFICIENT_MEMORY || result == LIBRAW_MEMPOOL_OVERFLOW) fail("DNG_MEMORY");
    if (result == LIBRAW_TOO_BIG) fail("DNG_LIMIT");
    fail("DNG_UNSUPPORTED");
}

jobject decodeDng(JNIEnv* env, jint fd, jint longEdge, jobject signal, jint wbMode) {
    if (fd < 0 || longEdge < 1 || longEdge > 1024 || !signal || (wbMode != 0 && wbMode != 1)) fail("DNG_UNSUPPORTED");
    Cancellation cancel{env, signal, env->GetMethodID(env->GetObjectClass(signal), "isCanceled", "()Z")};
    if (!cancel.isCancelled || cancel.cancelled()) fail("DNG_CANCELLED");
    struct stat file{};
    if (fstat(fd, &file) || !S_ISREG(file.st_mode) || file.st_size <= 0) fail("DNG_SOURCE");
    if (static_cast<uint64_t>(file.st_size) > kMaxFileBytes) fail("DNG_LIMIT");
    // A bounded in-memory snapshot avoids SIGBUS if an external provider truncates the file.
    // pread also preserves the provider's descriptor offset; no persistent DNG copy is made.
    std::vector<unsigned char> encoded(static_cast<size_t>(file.st_size));
    for (size_t offset = 0; offset < encoded.size();) {
        if (cancel.cancelled()) fail("DNG_CANCELLED");
        const auto read = pread(fd, encoded.data() + offset, std::min<size_t>(65536, encoded.size() - offset), offset);
        if (read <= 0) fail("DNG_SOURCE");
        offset += static_cast<size_t>(read);
    }

    // Construct after encoded: LibRaw releases all references before the snapshot is destroyed.
    cinematic::W1Metadata metadata(encoded.size());
    LibRaw raw(LIBRAW_OPTIONS_NO_DATAERR_CALLBACK);
    if (wbMode == 1) {
        raw.set_exifparser_handler(cinematic::W1Metadata::capture, &metadata);
        raw.imgdata.params.use_camera_matrix = 1;
    }
    raw.imgdata.rawparams.max_raw_memory_mb = 128;
    raw.set_progress_handler(progress, &cancel);
    const int opened = raw.open_buffer(encoded.data(), encoded.size());
    check(opened); // Preserve LibRaw cancellation/memory/source failures over partial metadata errors.
    std::array<float, 3> requestedWb{};
    int observedTags = -1; // UNKNOWN in the unchanged as-shot route, where no callback is installed.
    if (wbMode == 1) {
        requestedWb = metadata.requestedWb();
        observedTags = metadata.unappliedTags();
    }
    if (cancel.cancelled()) fail("DNG_CANCELLED");
    const auto& identity = raw.imgdata.idata;
    const auto& sizes = raw.imgdata.sizes;
    const int sourceWidth = sizes.width, sourceHeight = sizes.height;
    const uint64_t count = static_cast<uint64_t>(sizes.raw_width) * sizes.raw_height;
    if (!count || count > kMaxRawPixels) fail("DNG_LIMIT");
    // DefaultScale can otherwise amplify an accepted RAW during LibRaw's stretch().
    // First preview route supports square pixels and no Fuji diagonal-layout expansion.
    if (!std::isfinite(sizes.pixel_aspect) || sizes.pixel_aspect != 1.0 || raw.is_fuji_rotated() ||
        !sizes.width || !sizes.height || sizes.width > sizes.raw_width || sizes.height > sizes.raw_height)
        fail("DNG_UNSUPPORTED");
    // First route is single-frame three-colour Bayer DNG; no thumbnails or arbitrary RAW formats.
    if (!identity.dng_version || identity.raw_count != 1 || identity.colors != 3 ||
        identity.filters == 0 || identity.filters == 9 || identity.is_foveon) fail("DNG_UNSUPPORTED");
    if (wbMode == 0) {
        // Missing camera WB may trigger LibRaw's automatic fallback even with use_auto_wb=0.
        for (int c = 0; c < 3; ++c)
            if (!std::isfinite(raw.imgdata.color.cam_mul[c]) || raw.imgdata.color.cam_mul[c] <= .00001f)
                fail("DNG_UNSUPPORTED");
    }

    auto& params = raw.imgdata.params;
    params.half_size = 1;
    params.use_fuji_rotate = 0; // No geometry expansion; EXIF orientation is applied by make_mem_image.
    params.output_color = 1; // LibRaw linear sRGB/D65, not camera RGB or R7 base.
    params.output_bps = 16;
    params.gamm[0] = params.gamm[1] = 1.0;
    params.use_camera_wb = wbMode == 0 ? 1 : 0;
    params.use_auto_wb = 0;
    if (wbMode == 1) {
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
    check(raw.unpack());
    if (raw.error_count()) fail("DNG_UNSUPPORTED");
    if (wbMode == 1) cinematic::validateW1Processing(raw.imgdata.rawdata.color, requestedWb);
    check(raw.dcraw_process());
    if (raw.error_count()) fail("DNG_UNSUPPORTED");
    int error = 0;
    std::unique_ptr<libraw_processed_image_t, decltype(&LibRaw::dcraw_clear_mem)> image(
        raw.dcraw_make_mem_image(&error), LibRaw::dcraw_clear_mem);
    check(error);
    if (!image || image->type != LIBRAW_IMAGE_BITMAP || image->bits != 16 || image->colors != 3 ||
        !image->width || !image->height ||
        image->data_size != static_cast<uint64_t>(image->width) * image->height * 6) fail("DNG_UNSUPPORTED");
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
                          wbMode, requestedWb[0], requestedWb[1], requestedWb[2], observedTags);
}
} // namespace

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
