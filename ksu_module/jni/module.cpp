/*
 * PcMic Zygisk Module
 *
 * Hooks AudioRecord native read methods via RegisterNatives.
 * Uses ArtMethod offset detection to SAVE original function pointers
 * so we can fall through to the real microphone when PC is not connected.
 */

#include <cstdlib>
#include <cstring>
#include <unistd.h>
#include <fcntl.h>
#include <dlfcn.h>
#include <pthread.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/mman.h>
#include <android/log.h>
#include <jni.h>

#include "zygisk.hpp"

#define TAG "PcMic-Zygisk"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define UNIX_SOCK_PATH "/dev/socket/pcmic"
#define CONFIG_PATH "/data/adb/pcmic/config.properties"

using zygisk::Api;
using zygisk::AppSpecializeArgs;
using zygisk::ServerSpecializeArgs;

/* ---- ArtMethod JNI offset detection ---- */
static int g_jni_offset = -1;

static jlong jni_probe_func(JNIEnv*, jclass) { return 0xDEAD; }

static bool make_page_writable(void* addr) {
    long page = sysconf(_SC_PAGESIZE);
    uintptr_t base = (uintptr_t)addr & ~(page - 1);
    return mprotect((void*)base, page * 2, PROT_READ | PROT_WRITE | PROT_EXEC) == 0;
}

static void detect_jni_offset(JNIEnv* env) {
    jclass clazz = env->FindClass("java/lang/System");
    if (!clazz) { env->ExceptionClear(); return; }

    jmethodID mid = env->GetStaticMethodID(clazz, "nanoTime", "()J");
    if (!mid) { env->ExceptionClear(); env->DeleteLocalRef(clazz); return; }

    /* Save original ArtMethod bytes */
    uint8_t before[128];
    memcpy(before, (uint8_t*)mid, sizeof(before));

    /* Register probe function */
    JNINativeMethod m = {(char*)"nanoTime", (char*)"()J", (void*)jni_probe_func};
    if (env->RegisterNatives(clazz, &m, 1) != 0) {
        env->ExceptionClear();
        env->DeleteLocalRef(clazz);
        return;
    }

    /* Find which pointer slot changed to our probe */
    for (int i = 0; i <= 128 - (int)sizeof(void*); i += sizeof(void*)) {
        void* new_val = *(void**)((uint8_t*)mid + i);
        void* old_val = *(void**)(before + i);
        if (new_val == (void*)jni_probe_func && old_val != new_val) {
            g_jni_offset = i;
            LOGI("JNI entry offset = %d", i);
            /* Restore original nanoTime implementation */
            make_page_writable((uint8_t*)mid + i);
            *(void**)((uint8_t*)mid + i) = old_val;
            break;
        }
    }
    env->DeleteLocalRef(clazz);
}

/* ---- Unix socket client ---- */
static int g_sock_fd = -1;
static pthread_mutex_t g_sock_lock = PTHREAD_MUTEX_INITIALIZER;

