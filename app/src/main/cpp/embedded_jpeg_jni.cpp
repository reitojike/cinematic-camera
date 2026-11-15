#include <jni.h>
#include <sys/stat.h>
#include <unistd.h>
#include <fcntl.h>
#include <algorithm>
#include <array>
#include <cerrno>
#include <new>
#include <stdexcept>
#include <vector>
#include "embedded_jpeg.h"

namespace {
void fail(const char* code) { throw std::runtime_error(code); }
struct Cancellation {
    JNIEnv* env;
    jobject signal;
    jmethodID query;
    bool stopped() const noexcept {
        return env->ExceptionCheck() || env->CallBooleanMethod(signal, query) || env->ExceptionCheck();
    }
    void check() const { if (stopped()) fail("DNG_CANCELLED"); }
};
bool stopped(void* context) noexcept { return static_cast<Cancellation*>(context)->stopped(); }

// Hash precisely the owned bytes passed to LibRaw. No second FD read or full Java copy.
void verify(JNIEnv* env, const std::vector<uint8_t>& snapshot, jbyteArray expected,
            const Cancellation& cancel) {
    if (!expected || env->GetArrayLength(expected) != 32) fail("DNG_SOURCE");
    const jclass type = env->FindClass("java/security/MessageDigest");
    if (!type) fail("DNG_SOURCE");
    const auto factory = env->GetStaticMethodID(type, "getInstance", "(Ljava/lang/String;)Ljava/security/MessageDigest;");
    const auto update = env->GetMethodID(type, "update", "(Ljava/nio/ByteBuffer;)V");
    const auto digest = env->GetMethodID(type, "digest", "()[B");
    if (!factory || !update || !digest) fail("DNG_SOURCE");
    const auto algorithm = env->NewStringUTF("SHA-256");
    const auto hasher = env->CallStaticObjectMethod(type, factory, algorithm);
    if (!hasher || env->ExceptionCheck()) fail("DNG_SOURCE");
    for (size_t offset = 0; offset < snapshot.size();) {
        cancel.check();
        const size_t count = std::min<size_t>(65536, snapshot.size() - offset);
        const auto view = env->NewDirectByteBuffer(const_cast<uint8_t*>(snapshot.data() + offset), static_cast<jlong>(count));
        if (!view) fail("DNG_MEMORY");
        env->CallVoidMethod(hasher, update, view);
        env->DeleteLocalRef(view);
        if (env->ExceptionCheck()) fail("DNG_SOURCE");
        offset += count;
    }
    const auto observed = static_cast<jbyteArray>(env->CallObjectMethod(hasher, digest));
    if (!observed || env->ExceptionCheck() || env->GetArrayLength(observed) != 32) fail("DNG_SOURCE");
    std::array<jbyte, 32> actual{}, wanted{};
    env->GetByteArrayRegion(observed, 0, 32, actual.data());
    env->GetByteArrayRegion(expected, 0, 32, wanted.data());
    if (env->ExceptionCheck() || actual != wanted) fail("DNG_CHANGED");
    cancel.check();
}

jobject extract(JNIEnv* env, jint fd, jlong bytes, jbyteArray digest, jobject signal) {
    if (fd < 0 || bytes <= 0 || bytes > static_cast<jlong>(cinematic::embedded::maxInput) || !signal)
        fail("DNG_LIMIT");
    const auto signalType = env->GetObjectClass(signal);
    if (!signalType) fail("DNG_CANCELLED");
    const auto query = env->GetMethodID(signalType, "isCanceled", "()Z");
    if (!query) fail("DNG_CANCELLED");
    Cancellation cancel{env, signal, query};
    cancel.check();
    const int flags = fcntl(fd, F_GETFL);
    if (flags < 0 || (flags & O_ACCMODE) != O_RDONLY) fail("DNG_SOURCE");
    struct stat source{};
    if (fstat(fd, &source) || !S_ISREG(source.st_mode) || source.st_size != bytes) fail("DNG_CHANGED");
    std::vector<uint8_t> snapshot(static_cast<size_t>(bytes));
    for (size_t offset = 0; offset < snapshot.size();) {
        cancel.check();
        const auto count = pread(fd, snapshot.data() + offset, std::min<size_t>(65536, snapshot.size() - offset), offset);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) fail("DNG_SOURCE");
        offset += static_cast<size_t>(count);
    }
    verify(env, snapshot, digest, cancel);
    auto result = cinematic::embedded::extract(snapshot.data(), snapshot.size(), stopped, &cancel);
    cancel.check();
    // LibRaw has been destroyed by extract; release the original before copying JPEG into Java.
    std::vector<uint8_t>().swap(snapshot);
    using cinematic::embedded::Status;
    if (result.status == Status::Cancelled) fail("DNG_CANCELLED");
    if (result.status == Status::Memory) fail("DNG_MEMORY");
    if (result.status == Status::Found && (result.orientation < 1 || result.orientation > 8 ||
        result.evidence == cinematic::embedded::OrientationEvidence::Unknown)) {
        result.status = Status::Unsupported;
        std::vector<uint8_t>().swap(result.jpeg);
        result.width = result.height = result.orientation = 0;
    }
    jbyteArray jpeg = nullptr;
    if (result.status == Status::Found) {
        if (result.jpeg.empty() || result.jpeg.size() > cinematic::embedded::maxJpeg ||
            !result.width || !result.height || result.width > 4096 || result.height > 4096 ||
            uint64_t(result.width) * result.height > 4'000'000) fail("DNG_LIMIT");
        jpeg = env->NewByteArray(static_cast<jsize>(result.jpeg.size()));
        if (!jpeg) fail("DNG_MEMORY");
        env->SetByteArrayRegion(jpeg, 0, static_cast<jsize>(result.jpeg.size()),
            reinterpret_cast<const jbyte*>(result.jpeg.data()));
        if (env->ExceptionCheck()) fail("DNG_MEMORY");
    }
    std::vector<uint8_t>().swap(result.jpeg);
    cancel.check();
    const auto type = env->FindClass("dev/cinematic/p0/raw/NativeEmbeddedJpegResult");
    if (!type) fail("DNG_UNSUPPORTED");
    const auto constructor = env->GetMethodID(type, "<init>", "(I[BIIII)V");
    if (!constructor) fail("DNG_UNSUPPORTED");
    return env->NewObject(type, constructor, static_cast<jint>(result.status), jpeg,
        static_cast<jint>(result.width), static_cast<jint>(result.height),
        static_cast<jint>(result.orientation), static_cast<jint>(result.evidence));
}
}

extern "C" JNIEXPORT jobject JNICALL
Java_dev_cinematic_p0_raw_NativeEmbeddedJpeg_extractNative(JNIEnv* env, jobject, jint fd,
    jlong bytes, jbyteArray digest, jobject signal) {
    if (env->PushLocalFrame(24) < 0) return nullptr;
    jobject result = nullptr;
    try { result = extract(env, fd, bytes, digest, signal); }
    catch (const std::bad_alloc&) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), "DNG_MEMORY");
    } catch (const std::exception& error) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), error.what());
    } catch (...) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), "DNG_UNSUPPORTED");
    }
    return env->PopLocalFrame(env->ExceptionCheck() ? nullptr : result);
}
