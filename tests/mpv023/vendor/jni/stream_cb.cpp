#include "stream_cb.h"
#include "utf8.h"

#include <mpv/stream_cb.h>
#include <pthread.h>

#include <cstdint>
#include <cstring>
#include <mutex>
#include <unordered_map>
#include <unordered_set>
#include <vector>

static JavaVM *g_vm;
static jmethodID m_provider_open;
static jmethodID m_stream_read, m_stream_seek, m_stream_size, m_stream_close, m_stream_cancel, m_stream_seekable;
static pthread_key_t g_detach_key;

/* Handles that own their core, and the providers registered through each of them. */
static std::mutex g_lock;
static std::unordered_set<mpv_handle *> g_owners;
static std::unordered_map<mpv_handle *, std::vector<jobject>> g_providers;

/* A thread that attached itself detaches when it exits, through the key's destructor. */
static void detach_on_exit(void *) { g_vm->DetachCurrentThread(); }

static JNIEnv *env_for_this_thread() {
    JNIEnv *env = nullptr;
    if (g_vm->GetEnv((void **) &env, JNI_VERSION_1_6) == JNI_OK) return env;
    if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
    pthread_setspecific(g_detach_key, env);
    return env;
}

static void clear_exception(JNIEnv *env) {
    if (env->ExceptionCheck()) env->ExceptionClear();
}

bool stream_cb_init(JavaVM *vm, JNIEnv *env) {
    g_vm = vm;
    pthread_key_create(&g_detach_key, detach_on_exit);
    jclass provider = env->FindClass("io/github/yuroyami/libmpvkt/stream/MpvStreamProvider");
    jclass stream = env->FindClass("io/github/yuroyami/libmpvkt/stream/MpvStream");
    if (!provider || !stream) return false;
    m_provider_open = env->GetMethodID(provider, "open", "(Ljava/lang/String;)Lio/github/yuroyami/libmpvkt/stream/MpvStream;");
    m_stream_read = env->GetMethodID(stream, "read", "(Ljava/nio/ByteBuffer;)I");
    m_stream_seek = env->GetMethodID(stream, "seek", "(J)J");
    m_stream_size = env->GetMethodID(stream, "size", "()J");
    m_stream_close = env->GetMethodID(stream, "close", "()V");
    m_stream_cancel = env->GetMethodID(stream, "cancel", "()V");
    m_stream_seekable = env->GetMethodID(stream, "isSeekable", "()Z");
    return m_provider_open && m_stream_read && m_stream_seek && m_stream_size && m_stream_close && m_stream_cancel && m_stream_seekable;
}

/*
 * One open stream. mpv unlinks the cancel callback only after close_fn has returned, so a cancel
 * can still arrive then. close therefore clears `stream` under the lock and keeps the cookie, a few
 * bytes per stream, so that a late cancel never touches freed memory.
 */
struct StreamCookie {
    std::mutex lock;
    jobject stream = nullptr; // global reference, null once closed
};

static int64_t cb_read(void *cookie, char *buf, uint64_t nbytes) {
    JNIEnv *env = env_for_this_thread();
    if (!env) return MPV_ERROR_GENERIC;
    jobject buffer = env->NewDirectByteBuffer(buf, (jlong) nbytes);
    if (!buffer) { clear_exception(env); return MPV_ERROR_NOMEM; }
    jint n = env->CallIntMethod(static_cast<StreamCookie *>(cookie)->stream, m_stream_read, buffer);
    env->DeleteLocalRef(buffer);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return MPV_ERROR_GENERIC; }
    // A count larger than the buffer is a provider bug; mpv would take it as data it never got.
    if (n < 0 || (uint64_t) n > nbytes) return MPV_ERROR_GENERIC;
    return n;
}

static int64_t cb_seek(void *cookie, int64_t offset) {
    JNIEnv *env = env_for_this_thread();
    if (!env) return MPV_ERROR_GENERIC;
    jlong pos = env->CallLongMethod(static_cast<StreamCookie *>(cookie)->stream, m_stream_seek, (jlong) offset);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return MPV_ERROR_GENERIC; }
    return pos < 0 ? MPV_ERROR_UNSUPPORTED : pos;
}

