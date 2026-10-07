#pragma once
#include <jni.h>
#include <mpv/client.h>

/* Called from JNI_OnLoad: caches the method ids of MpvStreamProvider and MpvStream. */
bool stream_cb_init(JavaVM *vm, JNIEnv *env);
/* mpv_stream_cb_add_ro with a Kotlin provider. Takes a global reference to it. */
int stream_cb_register(JNIEnv *env, mpv_handle *mpv, const char *protocol, jobject provider);

/*
 * Provider lifetime. A provider registered through a handle that owns its core is released when
 * that core ends. One registered through a client handle stays held, because the core may outlive
 * the client and still open streams with it.
 */
void stream_cb_owner_created(mpv_handle *mpv);
void stream_cb_owner_terminated(JNIEnv *env, mpv_handle *mpv);
void stream_cb_handle_destroyed(mpv_handle *mpv);
