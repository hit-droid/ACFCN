// ACFCN on-device GGUF inference bridge over llama.cpp.
// All public llama.cpp symbols used here are verified against tag b11640
// (commit 3e390af18de1131dda27913c00511725b4274c8a).

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include <condition_variable>
#include <atomic>
#include <cstring>
#include <cstdlib>
#include <cstdio>
#include <chrono>
#include <algorithm>
#include <csignal>
#include <fcntl.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <sched.h>
#include <cerrno>

#include "llama.h"

#define LOG_TAG "ACFCN-LLM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Forward llama.cpp / ggml logs (including the OpenCL backend's device and
// kernel messages) to logcat. Without this they go to stderr and are lost in an
// Android app, which makes on-device diagnosis impossible.
// Inspect with: adb logcat -s ACFCN-LLM
static int llama_log_level_to_android(enum ggml_log_level level) {
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: return ANDROID_LOG_ERROR;
        case GGML_LOG_LEVEL_WARN:  return ANDROID_LOG_WARN;
        case GGML_LOG_LEVEL_DEBUG: return ANDROID_LOG_DEBUG;
        default:                   return ANDROID_LOG_INFO;
    }
}

// Summary of the last model load: build GPU-offload support, requested GPU
// layers, CPU features (confirms the dotprod build) and the captured OpenCL /
// layer-placement log lines.
static std::mutex g_gpu_log_mu;
static std::string g_gpu_log;
static std::string g_load_info;
// perf: human-readable report of the CPU-affinity decision made at model load
// (which cores were pinned, or why affinity was skipped), surfaced via
// nativeBigCoreInfo() and folded into the in-app diagnostics.
static std::string g_big_core_report;

static void llama_log_to_logcat(enum ggml_log_level level, const char *text, void * /*user_data*/) {
    if (text == nullptr) return;
    __android_log_write(llama_log_level_to_android(level), LOG_TAG, text);

    // Keep the load-time lines that reveal whether the OpenCL backend came up
    // and how many layers actually landed on the GPU. Shown in the in-app
    // diagnostics so a device can be profiled without adb logcat.
    static const char *keys[] = {
        "offload", "OpenCL", "opencl", "ggml_opencl", "backend", "device",
        "model buffer size", "failed", "not supported", "no usable",
    };
    bool keep = false;
    for (const char *k : keys) {
        if (strstr(text, k) != nullptr) { keep = true; break; }
    }
    if (keep) {
        std::lock_guard<std::mutex> lk(g_gpu_log_mu);
        if (g_gpu_log.size() < 6000) {
            g_gpu_log += text;
            if (g_gpu_log.empty() || g_gpu_log.back() != '\n') g_gpu_log += '\n';
        }
    }
}

// Runtime tuning without rebuilding, read once at model load:
//   adb shell setprop debug.acfcn.gpu vulkan     # GPU backend: vulkan|opencl|cpu|auto
//   adb shell setprop debug.acfcn.gpu_layers 99 # offload all layers to GPU
//   adb shell setprop debug.acfcn.gpu_layers 0  # force CPU
//   adb shell setprop debug.acfcn.threads 8     # override thread count
//   adb shell setprop debug.acfcn.cpu_affinity 0-3  # pin engine to big cores ("none" = off)
//   adb shell setprop debug.acfcn.opencl_xmem 0    # disable Adreno xmem GEMM (default on)
//   adb shell setprop debug.acfcn.warmup 0         # skip GPU kernel warmup at load (default on)
// Property changes take effect on the next model load.
static int get_property_int(const char *name, int fallback) {
    char value[PROP_VALUE_MAX] = {0};
    int len = __system_property_get(name, value);
    if (len <= 0) return fallback;
    char *end = nullptr;
    long parsed = strtol(value, &end, 10);
    if (end == value) return fallback;
    return (int) parsed;
}

// Pick the GPU backend device at model load. Vulkan is preferred over OpenCL
// on Adreno: llama.cpp's Vulkan decode kernels (subgroup tiling, cooperative
// matrix on Gen6+) run several times faster than its OpenCL ones, and unlike
// the OpenCL ICD path it needs no runtime loader wrapper. OpenCL stays as the
// fallback (and for A/B via debug.acfcn.gpu=opencl). A backend that was not
// compiled in simply never shows up in the device list.
//   debug.acfcn.gpu = vulkan (default) | opencl | cpu | auto
// Returns nullptr for "CPU only". name_out/how_out are for the load log.
static ggml_backend_dev_t pick_gpu_device(const char *pref, std::string &name_out,
                                          std::string &how_out) {
    const std::string p(pref && pref[0] ? pref : "auto");
    if (p == "cpu") return nullptr;

    struct Backend { const char *key; const char *match; };
    Backend order[2];
    int n = 0;
    auto add = [&](const char *key, const char *match) {
        if (n < 2) order[n++] = {key, match};
    };
    if (p == "opencl") {
        add("opencl", "OpenCL");
        add("vulkan", "Vulkan");
    } else { // vulkan (explicit) or auto: Vulkan first, OpenCL as fallback
        add("vulkan", "Vulkan");
        add("opencl", "OpenCL");
    }
    for (int i = 0; i < n; ++i) {
        for (size_t d = 0; d < ggml_backend_dev_count(); ++d) {
            ggml_backend_dev_t dev = ggml_backend_dev_get(d);
            enum ggml_backend_dev_type t = ggml_backend_dev_type(dev);
            if (t != GGML_BACKEND_DEVICE_TYPE_GPU &&
                t != GGML_BACKEND_DEVICE_TYPE_IGPU) {
                continue;
            }
            const char *nm = ggml_backend_dev_name(dev);
            if (nm != nullptr && strstr(nm, order[i].match) != nullptr) {
                name_out = nm;
                how_out = order[i].key;
                return dev;
            }
        }
    }
    return nullptr;
}

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
    llama_log_set(llama_log_to_logcat, nullptr);
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
    std::atomic<int> gpu_layers {0};
    int n_batch = 64;
    std::mutex mu;
    std::condition_variable cv;
    std::atomic<bool> ready {false};
    std::atomic<bool> abort_flag {false};
    int inflight = 0;
    int free_waiters = 0;
    jobject progressObj = nullptr;
    jmethodID progressMid = nullptr;
    // Cross-turn KV continuation: the serialized prompt of the previous turn
    // plus the tokens already decoded into the KV cache. Next turn re-decodes
    // only the suffix after the common prefix (llama_batch_get_one with
    // pos==nullptr appends at the sequence's current end), killing the
    // linear first-token-time growth with conversation length. Cleared with
    // the context in free_locked(); truncation to the actually committed
    // prefix happens in generate_impl().
    std::vector<llama_token> kv_tokens;
    bool kv_valid = false;
    std::string last_stats;   // one-line perf summary for the in-app log
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
    g_engine.kv_valid = false;
    g_engine.kv_tokens.clear();
    g_engine.kv_tokens.shrink_to_fit();
    g_engine.last_stats.clear();
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