static int64_t cb_size(void *cookie) {
    JNIEnv *env = env_for_this_thread();
    if (!env) return MPV_ERROR_UNSUPPORTED;
    jlong size = env->CallLongMethod(static_cast<StreamCookie *>(cookie)->stream, m_stream_size);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return MPV_ERROR_UNSUPPORTED; }
    return size < 0 ? MPV_ERROR_UNSUPPORTED : size;
}

static void cb_close(void *cookie) {
    auto *c = static_cast<StreamCookie *>(cookie);
    JNIEnv *env = env_for_this_thread();
    std::lock_guard<std::mutex> guard(c->lock);
    if (env && c->stream) {
        env->CallVoidMethod(c->stream, m_stream_close);
        clear_exception(env);
        env->DeleteGlobalRef(c->stream);
    }
    c->stream = nullptr;
}

/* Called by mpv from a thread other than the demuxer's, possibly during a read. */
static void cb_cancel(void *cookie) {
    auto *c = static_cast<StreamCookie *>(cookie);
    std::lock_guard<std::mutex> guard(c->lock);
    if (!c->stream) return;
    JNIEnv *env = env_for_this_thread();
    if (!env) return;
    env->CallVoidMethod(c->stream, m_stream_cancel);
    clear_exception(env);
}

static int cb_open(void *user_data, char *uri, mpv_stream_cb_info *info) {
    JNIEnv *env = env_for_this_thread();
    if (!env) return MPV_ERROR_LOADING_FAILED;
    jstring juri = jstring_from_utf8(env, uri);
    if (!juri) { clear_exception(env); return MPV_ERROR_LOADING_FAILED; }
    jobject stream = env->CallObjectMethod((jobject) user_data, m_provider_open, juri);
    env->DeleteLocalRef(juri);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return MPV_ERROR_LOADING_FAILED; }
    if (!stream) return MPV_ERROR_LOADING_FAILED;
    jboolean seekable = env->CallBooleanMethod(stream, m_stream_seekable);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        env->CallVoidMethod(stream, m_stream_close);
        clear_exception(env);
        env->DeleteLocalRef(stream);
        return MPV_ERROR_LOADING_FAILED;
    }
    auto *c = new StreamCookie();
    c->stream = env->NewGlobalRef(stream);
    env->DeleteLocalRef(stream);
    info->cookie = c;
    info->read_fn = cb_read;
    info->seek_fn = seekable ? cb_seek : nullptr;
    info->size_fn = cb_size;
    info->close_fn = cb_close;
    info->cancel_fn = cb_cancel;
    return 0;
}

int stream_cb_register(JNIEnv *env, mpv_handle *mpv, const char *protocol, jobject provider) {
    jobject global = env->NewGlobalRef(provider);
    int r = mpv_stream_cb_add_ro(mpv, protocol, global, cb_open);
    if (r < 0) {
        env->DeleteGlobalRef(global);
        return r;
    }
    std::lock_guard<std::mutex> guard(g_lock);
    if (g_owners.count(mpv)) g_providers[mpv].push_back(global);
    return r;
}

void stream_cb_owner_created(mpv_handle *mpv) {
    std::lock_guard<std::mutex> guard(g_lock);
    g_owners.insert(mpv);
}

void stream_cb_owner_terminated(JNIEnv *env, mpv_handle *mpv) {
    std::vector<jobject> held;
    {
        std::lock_guard<std::mutex> guard(g_lock);
        g_owners.erase(mpv);
        auto it = g_providers.find(mpv);
        if (it != g_providers.end()) {
            held.swap(it->second);
            g_providers.erase(it);
        }
    }
    // The core has ended, so mpv opens no more streams with these providers.
    for (jobject p : held) env->DeleteGlobalRef(p);
}

void stream_cb_handle_destroyed(mpv_handle *mpv) {
    // mpv_destroy may leave the core running for its other handles: forget the handle, keep the providers.
    std::lock_guard<std::mutex> guard(g_lock);
    g_owners.erase(mpv);
    g_providers.erase(mpv);
}
