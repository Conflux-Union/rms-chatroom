// JNI surface for RnNoiseNative (cn.net.rms.chatroom.data.livekit).
// Frames are 480 mono floats at 48 kHz in rnnoise's native +/-32768 scaling.
#include <jni.h>
#include <stdint.h>

#include "rnnoise.h"

static DenoiseState *as_state(jlong ptr) {
    if (ptr == 0) return NULL;
    return (DenoiseState *)(intptr_t)ptr;
}

JNIEXPORT jlong JNICALL
Java_cn_net_rms_chatroom_data_livekit_RnNoiseNative_create(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    return (jlong)(intptr_t)rnnoise_create(NULL);
}

JNIEXPORT void JNICALL
Java_cn_net_rms_chatroom_data_livekit_RnNoiseNative_destroy(JNIEnv *env, jclass clazz, jlong ptr) {
    (void)env;
    (void)clazz;
    DenoiseState *st = as_state(ptr);
    if (st != NULL) {
        rnnoise_destroy(st);
    }
}

JNIEXPORT jint JNICALL
Java_cn_net_rms_chatroom_data_livekit_RnNoiseNative_frameSize(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    return rnnoise_get_frame_size();
}

JNIEXPORT jfloat JNICALL
Java_cn_net_rms_chatroom_data_livekit_RnNoiseNative_processFrame(
        JNIEnv *env, jclass clazz, jlong ptr, jfloatArray j_in, jfloatArray j_out) {
    (void)clazz;
    DenoiseState *st = as_state(ptr);
    if (st == NULL) {
        return 0.0f;
    }

    int frame = rnnoise_get_frame_size();
    if ((*env)->GetArrayLength(env, j_in) < frame || (*env)->GetArrayLength(env, j_out) < frame) {
        return 0.0f;
    }

    jfloat *in = (*env)->GetFloatArrayElements(env, j_in, NULL);
    jfloat *out = (*env)->GetFloatArrayElements(env, j_out, NULL);
    jfloat vad = 0.0f;
    if (in != NULL && out != NULL) {
        vad = (jfloat)rnnoise_process_frame(st, out, in);
    }
    if (in != NULL) {
        (*env)->ReleaseFloatArrayElements(env, j_in, in, JNI_ABORT);
    }
    if (out != NULL) {
        (*env)->ReleaseFloatArrayElements(env, j_out, out, 0);
    }
    return vad;
}