// ---------------------------------------------------------------------------
// CPU affinity pinning for the engine thread
//
// The target device (Honor 90 GT, Snapdragon 8+ Gen 1) is 1x Cortex-X2 +
// 3x Cortex-A710 (Linux CPU 0-3, the big cluster) + 4x Cortex-A510 (CPU 4-7).
// Cross-cluster decode with all cores spreads work over the little cores,
// dragging per-token latency to the slowest cluster's memory pipeline and
// adding scheduler noise. Community measurement on this same SoC class
// (paragon83114/llama-adreno) showed 4 threads bound to cores 0-3 beating 6
// threads across both clusters: tg128 31.4±0.0 vs 30.5±1.5 tok/s.
//
// llama.cpp spawns its compute threadpools (n_threads and n_threads_batch)
// from whichever thread calls llama_init_from_model() — the nativeInit JNI
// thread — and pool threads inherit the creator's affinity mask. Pinning
// *this* thread once therefore covers both pools for the whole context
// lifetime; the caller thread itself only orchestrates decode.
//
// Default (no property set): auto-detect the big cluster from sysfs and pin to
// it, so the optimization works on *any* big.LITTLE device, not just the one
// the hardcoded 0-3 mask was tuned for. Manual override still wins:
//   debug.acfcn.cpu_affinity = 0-3   (comma-separated ranges: 0-3,7)
//   debug.acfcn.cpu_affinity = auto  (force the sysfs auto-detection path)
//   debug.acfcn.cpu_affinity = none  (disable: kernel default placement)
// The requested mask is intersected with this process's actually-allowed
// set (cpuset / cgroup restrictions, e.g. background app limits), so a
// restricted device degrades to fewer cores instead of failing to start.

// Read a single integer from a sysfs file; returns -1 on any failure.
static int read_cpu_int(const char *path) {
    FILE *f = fopen(path, "r");
    if (!f) return -1;
    int v = -1;
    if (fscanf(f, "%d", &v) != 1) v = -1;
    fclose(f);
    return v;
}

// Parse /sys/devices/system/cpu/online (e.g. "0-3,5,7-8") into a core list.
static bool parse_online_cpus(std::vector<int> &cpus) {
    FILE *f = fopen("/sys/devices/system/cpu/online", "r");
    if (!f) return false;
    char buf[160];
    if (!fgets(buf, sizeof(buf), f)) { fclose(f); return false; }
    fclose(f);
    const char *p = buf;
    while (*p) {
        while (*p == ' ' || *p == '\n' || *p == '\t' || *p == ',') p++;
        if (!*p) break;
        int lo = 0, hi = 0;
        if (sscanf(p, "%d-%d", &lo, &hi) == 2) {
            for (int c = lo; c <= hi && c < 1024; ++c) cpus.push_back(c);
            while (*p && *p != ',') p++;
        } else if (sscanf(p, "%d", &lo) == 1) {
            if (lo < 1024) cpus.push_back(lo);
            while (*p && *p != ',') p++;
        } else {
            p++;
        }
    }
    return !cpus.empty();
}

