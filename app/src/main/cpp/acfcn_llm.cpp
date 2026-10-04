// ACFCN on-device GGUF inference bridge over llama.cpp.
// All public llama.cpp symbols used here are verified against tag b11179.

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include <condition_variable>
#include <atomic>
#include <cstring>
#include <chrono>
#include <algorithm>
#include <csignal>
#include <fcntl.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/stat.h>

#include "llama.h"

#define LOG_TAG "ACFCN-LLM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM *g_jvm = nullptr;

// ---------------------------------------------------------------------------
// M14 — native crash capture. llama.cpp / our JNI bridge can SIGSEGV / SIGABRT /
// SIGBUS (e.g. a bad GGUF tensor shape, mmap failure, corrupted model file) and
// the Java Thread.UncaughtExceptionHandler never fires for those — the process
// just dies and the CrashStore has no report to show on next launch.
//
// We install handlers that write a *minimal* report using only async-signal-safe
// syscalls (open/write/close — never malloc, std::string, LOGI or JNI), then
// re-raise so the process still dies and debuggerd still records the tombstone.
// The report goes to the same file the Java CrashStore writes, so MainActivity
// shows it identically. The path is provided by Java via nativeSetCrashDir.
// ---------------------------------------------------------------------------
namespace {

// Destination report path, set once from Java. Sized generously; a long
// filesDir path is common. Atomic read in the handler is fine because the
// path is written once before any model load and never mutated afterwards.
constexpr size_t kCrashDirMax = 512;
char g_crash_dir[kCrashDirMax] = {0};

void install_signal_handlers();
void native_crash_handler(int sig, siginfo_t * /*si*/, void * /*ctx*/);

} // namespace

extern "C" jint JNI_OnLoad(JavaVM *vm, void * /*reserved*/) {
    g_jvm = vm;
    install_signal_handlers();
    return JNI_VERSION_1_6;
}

// M14: records the CrashStore directory so the native signal handler knows
// where to write last_crash.txt. Called once from Java during library load,
// before any model is loaded.
extern "C" JNIEXPORT void JNICALL
Java_com_selfmod_agent_offline_native_LocalLlmEngine_nativeSetCrashDir(
        JNIEnv *env, jclass /*clazz*/, jstring crashDir) {
    if (crashDir == nullptr) return;
    const char *utf = env->GetStringUTFChars(crashDir, nullptr);
    if (utf == nullptr) return;
    size_t len = strlen(utf);
    if (len >= kCrashDirMax) len = kCrashDirMax - 1;
    memcpy(g_crash_dir, utf, len);
    g_crash_dir[len] = '\0';
    env->ReleaseStringUTFChars(crashDir, utf);
}

namespace {

struct Engine {
    llama_model   *model = nullptr;
    llama_context *ctx   = nullptr;
    const llama_vocab *vocab = nullptr;
    std::atomic<int> n_ctx {2048};
    int n_batch = 64;
    std::mutex mu;
    std::condition_variable cv;
    std::atomic<bool> ready {false};
    std::atomic<bool> abort_flag {false};
    int inflight = 0;
    int free_waiters = 0;
    jobject progressObj = nullptr;
    jmethodID progressMid = nullptr;
};

Engine g_engine;

static bool abort_trampoline(void *data) {
    Engine *e = static_cast<Engine *>(data);
    return e != nullptr && e->abort_flag.load(std::memory_order_relaxed);
}

static bool progress_trampoline(float progress, void *user_data) {
    Engine *e = static_cast<Engine *>(user_data);
    if (!e) return true;
    if (e->abort_flag.load(std::memory_order_relaxed)) return false;
    if (!e->progressObj || !e->progressMid) return true;
    JNIEnv *env = nullptr;
    bool attached = false;
    if (g_jvm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return !e->abort_flag.load(std::memory_order_relaxed);
        }
        attached = true;
    }
    env->CallVoidMethod(e->progressObj, e->progressMid, (jfloat) progress);
    if (attached) g_jvm->DetachCurrentThread();
    return !e->abort_flag.load(std::memory_order_relaxed);
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
    g_engine.ready.store(false);
}

