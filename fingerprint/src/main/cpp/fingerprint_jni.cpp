// JNI bridge to Chromaprint's C API: start, feed 16-bit PCM, finish and read the compressed fingerprint.
// MP3 files are decoded here with minimp3: the platform decoder needs one round trip per frame and was
// about 30 times slower (DECISIONS.md D-075).
#include <jni.h>
#include <unistd.h>
#include <vector>
#include <chromaprint.h>

#define MINIMP3_IMPLEMENTATION
#define MINIMP3_NO_STDIO
#include "minimp3/minimp3_ex.h"

static size_t fd_read(void *buf, size_t size, void *user_data) {
    int fd = *static_cast<int *>(user_data);
    size_t total = 0;
    while (total < size) {
        ssize_t n = read(fd, static_cast<uint8_t *>(buf) + total, size - total);
        if (n <= 0) break;
        total += static_cast<size_t>(n);
    }
    return total;
}

static int fd_seek(uint64_t position, void *user_data) {
    int fd = *static_cast<int *>(user_data);
    return lseek64(fd, static_cast<off64_t>(position), SEEK_SET) < 0 ? -1 : 0;
}

static jstring finish(JNIEnv *env, ChromaprintContext *ctx) {
    if (chromaprint_finish(ctx) != 1) return nullptr;
    char *fp = nullptr;
    if (chromaprint_get_fingerprint(ctx, &fp) != 1 || fp == nullptr) return nullptr;
    jstring result = env->NewStringUTF(fp);
    chromaprint_dealloc(fp);
    return result;
}

extern "C" {

/** Fingerprint of the first [maxSeconds] of an MP3 file, read from [fd] (not closed); null on failure. */
JNIEXPORT jstring JNICALL
Java_com_trackrecord_fingerprint_Chromaprint_nativeFingerprintMp3(JNIEnv *env, jclass, jint fd, jint maxSeconds) {
    int handle = fd;
    mp3dec_io_t io = {fd_read, &handle, fd_seek, &handle};
    auto *dec = new mp3dec_ex_t();
    jstring result = nullptr;
    if (mp3dec_ex_open_cb(dec, &io, MP3D_DO_NOT_SCAN) == 0 && dec->info.hz > 0 && dec->info.channels > 0) {
        ChromaprintContext *ctx = chromaprint_new(CHROMAPRINT_ALGORITHM_DEFAULT);
        if (ctx != nullptr && chromaprint_start(ctx, dec->info.hz, dec->info.channels) == 1) {
            size_t limit = static_cast<size_t>(maxSeconds) * dec->info.hz * dec->info.channels;
            std::vector<mp3d_sample_t> buffer(MINIMP3_MAX_SAMPLES_PER_FRAME * 8);
            size_t fed = 0;
            bool ok = true;
            while (fed < limit) {
                size_t want = std::min(buffer.size(), limit - fed);
                size_t got = mp3dec_ex_read(dec, buffer.data(), want);
                if (got == 0) break;
                if (chromaprint_feed(ctx, buffer.data(), static_cast<int>(got)) != 1) { ok = false; break; }
                fed += got;
                if (got < want) break; // end of file (or an error minimp3 could not skip)
            }
            if (ok && fed > 0) result = finish(env, ctx);
        }
        if (ctx != nullptr) chromaprint_free(ctx);
    }
    mp3dec_ex_close(dec);
    delete dec;
    return result;
}

JNIEXPORT jlong JNICALL
Java_com_trackrecord_fingerprint_Chromaprint_nativeNew(JNIEnv *, jclass) {
    return reinterpret_cast<jlong>(chromaprint_new(CHROMAPRINT_ALGORITHM_DEFAULT));
}

JNIEXPORT jboolean JNICALL
Java_com_trackrecord_fingerprint_Chromaprint_nativeStart(JNIEnv *, jclass, jlong ctx, jint sampleRate, jint channels) {
    if (ctx == 0) return JNI_FALSE;
    return chromaprint_start(reinterpret_cast<ChromaprintContext *>(ctx), sampleRate, channels) == 1;
}

JNIEXPORT jboolean JNICALL
Java_com_trackrecord_fingerprint_Chromaprint_nativeFeed(JNIEnv *env, jclass, jlong ctx, jshortArray samples, jint count) {
    if (ctx == 0 || count < 0 || count > env->GetArrayLength(samples)) return JNI_FALSE;
    jshort *data = env->GetShortArrayElements(samples, nullptr);
    if (data == nullptr) return JNI_FALSE;
    int ok = chromaprint_feed(reinterpret_cast<ChromaprintContext *>(ctx), reinterpret_cast<const int16_t *>(data), count);
    env->ReleaseShortArrayElements(samples, data, JNI_ABORT);
    return ok == 1;
}

JNIEXPORT jstring JNICALL
Java_com_trackrecord_fingerprint_Chromaprint_nativeFinish(JNIEnv *env, jclass, jlong ctx) {
    if (ctx == 0) return nullptr;
    return finish(env, reinterpret_cast<ChromaprintContext *>(ctx));
}

JNIEXPORT jintArray JNICALL
Java_com_trackrecord_fingerprint_Chromaprint_nativeDecode(JNIEnv *env, jclass, jstring encoded) {
    const char *chars = env->GetStringUTFChars(encoded, nullptr);
    if (chars == nullptr) return nullptr;
    uint32_t *raw = nullptr;
    int size = 0, algorithm = 0;
    int ok = chromaprint_decode_fingerprint(chars, env->GetStringUTFLength(encoded), &raw, &size, &algorithm, 1);
    env->ReleaseStringUTFChars(encoded, chars);
    if (ok != 1 || raw == nullptr) return nullptr;
    jintArray result = env->NewIntArray(size);
    if (result != nullptr) env->SetIntArrayRegion(result, 0, size, reinterpret_cast<const jint *>(raw));
    chromaprint_dealloc(raw);
    return result;
}

JNIEXPORT void JNICALL
Java_com_trackrecord_fingerprint_Chromaprint_nativeFree(JNIEnv *, jclass, jlong ctx) {
    if (ctx == 0) return;
    chromaprint_free(reinterpret_cast<ChromaprintContext *>(ctx));
}

}