// Rank online cores by compute capacity and fill `out` with the big cluster
// (the highest-capacity cores). Uses EAS cpu_capacity when present, else
// cpuinfo_max_freq, else treats all cores as equal (uses all). Returns true if
// `out` was populated. This is what makes the pinning device-agnostic.
//
// Tier rule: take every core in the top tier. When the top tier is a lone prime
// core (very common — e.g. cpu_capacity 1024 on the X2 vs ~900 on the A710s),
// also take the next tier down when it stays within 75% of the peak, so the pin
// covers the whole performance cluster instead of one core. Pinning a 4B model
// to a single core is what produced `big_suggested=1 -> threads=2`.
static bool detect_big_cluster(cpu_set_t *out) {
    CPU_ZERO(out);
    std::vector<int> cpus;
    if (!parse_online_cpus(cpus) || cpus.empty()) return false;
    const size_t n = cpus.size();
    // Read one source for *all* cores before falling back, so scores never mix
    // EAS capacity (e.g. 1024) with max-freq (e.g. 2800000) in the same ranking.
    std::vector<int> score(n, 0);
    bool any_capacity = false;
    for (size_t i = 0; i < n; ++i) {
        char pb[160];
        snprintf(pb, sizeof(pb), "/sys/devices/system/cpu/cpu%d/cpu_capacity", cpus[i]);
        int s = read_cpu_int(pb);
        if (s > 0) { score[i] = s; any_capacity = true; }
    }
    if (!any_capacity) {
        for (size_t i = 0; i < n; ++i) {
            char pb[160];
            snprintf(pb, sizeof(pb),
                     "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpus[i]);
            int s = read_cpu_int(pb);
            if (s > 0) score[i] = s;
        }
    }
    int maxs = 0;
    for (int s : score) maxs = std::max(maxs, s);
    // Highest tier strictly below the peak, used to widen a lone prime core.
    int nexts = 0;
    for (int s : score) if (s < maxs) nexts = std::max(nexts, s);
    int floor_score = maxs;
    if (maxs > 0 && nexts > 0 && (long long) nexts * 100 >= (long long) maxs * 75) {
        int top_n = 0;
        for (int s : score) if (s == maxs) ++top_n;
        if (top_n < 2) floor_score = nexts;
    }
    bool added = false;
    for (size_t i = 0; i < n; ++i) {
        if (maxs > 0 ? (score[i] >= floor_score && score[i] > 0) : true) {
            CPU_SET(cpus[i], out);
            added = true;
        }
    }
    return added;
}

void apply_cpu_affinity() {
    char value[PROP_VALUE_MAX] = {0};
    __system_property_get("debug.acfcn.cpu_affinity", value);
    const std::string prop(value[0] ? value : "");

    cpu_set_t want;
    CPU_ZERO(&want);

    if (prop.empty() || prop == "auto") {
        // Device-agnostic default: pin to the big cluster detected from sysfs.
        // Falls back to kernel default placement when detection is unavailable
        // (e.g. a device exposing neither cpu_capacity nor cpuinfo_max_freq).
        if (detect_big_cluster(&want)) {
            g_big_core_report = "cpu_affinity: auto-detected big cluster";
        } else {
            LOGI("cpu affinity: auto-detect failed; kernel default placement");
            g_big_core_report = "cpu_affinity: auto-detect failed, default placement";
            return;
        }
    } else if (prop == "none" || prop == "off" || prop == "false") {
        LOGI("cpu affinity: disabled by property (kernel default placement)");
        g_big_core_report = "cpu_affinity: disabled (debug.acfcn.cpu_affinity=" + prop + ")";
        return;
    } else {
        auto is_num = [](const std::string &s) {
            if (s.empty()) return false;
            for (char c : s) if (c < '0' || c > '9') return false;
            return true;
        };
        int added = 0;
        size_t i = 0;
        while (i < prop.size()) {
            size_t comma = prop.find(',', i);
            if (comma == std::string::npos) comma = prop.size();
            const std::string item = prop.substr(i, comma - i);
            i = comma + 1;
            const size_t dash = item.find('-');
            long lo = 0, hi = -1;
            if (dash == std::string::npos) {
                if (is_num(item)) { lo = hi = strtol(item.c_str(), nullptr, 10); }
            } else {
                const std::string a = item.substr(0, dash);
                const std::string b = item.substr(dash + 1);
                if (is_num(a) && is_num(b)) {
                    lo = strtol(a.c_str(), nullptr, 10);
                    hi = strtol(b.c_str(), nullptr, 10);
                    if (lo > hi) std::swap(lo, hi);
                }
            }
            for (long v = lo; v <= hi && v >= 0 && v < CPU_SETSIZE; ++v) {
                CPU_SET((int) v, &want);
                ++added;
            }
        }
        if (added == 0) {
            LOGI("cpu affinity: unparseable spec \"%s\"; leaving default placement",
                 prop.c_str());
            g_big_core_report = "cpu_affinity: unparseable spec, default placement";
            return;
        }
        g_big_core_report = "cpu_affinity: manual spec " + prop;
    }

    // Intersect with what this process is actually allowed to run on.
    cpu_set_t allowed;
    CPU_ZERO(&allowed);
    if (sched_getaffinity(0, sizeof(allowed), &allowed) != 0) {
        LOGI("cpu affinity: sched_getaffinity failed (%s); leaving default placement",
             strerror(errno));
        g_big_core_report = "cpu_affinity: sched_getaffinity failed, default placement";
        return;
    }
    for (int cpu = 0; cpu < CPU_SETSIZE; ++cpu) {
        if (CPU_ISSET(cpu, &want) && !CPU_ISSET(cpu, &allowed)) CPU_CLR(cpu, &want);
    }
    std::string mask;
    int n = 0;
    for (int cpu = 0; cpu < CPU_SETSIZE; ++cpu) {
        if (CPU_ISSET(cpu, &want)) {
            if (!mask.empty()) mask += ',';
            mask += std::to_string(cpu);
            ++n;
        }
    }
    if (n == 0) {
        LOGI("cpu affinity: requested mask disjoint from the allowed set; leaving default placement");
        g_big_core_report = "cpu_affinity: mask disjoint from allowed set, default placement";
        return;
    }
    if (sched_setaffinity(0, sizeof(want), &want) != 0) {
        LOGI("cpu affinity: sched_setaffinity({%s}) failed (%s); leaving default placement",
             mask.c_str(), strerror(errno));
        g_big_core_report = "cpu_affinity: sched_setaffinity failed, default placement";
        return;
    }
    LOGI("cpu affinity: engine thread pinned to cpu {%s} (llama threadpools inherit;"
         " override: debug.acfcn.cpu_affinity)", mask.c_str());
    g_big_core_report += " -> pinned cpu {" + mask + "}";
}

} // namespace

