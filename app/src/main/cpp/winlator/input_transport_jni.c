#include "input_transport.h"
#include <errno.h>
#include <jni.h>

JNIEXPORT jint JNICALL
Java_com_winlator_cmod_inputcontrols_NativeInputTransport_nativeStart(JNIEnv *env, jclass type, jstring directory) {
    (void)type;
    const char *path = (*env)->GetStringUTFChars(env, directory, NULL);
    if (!path) return ENOMEM;
    int error = sky_input_start(path) < 0 ? errno : 0;
    (*env)->ReleaseStringUTFChars(env, directory, path);
    return error;
}
JNIEXPORT void JNICALL
Java_com_winlator_cmod_inputcontrols_NativeInputTransport_nativeStop(JNIEnv *env, jclass type) {
    (void)env; (void)type;
    sky_input_stop();
}
JNIEXPORT jint JNICALL
Java_com_winlator_cmod_inputcontrols_NativeInputTransport_nativeActivate(JNIEnv *env, jclass type, jint slot) {
    (void)env; (void)type;
    return sky_input_activate(slot) < 0 ? errno : 0;
}
JNIEXPORT void JNICALL
Java_com_winlator_cmod_inputcontrols_NativeInputTransport_disconnect(JNIEnv *env, jclass type, jint slot) {
    (void)env; (void)type;
    sky_input_disconnect(slot);
}
JNIEXPORT jint JNICALL
Java_com_winlator_cmod_inputcontrols_NativeInputTransport_nativePublish(JNIEnv *env, jclass type,
        jint slot, jobject data, jint offset, jint bytes) {
    (void)type;
    unsigned char *address = (*env)->GetDirectBufferAddress(env, data);
    jlong capacity = (*env)->GetDirectBufferCapacity(env, data);
    if (!address || offset < 0 || bytes < 0 || (jlong)offset + bytes > capacity) return -EINVAL;
    int result = sky_input_publish(slot, address + offset, (size_t)bytes);
    return result < 0 ? -errno : result;
}
