// MNN LLM engine bridge: mirrors acfcn_llm.cpp's Kotlin contract so MnnLlmEngine
// looks identical to LocalLlmEngine to the app layer.
//
// The MNN runtime (libllm.so + libMNN*.so) is prebuilt at MNN 3.6.1 and ships in
// jniLibs/arm64-v8a. All of those libs link the *dynamic* libc++_shared, so this
// bridge must be built with c++_shared too (AGP default for a shared lib) — that
// is what makes the std::string across the libllm.so boundary resolve.
//
// Stepping model (from the official MnnLlmChat on-device path):
//   1. llm->response(msgs, &os, "<eop>", 0)  -- prefill + first token only
//   2. loop llm->generate(1)                 -- one decode token per step
//      watching LlmContext.status; the prebuilt runtime flips to
//      MAX_TOKENS_FINISHED after every step, so terminal states are reset back
//      to RUNNING locally (restoreAndroidSteppingStatusIfNeeded) until a real
//      stop token or our own max_tokens bound is hit.
//
// Cancellation: the prebuilt libllm.so has no USER_CANCEL hook, so the abort
// flag is checked between generate(1) steps.

#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <unistd.h>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <map>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

#include "mnn/include/llm/llm.hpp"

#define LOG_TAG "acfcn_mnn"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using MNN::Transformer::ChatMessages;
using MNN::Transformer::Llm;
using MNN::Transformer::LlmContext;
using MNN::Transformer::LlmStatus;

