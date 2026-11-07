#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>
#include "llama.h"

#define TAG "PrivateAI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Session {
    llama_model*   model = nullptr;
    llama_context* ctx   = nullptr;
    int            n_ctx = 0;
    int            n_batch = 512;
    std::atomic<bool> stop{false};
};

std::once_flag g_backend_once;

// Java String -> real UTF-8 (JNI's GetStringUTFChars gives "modified" UTF-8, which breaks emoji).
std::string to_utf8(JNIEnv* env, jstring s) {
    jclass cls = env->FindClass("java/lang/String");
    jmethodID mid = env->GetMethodID(cls, "getBytes", "(Ljava/lang/String;)[B");
    jstring enc = env->NewStringUTF("UTF-8");
    auto arr = (jbyteArray) env->CallObjectMethod(s, mid, enc);
    jsize n = env->GetArrayLength(arr);
    std::string out(n, '\0');
    env->GetByteArrayRegion(arr, 0, n, reinterpret_cast<jbyte*>(out.data()));
    env->DeleteLocalRef(arr);
    env->DeleteLocalRef(enc);
    env->DeleteLocalRef(cls);
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_privateai_ai_llama_LlamaBridge_nativeLoad(JNIEnv* env, jobject, jstring jpath,
                                                   jint nCtx, jint nThreads) {
    std::call_once(g_backend_once, [] { llama_backend_init(); });
    std::string path = to_utf8(env, jpath);

    llama_model_params mp = llama_model_default_params();
    llama_model* model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) { LOGE("model load failed: %s", path.c_str()); return 0; }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = nCtx;
    cp.n_batch = 512;
    cp.n_threads = nThreads;
    cp.n_threads_batch = nThreads;
    llama_context* ctx = llama_init_from_model(model, cp);
    if (!ctx) { LOGE("context init failed"); llama_model_free(model); return 0; }

    auto* s = new Session();
    s->model = model;
    s->ctx = ctx;
    s->n_ctx = (int) llama_n_ctx(ctx);
    s->n_batch = (int) cp.n_batch;
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_com_privateai_ai_llama_LlamaBridge_nativeStop(JNIEnv*, jobject, jlong handle) {
    if (handle) reinterpret_cast<Session*>(handle)->stop.store(true);
}

JNIEXPORT void JNICALL
Java_com_privateai_ai_llama_LlamaBridge_nativeSetThreads(JNIEnv*, jobject, jlong handle, jint n) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (s && s->ctx && n > 0) llama_set_n_threads(s->ctx, n, n);
}

JNIEXPORT void JNICALL
Java_com_privateai_ai_llama_LlamaBridge_nativeFree(JNIEnv*, jobject, jlong handle) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s) return;
    if (s->ctx) llama_free(s->ctx);
    if (s->model) llama_model_free(s->model);
    delete s;
}

// Returns number of generated tokens (>= 0) or a negative error code:
// -1 no session, -3 template failed, -4 tokenize failed, -5 context full, -6 decode failed
JNIEXPORT jint JNICALL
Java_com_privateai_ai_llama_LlamaBridge_nativeGenerate(JNIEnv* env, jobject, jlong handle,
        jobjectArray roles, jobjectArray contents, jint maxTokens, jfloat temperature,
        jobject callback) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s) return -1;
    s->stop.store(false);

    // 1) Build the prompt with the model's own chat template.
    jsize n = env->GetArrayLength(roles);
    std::vector<std::string> r(n), c(n);
    std::vector<llama_chat_message> msgs(n);
    for (jsize i = 0; i < n; i++) {
        auto jr = (jstring) env->GetObjectArrayElement(roles, i);
        auto jc = (jstring) env->GetObjectArrayElement(contents, i);
        r[i] = to_utf8(env, jr);
        c[i] = to_utf8(env, jc);
        env->DeleteLocalRef(jr);
        env->DeleteLocalRef(jc);
        msgs[i] = { r[i].c_str(), c[i].c_str() };
    }
    const char* tmpl = llama_model_chat_template(s->model, nullptr);
    std::vector<char> buf(16384);
    int32_t len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int32_t) buf.size());
    if (len < 0) len = llama_chat_apply_template("chatml", msgs.data(), msgs.size(), true, buf.data(), (int32_t) buf.size());
    if (len > (int32_t) buf.size()) {
        buf.resize(len);
        len = llama_chat_apply_template(tmpl ? tmpl : "chatml", msgs.data(), msgs.size(), true, buf.data(), (int32_t) buf.size());
    }
    if (len < 0) return -3;
    std::string prompt(buf.data(), len);

    // 2) Tokenize.
    const llama_vocab* vocab = llama_model_get_vocab(s->model);
    int n_tok = -llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, false, true);
    if (n_tok <= 0) return -4;
    std::vector<llama_token> toks(n_tok);
    if (llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), toks.data(), n_tok, false, true) < 0) return -4;
    if (n_tok >= s->n_ctx - 16) return -5;
    int budget = std::min<int>(maxTokens, s->n_ctx - n_tok);

    // 3) Fresh KV cache, evaluate the prompt in batches.
    llama_memory_clear(llama_get_memory(s->ctx), true);
    for (int i = 0; i < n_tok; i += s->n_batch) {
        int cnt = std::min(s->n_batch, n_tok - i);
        llama_batch b = llama_batch_get_one(toks.data() + i, cnt);
        if (llama_decode(s->ctx, b) != 0) return -6;
        if (s->stop.load()) return 0;
    }

    // 4) Sample and stream.
    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (temperature <= 0.01f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    jclass cbCls = env->GetObjectClass(callback);
    jmethodID onPiece = env->GetMethodID(cbCls, "onPiece", "([B)V");
    int generated = 0;
    while (generated < budget && !s->stop.load()) {
        llama_token tok = llama_sampler_sample(smpl, s->ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;

        char piece[256];
        int pn = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, false);
        if (pn > 0) {
            jbyteArray arr = env->NewByteArray(pn);
            env->SetByteArrayRegion(arr, 0, pn, reinterpret_cast<const jbyte*>(piece));
            env->CallVoidMethod(callback, onPiece, arr);
            env->DeleteLocalRef(arr);
            if (env->ExceptionCheck()) { env->ExceptionClear(); break; }
        }
        generated++;

        llama_batch b = llama_batch_get_one(&tok, 1);
        if (llama_decode(s->ctx, b) != 0) { llama_sampler_free(smpl); return -6; }
    }
    llama_sampler_free(smpl);
    return generated;
}

} // extern "C"
