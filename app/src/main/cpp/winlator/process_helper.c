#define _GNU_SOURCE  
#include <jni.h>
#include <sched.h>
#include <errno.h>
#include <string.h>
#include <sys/types.h>
#include <unistd.h>

JNIEXPORT jint JNICALL
Java_com_winlator_cmod_core_ProcessHelper_nativeSetProcessAffinity(
        JNIEnv *env, jclass clazz, jint pid, jint mask) {
    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);

    for (int i = 0; i < 32; i++) {
        if (mask & (1U << i))
            CPU_SET(i, &cpuset);
    }

    int result = sched_setaffinity((pid_t) pid, sizeof(cpuset), &cpuset);

    return result == 0 ? 0 : errno;
}

JNIEXPORT jint JNICALL
Java_com_winlator_cmod_core_ProcessHelper_nativeGetProcessAffinity(
        JNIEnv *env, jclass clazz, jint pid) {
    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);

    if (sched_getaffinity((pid_t) pid, sizeof(cpuset), &cpuset) != 0)
        return -1; 

    int mask = 0;
    for (int i = 0; i < 32; i++) {
        if (CPU_ISSET(i, &cpuset))
            mask |= (1U << i);
    }
    return mask;
}

#include <fcntl.h>
#include <stdint.h>
#include <sys/resource.h>
#include "../adrenotools/src/hook/kgsl.h"

JNIEXPORT jlong JNICALL Java_com_winlator_cmod_perf_PerformanceSession_getAffinity(JNIEnv *env, jclass cls, jint tid) {
    cpu_set_t set; CPU_ZERO(&set);
    if (sched_getaffinity(tid, sizeof(set), &set)) return -errno;
    uint32_t mask=0;
    for(int i=0;i<32;i++) if(CPU_ISSET(i,&set)) mask |= 1U << i;
    return (jlong)mask;
}
JNIEXPORT jint JNICALL Java_com_winlator_cmod_perf_PerformanceSession_setAffinity(JNIEnv *env, jclass cls, jint tid, jint mask) {
    return Java_com_winlator_cmod_core_ProcessHelper_nativeSetProcessAffinity(env, cls, tid, mask);
}
JNIEXPORT jint JNICALL Java_com_winlator_cmod_perf_PerformanceSession_getNice(JNIEnv *env, jclass cls, jint tid) {
    errno=0;
    int value=getpriority(PRIO_PROCESS,tid);
    return errno ? 1000+errno : value;
}
JNIEXPORT jint JNICALL Java_com_winlator_cmod_perf_PerformanceSession_setNice(JNIEnv *env, jclass cls, jint tid, jint value) {
    return setpriority(PRIO_PROCESS,tid,value)==0 ? 0 : errno;
}
JNIEXPORT jint JNICALL Java_com_winlator_cmod_perf_PerformanceSession_setGpuTurbo(JNIEnv *env, jclass cls, jboolean enabled) {
    // Legacy PWRCTRL overrides can regress Gen8 performance. Keep only release/recovery.
    if (enabled) return EOPNOTSUPP;
    int fd=open("/dev/kgsl-3d0",O_RDWR|O_CLOEXEC);
    if(fd<0) return errno;
    uint32_t value=enabled ? 0U : 1U;
    struct kgsl_device_getproperty prop={.type=KGSL_PROP_PWRCTRL,.value=&value,.sizebytes=sizeof(value)};
    int result=ioctl(fd,IOCTL_KGSL_SETPROPERTY,&prop);
    int error=result==0 ? 0 : errno;
    close(fd);
    return error;
}