static int daemon_connect() {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, UNIX_SOCK_PATH, sizeof(addr.sun_path) - 1);
    struct timeval tv = {0, 100000};
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
    if (connect(fd, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

/* Returns: >0 = got PC audio, -1 = daemon error, -2 = PC not connected */
static int daemon_read_audio(uint8_t* buf, int len) {
    pthread_mutex_lock(&g_sock_lock);
    if (g_sock_fd < 0) {
        g_sock_fd = daemon_connect();
        if (g_sock_fd < 0) {
            pthread_mutex_unlock(&g_sock_lock);
            return -1;
        }
    }
    uint8_t req[4] = {(uint8_t)(len&0xFF), (uint8_t)((len>>8)&0xFF),
                      (uint8_t)((len>>16)&0xFF), (uint8_t)((len>>24)&0xFF)};
    if (send(g_sock_fd, req, 4, 0) != 4) {
        close(g_sock_fd); g_sock_fd = -1;
        pthread_mutex_unlock(&g_sock_lock);
        return -1;
    }
    uint8_t hdr[4];
    if (recv(g_sock_fd, hdr, 4, MSG_WAITALL) != 4) {
        close(g_sock_fd); g_sock_fd = -1;
        pthread_mutex_unlock(&g_sock_lock);
        return -1;
    }
    int pc_connected = hdr[0];
    /* Always consume data to keep socket in sync */
    int remaining = len, total = 0;
    while (remaining > 0) {
        uint8_t tmp[4096];
        int chunk = remaining > (int)sizeof(tmp) ? (int)sizeof(tmp) : remaining;
        int n = recv(g_sock_fd, pc_connected ? buf + total : tmp, chunk, MSG_WAITALL);
        if (n <= 0) {
            close(g_sock_fd); g_sock_fd = -1;
            pthread_mutex_unlock(&g_sock_lock);
            return -1;
        }
        total += n;
        remaining -= n;
    }
    pthread_mutex_unlock(&g_sock_lock);
    return pc_connected ? len : -2;
}

/* ---- Hook functions ---- */
typedef jint (*read_byte_fn)(JNIEnv*, jobject, jbyteArray, jint, jint, jboolean);
typedef jint (*read_short_fn)(JNIEnv*, jobject, jshortArray, jint, jint, jboolean);
typedef jint (*read_direct_fn)(JNIEnv*, jobject, jobject, jint, jboolean);

static read_byte_fn orig_read_byte = nullptr;
static read_short_fn orig_read_short = nullptr;
static read_direct_fn orig_read_direct = nullptr;

static jint hook_read_byte(JNIEnv* env, jobject thiz,
                           jbyteArray buf, jint off, jint size, jboolean blocking) {
    if (size <= 0 || !orig_read_byte)
        return orig_read_byte ? orig_read_byte(env, thiz, buf, off, size, blocking) : 0;

    uint8_t* tmp = (uint8_t*)malloc(size);
    if (!tmp) return orig_read_byte(env, thiz, buf, off, size, blocking);

    int ret = daemon_read_audio(tmp, size);
    if (ret < 0) {
        free(tmp);
        return orig_read_byte(env, thiz, buf, off, size, blocking);
    }
    env->SetByteArrayRegion(buf, off, size, (jbyte*)tmp);
    free(tmp);
    return size;
}

static jint hook_read_short(JNIEnv* env, jobject thiz,
                            jshortArray buf, jint off, jint size, jboolean blocking) {
    if (size <= 0 || !orig_read_short)
        return orig_read_short ? orig_read_short(env, thiz, buf, off, size, blocking) : 0;

    int byteLen = size * 2;
    uint8_t* tmp = (uint8_t*)malloc(byteLen);
    if (!tmp) return orig_read_short(env, thiz, buf, off, size, blocking);

    int ret = daemon_read_audio(tmp, byteLen);
    if (ret < 0) {
        free(tmp);
        return orig_read_short(env, thiz, buf, off, size, blocking);
    }
    env->SetShortArrayRegion(buf, off, size, (jshort*)tmp);
    free(tmp);
    return size;
}

static jint hook_read_direct(JNIEnv* env, jobject thiz,
                             jobject jbuf, jint size, jboolean blocking) {
    if (size <= 0 || !orig_read_direct)
        return orig_read_direct ? orig_read_direct(env, thiz, jbuf, size, blocking) : 0;

    uint8_t* ptr = (uint8_t*)env->GetDirectBufferAddress(jbuf);
    if (!ptr) return orig_read_direct(env, thiz, jbuf, size, blocking);

    int ret = daemon_read_audio(ptr, size);
    if (ret < 0) return orig_read_direct(env, thiz, jbuf, size, blocking);
    return size;
}

/* ---- Save original + install hook ---- */
static void* save_orig_and_hook(JNIEnv* env, jclass clazz,
                                const char* name, const char* sig, void* hook_fn) {
    if (g_jni_offset < 0) return nullptr;
    jmethodID mid = env->GetMethodID(clazz, name, sig);
    if (!mid) { env->ExceptionClear(); return nullptr; }

    /* Read original JNI function pointer from ArtMethod */
    void* original = *(void**)((uint8_t*)mid + g_jni_offset);

    /* Install our hook */
    JNINativeMethod m = {(char*)name, (char*)sig, hook_fn};
    if (env->RegisterNatives(clazz, &m, 1) != 0) {
        env->ExceptionClear();
        return nullptr;
    }
    return original;
}

static bool install_hooks(JNIEnv* env) {
    if (g_jni_offset < 0) {
        LOGE("JNI offset not detected, cannot hook");
        return false;
    }
    jclass clazz = env->FindClass("android/media/AudioRecord");
    if (!clazz) { env->ExceptionClear(); return false; }

    int count = 0;
    /* Try with boolean param first (Android 6+), then legacy */
    void* p;
    p = save_orig_and_hook(env, clazz, "native_read_in_byte_array", "([BIIZ)I", (void*)hook_read_byte);
    if (p) { orig_read_byte = (read_byte_fn)p; count++; LOGI("Hooked byte_array read"); }
    else {
        p = save_orig_and_hook(env, clazz, "native_read_in_byte_array", "([BII)I", (void*)hook_read_byte);
        if (p) { orig_read_byte = (read_byte_fn)p; count++; LOGI("Hooked byte_array (legacy)"); }
    }

    p = save_orig_and_hook(env, clazz, "native_read_in_short_array", "([SIIZ)I", (void*)hook_read_short);
    if (p) { orig_read_short = (read_short_fn)p; count++; LOGI("Hooked short_array read"); }
    else {
        p = save_orig_and_hook(env, clazz, "native_read_in_short_array", "([SII)I", (void*)hook_read_short);
        if (p) { orig_read_short = (read_short_fn)p; count++; }
    }

    p = save_orig_and_hook(env, clazz, "native_read_in_direct_buffer", "(Ljava/lang/Object;IZ)I", (void*)hook_read_direct);
    if (p) { orig_read_direct = (read_direct_fn)p; count++; LOGI("Hooked direct_buffer read"); }
    else {
        p = save_orig_and_hook(env, clazz, "native_read_in_direct_buffer", "(Ljava/lang/Object;I)I", (void*)hook_read_direct);
        if (p) { orig_read_direct = (read_direct_fn)p; count++; }
    }

    env->DeleteLocalRef(clazz);
    LOGI("Installed %d hooks (jni_offset=%d)", count, g_jni_offset);
    return count > 0;
}

/* ---- Zygisk Module ---- */
class PcMicModule : public zygisk::ModuleBase {
public:
    void onLoad(Api* api, JNIEnv* env) override {
        this->api = api;
        this->env = env;
    }

    void preAppSpecialize(AppSpecializeArgs* args) override {}

    void postAppSpecialize(const AppSpecializeArgs* args) override {
        /* Check config */
        FILE* f = fopen(CONFIG_PATH, "r");
        if (f) {
            char line[256];
            while (fgets(line, sizeof(line), f)) {
                if (strncmp(line, "enabled=", 8) == 0) {
                    char* val = line + 8;
                    while (*val == ' ') val++;
                    if (strncmp(val, "false", 5) == 0) {
                        fclose(f);
                        LOGI("Disabled in config, skipping");
                        return;
                    }
                }
            }
            fclose(f);
        }

        /* Check daemon is running */
        if (access(UNIX_SOCK_PATH, F_OK) != 0) {
            LOGI("Daemon socket not found, skipping");
            return;
        }

        /* Detect JNI offset then install hooks */
        detect_jni_offset(env);
        if (g_jni_offset < 0) {
            LOGE("Failed to detect JNI offset");
            return;
        }
        install_hooks(env);
    }

    void preServerSpecialize(ServerSpecializeArgs* args) override {}
    void postServerSpecialize(const ServerSpecializeArgs* args) override {}

private:
    Api* api = nullptr;
    JNIEnv* env = nullptr;
};

REGISTER_ZYGISK_MODULE(PcMicModule)