void wait_idle_locked(std::unique_lock<std::mutex> &lock) {
    g_engine.free_waiters++;
    g_engine.abort_flag.store(true);
    g_engine.cv.wait(lock, [] { return g_engine.inflight == 0; });
    g_engine.free_waiters--;
    g_engine.cv.notify_all();
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

// --- M14 native crash handler -----------------------------------------------
// Async-signal-safe. No heap, no std::string, no JNI, no logging. Writes a small
// fixed-format report to <crash_dir>/last_crash.txt then re-raises the signal.
void native_crash_handler(int sig, siginfo_t * /*si*/, void * /*ctx*/) {
    if (g_crash_dir[0] != '\0') {
        char path[kCrashDirMax + 16];
        size_t n = 0;
        for (; n < kCrashDirMax && g_crash_dir[n] != '\0'; ++n) path[n] = g_crash_dir[n];
        const char suffix[] = "/last_crash.txt";
        size_t s = 0;
        for (; s < sizeof(suffix) - 1 && n + s < sizeof(path) - 1; ++s) {
            path[n + s] = suffix[s];
        }
        path[n + s] = '\0';

        const char header[] =
            "ACFCN native crash\n"
            "source: native signal\n"
            "kind: SIGSEGV/SIGABRT/SIGBUS\n";
        const char sigline[] = "signal: ";
        const char sigdigits[4] = {
            static_cast<char>('0' + (sig / 100) % 10),
            static_cast<char>('0' + (sig / 10) % 10),
            static_cast<char>('0' + sig % 10),
            '\n',
        };
        const char hint[] =
            "detail: crashed in the native LLM layer (llama.cpp / JNI bridge)\n"
            "reported: native signal handler\n";

        int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
        if (fd >= 0) {
            write(fd, header, sizeof(header) - 1);
            write(fd, sigline, sizeof(sigline) - 1);
            write(fd, sigdigits, sizeof(sigdigits));
            write(fd, hint, sizeof(hint) - 1);
            close(fd);
        }
    }

    // Restore default disposition and re-raise so the process dies normally and
    // debuggerd / the system still log the tombstone.
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = SIG_DFL;
    sigemptyset(&sa.sa_mask);
    sigaction(sig, &sa, nullptr);
    raise(sig);
}

void install_signal_handlers() {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = native_crash_handler;
    sa.sa_flags = SA_SIGINFO;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGSEGV, &sa, nullptr);
    sigaction(SIGABRT, &sa, nullptr);
    sigaction(SIGBUS, &sa, nullptr);
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_selfmod_agent_offline_native_LocalLlmEngine_nativeInit(
        JNIEnv *env, jobject /*thiz*/, jstring modelPath, jint nCtx, jint nThreads,
        jint nBatch, jobject progressCallback) {
    std::unique_lock<std::mutex> lock(g_engine.mu);
    wait_idle_locked(lock);
    g_engine.abort_flag.store(false);
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
    int batch = nBatch > 0 ? (int) nBatch : 64;
    if (batch < 8) batch = 8;
    if (batch > 512) batch = 512;
    cparams.n_ctx     = (uint32_t) nCtx;
    cparams.n_batch   = (uint32_t) batch;
    cparams.n_ubatch  = (uint32_t) batch;
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
    llama_set_abort_callback(g_engine.ctx, abort_trampoline, &g_engine);
    g_engine.n_ctx.store(nCtx);
    g_engine.n_batch = batch;
    g_engine.ready.store(true);
    LOGI("engine ready, n_ctx=%d", nCtx);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_selfmod_agent_offline_native_LocalLlmEngine_nativeFree(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    std::unique_lock<std::mutex> lock(g_engine.mu);
    wait_idle_locked(lock);
    if (g_engine.ctx || g_engine.model) {
        free_locked();
        llama_backend_free();
    }
    g_engine.abort_flag.store(false);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_selfmod_agent_offline_native_LocalLlmEngine_nativeIsReady(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    return g_engine.ready.load() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_selfmod_agent_offline_native_LocalLlmEngine_nativeCancel(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    g_engine.abort_flag.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_selfmod_agent_offline_native_LocalLlmEngine_nativeClearAbort(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    g_engine.abort_flag.store(false);
}

static jint generate_impl(
        JNIEnv *env, jobject callback,
        const std::string &prompt,
        jint maxTokens, jfloat temperature, jint topK, jfloat topP) {

    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    int n_batch = 64;
    {
        std::unique_lock<std::mutex> lock(g_engine.mu);
        g_engine.cv.wait(lock, [] {
            return g_engine.inflight == 0 && g_engine.free_waiters == 0;
        });
        if (!g_engine.ready.load() || !g_engine.ctx || !g_engine.vocab) {
            LOGE("generate() called before init()");
            return -1;
        }
        g_engine.inflight++;
        ctx = g_engine.ctx;
        vocab = g_engine.vocab;
        n_batch = g_engine.n_batch > 0 ? g_engine.n_batch : 64;
    }

    struct InflightGuard {
        ~InflightGuard() {
            std::lock_guard<std::mutex> lock(g_engine.mu);
            if (g_engine.inflight > 0) g_engine.inflight--;
            if (g_engine.free_waiters == 0) {
                g_engine.abort_flag.store(false);
            }
            g_engine.cv.notify_all();
        }
    } guard;

    auto aborted = []() {
        return g_engine.abort_flag.load(std::memory_order_relaxed);
    };

    jmethodID mid = nullptr;
    if (callback != nullptr) {
        jclass cls = env->GetObjectClass(callback);
        mid = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)Z");
        env->DeleteLocalRef(cls);
    }

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

    // Keep the prompt inside n_ctx or llama_decode fails with a vague -3.
    // Reserve room for the generated reply, then drop the oldest turns from
    // the middle while keeping the head (BOS/system) and the recent tail.
    const int ctx_size = g_engine.n_ctx.load();
    const int out_budget = maxTokens > 0 ? (int) maxTokens : 512;
    int limit = ctx_size - out_budget;
    if (limit < 8) limit = ctx_size / 2;
    if (limit < 1) limit = ctx_size;
    if ((int) tokens.size() > limit) {
        int keep_head = std::min(4, limit / 4);
        int keep_tail = limit - keep_head;
        if (keep_tail < 0) { keep_head = 0; keep_tail = limit; }
        std::vector<llama_token> trimmed;
        trimmed.reserve(limit);
        trimmed.insert(trimmed.end(), tokens.begin(), tokens.begin() + keep_head);
        trimmed.insert(trimmed.end(), tokens.end() - keep_tail, tokens.end());
        LOGI("prompt truncated: %d -> %d tokens (n_ctx=%d out_budget=%d, dropped %d)",
             (int) tokens.size(), (int) trimmed.size(), ctx_size, out_budget,
             (int) tokens.size() - (int) trimmed.size());
        tokens.swap(trimmed);
    }
    if (tokens.empty()) {
        LOGE("prompt is empty after truncation (n_ctx=%d)", ctx_size);
        return -5;
    }

    llama_memory_clear(llama_get_memory(ctx), true);

    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler *smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    int emitted = 0;
    std::string piece_buf;
    jint result = 0;
    bool fail = false;

    int pos = 0;
    LOGI("prefill start: %d prompt tokens", (int) tokens.size());
    auto prefill_t0 = std::chrono::steady_clock::now();
    while (!fail && pos < (int) tokens.size()) {
        if (aborted()) {
            LOGI("prefill cancelled at pos %d", pos);
            result = -98;
            fail = true;
            break;
        }
        int chunk = std::min(n_batch, (int) tokens.size() - pos);
        llama_batch batch = llama_batch_get_one(tokens.data() + pos, chunk);
        int rc = llama_decode(ctx, batch);
        if (rc == 2 || aborted()) {
            LOGI("prefill aborted by llama at pos %d rc=%d", pos, rc);
            result = -98;
            fail = true;
            break;
        }
        if (rc == 1) {
            LOGE("prompt needs more KV slots at pos %d (n_ctx=%d)", pos, ctx_size);
            result = -5;
            fail = true;
            break;
        }
        if (rc != 0) {
            LOGE("prompt decode failed rc=%d", rc);
            result = -3;
            fail = true;
            break;
        }
        pos += chunk;
    }

    if (!fail) {
        auto prefill_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - prefill_t0).count();
        LOGI("prefill done in %lld ms", (long long) prefill_ms);

        std::vector<char> buf(256);
        for (int i = 0; i < maxTokens; ++i) {
            if (aborted()) {
                LOGI("generation cancelled before sample at token %d", i);
                result = -98;
                fail = true;
                break;
            }
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
            if (!emit(env, callback, mid, piece_buf) || aborted()) {
                LOGI("generation cancelled by caller at token %d", i);
                result = -98;
                fail = true;
                break;
            }

            llama_batch next = llama_batch_get_one(&id, 1);
            int rc = llama_decode(ctx, next);
            if (rc == 2 || aborted()) {
                LOGI("decode aborted at token %d rc=%d", i, rc);
                result = -98;
                fail = true;
                break;
            }
            if (rc == 1) {
                // Context window full: stop cleanly, the reply so far is valid.
                LOGI("context full at token %d (n_ctx=%d), stopping", i, ctx_size);
                break;
            }
            if (rc != 0) {
                LOGE("decode failed at token %d rc=%d", i, rc);
                break;
            }
        }
        if (!fail) result = emitted;
    }

    llama_sampler_free(smpl);
    LOGI("generation done: emitted=%d result=%d", emitted, (int) result);
    return result;
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
    return g_engine.n_ctx.load();
}
