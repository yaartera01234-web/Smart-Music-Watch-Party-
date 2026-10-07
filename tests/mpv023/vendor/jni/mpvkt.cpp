#include <jni.h>
#include <mpv/client.h>

#include <clocale>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

extern "C" {
#include <libavcodec/jni.h>
}

#include "node_codec.h"
#include "stream_cb.h"
#include "utf8.h"

#define FN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_io_github_yuroyami_libmpvkt_jni_MpvNative_##name

static mpv_handle *H(jlong h) { return reinterpret_cast<mpv_handle *>(h); }

/* A jstring as standard UTF-8 for the scope of a call. `s` is null when the jstring is. */
struct JStr {
    std::string v; const char *s;
    JStr(JNIEnv *e, jstring j) : v(utf8_from_jstring(e, j)), s(j ? v.c_str() : nullptr) {}
};

/* A jbyteArray's bytes for the scope of a call, never written back. */
struct JBytes {
    JNIEnv *env; jbyteArray arr; jbyte *p; jsize n;
    JBytes(JNIEnv *e, jbyteArray a) : env(e), arr(a), p(a ? e->GetByteArrayElements(a, nullptr) : nullptr), n(a ? e->GetArrayLength(a) : 0) {}
    ~JBytes() { if (p) env->ReleaseByteArrayElements(arr, p, JNI_ABORT); }
};

/* A String[] as a NULL-terminated array of UTF-8 strings for the scope of a call. A null element is "". */
struct JArgs {
    std::vector<std::string> strs; std::vector<const char *> argv;
    JArgs(JNIEnv *e, jobjectArray a) {
        jsize n = a ? e->GetArrayLength(a) : 0;
        strs.reserve(n);
        for (jsize i = 0; i < n; i++) {
            auto s = static_cast<jstring>(e->GetObjectArrayElement(a, i));
            strs.push_back(utf8_from_jstring(e, s));
            if (s) e->DeleteLocalRef(s);
        }
        for (const auto &s : strs) argv.push_back(s.c_str());
        argv.push_back(nullptr);
    }
};

/* Null, with Java's OutOfMemoryError pending, when the array cannot be allocated. */
static jbyteArray to_jbytes(JNIEnv *env, const std::string &s) {
    jbyteArray a = env->NewByteArray((jsize) s.size());
    if (a) env->SetByteArrayRegion(a, 0, (jsize) s.size(), (const jbyte *) s.data());
    return a;
}

static jbyteArray result_envelope(JNIEnv *env, int error, const mpv_node *node) {
    std::string out;
    encode_i32(out, error);
    if (node && error >= 0) encode_node(out, node); else encode_none(out);
    return to_jbytes(env, out);
}

static bool decode_arg(JNIEnv *env, jbyteArray bytes, mpv_node *out, NodeArena &arena) {
    JBytes b(env, bytes);
    if (!b.p) return false;
    size_t pos = 0;
    return decode_node((const uint8_t *) b.p, (size_t) b.n, pos, out, arena);
}

static JavaVM *g_vm;

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    JNIEnv *env;
    if (vm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    if (!stream_cb_init(vm, env)) return JNI_ERR;
    return JNI_VERSION_1_6;
}

FN(void, initAndroid)(JNIEnv *env, jobject, jobject appctx) {
    static std::once_flag once;
    std::call_once(once, [&] {
        setlocale(LC_NUMERIC, "C");
        av_jni_set_java_vm(g_vm, nullptr);
        jobject global = env->NewGlobalRef(appctx);
        if (global) av_jni_set_android_app_ctx(global, nullptr);
    });
}

FN(jlong, clientApiVersion)(JNIEnv *, jobject) { return (jlong) mpv_client_api_version(); }

FN(jlong, create)(JNIEnv *, jobject) {
    mpv_handle *h = mpv_create();
    if (h) stream_cb_owner_created(h);
    return reinterpret_cast<jlong>(h);
}

FN(jlong, createClient)(JNIEnv *env, jobject, jlong h, jstring name) { JStr n(env, name); return reinterpret_cast<jlong>(mpv_create_client(H(h), n.s)); }
FN(jlong, createWeakClient)(JNIEnv *env, jobject, jlong h, jstring name) { JStr n(env, name); return reinterpret_cast<jlong>(mpv_create_weak_client(H(h), n.s)); }
FN(jstring, clientName)(JNIEnv *env, jobject, jlong h) { return jstring_from_utf8(env, mpv_client_name(H(h))); }
FN(jlong, clientId)(JNIEnv *, jobject, jlong h) { return (jlong) mpv_client_id(H(h)); }
FN(jint, initialize)(JNIEnv *, jobject, jlong h) { return mpv_initialize(H(h)); }
FN(void, destroy)(JNIEnv *, jobject, jlong h) {
    mpv_destroy(H(h));
    stream_cb_handle_destroyed(H(h));
}

