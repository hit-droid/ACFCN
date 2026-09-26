// ACFCN on-device GGUF inference bridge over llama.cpp.
// All public llama.cpp symbols used here are verified against tag b11179.

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include <cstring>
#include <chrono>
#include <algorithm>

#include "llama.h"

#define LOG_TAG "ACFCN-LLM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM *g_jvm = nullptr;

extern "C" jint JNI_OnLoad(JavaVM *vm, void * /*reserved*/) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

namespace {

struct Engine {
    llama_model   *model = nullptr;
    llama_context *ctx   = nullptr;
    const llama_vocab *vocab = nullptr;
    int n_ctx = 2048;
    std::mutex mu;
    bool ready = false;
    // Progress reporting back to Kotlin during model load (0.0..1.0).
    jobject progressObj = nullptr;
    jmethodID progressMid = nullptr;
};

Engine g_engine;

static bool progress_trampoline(float progress, void *user_data) {
    Engine *e = static_cast<Engine *>(user_data);
    if (!e || !e->progressObj || !e->progressMid) return true;
    JNIEnv *env = nullptr;
    bool attached = false;
    if (g_jvm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return true;
        attached = true;
    }
    env->CallVoidMethod(e->progressObj, e->progressMid, (jfloat) progress);
    if (attached) g_jvm->DetachCurrentThread();
    return true;
}

std::string jstr(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

void free_locked() {
    if (g_engine.ctx)   { llama_free(g_engine.ctx); g_engine.ctx = nullptr; }
    if (g_engine.model) { llama_model_free(g_engine.model); g_engine.model = nullptr; }
    g_engine.vocab = nullptr;
    g_engine.ready = false;
}

// Emits one decoded piece to the Kotlin callback; returns false if generation
// should stop (callback returned false, i.e. user cancelled).
bool emit(JNIEnv *env, jobject cb, jmethodID mid, const std::string &piece) {
    if (cb == nullptr || mid == nullptr) return true;
    jstring jp = env->NewStringUTF(piece.c_str());
    jboolean keep = env->CallBooleanMethod(cb, mid, jp);
    env->DeleteLocalRef(jp);
    return keep == JNI_TRUE;
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_selfmod_agent_offline_native_LocalLlmEngine_nativeInit(
        JNIEnv *env, jobject /*thiz*/, jstring modelPath, jint nCtx, jint nThreads,
        jobject progressCallback) {
    std::lock_guard<std::mutex> lock(g_engine.mu);
    free_locked();

    llama_backend_init();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // CPU only; keeps it portable across all arm64 phones

    // mmap: map the weights instead of reading them fully into RAM. This is the
    // single biggest win for load time and memory (matches what MNN does).
    mparams.load_mode = LLAMA_LOAD_MODE_MMAP;
    // Skip full tensor validation; GGUF is already integrity-safe and this
    // meaningfully cuts load time on multi-GB files.
    mparams.check_tensors = false;

    // Wire the progress callback so the UI can show a real percentage.
    if (progressCallback != nullptr) {
        jclass cls = env->GetObjectClass(progressCallback);
        g_engine.progressMid = env->GetMethodID(cls, "onProgress", "(F)V");
        env->DeleteLocalRef(cls);
        if (g_engine.progressMid != nullptr) {
            g_engine.progressObj = env->NewGlobalRef(progressCallback);
            mparams.progress_callback = progress_trampoline;
            mparams.progress_callback_user_data = &g_engine;
        }
    }

    const std::string path = jstr(env, modelPath);
    LOGI("loading model (mmap): %s", path.c_str());
    g_engine.model = llama_model_load_from_file(path.c_str(), mparams);

    // Release the progress ref; no longer needed after load.
    if (g_engine.progressObj != nullptr) {
        env->DeleteGlobalRef(g_engine.progressObj);
        g_engine.progressObj = nullptr;
        g_engine.progressMid = nullptr;
    }

    if (!g_engine.model) {
        LOGE("failed to load model");
        return JNI_FALSE;
    }
    g_engine.vocab = llama_model_get_vocab(g_engine.model);

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx     = (uint32_t) nCtx;
    cparams.n_batch   = 512;
    cparams.n_ubatch  = 512;
    cparams.n_threads = nThreads;
    cparams.n_threads_batch = nThreads;

    LOGI("ctx params: n_ctx=%u n_batch=%u n_ubatch=%u threads=%d",
         cparams.n_ctx, cparams.n_batch, cparams.n_ubatch, nThreads);

    g_engine.ctx = llama_init_from_model(g_engine.model, cparams);
    if (!g_engine.ctx) {
        LOGE("failed to create context");
        free_locked();
        return JNI_FALSE;
    }
    g_engine.n_ctx = nCtx;
    g_engine.ready = true;
    LOGI("engine ready, n_ctx=%d", nCtx);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_selfmod_agent_offline_native_LocalLlmEngine_nativeFree(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_engine.mu);
    if (g_engine.ctx || g_engine.model) {
        free_locked();
        llama_backend_free();
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_selfmod_agent_offline_native_LocalLlmEngine_nativeIsReady(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    return g_engine.ready ? JNI_TRUE : JNI_FALSE;
}

static jint generate_impl(
        JNIEnv *env, jobject callback,
        const std::string &prompt,
        jint maxTokens, jfloat temperature, jint topK, jfloat topP) {

    std::lock_guard<std::mutex> lock(g_engine.mu);
    if (!g_engine.ready || !g_engine.ctx || !g_engine.vocab) {
        LOGE("generate() called before init()");
        return -1;
    }

    const llama_vocab *vocab = g_engine.vocab;
    llama_context *ctx = g_engine.ctx;

    jmethodID mid = nullptr;
    if (callback != nullptr) {
        jclass cls = env->GetObjectClass(callback);
        mid = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)Z");
        env->DeleteLocalRef(cls);
    }

    // Tokenize prompt (add_special = true so BOS is inserted when the model wants it).
    int n_prompt_max = prompt.size() + 64;
    std::vector<llama_token> tokens(n_prompt_max);
    int n_tokens = llama_tokenize(
            vocab, prompt.c_str(), (int32_t) prompt.size(),
            tokens.data(), (int32_t) tokens.size(),
            /*add_special=*/true, /*parse_special=*/true);
    if (n_tokens < 0) {
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(
                vocab, prompt.c_str(), (int32_t) prompt.size(),
                tokens.data(), (int32_t) tokens.size(),
                true, true);
    }
    if (n_tokens < 0) {
        LOGE("tokenize failed: %d", n_tokens);
        return -2;
    }
    tokens.resize(n_tokens);

    // Fresh KV cache per request.
    llama_memory_clear(llama_get_memory(ctx), true);

    // Sampler chain: top_k -> top_p -> temp -> dist
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler *smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    int emitted = 0;
    std::string piece_buf;

    // Feed prompt in chunks of n_batch (llama_batch_get_one keeps a single seq).
    const int n_batch = 512;
    int pos = 0;
    LOGI("prefill start: %d prompt tokens", (int) tokens.size());
    auto prefill_t0 = std::chrono::steady_clock::now();
    while (pos < (int) tokens.size()) {
        int chunk = std::min(n_batch, (int) tokens.size() - pos);
        llama_batch batch = llama_batch_get_one(tokens.data() + pos, chunk);
        if (llama_decode(ctx, batch) != 0) {
            LOGE("prompt decode failed");
            llama_sampler_free(smpl);
            return -3;
        }
        pos += chunk;
    }
    auto prefill_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - prefill_t0).count();
    LOGI("prefill done in %lld ms", (long long) prefill_ms);

    std::vector<char> buf(256);
    for (int i = 0; i < maxTokens; ++i) {
        llama_token id = llama_sampler_sample(smpl, ctx, -1);
        llama_sampler_accept(smpl, id);

        if (llama_vocab_is_eog(vocab, id)) {
            LOGI("hit EOG at token %d", i);
            break;
        }

        int n = llama_token_to_piece(vocab, id, buf.data(), (int) buf.size(), 0, true);
        if (n < 0) {
            buf.resize(-n);
            n = llama_token_to_piece(vocab, id, buf.data(), (int) buf.size(), 0, true);
        }
        if (n <= 0) continue;

        piece_buf.assign(buf.data(), n);
        emitted++;
        if (i < 3 || i % 16 == 0) {
            LOGI("token %d: emitted=%d piece_len=%d", i, emitted, n);
        }
        if (!emit(env, callback, mid, piece_buf)) {
            LOGI("generation cancelled by caller at token %d", i);
            break;
        }

        llama_batch next = llama_batch_get_one(&id, 1);
        if (llama_decode(ctx, next) != 0) {
            LOGE("decode failed at token %d", i);
            break;
        }
    }

    llama_sampler_free(smpl);
    LOGI("generation done: %d tokens emitted", emitted);
    return emitted;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_selfmod_agent_offline_native_LocalLlmEngine_nativeGenerate(
        JNIEnv *env, jobject /*thiz*/, jstring prompt, jint maxTokens,
        jfloat temperature, jint topK, jfloat topP, jobject callback) {
    return generate_impl(env, callback, jstr(env, prompt), maxTokens, temperature, topK, topP);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_selfmod_agent_offline_native_LocalLlmEngine_nativeChat(
        JNIEnv *env, jobject /*thiz*/, jstring templateJ, jobjectArray roles,
        jobjectArray contents, jint maxTokens, jfloat temperature, jint topK,
        jfloat topP, jobject callback) {

    std::string tmpl = jstr(env, templateJ);
    jsize n = env->GetArrayLength(roles);

    std::vector<std::string> role_store(n), content_store(n);
    std::vector<llama_chat_message> chat(n);
    for (jsize i = 0; i < n; ++i) {
        auto r = (jstring) env->GetObjectArrayElement(roles, i);
        auto c = (jstring) env->GetObjectArrayElement(contents, i);
        role_store[i] = jstr(env, r);
        content_store[i] = jstr(env, c);
        env->DeleteLocalRef(r);
        env->DeleteLocalRef(c);
    }
    for (jsize i = 0; i < n; ++i) {
        chat[i].role = role_store[i].c_str();
        chat[i].content = content_store[i].c_str();
    }

    // Two-pass template application to size the buffer correctly.
    int need = llama_chat_apply_template(
            tmpl.c_str(), chat.data(), chat.size(), true, nullptr, 0);
    if (need < 0) {
        LOGE("chat template apply failed: %d", need);
        return -4;
    }
    std::vector<char> out(need + 1);
    int written = llama_chat_apply_template(
            tmpl.c_str(), chat.data(), chat.size(), true, out.data(), (int) out.size());
    if (written < 0) {
        LOGE("chat template write failed: %d", written);
        return -4;
    }
    std::string prompt(out.data(), written);

    return generate_impl(env, callback, prompt, maxTokens, temperature, topK, topP);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_selfmod_agent_offline_native_LocalLlmEngine_nativeChatTemplate(
        JNIEnv *env, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_engine.mu);
    if (!g_engine.model) return env->NewStringUTF("chatml");
    const char *tmpl = llama_model_chat_template(g_engine.model, nullptr);
    return env->NewStringUTF(tmpl ? tmpl : "chatml");
}

extern "C" JNIEXPORT jint JNICALL
Java_com_selfmod_agent_offline_native_LocalLlmEngine_nativeContextSize(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    return g_engine.n_ctx;
}