#ifdef ACFCN_USE_OPENCL
namespace {

bool path_exists(const char *path) {
    struct stat st;
    return path != nullptr && stat(path, &st) == 0;
}

// Android exposes no public libOpenCL.so, so the app ships a forwarding
// wrapper named libOpenCL.so (see opencl_wrapper.c +
// scripts/setup-opencl-ndk.sh; the Khronos ICD loader from v2.14 is gone
// because Adreno drivers implement no ICD entry and yielded 0 devices).
// The wrapper dlopen()s the vendor driver by absolute path. Unless the caller
// already set an override, seed the path candidates from well-known locations
// here; the wrapper understands OCL_ICD_FILENAMES (absolute paths) but NOT the
// .icd-dir machinery, so we skip that branch entirely.
void ensure_opencl_icd_configured() {
    const char *filenames = getenv("OCL_ICD_FILENAMES");
    const char *vendors = getenv("OCL_ICD_VENDORS");
    if (filenames != nullptr || vendors != nullptr) {
        LOGI("OpenCL: honoring existing ICD env (FILENAMES=%s VENDORS=%s)",
             filenames ? filenames : "-", vendors ? vendors : "-");
        return;
    }

    static const char *kIcdDirs[] = {
        // The loader's own Android default; Qualcomm Adreno ships its .icd here.
        "/system/vendor/Khronos/OpenCL/vendors",
        "/vendor/Khronos/OpenCL/vendors",
        // Some OEM builds relocate the vendor directory.
        "/vendor/etc/OpenCL/vendors",
        "/system/etc/OpenCL/vendors",
    };
    for (const char *dir : kIcdDirs) {
        if (path_exists(dir)) {
            setenv("OCL_ICD_VENDORS", dir, 0);
            LOGI("OpenCL: using ICD vendors dir %s", dir);
            return;
        }
    }

    static const char *kDriverLibs[] = {
        "/vendor/lib64/libOpenCL.so",
        "/vendor/lib64/libOpenCL_adreno.so",
        "/vendor/lib64/libOpenCL_adreno_driver.so",
    };
    for (const char *lib : kDriverLibs) {
        if (path_exists(lib)) {
            setenv("OCL_ICD_FILENAMES", lib, 0);
            LOGI("OpenCL: using ICD driver %s", lib);
            return;
        }
    }

    LOGI("OpenCL: no vendor ICD found; bundled loader will use its defaults");
}

// Enumerate the backends/devices the ggml registry actually sees at runtime.
// This is the ground truth for GPU offload: if OpenCL was compiled in but the
// driver could not be reached, it shows up as either a missing "OpenCL" entry
// or an entry with zero devices, instead of us guessing from layer placement.
std::string describe_backends() {
    std::string out;
    const size_t n = ggml_backend_reg_count();
    out += "backends=" + std::to_string(n);
    for (size_t i = 0; i < n; ++i) {
        ggml_backend_reg_t reg = ggml_backend_reg_get(i);
        if (reg == nullptr) continue;
        const char *rname = ggml_backend_reg_name(reg);
        const size_t devs = ggml_backend_reg_dev_count(reg);
        out += "\n  [" + std::to_string(i) + "] " + (rname ? rname : "?")
             + " devs=" + std::to_string(devs);
        for (size_t j = 0; j < devs; ++j) {
            ggml_backend_dev_t dev = ggml_backend_reg_dev_get(reg, j);
            if (dev == nullptr) continue;
            const char *dname = ggml_backend_dev_name(dev);
            out += "\n      - " + std::string(dname ? dname : "?")
                 + " type=" + std::to_string((int) ggml_backend_dev_type(dev));
        }
    }
    return out;
}

} // namespace
#endif