namespace {

const char *kEop = "<eop>";

struct Engine {
    std::mutex mu;
    // std::atomic<std::string> is not valid in C++17 (string is not trivially
    // copyable); guard the stats string with its own mutex instead.
    std::mutex stats_mu;
    std::string last_stats{"(none yet)"};
    std::atomic<bool> ready{false};
    std::atomic<bool> abort_flag{false};
    int inflight = 0;
    Llm *llm = nullptr;
    JavaVM *jvm = nullptr;
    int n_ctx = 0;
};

Engine g_engine;
JavaVM *g_jvm = nullptr;

std::string jstr(JNIEnv *env, jstring s) {
    if (s == nullptr) return "";
    const char *utf = env->GetStringUTFChars(s, nullptr);
    std::string out = utf ? utf : "";
    if (utf) env->ReleaseStringUTFChars(s, utf);
    return out;
}

// Remove the per-step <eop> sentinel the prebuilt runtime appends.
std::string stripMarkers(const std::string &raw) {
    std::string out;
    out.reserve(raw.size());
    size_t i = 0;
    while (i < raw.size()) {
        if (raw.compare(i, 5, kEop) == 0) {
            i += 5;
            continue;
        }
        out.push_back(raw[i]);
        i++;
    }
    return out;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    g_jvm = vm;
    g_engine.jvm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_selfmod_agent_offline_native_MnnLlmEngine_nativeIsReady(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    return g_engine.ready.load() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_selfmod_agent_offline_native_MnnLlmEngine_nativeCancel(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    g_engine.abort_flag.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_selfmod_agent_offline_native_MnnLlmEngine_nativeClearAbort(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    g_engine.abort_flag.store(false);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_selfmod_agent_offline_native_MnnLlmEngine_nativeInit(
        JNIEnv *env, jobject, jstring modelDir, jint nCtx, jint nThreads,
        jint nBatch, jobject /*progressCallback*/) {
    std::lock_guard<std::mutex> lock(g_engine.mu);
    if (g_engine.llm != nullptr) {
        LOGI("mnn init: already loaded, ignoring");
        return JNI_TRUE;
    }
    const std::string dir = jstr(env, modelDir);
    if (dir.empty()) {
        LOGE("mnn init: empty dir");
        return JNI_FALSE;
    }

    std::string cfg = dir + "/config.json";
    if (access(cfg.c_str(), R_OK) != 0) cfg = dir + "/llm_config.json";
    Llm *llm = Llm::createLLM(cfg);
    if (llm == nullptr) {
        LOGE("mnn: createLLM(%s) failed", cfg.c_str());
        return JNI_FALSE;
    }

    int threadNum = nThreads > 0 ? nThreads : 4;
    int n_batch = nBatch > 0 ? nBatch : 128;
    n_batch = n_batch < 8 ? 8 : (n_batch > 512 ? 512 : n_batch);
    int n_ctx = nCtx > 0 ? nCtx : 2048;

    char cfgbuf[1024];
    snprintf(cfgbuf, sizeof(cfgbuf),
             R"({"backend_type":"opencl","thread_num":%d,"precision":"low","memory":"low","use_mmap":true,"prompt_cache":true,"reuse_kv":true,"sliding_window":0,"max_new_tokens":%d,"chunk":%d})",
             threadNum, 1024, n_batch);
    bool ok = llm->set_config(cfgbuf) && llm->load();
    if (!ok) {
        LOGE("mnn: load failed; dump:\n%s", llm->dump_config().c_str());
        Llm::destroy(llm);
        return JNI_FALSE;
    }
    g_engine.llm = llm;
    g_engine.n_ctx = n_ctx;
    g_engine.ready.store(true);
    {
        std::lock_guard<std::mutex> sl(g_engine.stats_mu);
        g_engine.last_stats = "(loaded)";
    }
    LOGI("mnn ready: %s ctx=%d threads=%d batch=%d", dir.c_str(), n_ctx,
         threadNum, n_batch);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_selfmod_agent_offline_native_MnnLlmEngine_nativeFree(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_engine.mu);
    // Refuse to tear the engine down underneath a running generation: chat
    // checks the abort flag between decode steps, so signpost it and let the
    // caller retry once it has unwound instead of freeing live state.
    if (g_engine.inflight > 0) {
        LOGE("mnn free: %d generation(s) in flight, aborting them first",
             g_engine.inflight);
        g_engine.abort_flag.store(true);
        return;
    }
    if (g_engine.llm) {
        Llm::destroy(g_engine.llm);
        g_engine.llm = nullptr;
    }
    g_engine.ready.store(false);
    g_engine.abort_flag.store(false);
    {
        std::lock_guard<std::mutex> sl(g_engine.stats_mu);
        g_engine.last_stats = "(none yet)";
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_selfmod_agent_offline_native_MnnLlmEngine_nativeContextSize(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    return g_engine.n_ctx;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_selfmod_agent_offline_native_MnnLlmEngine_nativeChatTemplate(
        JNIEnv *env, jobject /*thiz*/) {
    // MNN applies the jinja chat_template from config.json on its own; we just
    // report "mnn" so the app can skip its llama.cpp template folding.
    return env->NewStringUTF("mnn");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_selfmod_agent_offline_native_MnnLlmEngine_nativeLoadInfo(
        JNIEnv *env, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_engine.mu);
    std::string out = "backend=opencl (MNN 3.6.1 prebuilt, GPU offload)\n";
    if (g_engine.llm) {
        const LlmContext *ctx = g_engine.llm->getContext();
        if (ctx) out += "status=" + std::to_string((int) ctx->status) + "\n";
    }
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_selfmod_agent_offline_native_MnnLlmEngine_nativeLastChatStats(
        JNIEnv *env, jobject /*thiz*/) {
    std::lock_guard<std::mutex> sl(g_engine.stats_mu);
    return env->NewStringUTF(g_engine.last_stats.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_selfmod_agent_offline_native_MnnLlmEngine_nativeChat(
        JNIEnv *env, jobject, jobjectArray roles,
        jobjectArray contents, jint maxTokens, jfloat temperature, jint topK,
        jfloat topP, jobject callback) {
    Llm *llm = nullptr;
    {
        // Hold the engine mutex only long enough to check readiness and mark an
        // in-flight call. nativeFree() takes the same mutex, so keeping it for
        // the whole generation would deadlock an unload against a running chat.
        std::lock_guard<std::mutex> lock(g_engine.mu);
        llm = g_engine.llm;
        if (llm == nullptr || !g_engine.ready.load()) {
            LOGE("mnn chat called before init");
            return -1;
        }
        g_engine.inflight++;
        // Drop any leftover abort so this request actually starts.
        g_engine.abort_flag.store(false);
    }
    // Always release the in-flight marker, whichever way we leave.
    struct InflightGuard {
        ~InflightGuard() {
            std::lock_guard<std::mutex> lock(g_engine.mu);
            if (g_engine.inflight > 0) g_engine.inflight--;
        }
    } inflight_guard;

    jsize n = env->GetArrayLength(roles);
    ChatMessages msgs;
    msgs.reserve(n);
    for (jsize i = 0; i < n; ++i) {
        auto r = (jstring) env->GetObjectArrayElement(roles, i);
        auto c = (jstring) env->GetObjectArrayElement(contents, i);
        msgs.emplace_back(jstr(env, r), jstr(env, c));
        env->DeleteLocalRef(r);
        env->DeleteLocalRef(c);
    }

    int tn = maxTokens > 0 ? (int) maxTokens : 1024;

    jobject cb = callback;
    jmethodID mid = nullptr;
    if (cb != nullptr) {
        jclass cls = env->GetObjectClass(cb);
        mid = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)Z");
        env->DeleteLocalRef(cls);
    }
    auto emit = [&](const std::string &piece) -> bool {
        if (mid == nullptr || cb == nullptr || piece.empty()) return true;
        jstring jp = env->NewStringUTF(piece.c_str());
        jboolean keep = env->CallBooleanMethod(cb, mid, jp);
        env->DeleteLocalRef(jp);
        return keep == JNI_TRUE;
    };

    // Buffers for the step loop. The stream writes raw tokens + <eop> markers;
    // we strip markers at each step boundary and emit the cleaned piece.
    std::string buf, lastPiece;

    auto t0 = std::chrono::steady_clock::now();
    // Prefill + first token. max_new_tokens=0 keeps the engine in RUNNING so
    // we take over stepping below.
    llm->response(msgs, &std::cout, kEop, 0);
    auto prefill_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - t0).count();

    int current_size = 0;
    int stop_code = 0;
    bool finished = false;
    auto decode_t0 = std::chrono::steady_clock::now();
    while (!g_engine.abort_flag.load() && !finished && current_size < tn) {
        llm->generate(1);
        current_size++;
        const LlmContext *ctx = llm->getContext();
        if (ctx == nullptr) break;
        if (ctx->status == LlmStatus::NORMAL_FINISHED) {
            finished = true;
            break;
        }
        if (ctx->status == LlmStatus::INTERNAL_ERROR) {
            LOGE("mnn decode INTERNAL_ERROR at step %d", current_size);
            stop_code = -3;
            finished = true;
            break;
        }
        // MAX_TOKENS_FINISHED after every step: reset so the next generate(1)
        // keeps going until a real stop token.
        if (ctx->status == LlmStatus::MAX_TOKENS_FINISHED) {
            const_cast<LlmContext *>(ctx)->status = LlmStatus::RUNNING;
        }
    }
    auto decode_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - decode_t0).count();

    // Emit the final assembled text in one cleaned shot. The streaming buffer
    // above is unused on the device path; the chat UI already paces deltas
    // server-side, and MNN's token granularity is fine for a single flush.
    auto raw = llm->getContext() ? llm->getContext()->generate_str : "";
    std::string text = stripMarkers(raw);
    if (!text.empty()) emit(text);

    if (g_engine.abort_flag.load() && stop_code == 0) stop_code = -98;
    if (stop_code == 0) stop_code = (int) current_size;

    // Keep the engine's prompt cache in sync so the next turn's prefill is a
    // prefix hit instead of a full re-prefill.
    if (!finished) {
        ChatMessages with_assistant = msgs;
        with_assistant.emplace_back("assistant", text);
        llm->syncPromptCache(with_assistant);
    }

    double decode_tps = decode_ms > 0
            ? (double) current_size * 1000.0 / (double) decode_ms : 0.0;
    const LlmContext *fctx = llm->getContext();
    char stats[512];
    snprintf(stats, sizeof(stats),
             "mnn chat: prefill %lldms (%d tok, %.1f t/s), decode %lldms (%d tok, %.2f t/s), "
             "ctx all_seq=%d gen_seq=%d",
             (long long) prefill_ms, fctx ? fctx->prompt_len : 0,
             fctx && prefill_ms > 0 ? (double) fctx->prompt_len * 1000.0 / prefill_ms : 0.0,
             (long long) decode_ms, current_size, decode_tps,
             fctx ? fctx->all_seq_len : 0, fctx ? fctx->gen_seq_len : 0);
    {
        std::lock_guard<std::mutex> sl(g_engine.stats_mu);
        g_engine.last_stats = stats;
    }

    return stop_code;
}
