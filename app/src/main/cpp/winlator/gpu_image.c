#include <android/log.h>
#include <android/hardware_buffer.h>
#include <android/native_window.h>

#define EGL_EGLEXT_PROTOTYPES
#define GL_GLEXT_PROTOTYPES

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <jni.h>
#include <unistd.h>
#include <string.h>
#include <sys/socket.h>
#include "buffer_geometry.h"

#define LOG_TAG "System.out"
#define printf(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define HAL_PIXEL_FORMAT_BGRA_8888 5

AHardwareBuffer* createHardwareBuffer(int width, int height) {
    if (width <= 0 || height <= 0 || width > INT16_MAX || height > INT16_MAX) {
        printf("Invalid hardware buffer dimensions %dx%d", width, height);
        return NULL;
    }
    AHardwareBuffer_Desc buffDesc = {};
    buffDesc.width = width;
    buffDesc.height = height;
    buffDesc.layers = 1;
    buffDesc.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN;
    buffDesc.format = HAL_PIXEL_FORMAT_BGRA_8888;

    AHardwareBuffer *hardwareBuffer = NULL;
    if (AHardwareBuffer_allocate(&buffDesc, &hardwareBuffer) != 0) {
        printf("Failed to allocate AHardwareBuffer\n");
        return NULL;
    }

    return hardwareBuffer;
}

JNIEXPORT jlong JNICALL
Java_com_winlator_cmod_renderer_GPUImage_createHardwareBuffer(JNIEnv *env, jclass obj, jshort width, jshort height) {
    AHardwareBuffer *buffer = createHardwareBuffer(width, height);
    if (!buffer) {
        printf("Failed to create hardware buffer\n");
        return 0;
    }
    return (jlong)buffer;
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_renderer_GPUImage_releaseHardwareBuffer(JNIEnv *env, jclass obj, jlong ptr) {
    if (ptr) AHardwareBuffer_release((AHardwareBuffer*)ptr);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_renderer_GPUImage_destroyHardwareBuffer(JNIEnv *env, jclass obj, jlong hardwareBufferPtr) {
    AHardwareBuffer* hardwareBuffer = (AHardwareBuffer*)hardwareBufferPtr;
    if (hardwareBuffer) {
        AHardwareBuffer_unlock(hardwareBuffer, NULL);
        AHardwareBuffer_release(hardwareBuffer);
    }
}

JNIEXPORT jobject JNICALL
Java_com_winlator_cmod_renderer_GPUImage_lockHardwareBuffer(JNIEnv *env, jclass obj, jlong hardwareBufferPtr) {
    AHardwareBuffer* hardwareBuffer = (AHardwareBuffer*)hardwareBufferPtr;
    if (!hardwareBuffer) {
        printf("Invalid AHardwareBuffer pointer\n");
        return NULL;
    }
    
    void *virtualAddr;
    if (AHardwareBuffer_lock(hardwareBuffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, NULL, &virtualAddr) != 0) {
        printf("Failed to lock AHardwareBuffer\n");
        return NULL;
    }

    AHardwareBuffer_Desc buffDesc;
    AHardwareBuffer_describe(hardwareBuffer, &buffDesc);
    if (!valid_ahb_geometry(buffDesc.width, buffDesc.height, buffDesc.stride,
                           buffDesc.layers, buffDesc.format)) {
        printf("Invalid locked AHB geometry %ux%u stride=%u", buffDesc.width,buffDesc.height,buffDesc.stride);
        AHardwareBuffer_unlock(hardwareBuffer, NULL);
        return NULL;
    }

    jclass cls = (*env)->GetObjectClass(env, obj);
    if (cls == NULL) {
        printf("Failed to get Java class reference\n");
        AHardwareBuffer_unlock(hardwareBuffer, NULL);
        return NULL;
    }

    jmethodID setStride = (*env)->GetMethodID(env, cls, "setStride", "(S)V");
    if (setStride == NULL) {
        printf("Failed to get setStride method ID\n");
        AHardwareBuffer_unlock(hardwareBuffer, NULL);
        return NULL;
    }
    (*env)->CallVoidMethod(env, obj, setStride, (jshort)buffDesc.stride);

    jlong size = (jlong)buffDesc.stride * buffDesc.height * 4;
    jobject buffer = (*env)->NewDirectByteBuffer(env, virtualAddr, size);
    if (buffer == NULL) {
        printf("Failed to create Java ByteBuffer\n");
        AHardwareBuffer_unlock(hardwareBuffer, NULL);
    }

    return buffer;
}

JNIEXPORT jlong JNICALL
Java_com_winlator_cmod_renderer_GPUImage_nativeHardwareBufferFromSocket(JNIEnv *env, jclass obj, jint fd) {
    AHardwareBuffer *ahb = NULL;
    uint8_t buf = 1;
    
    if (send(fd, &buf, 1, MSG_NOSIGNAL) != 1) {
        printf("Failed to write data to socketpair");
        return 0;
    }
    
    if ((AHardwareBuffer_recvHandleFromUnixSocket(fd, &ahb)) != 0) {
        printf("Failed to extract hardware buffer from socketpair");
        return 0;
    }
    
    AHardwareBuffer_Desc buffDesc;
    AHardwareBuffer_describe(ahb, &buffDesc);
    if (!valid_ahb_geometry(buffDesc.width, buffDesc.height, buffDesc.stride,
                           buffDesc.layers, buffDesc.format)
        || !(buffDesc.usage & AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE)) {
        printf("Rejected AHB geometry %ux%u stride=%u layers=%u format=%u",
               buffDesc.width, buffDesc.height, buffDesc.stride, buffDesc.layers, buffDesc.format);
        AHardwareBuffer_release(ahb);
        return 0;
    }
    
    jclass cls = (*env)->GetObjectClass(env, obj);
    if (cls == NULL) {
        printf("Failed to get Java class reference\n");
        AHardwareBuffer_release(ahb);
        return 0;
    }

    jmethodID setStride = (*env)->GetMethodID(env, cls, "setStride", "(S)V");
    if (setStride == NULL) {
        printf("Failed to get setStride method ID\n");
        AHardwareBuffer_release(ahb);
        return 0;
    }
    (*env)->CallVoidMethod(env, obj, setStride, (jshort)buffDesc.stride);
    
    jfieldID format = (*env)->GetFieldID(env, cls, "format", "I");
    jmethodID setDimensions = (*env)->GetMethodID(env, cls, "setDimensions", "(II)V");
    if (!format || !setDimensions) {
        AHardwareBuffer_release(ahb);
        return 0;
    }
    (*env)->SetIntField(env, obj, format, buffDesc.format);
    (*env)->CallVoidMethod(env, obj, setDimensions, (jint)buffDesc.width, (jint)buffDesc.height);
    if ((*env)->ExceptionCheck(env)) {
        AHardwareBuffer_release(ahb);
        return 0;
    }
    
    return (jlong)ahb;
}
