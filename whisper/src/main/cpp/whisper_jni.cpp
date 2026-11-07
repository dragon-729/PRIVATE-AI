#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include "whisper.h"

#define TAG "PrivateAI-whisper"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_privateai_whisper_WhisperBridge_nativeInit(JNIEnv* env, jobject, jstring jpath) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context* ctx = whisper_init_from_file_with_params(path, cp);
    if (!ctx) LOGE("could not load whisper model: %s", path);
    env->ReleaseStringUTFChars(jpath, path);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jbyteArray JNICALL
Java_com_privateai_whisper_WhisperBridge_nativeTranscribe(JNIEnv* env, jobject, jlong handle,
        jfloatArray audio, jint threads, jstring jlang) {
    auto* ctx = reinterpret_cast<whisper_context*>(handle);
    if (!ctx) return nullptr;

    jsize n = env->GetArrayLength(audio);
    std::vector<float> pcm(n);
    env->GetFloatArrayRegion(audio, 0, n, pcm.data());

    const char* l = env->GetStringUTFChars(jlang, nullptr);
    std::string language(l);
    env->ReleaseStringUTFChars(jlang, l);

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads;
    p.language = language.c_str();
    p.translate = false;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_special = false;
    p.print_timestamps = false;
    p.no_context = true;

    if (whisper_full(ctx, p, pcm.data(), (int) n) != 0) {
        LOGE("whisper_full failed");
        return nullptr;
    }

    std::string out;
    int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; i++) {
        const char* t = whisper_full_get_segment_text(ctx, i);
        if (t) out += t;
    }
    jbyteArray arr = env->NewByteArray((jsize) out.size());
    env->SetByteArrayRegion(arr, 0, (jsize) out.size(), reinterpret_cast<const jbyte*>(out.data()));
    return arr;
}

JNIEXPORT void JNICALL
Java_com_privateai_whisper_WhisperBridge_nativeFree(JNIEnv*, jobject, jlong handle) {
    auto* ctx = reinterpret_cast<whisper_context*>(handle);
    if (ctx) whisper_free(ctx);
}

} // extern "C"