FN(void, terminateDestroy)(JNIEnv *env, jobject, jlong h) {
    mpv_terminate_destroy(H(h));
    stream_cb_owner_terminated(env, H(h));
}
FN(jint, loadConfigFile)(JNIEnv *env, jobject, jlong h, jstring path) { JStr p(env, path); return mpv_load_config_file(H(h), p.s); }
FN(jlong, timeUs)(JNIEnv *, jobject, jlong h) { return (jlong) mpv_get_time_us(H(h)); }

FN(jint, setOptionString)(JNIEnv *env, jobject, jlong h, jstring name, jstring value) {
    JStr n(env, name), v(env, value);
    return mpv_set_option_string(H(h), n.s, v.s);
}

FN(jint, setOptionNode)(JNIEnv *env, jobject, jlong h, jstring name, jbyteArray node) {
    NodeArena arena; mpv_node n{};
    if (!decode_arg(env, node, &n, arena)) return MPV_ERROR_INVALID_PARAMETER;
    JStr nm(env, name);
    return mpv_set_option(H(h), nm.s, MPV_FORMAT_NODE, &n);
}

FN(jint, command)(JNIEnv *env, jobject, jlong h, jobjectArray args) {
    JArgs a(env, args);
    return mpv_command(H(h), a.argv.data());
}

FN(jbyteArray, commandNode)(JNIEnv *env, jobject, jlong h, jbyteArray args) {
    NodeArena arena; mpv_node in{};
    if (!decode_arg(env, args, &in, arena)) return result_envelope(env, MPV_ERROR_INVALID_PARAMETER, nullptr);
    mpv_node result{};
    int r = mpv_command_node(H(h), &in, &result);
    jbyteArray out = result_envelope(env, r, &result);
    if (r >= 0) mpv_free_node_contents(&result);
    return out;
}

FN(jint, commandString)(JNIEnv *env, jobject, jlong h, jstring command) { JStr c(env, command); return mpv_command_string(H(h), c.s); }

FN(jint, commandAsync)(JNIEnv *env, jobject, jlong h, jlong reply, jobjectArray args) {
    JArgs a(env, args);
    return mpv_command_async(H(h), (uint64_t) reply, a.argv.data());
}

FN(jint, commandNodeAsync)(JNIEnv *env, jobject, jlong h, jlong reply, jbyteArray args) {
    NodeArena arena; mpv_node in{};
    if (!decode_arg(env, args, &in, arena)) return MPV_ERROR_INVALID_PARAMETER;
    return mpv_command_node_async(H(h), (uint64_t) reply, &in);
}

FN(void, abortAsyncCommand)(JNIEnv *, jobject, jlong h, jlong reply) { mpv_abort_async_command(H(h), (uint64_t) reply); }

FN(jbyteArray, getPropertyNode)(JNIEnv *env, jobject, jlong h, jstring name) {
    JStr n(env, name);
    mpv_node v{};
    int r = mpv_get_property(H(h), n.s, MPV_FORMAT_NODE, &v);
    jbyteArray out = result_envelope(env, r, &v);
    if (r >= 0) mpv_free_node_contents(&v);
    return out;
}

FN(jstring, getPropertyString)(JNIEnv *env, jobject, jlong h, jstring name) {
    JStr n(env, name);
    char *s = mpv_get_property_string(H(h), n.s);
    if (!s) return nullptr;
    jstring js = jstring_from_utf8(env, s);
    mpv_free(s);
    return js;
}

FN(jstring, getPropertyOsdString)(JNIEnv *env, jobject, jlong h, jstring name) {
    JStr n(env, name);
    char *s = mpv_get_property_osd_string(H(h), n.s);
    if (!s) return nullptr;
    jstring js = jstring_from_utf8(env, s);
    mpv_free(s);
    return js;
}

FN(jint, setPropertyNode)(JNIEnv *env, jobject, jlong h, jstring name, jbyteArray node) {
    NodeArena arena; mpv_node n{};
    if (!decode_arg(env, node, &n, arena)) return MPV_ERROR_INVALID_PARAMETER;
    JStr nm(env, name);
    return mpv_set_property(H(h), nm.s, MPV_FORMAT_NODE, &n);
}

FN(jint, setPropertyString)(JNIEnv *env, jobject, jlong h, jstring name, jstring value) {
    JStr n(env, name), v(env, value);
    return mpv_set_property_string(H(h), n.s, v.s);
}

FN(jint, getPropertyAsync)(JNIEnv *env, jobject, jlong h, jlong reply, jstring name) {
    JStr n(env, name);
    return mpv_get_property_async(H(h), (uint64_t) reply, n.s, MPV_FORMAT_NODE);
}

FN(jint, setPropertyAsync)(JNIEnv *env, jobject, jlong h, jlong reply, jstring name, jbyteArray node) {
    NodeArena arena; mpv_node n{};
    if (!decode_arg(env, node, &n, arena)) return MPV_ERROR_INVALID_PARAMETER;
    JStr nm(env, name);
    return mpv_set_property_async(H(h), (uint64_t) reply, nm.s, MPV_FORMAT_NODE, &n);
}

