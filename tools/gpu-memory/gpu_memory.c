/* MIT. Optional Vulkan reported-memory budget. Never rejects allocations or changes usage. */
#define _GNU_SOURCE
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>
#include <vulkan/vk_layer.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define EXPORT __attribute__((visibility("default")))
#define MAX_INSTANCES 32
#define MAX_DEVICES 128
typedef struct {
    void *key;
    VkInstance handle;
    PFN_vkGetInstanceProcAddr gipa;
    PFN_GetPhysicalDeviceProcAddr pgpa;
    uint64_t cap;
    VkPhysicalDevice logged[VK_MAX_DEVICE_GROUP_SIZE];
    int occupied;
} Instance;
typedef struct { void *key; VkDevice handle; PFN_vkGetDeviceProcAddr gdpa; int occupied; } Device;
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static Instance instances[MAX_INSTANCES];
static Device devices[MAX_DEVICES];
static void *dispatch_key(const void *h) { return h ? *(void *const *)h : NULL; }
static Instance instance_for(const void *h) {
    Instance result = {0}; void *key = dispatch_key(h);
    pthread_mutex_lock(&lock);
    for (int n=0; key && n<MAX_INSTANCES; n++) if (instances[n].key==key) { result=instances[n]; break; }
    pthread_mutex_unlock(&lock); return result;
}
static Device device_for(const void *h) {
    Device result = {0}; void *key = dispatch_key(h);
    pthread_mutex_lock(&lock);
    for (int n=0; key && n<MAX_DEVICES; n++) if (devices[n].key==key) { result=devices[n]; break; }
    pthread_mutex_unlock(&lock); return result;
}
static uint64_t configured_cap(void) {
    const char *s=getenv("SKY_GPU_MEMORY_MB");
    if (!s || !*s) return 0;
    uint64_t mib=0;
    for (; *s; s++) { if (*s<'0' || *s>'9' || mib>16384) return 0; mib=mib*10+(*s-'0'); }
    switch (mib) {
        case 512: case 1024: case 2048: case 4096: case 8192: case 12288: case 16384: return mib<<20;
        default: return 0;
    }
}
/* One aggregate cap for device-local heaps, including UMA heaps. Host-only heaps and type indices stay intact. */
static void cap_heaps(VkPhysicalDeviceMemoryProperties *p, uint64_t cap) {
    long double total=0;
    uint32_t count=0;
    for (uint32_t n=0; n<p->memoryHeapCount; n++) if ((p->memoryHeaps[n].flags&VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) && p->memoryHeaps[n].size) {
        total+=p->memoryHeaps[n].size; count++;
    }
    if (!cap || total<=cap || cap<count) return;
    uint64_t remaining=cap;
    const uint32_t heap_count=count;
    VkDeviceSize original[VK_MAX_MEMORY_HEAPS];
    for (uint32_t n=0; n<p->memoryHeapCount; n++) {
        original[n]=p->memoryHeaps[n].size;
        if (!(p->memoryHeaps[n].flags&VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) || !original[n]) continue;
        uint64_t size=1+(uint64_t)(((long double)(original[n]-1)*(cap-heap_count))/(total-heap_count));
        count--;
        if (size>remaining-count) size=remaining-count;
        if (size>original[n]) size=original[n];
        p->memoryHeaps[n].size=size; remaining-=size;
    }
    for (uint32_t n=0; remaining && n<p->memoryHeapCount; n++) {
        if ((p->memoryHeaps[n].flags&VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) && p->memoryHeaps[n].size<original[n]) {
            uint64_t add=original[n]-p->memoryHeaps[n].size;
            if (add>remaining) add=remaining;
            p->memoryHeaps[n].size+=add; remaining-=add;
        }
    }
}
static void apply_budget(Instance i, VkPhysicalDevice h, VkPhysicalDeviceMemoryProperties *p, void *next) {
    if (!i.cap) return;
    long double original=0, effective=0;
    for (uint32_t n=0; n<p->memoryHeapCount; n++) if (p->memoryHeaps[n].flags&VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) original+=p->memoryHeaps[n].size;
    cap_heaps(p,i.cap);
    for (uint32_t n=0; n<p->memoryHeapCount; n++) if (p->memoryHeaps[n].flags&VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) effective+=p->memoryHeaps[n].size;
    for (VkBaseOutStructure *s=next; s; s=s->pNext) if (s->sType==VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MEMORY_BUDGET_PROPERTIES_EXT) {
        VkPhysicalDeviceMemoryBudgetPropertiesEXT *b=(void *)s;
        for (uint32_t n=0; n<p->memoryHeapCount; n++)
            if ((p->memoryHeaps[n].flags&VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) && b->heapBudget[n]>p->memoryHeaps[n].size)
                b->heapBudget[n]=p->memoryHeaps[n].size;
    }
    int log=0;
    pthread_mutex_lock(&lock);
    for (int n=0; n<MAX_INSTANCES; n++) if (instances[n].handle==i.handle) {
        for (unsigned k=0; k<VK_MAX_DEVICE_GROUP_SIZE; k++) {
            if (instances[n].logged[k]==h) break;
            if (!instances[n].logged[k]) { instances[n].logged[k]=h; log=1; break; }
        }
        break;
    }
    pthread_mutex_unlock(&lock);
    if (log) fprintf(stderr,"[SkyNet GPU memory] requested_mib=%llu native_local_mib=%llu reported_local_mib=%llu mode=reported_budget\n",
        (unsigned long long)(i.cap>>20),(unsigned long long)(original/(1ull<<20)),(unsigned long long)(effective/(1ull<<20)));
}
static void VKAPI_CALL memory_properties(VkPhysicalDevice h, VkPhysicalDeviceMemoryProperties *p) {
    Instance i=instance_for(h);
    PFN_vkGetPhysicalDeviceMemoryProperties fn=(void *)i.gipa(i.handle,"vkGetPhysicalDeviceMemoryProperties");
    fn(h,p); apply_budget(i,h,p,NULL);
}
static void properties2(VkPhysicalDevice h, VkPhysicalDeviceMemoryProperties2 *p, const char *name) {
    Instance i=instance_for(h);
    PFN_vkGetPhysicalDeviceMemoryProperties2 fn=(void *)i.gipa(i.handle,name);
    fn(h,p); apply_budget(i,h,&p->memoryProperties,p->pNext);
}
static void VKAPI_CALL memory_properties2(VkPhysicalDevice h, VkPhysicalDeviceMemoryProperties2 *p) { properties2(h,p,"vkGetPhysicalDeviceMemoryProperties2"); }
static void VKAPI_CALL memory_properties2_khr(VkPhysicalDevice h, VkPhysicalDeviceMemoryProperties2 *p) { properties2(h,p,"vkGetPhysicalDeviceMemoryProperties2KHR"); }
static VkResult VKAPI_CALL create_instance(const VkInstanceCreateInfo *ci,const VkAllocationCallbacks *a,VkInstance *out) {
    VkLayerInstanceCreateInfo *chain=(void *)ci->pNext;
    while (chain && !(chain->sType==VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO && chain->function==VK_LAYER_LINK_INFO)) chain=(void *)chain->pNext;
    if (!chain || !chain->u.pLayerInfo) return VK_ERROR_INITIALIZATION_FAILED;
    PFN_vkGetInstanceProcAddr next=chain->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    PFN_GetPhysicalDeviceProcAddr pgpa=chain->u.pLayerInfo->pfnNextGetPhysicalDeviceProcAddr;
    PFN_vkCreateInstance fn=(void *)next(VK_NULL_HANDLE,"vkCreateInstance");
    if (!fn) return VK_ERROR_INITIALIZATION_FAILED;
    int slot=-1;
    pthread_mutex_lock(&lock);
    for (int n=0; n<MAX_INSTANCES; n++) if (!instances[n].occupied) { slot=n; instances[n].occupied=1; break; }
    pthread_mutex_unlock(&lock);
    if (slot<0) return VK_ERROR_OUT_OF_HOST_MEMORY;
    chain->u.pLayerInfo=chain->u.pLayerInfo->pNext;
    VkResult result=fn(ci,a,out);
    pthread_mutex_lock(&lock);
    if (result==VK_SUCCESS) instances[slot]=(Instance){.key=dispatch_key(*out),.handle=*out,.gipa=next,.pgpa=pgpa,.cap=configured_cap(),.occupied=1};
    else memset(&instances[slot],0,sizeof(Instance));
    pthread_mutex_unlock(&lock); return result;
}
static void VKAPI_CALL destroy_instance(VkInstance h,const VkAllocationCallbacks *a) {
    Instance i=instance_for(h); if (!i.gipa) return;
    PFN_vkDestroyInstance fn=(void *)i.gipa(h,"vkDestroyInstance");
    pthread_mutex_lock(&lock);
    for (int n=0; n<MAX_INSTANCES; n++) if (instances[n].handle==h) memset(&instances[n],0,sizeof(Instance));
    pthread_mutex_unlock(&lock); fn(h,a);
}
static VkResult VKAPI_CALL create_device(VkPhysicalDevice h,const VkDeviceCreateInfo *ci,const VkAllocationCallbacks *a,VkDevice *out) {
    Instance i=instance_for(h); VkLayerDeviceCreateInfo *chain=(void *)ci->pNext;
    while (chain && !(chain->sType==VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO && chain->function==VK_LAYER_LINK_INFO)) chain=(void *)chain->pNext;
    if (!chain || !chain->u.pLayerInfo || !i.handle) return VK_ERROR_INITIALIZATION_FAILED;
    PFN_vkGetDeviceProcAddr gdpa=chain->u.pLayerInfo->pfnNextGetDeviceProcAddr;
    PFN_vkCreateDevice fn=(void *)chain->u.pLayerInfo->pfnNextGetInstanceProcAddr(i.handle,"vkCreateDevice");
    if (!fn) return VK_ERROR_INITIALIZATION_FAILED;
    int slot=-1; pthread_mutex_lock(&lock);
    for (int n=0; n<MAX_DEVICES; n++) if (!devices[n].occupied) { slot=n;devices[n].occupied=1;break; }
    pthread_mutex_unlock(&lock);
    if (slot<0) return VK_ERROR_OUT_OF_HOST_MEMORY;
    chain->u.pLayerInfo=chain->u.pLayerInfo->pNext;
    VkResult result=fn(h,ci,a,out);
    pthread_mutex_lock(&lock);
    if (result==VK_SUCCESS) devices[slot]=(Device){dispatch_key(*out),*out,gdpa,1};
    else memset(&devices[slot],0,sizeof(Device));
    pthread_mutex_unlock(&lock); return result;
}
static void VKAPI_CALL destroy_device(VkDevice h,const VkAllocationCallbacks *a) {
    Device d=device_for(h); if (!d.gdpa) return;
    PFN_vkDestroyDevice fn=(void *)d.gdpa(h,"vkDestroyDevice");
    pthread_mutex_lock(&lock);
    for (int n=0; n<MAX_DEVICES; n++) if (devices[n].handle==h) memset(&devices[n],0,sizeof(Device));
    pthread_mutex_unlock(&lock); fn(h,a);
}
EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance,const char *);
EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetDeviceProcAddr(VkDevice h,const char *name) {
    if (!name || !h) return NULL;
    Device d=device_for(h); if (!d.gdpa) return NULL;
    PFN_vkVoidFunction fn=d.gdpa(h,name); if (!fn) return NULL;
    if (!strcmp(name,"vkGetDeviceProcAddr")) return (PFN_vkVoidFunction)vkGetDeviceProcAddr;
    if (!strcmp(name,"vkDestroyDevice")) return (PFN_vkVoidFunction)destroy_device;
    return fn;
}
static PFN_vkVoidFunction hook(const char *name,PFN_vkVoidFunction fn) {
    if (!fn) return NULL;
    #define HOOK(n,f) if (!strcmp(name,n)) return (PFN_vkVoidFunction)f
    HOOK("vkDestroyDevice",destroy_device);
    HOOK("vkDestroyInstance",destroy_instance); HOOK("vkCreateDevice",create_device);
    HOOK("vkGetPhysicalDeviceMemoryProperties",memory_properties);
    HOOK("vkGetPhysicalDeviceMemoryProperties2",memory_properties2);
    HOOK("vkGetPhysicalDeviceMemoryProperties2KHR",memory_properties2_khr);
    return fn;
}
EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance h,const char *name) {
    if (!name) return NULL;
    HOOK("vkGetInstanceProcAddr",vkGetInstanceProcAddr); HOOK("vkGetDeviceProcAddr",vkGetDeviceProcAddr); HOOK("vkCreateInstance",create_instance);
    if (!h) return NULL;
    Instance i=instance_for(h); return i.gipa ? hook(name,i.gipa(h,name)) : NULL;
}
static PFN_vkVoidFunction VKAPI_CALL physical_proc(VkInstance h,const char *name) {
    if (!h || !name) return NULL;
    Instance i=instance_for(h);
    return hook(name,i.pgpa ? i.pgpa(h,name) : i.gipa ? i.gipa(h,name) : NULL);
}
EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkNegotiateLoaderLayerInterfaceVersion(VkNegotiateLayerInterface *v) {
    if (!v || v->loaderLayerInterfaceVersion<2) return VK_ERROR_INITIALIZATION_FAILED;
    v->loaderLayerInterfaceVersion=2; v->pfnGetInstanceProcAddr=vkGetInstanceProcAddr;
    v->pfnGetDeviceProcAddr=vkGetDeviceProcAddr; v->pfnGetPhysicalDeviceProcAddr=physical_proc; return VK_SUCCESS;
}