extern "C" JNIEXPORT jboolean JNICALL
Java_com_selfmod_agent_offline_native_LocalLlmEngine_nativeInit(
        JNIEnv *env, jobject /*thiz*/, jstring modelPath, jint nCtx, jint nThreads,
        jint nBatch, jobject progressCallback) {
    std::unique_lock<std::mutex> lock(g_engine.mu);
    wait_idle_locked(lock);
    g_engine.abort_flag.store(false);
    free_locked();

    // Phase timing surfaced in the in-app diagnostics: "loading" can sit still
    // for a long time on a memory-tight device, and without these numbers there
    // is no way to tell weight mapping from context/GPU-buffer setup.
    const auto t_start = std::chrono::steady_clock::now();

    // Must run before llama_init_from_model(): the compute threadpools are
    // created there and inherit this thread's affinity mask.
    apply_cpu_affinity();

#ifdef ACFCN_USE_OPENCL
    ensure_opencl_icd_configured();
    // Adreno xmem F16xF32 GEMM is ON by default since the llama.cpp b11640
    // bump (commit 3e390af, see CMakeLists.txt) — the backend logs "Adreno
    // xmem F16xF32 GEMM enabled" when the device accepts it. This is only
    // the escape hatch to force it back off (A/B, or a driver that
    // misbehaves with the temporary weight prepack). Note the path only
    // applies to f16/BF16-weight GEMMs (M>=64 prefill), so it is a no-op
    // for most quantized models.
    {
        char xmem[PROP_VALUE_MAX] = {0};
        const int len = __system_property_get("debug.acfcn.opencl_xmem", xmem);
        if (len > 0) {
            const std::string v(xmem);
            if (v == "0" || v == "off" || v == "false") {
                setenv("GGML_OPENCL_ADRENO_XMEM_GEMM", "0", 1);
                LOGI("opencl: Adreno xmem GEMM disabled via debug.acfcn.opencl_xmem=0");
            } else {
                setenv("GGML_OPENCL_ADRENO_XMEM_GEMM", "1", 1);
                LOGI("opencl: Adreno xmem GEMM force-enabled via debug.acfcn.opencl_xmem");
            }
        }
    }
#endif
    llama_backend_init();
    { std::lock_guard<std::mutex> lk(g_gpu_log_mu); g_gpu_log.clear(); g_load_info.clear(); }

    int gpu_layers = 0;
    llama_model_params mparams = llama_model_default_params();

    // GPU backend selection: Vulkan first (faster decode on Adreno), OpenCL
    // fallback, CPU last. A backend only appears here if it was compiled in.
    char gpu_pref[PROP_VALUE_MAX] = {0};
    __system_property_get("debug.acfcn.gpu", gpu_pref);
    std::string gpu_name, gpu_how;
    ggml_backend_dev_t gpu_dev = pick_gpu_device(gpu_pref, gpu_name, gpu_how);
    gpu_layers = get_property_int("debug.acfcn.gpu_layers", gpu_dev ? 99 : 0);
    if (gpu_layers < 0) gpu_layers = 0;
    if (!gpu_dev) gpu_layers = 0;
    mparams.n_gpu_layers = gpu_layers;
    g_engine.gpu_layers.store(gpu_layers);
    if (gpu_dev) {
        // NULL-terminated device list; llama.cpp offloads n_gpu_layers layers
        // to this device and keeps the rest on the CPU automatically.
        ggml_backend_dev_t devices[2] = { gpu_dev, nullptr };
        mparams.devices = devices;
        LOGI("gpu backend: %s (via %s), n_gpu_layers=%d (override: debug.acfcn.gpu / debug.acfcn.gpu_layers)",
             gpu_name.c_str(), gpu_how.c_str(), (int) mparams.n_gpu_layers);
    } else {
        LOGI("no GPU backend device selected (override: debug.acfcn.gpu); running CPU-only");
    }

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
    LOGI("loading model (mmap): %s (n_gpu_layers=%d)", path.c_str(), (int) mparams.n_gpu_layers);
    g_engine.model = llama_model_load_from_file(path.c_str(), mparams);
    if (!g_engine.model && gpu_dev) {
        // The selected GPU backend refused the model (driver init failure, out
        // of VRAM, ...). Retry with llama.cpp's own device choice before
        // giving up on the GPU entirely.
        LOGI("model load failed on %s; retrying with automatic device selection",
             gpu_name.c_str());
        mparams.devices = nullptr;
        g_engine.model = llama_model_load_from_file(path.c_str(), mparams);
    }
    if (!g_engine.model && mparams.n_gpu_layers != 0) {
        LOGI("model load failed on GPU; retrying CPU-only");
        mparams.n_gpu_layers = 0;
        g_engine.gpu_layers.store(0);
        g_engine.model = llama_model_load_from_file(path.c_str(), mparams);
    }

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
    const auto t_model = std::chrono::steady_clock::now();
    g_engine.vocab = llama_model_get_vocab(g_engine.model);

    llama_context_params cparams = llama_context_default_params();
    int batch = nBatch > 0 ? (int) nBatch : 64;
    if (batch < 8) batch = 8;
    if (batch > 512) batch = 512;
    int threads = nThreads;
    int prop_threads = get_property_int("debug.acfcn.threads", 0);
    if (prop_threads > 0) threads = prop_threads;
    cparams.n_ctx     = (uint32_t) nCtx;
    cparams.n_batch   = (uint32_t) batch;
    cparams.n_ubatch  = (uint32_t) batch;
    cparams.n_threads = threads;
    cparams.n_threads_batch = threads;

    // Flash attention + KV cache quantization, unified across the GPU and CPU
    // decode paths (the "match MNN speed" line).
    //
    // Decode is memory-bandwidth bound and re-reads the whole KV cache on every
    // token, so KV *width* and the attention memory traffic are the dominant
    // per-token costs. q8_0 KV is 4x smaller than f32 (1 vs 4 bytes), and flash
    // attention fuses the softmax into registers so the K/V are read once per
    // query instead of being scattered across the naive attention loops — this
    // is exactly what makes MNN's CPU path fast. Both GPU backends and the
    // current llama.cpp CPU backend have a quantized-KV FA path: ggml-opencl
    // ships a dedicated q8_0 FA variant (FA_VARIANT_Q8_0) plus a q4_0 variant,
    // ggml-vulkan's FA dequantizes q8_0 K/V inline, and ggml-cpu's FA dequant
    // q8_0 K/V as it streams. q4_0 KV (8x) is available behind debug.acfcn.kv.
    //
    // Tunables (take effect on the next model load):
    //   debug.acfcn.fa=off                 -> f16 KV, flash attn off (old safe path)
    //   debug.acfcn.kv=q8_0|q4_0|f16|f32   -> KV cache type for the FA path (default q8_0)
    // Any context that fails to build with the quantized KV + FA (e.g. a CPU
    // core without a matching FA kernel, or the OpenCL/Adreno f16-KV compiler
    // crash) falls back to f16 KV + FA off, so a kernel problem degrades speed,
    // not loadability.
    {
        char value[PROP_VALUE_MAX] = {0};
        bool fa = true;
        int len = __system_property_get("debug.acfcn.fa", value);
        if (len > 0) {
            std::string v(value);
            fa = !(v == "off" || v == "0" || v == "false");
        }
        ggml_type ktype = GGML_TYPE_Q8_0;
        len = __system_property_get("debug.acfcn.kv", value);
        if (len > 0) {
            std::string v(value);
            if (v == "q4_0")      ktype = GGML_TYPE_Q4_0;
            else if (v == "f16")  ktype = GGML_TYPE_F16;
            else if (v == "f32")  ktype = GGML_TYPE_F32;
            else if (v == "q8_0") ktype = GGML_TYPE_Q8_0;
        }
        // Flash attention is a *quantized*-KV optimization: it dequantizes the
        // q8_0/q4_0 K/V as it streams, so it only makes sense with a quantized
        // KV. f16/f32 are the explicit escape hatches (debug.acfcn.kv) and stay
        // on the old plain attention, matching PR #87's "kv=f16/f32 revert to
        // safe" contract — this also sidesteps the OpenCL/Adreno f16-KV compiler
        // crash (E031.41) that originally forced FA off on that backend.
        const bool use_fa =
            fa && (ktype == GGML_TYPE_Q8_0 || ktype == GGML_TYPE_Q4_0);
        auto make_ctx = [&](ggml_type kt, bool fa_on) -> llama_context * {
            cparams.type_k = kt;
            cparams.type_v = kt;
            cparams.flash_attn_type = fa_on ? LLAMA_FLASH_ATTN_TYPE_ENABLED
                                            : LLAMA_FLASH_ATTN_TYPE_DISABLED;
            return llama_init_from_model(g_engine.model, cparams);
        };
        // Try the quantized-KV + FA path first, GPU and CPU alike. The previous
        // build hard-coded the CPU-only path to f16 + FA-off "for portability";
        // that left every no-GPU-offload device (the majority of CPU-only
        // builds) on the slow path. Quantized-KV FA on CPU is the single
        // biggest remaining CPU-decode speedup and the main reason MNN's CPU
        // path is fast: it fuses the softmax into registers and dequantizes the
        // q8_0 K/V as it streams, so the per-token attention memory traffic
        // roughly halves vs. the old f16 naive attention. If the quantized-KV +
        // FA context cannot build (a CPU core without a matching FA kernel, or
        // the OpenCL/Adreno f16-KV compiler crash), fall back to f16 KV + FA
        // off so a kernel/capacity problem degrades speed, never loadability.
        //   debug.acfcn.fa=off  -> revert to f16 + FA-off on CPU too
        //   debug.acfcn.kv=f16  -> quantized-KV build skipped, f16 + FA off
        g_engine.ctx = make_ctx(ktype, use_fa);
        if (!g_engine.ctx) {
            LOGI("ctx build failed with kv=%s + FA=%s; retrying f16 KV, FA off",
                 ggml_type_name(ktype), use_fa ? "on" : "off");
            g_engine.ctx = make_ctx(GGML_TYPE_F16, false);
        }
        LOGI("flash attn %s, kv cache %s on %s (n_gpu_layers=%d)",
             use_fa ? "enabled" : "disabled", ggml_type_name(cparams.type_k),
             gpu_how.empty() ? "cpu" : gpu_how.c_str(), (int) mparams.n_gpu_layers);
    }

    LOGI("ctx params: n_ctx=%u n_batch=%u n_ubatch=%u threads=%d",
         cparams.n_ctx, cparams.n_batch, cparams.n_ubatch, threads);

    if (!g_engine.ctx) {
        LOGE("failed to create context");
        free_locked();
        return JNI_FALSE;
    }
    llama_set_abort_callback(g_engine.ctx, abort_trampoline, &g_engine);
    g_engine.n_ctx.store(nCtx);
    g_engine.n_batch = batch;

    // --- Kernel warmup (moves first-chat latency into model load) -----------
    // The first llama_decode() on a GPU backend compiles every kernel it
    // touches — Adreno drivers keep no shader cache across processes — so
    // that cost used to be billed to the user's first message (seconds of
    // "stuck" first token after a perfectly fast load). Decode one token
    // here (the exact shape every generated token costs) plus a small
    // prefill-shaped batch, then wipe the KV cache: the work happens while
    // the load progress bar is still on screen. Non-fatal by design — a
    // warmup failure only falls back to paying it at first chat.
    //   debug.acfcn.warmup = 0   # disable (default: on)
    std::string warmup_report;
    {
        char value[PROP_VALUE_MAX] = {0};
        bool warmup = true;
        int len = __system_property_get("debug.acfcn.warmup", value);
        if (len > 0) {
            std::string v(value);
            warmup = !(v == "0" || v == "off" || v == "false");
        }
        if (!warmup) {
            LOGI("kernel warmup disabled via debug.acfcn.warmup");
            warmup_report = "kernel warmup: disabled";
        } else if (g_engine.abort_flag.load()) {
            // Load itself was cancelled; do not start new GPU work.
            warmup_report = "kernel warmup: skipped (load aborted)";
        } else {
            llama_memory_t wmem = llama_get_memory(g_engine.ctx);
            llama_token warm = llama_vocab_bos(g_engine.vocab);
            const int wpf = std::min((int) g_engine.n_batch, 64);
            int wrc = -1;
            if (warm >= 0) {
                // 1) decode shape: one token, what every generated token costs
                llama_batch wb = llama_batch_get_one(&warm, 1);
                wrc = llama_decode(g_engine.ctx, wb);
                // 2) prefill shape: a small ubatch (shape-generic kernels cover
                //    the larger real prefills from here)
                if (wrc == 0) {
                    std::vector<llama_token> pad(wpf > 1 ? wpf : 1, warm);
                    llama_batch pb = llama_batch_get_one(pad.data(), (int) pad.size());
                    wrc = llama_decode(g_engine.ctx, pb);
                }
            }
            llama_memory_clear(wmem, true);   // warmup must not pollute the KV
            if (warm < 0) {
                LOGI("kernel warmup skipped: no BOS token");
                warmup_report = "kernel warmup: skipped (no BOS)";
            } else if (wrc == 0) {
                LOGI("kernel warmup done (decode + %d-token prefill shape), kv cleared", wpf);
                warmup_report = "kernel warmup: ok (decode + " + std::to_string(wpf) + "-token prefill)";
            } else {
                LOGI("kernel warmup decode failed rc=%d (non-fatal; first chat pays it)", wrc);
                warmup_report = "kernel warmup: failed rc=" + std::to_string(wrc);
            }
            // A cancel pressed during the warmup window was aimed at the load
            // UI, which has already finished; leaving abort_flag set would
            // instantly cancel the next chat instead.
            g_engine.abort_flag.store(false);
        }
    }
    const auto t_ctx = std::chrono::steady_clock::now();

    // Build the load summary surfaced to Kotlin for the in-app diagnostics:
    // whether the build can offload, how many layers were requested, the CPU
    // feature set (confirms the dotprod build) and the captured OpenCL /
    // layer-placement lines from llama.cpp.
    {
        char desc[512] = {0};
        llama_model_desc(g_engine.model, desc, sizeof(desc));
        std::lock_guard<std::mutex> lk(g_gpu_log_mu);
        std::string info = std::string("model=") + desc
                    + " gpu_backend=" + (gpu_name.empty() ? std::string("cpu")
                                                             : gpu_name + " (" + gpu_how + ")")
                    + " gpu_offload_build=" + (llama_supports_gpu_offload() ? "yes" : "no")
                    + " requested_gpu_layers=" + std::to_string(gpu_layers)
                    + "\n" + llama_print_system_info();
#ifdef ACFCN_USE_OPENCL
        const char *icd_v = getenv("OCL_ICD_VENDORS");
        const char *icd_f = getenv("OCL_ICD_FILENAMES");
        info += "\n" + describe_backends();
        info += "\nicd_vendors=" + std::string(icd_v ? icd_v : "-");
        info += " icd_filenames=" + std::string(icd_f ? icd_f : "-");
#endif
        info += "\n" + g_gpu_log;
        if (!g_big_core_report.empty()) info += "\n" + g_big_core_report;
        if (!warmup_report.empty()) info += "\n" + warmup_report;
        auto ms = [](std::chrono::steady_clock::time_point a,
                     std::chrono::steady_clock::time_point b) {
            return std::chrono::duration_cast<std::chrono::milliseconds>(b - a).count();
        };
        info += "\nload_ms=" + std::to_string(ms(t_start, t_model))
              + " ctx_ms=" + std::to_string(ms(t_model, t_ctx))
              + " total_ms=" + std::to_string(ms(t_start, t_ctx));
        g_load_info = info;
    }

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
    std::vector<llama_token> prev_kv;   // committed prefix snapshot from last turn
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
        prev_kv = g_engine.kv_valid ? g_engine.kv_tokens : std::vector<llama_token>();
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

    // --- KV continuation -------------------------------------------------
    // Compare against the committed prefix of the previous turn and keep
    // only the differing suffix to decode. This llama_batch_get_one() passes
    // pos/seq = nullptr, so llama appends each chunk at the sequence's
    // current end — after llama_memory_seq_rm() drops the stale tail the next
    // chunk lands exactly at `common`. First-token time used to grow linearly
    // with the conversation because we re-prefilled everything each turn.
    llama_memory_t mem = llama_get_memory(ctx);
    int common = 0;
    bool kv_ok = true;
    if (!prev_kv.empty()) {
        const size_t max_cmp = std::min(prev_kv.size(), tokens.size());
        while ((size_t) common < max_cmp && prev_kv[common] == tokens[common]) {
            common++;
        }
        if (common >= (int) tokens.size()) common = (int) tokens.size() - 1;
        if (common < 8) {
            common = 0;
        } else if (!llama_memory_seq_rm(mem, 0, common, -1)) {
            LOGI("kv seq_rm failed; full prefill instead");
            common = 0;
        }
    }
    if (common > 0) {
        LOGI("kv reuse: %d/%d prompt tokens already cached", common, (int) tokens.size());
    } else {
        llama_memory_clear(mem, true);
    }

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
    bool prefill_complete = false;

    std::vector<llama_token> gen_committed;   // reply ids actually decoded

    int pos = common;
    long long prefill_ms = 0, decode_ms = 0;
    int n_new = 0;
    LOGI("prefill start: %d prompt tokens from pos %d", (int) tokens.size(), common);
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
        if (rc == 2) {
            LOGI("prefill aborted by llama at pos %d rc=%d", pos, rc);
            result = -98;
            kv_ok = false;   // partial-chunk commit state is unknowable
            fail = true;
            break;
        }
        if (aborted()) {
            LOGI("prefill cancelled at pos %d", pos);
            result = -98;
            fail = true;     // prefix tokens[0..pos) is fully committed
            break;
        }
        if (rc == 1) {
            LOGE("prompt needs more KV slots at pos %d (n_ctx=%d)", pos, ctx_size);
            result = -5;
            kv_ok = false;
            fail = true;
            break;
        }
        if (rc != 0) {
            LOGE("prompt decode failed rc=%d", rc);
            result = -3;
            kv_ok = false;
            fail = true;
            break;
        }
        pos += chunk;
    }
    if (!fail) prefill_complete = true;

    if (!fail) {
        prefill_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - prefill_t0).count();
        n_new = (int) tokens.size() - common;
        double prefill_tps = prefill_ms > 0
                ? (double) n_new * 1000.0 / (double) prefill_ms : 0.0;
        LOGI("prefill done in %lld ms (%d new tokens, %.2f tok/s)",
             (long long) prefill_ms, n_new, prefill_tps);

        auto decode_t0 = std::chrono::steady_clock::now();
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
            if (rc == 0) gen_committed.push_back(id);
            if (rc == 2) {
                LOGI("decode aborted mid-step at token %d rc=%d", i, rc);
                result = -98;
                kv_ok = false;
                fail = true;
                break;
            }
            if (aborted()) {
                LOGI("generation cancelled at token %d", i);
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
                kv_ok = false;
                break;
            }
        }
        if (!fail) result = emitted;

        decode_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - decode_t0).count();
        double decode_tps = decode_ms > 0 ? (double) emitted * 1000.0 / (double) decode_ms : 0.0;
        LOGI("decode done in %lld ms (%d tokens, %.2f tok/s)",
             (long long) decode_ms, emitted, decode_tps);
    }

    // Commit the KV state so the next turn can reuse this prefix.
    {
        std::lock_guard<std::mutex> lock(g_engine.mu);
        if (g_engine.ctx == ctx && kv_ok) {
            int keep = prefill_complete ? (int) tokens.size() : pos;
            if (keep < 0) keep = 0;
            if (keep > (int) tokens.size()) keep = (int) tokens.size();
            std::vector<llama_token> saved;
            saved.reserve(keep + (prefill_complete ? gen_committed.size() : 0));
            saved.insert(saved.end(), tokens.begin(), tokens.begin() + keep);
            if (prefill_complete) {
                saved.insert(saved.end(), gen_committed.begin(), gen_committed.end());
            }
            g_engine.kv_tokens = std::move(saved);
            g_engine.kv_valid = !g_engine.kv_tokens.empty();
            char stats[256];
            snprintf(stats, sizeof(stats),
                     "chat perf: prefill %lldms (%d new/%d tok), decode %lldms (%d tok, %.2f tok/s), kv reuse %d/%d",
                     prefill_ms, n_new, (int) tokens.size(), decode_ms, emitted,
                     decode_ms > 0 ? (double) emitted * 1000.0 / (double) decode_ms : 0.0,
                     common, (int) tokens.size());
            g_engine.last_stats = stats;
        } else {
            g_engine.kv_tokens.clear();
            g_engine.kv_valid = false;
        }
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

// Number of layers the last model load requested on the GPU (0 = CPU only).
// Surfaced to Kotlin so the in-app diagnostics can show whether GPU offload was
// active without needing adb logcat.
extern "C" JNIEXPORT jint JNICALL
Java_com_selfmod_agent_offline_native_LocalLlmEngine_nativeLastGpuLayers(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    return g_engine.gpu_layers.load();
}

// Human-readable summary of the last model load (build GPU support, requested
// layers, CPU features, OpenCL/placement log lines) for the in-app diagnostics.
extern "C" JNIEXPORT jstring JNICALL
Java_com_selfmod_agent_offline_native_LocalLlmEngine_nativeLoadInfo(
        JNIEnv *env, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lk(g_gpu_log_mu);
    return env->NewStringUTF(g_load_info.c_str());
}

// One-line perf summary of the last generation (prefix reuse, prefill/decode
// timings) so the in-app diagnostics can verify a speedup without adb.
extern "C" JNIEXPORT jstring JNICALL
Java_com_selfmod_agent_offline_native_LocalLlmEngine_nativeLastChatStats(
        JNIEnv *env, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lk(g_engine.mu);
    return env->NewStringUTF(g_engine.last_stats.c_str());
}

// Human-readable summary of the big-core affinity decision at the last model
// load (which cores were pinned, or why affinity was skipped) so the in-app
// diagnostics can confirm the perf optimization took effect without adb.
extern "C" JNIEXPORT jstring JNICALL
Java_com_selfmod_agent_offline_native_LocalLlmEngine_nativeBigCoreInfo(
        JNIEnv *env, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lk(g_gpu_log_mu);
    return env->NewStringUTF(g_big_core_report.c_str());
}

// Recommended llama.cpp worker count: big-cluster size intersected with this
// process's allowed CPU set. Returns the allowed-set size when sysfs detection
// is unavailable, then 4, so a device that blocks cpu_capacity still gets the
// tuned default instead of the 2-thread floor. Keeping this next to
// detect_big_cluster() means the thread count and the affinity mask can never
// disagree.
extern "C" JNIEXPORT jint JNICALL
Java_com_selfmod_agent_offline_native_LocalLlmEngine_nativeSuggestedThreads(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    cpu_set_t allowed;
    int allowed_n = 0;
    if (sched_getaffinity(0, sizeof(allowed), &allowed) == 0) {
        allowed_n = CPU_COUNT(&allowed);
    }
    cpu_set_t big;
    int big_n = 0;
    int usable = 0;
    std::string mask;
    if (detect_big_cluster(&big)) {
        for (int c = 0; c < CPU_SETSIZE; ++c) {
            if (!CPU_ISSET(c, &big)) continue;
            ++big_n;
            if (!mask.empty()) mask += ",";
            mask += std::to_string(c);
            if (allowed_n == 0 || CPU_ISSET(c, &allowed)) ++usable;
        }
    }
    int suggested = usable > 0 ? usable : (allowed_n > 0 ? allowed_n : 4);
    LOGI("cpu threads: big=%d [%s] allowed=%d usable=%d -> suggested=%d",
         big_n, mask.c_str(), allowed_n, usable, suggested);
    return suggested;
}