FN(jint, delProperty)(JNIEnv *env, jobject, jlong h, jstring name) { JStr n(env, name); return mpv_del_property(H(h), n.s); }

FN(jint, observeProperty)(JNIEnv *env, jobject, jlong h, jlong reply, jstring name, jint format) {
    JStr n(env, name);
    return mpv_observe_property(H(h), (uint64_t) reply, n.s, (mpv_format) format);
}

FN(jint, unobserveProperty)(JNIEnv *, jobject, jlong h, jlong reply) { return mpv_unobserve_property(H(h), (uint64_t) reply); }
FN(jint, requestEvent)(JNIEnv *, jobject, jlong h, jint id, jboolean enable) { return mpv_request_event(H(h), (mpv_event_id) id, enable ? 1 : 0); }
FN(jint, requestLogMessages)(JNIEnv *env, jobject, jlong h, jstring level) { JStr l(env, level); return mpv_request_log_messages(H(h), l.s); }

/* {name, data} for a property event. mpv_event_to_node writes no data for a get-property reply or an INT64 value. */
static void encode_property_event(std::string &out, const mpv_event_property *p) {
    encode_map_header(out, 2);
    encode_key(out, "name");
    encode_string(out, p->name);
    encode_key(out, "data");
    switch (p->format) {
    case MPV_FORMAT_STRING:
    case MPV_FORMAT_OSD_STRING: encode_string(out, *static_cast<char **>(p->data)); break;
    case MPV_FORMAT_FLAG: encode_flag(out, *static_cast<int *>(p->data)); break;
    case MPV_FORMAT_INT64: encode_int64_node(out, *static_cast<int64_t *>(p->data)); break;
    case MPV_FORMAT_DOUBLE: encode_double_node(out, *static_cast<double *>(p->data)); break;
    case MPV_FORMAT_NODE: encode_node(out, static_cast<const mpv_node *>(p->data)); break;
    default: encode_none(out); break;
    }
}

/* {name, hook_id}. mpv_event_to_node leaves the name out. */
static void encode_hook_event(std::string &out, const mpv_event_hook *hook) {
    encode_map_header(out, 2);
    encode_key(out, "name");
    encode_string(out, hook->name);
    encode_key(out, "hook_id");
    encode_int64_node(out, (int64_t) hook->id);
}

FN(jbyteArray, waitEvent)(JNIEnv *env, jobject, jlong h, jdouble timeout) {
    mpv_event *e = mpv_wait_event(H(h), timeout);
    std::string out;
    out.reserve(128);
    encode_i32(out, e->event_id);
    encode_i64(out, (int64_t) e->reply_userdata);
    encode_i32(out, e->error);
    switch (e->event_id) {
    case MPV_EVENT_NONE:
        encode_none(out);
        break;
    case MPV_EVENT_PROPERTY_CHANGE:
    case MPV_EVENT_GET_PROPERTY_REPLY:
        if (e->data) encode_property_event(out, static_cast<const mpv_event_property *>(e->data)); else encode_none(out);
        break;
    case MPV_EVENT_HOOK:
        if (e->data) encode_hook_event(out, static_cast<const mpv_event_hook *>(e->data)); else encode_none(out);
        break;
    default: {
        mpv_node n{};
        if (mpv_event_to_node(&n, e) >= 0) {
            encode_node(out, &n);
            mpv_free_node_contents(&n);
        } else {
            encode_none(out);
        }
    }
    }
    return to_jbytes(env, out);
}

FN(void, wakeup)(JNIEnv *, jobject, jlong h) { mpv_wakeup(H(h)); }

FN(jint, hookAdd)(JNIEnv *env, jobject, jlong h, jlong reply, jstring name, jint priority) {
    JStr n(env, name);
    return mpv_hook_add(H(h), (uint64_t) reply, n.s, priority);
}

FN(jint, hookContinue)(JNIEnv *, jobject, jlong h, jlong id) { return mpv_hook_continue(H(h), (uint64_t) id); }
FN(jstring, errorString)(JNIEnv *env, jobject, jint code) { return jstring_from_utf8(env, mpv_error_string(code)); }
FN(jstring, eventName)(JNIEnv *env, jobject, jint id) {
    const char *name = mpv_event_name((mpv_event_id) id);
    return jstring_from_utf8(env, name ? name : "");
}

FN(jint, streamCbAddRo)(JNIEnv *env, jobject, jlong h, jstring protocol, jobject provider) {
    JStr p(env, protocol);
    return stream_cb_register(env, H(h), p.s, provider);
}

FN(jlong, surfaceHandle)(JNIEnv *env, jobject, jobject surface) { return reinterpret_cast<jlong>(env->NewGlobalRef(surface)); }
FN(void, releaseSurfaceHandle)(JNIEnv *env, jobject, jlong handle) { if (handle) env->DeleteGlobalRef(reinterpret_cast<jobject>(handle)); }
