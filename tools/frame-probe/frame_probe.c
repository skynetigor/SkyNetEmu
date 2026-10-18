/* MIT. Opt-in Vulkan layer: counts queued presents, never inserts GPU waits. */
#define _GNU_SOURCE
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>
#include <vulkan/vk_layer.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/stat.h>

#define EXPORT __attribute__((visibility("default")))
#define LIMIT 128
static pthread_mutex_t mutex=PTHREAD_MUTEX_INITIALIZER;
static uint64_t next_id;
static uint64_t now_ns(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC,&t); return (uint64_t)t.tv_sec*1000000000+t.tv_nsec; }
static void *key(const void *handle) { return handle ? *(void *const *)handle : NULL; }
typedef struct { void *key; VkInstance instance; PFN_vkGetInstanceProcAddr gipa; } Instance;
typedef struct { void *key; VkDevice device; PFN_vkGetDeviceProcAddr gdpa; } Device;
typedef struct { VkSurfaceKHR surface; uint64_t window; } Surface;
typedef struct {
 VkSwapchainKHR swap; VkDevice device; uint64_t id,window,start,updated,last_write;
 uint64_t attempts,queued,failures,acquires,acquire_us,present_us,acquire_max_us,present_max_us;
 uint32_t width,height,mode,images; int last_result,alive;
} Swap;
static Instance instances[LIMIT]; static Device devices[LIMIT]; static Surface surfaces[LIMIT]; static Swap swaps[LIMIT];
static Instance instance_for(const void *h) { Instance found={0}; pthread_mutex_lock(&mutex); for(int i=0;i<LIMIT;i++) if(instances[i].key==key(h)) {found=instances[i];break;} pthread_mutex_unlock(&mutex); return found; }
static Device device_for(const void *h) { Device found={0}; pthread_mutex_lock(&mutex); for(int i=0;i<LIMIT;i++) if(devices[i].key==key(h)) {found=devices[i];break;} pthread_mutex_unlock(&mutex); return found; }
static Swap *swap_for(VkSwapchainKHR swap) { for(int i=0;i<LIMIT;i++) if(swaps[i].swap==swap && swaps[i].alive) return &swaps[i]; return NULL; }
/* Caller holds mutex. Only numeric metadata, at most one write/second/swapchain plus create/destroy. */
static void publish(Swap *s,int force) {
 const char *dir=getenv("SKY_FRAME_PROBE_DIR"); uint64_t now=now_ns(); s->updated=now;
 if(!dir || !*dir || (!force && now-s->last_write<1000000000)) return;
 s->last_write=now;
 char path[1024],tmp[1032];
 if(snprintf(path,sizeof(path),"%s/vk-%d-%llu.json",dir,getpid(),(unsigned long long)s->id)>=(int)sizeof(path)) return;
 snprintf(tmp,sizeof(tmp),"%s.tmp",path);
 FILE *f=fopen(tmp,"w"); if(!f) return;
 fprintf(f,"{\"version\":1,\"pid\":%d,\"swap\":%llu,\"window\":%llu,\"start_ns\":%llu,\"updated_ns\":%llu,\"alive\":%d,\"width\":%u,\"height\":%u,\"mode\":%u,\"images\":%u,\"attempts\":%llu,\"queued\":%llu,\"failures\":%llu,\"acquires\":%llu,\"acquire_us\":%llu,\"acquire_max_us\":%llu,\"present_us\":%llu,\"present_max_us\":%llu,\"last_result\":%d}\n",
 getpid(),(unsigned long long)s->id,(unsigned long long)s->window,(unsigned long long)s->start,(unsigned long long)s->updated,s->alive,s->width,s->height,s->mode,s->images,(unsigned long long)s->attempts,(unsigned long long)s->queued,(unsigned long long)s->failures,(unsigned long long)s->acquires,(unsigned long long)s->acquire_us,(unsigned long long)s->acquire_max_us,(unsigned long long)s->present_us,(unsigned long long)s->present_max_us,s->last_result);
 fclose(f); rename(tmp,path);
}
static VkResult VKAPI_CALL create_instance(const VkInstanceCreateInfo *ci,const VkAllocationCallbacks *a,VkInstance *out) {
 VkLayerInstanceCreateInfo *chain=(void *)ci->pNext;
 while(chain && !(chain->sType==VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO && chain->function==VK_LAYER_LINK_INFO)) chain=(void *)chain->pNext;
 if(!chain || !chain->u.pLayerInfo) return VK_ERROR_INITIALIZATION_FAILED;
 PFN_vkGetInstanceProcAddr next=chain->u.pLayerInfo->pfnNextGetInstanceProcAddr;
 PFN_vkCreateInstance fn=(void *)next(VK_NULL_HANDLE,"vkCreateInstance"); chain->u.pLayerInfo=chain->u.pLayerInfo->pNext;
 if(!fn) return VK_ERROR_INITIALIZATION_FAILED;
 VkResult result=fn(ci,a,out);
 if(result==VK_SUCCESS) { pthread_mutex_lock(&mutex); for(int i=0;i<LIMIT;i++) if(!instances[i].key) {instances[i]=(Instance){key(*out),*out,next};break;} pthread_mutex_unlock(&mutex); }
 return result;
}
static void VKAPI_CALL destroy_instance(VkInstance h,const VkAllocationCallbacks *a) {
 Instance i=instance_for(h); if(!i.gipa) return; PFN_vkDestroyInstance fn=(void *)i.gipa(h,"vkDestroyInstance");
 pthread_mutex_lock(&mutex); for(int n=0;n<LIMIT;n++) if(instances[n].instance==h) memset(&instances[n],0,sizeof(Instance)); pthread_mutex_unlock(&mutex); fn(h,a);
}
static VkResult VKAPI_CALL create_device(VkPhysicalDevice h,const VkDeviceCreateInfo *ci,const VkAllocationCallbacks *a,VkDevice *out) {
 Instance i=instance_for(h); VkLayerDeviceCreateInfo *chain=(void *)ci->pNext;
 while(chain && !(chain->sType==VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO && chain->function==VK_LAYER_LINK_INFO)) chain=(void *)chain->pNext;
 if(!chain || !chain->u.pLayerInfo || !i.instance) return VK_ERROR_INITIALIZATION_FAILED;
 PFN_vkGetDeviceProcAddr gdpa=chain->u.pLayerInfo->pfnNextGetDeviceProcAddr;
 PFN_vkCreateDevice fn=(void *)chain->u.pLayerInfo->pfnNextGetInstanceProcAddr(i.instance,"vkCreateDevice");
 chain->u.pLayerInfo=chain->u.pLayerInfo->pNext;
 VkResult result=fn(h,ci,a,out);
 if(result==VK_SUCCESS) { pthread_mutex_lock(&mutex); for(int n=0;n<LIMIT;n++) if(!devices[n].key) {devices[n]=(Device){key(*out),*out,gdpa};break;} pthread_mutex_unlock(&mutex); }
 return result;
}
static void VKAPI_CALL destroy_device(VkDevice h,const VkAllocationCallbacks *a) {
 Device d=device_for(h); if(!d.gdpa) return; PFN_vkDestroyDevice fn=(void *)d.gdpa(h,"vkDestroyDevice");
 pthread_mutex_lock(&mutex); for(int n=0;n<LIMIT;n++) if(devices[n].device==h) memset(&devices[n],0,sizeof(Device)); pthread_mutex_unlock(&mutex); fn(h,a);
}
/* XCB/Xlib surface create-info ABI, without depending on the build host's X11 headers. */
typedef struct { VkStructureType sType; const void *pNext; VkFlags flags; void *connection; uint32_t window; } XcbInfo;
typedef struct { VkStructureType sType; const void *pNext; VkFlags flags; void *display; unsigned long window; } XlibInfo;
static void remember_surface(VkSurfaceKHR h,uint64_t w) { pthread_mutex_lock(&mutex); for(int n=0;n<LIMIT;n++) if(!surfaces[n].surface) { surfaces[n]=(Surface){h,w};break;} pthread_mutex_unlock(&mutex); }
static VkResult VKAPI_CALL create_xcb(VkInstance h,const XcbInfo *ci,const VkAllocationCallbacks *a,VkSurfaceKHR *out) {
 Instance i=instance_for(h); typedef VkResult (*Fn)(VkInstance,const XcbInfo *,const VkAllocationCallbacks *,VkSurfaceKHR *); Fn fn=(void *)i.gipa(h,"vkCreateXcbSurfaceKHR"); VkResult r=fn(h,ci,a,out); if(r==VK_SUCCESS) remember_surface(*out,ci->window); return r;
}
static VkResult VKAPI_CALL create_xlib(VkInstance h,const XlibInfo *ci,const VkAllocationCallbacks *a,VkSurfaceKHR *out) {
 Instance i=instance_for(h); typedef VkResult (*Fn)(VkInstance,const XlibInfo *,const VkAllocationCallbacks *,VkSurfaceKHR *); Fn fn=(void *)i.gipa(h,"vkCreateXlibSurfaceKHR"); VkResult r=fn(h,ci,a,out); if(r==VK_SUCCESS) remember_surface(*out,ci->window); return r;
}
static void VKAPI_CALL destroy_surface(VkInstance h,VkSurfaceKHR surface,const VkAllocationCallbacks *a) {
 Instance i=instance_for(h); PFN_vkDestroySurfaceKHR fn=(void *)i.gipa(h,"vkDestroySurfaceKHR");
 pthread_mutex_lock(&mutex); for(int n=0;n<LIMIT;n++) if(surfaces[n].surface==surface) memset(&surfaces[n],0,sizeof(Surface)); pthread_mutex_unlock(&mutex); fn(h,surface,a);
}
static VkResult VKAPI_CALL create_swap(VkDevice h,const VkSwapchainCreateInfoKHR *ci,const VkAllocationCallbacks *a,VkSwapchainKHR *out) {
 Device d=device_for(h); PFN_vkCreateSwapchainKHR fn=(void *)d.gdpa(h,"vkCreateSwapchainKHR"); VkResult r=fn(h,ci,a,out);
 if(r==VK_SUCCESS) {
  pthread_mutex_lock(&mutex);
  for(int n=0;n<LIMIT;n++) if(!swaps[n].alive) {
   Swap *s=&swaps[n]; memset(s,0,sizeof(*s)); s->swap=*out;s->device=h;s->id=++next_id;s->alive=1;s->start=now_ns();
   s->width=ci->imageExtent.width;s->height=ci->imageExtent.height;s->mode=ci->presentMode;s->images=ci->minImageCount;
   for(int k=0;k<LIMIT;k++) if(surfaces[k].surface==ci->surface) s->window=surfaces[k].window;
   publish(s,1);break;
  }
  pthread_mutex_unlock(&mutex);
 }
 return r;
}
static void VKAPI_CALL destroy_swap(VkDevice h,VkSwapchainKHR swap,const VkAllocationCallbacks *a) {
 Device d=device_for(h); PFN_vkDestroySwapchainKHR fn=(void *)d.gdpa(h,"vkDestroySwapchainKHR");
 pthread_mutex_lock(&mutex); Swap *s=swap_for(swap);if(s) {s->alive=0;publish(s,1);} pthread_mutex_unlock(&mutex); fn(h,swap,a);
}
static void record_acquire(VkSwapchainKHR swap,uint64_t elapsed) {
 pthread_mutex_lock(&mutex); Swap *s=swap_for(swap);if(s) {s->acquires++;s->acquire_us+=elapsed;if(elapsed>s->acquire_max_us)s->acquire_max_us=elapsed;publish(s,0);} pthread_mutex_unlock(&mutex);
}
static VkResult VKAPI_CALL acquire(VkDevice h,VkSwapchainKHR swap,uint64_t timeout,VkSemaphore sem,VkFence fence,uint32_t *index) {
 Device d=device_for(h); PFN_vkAcquireNextImageKHR fn=(void *)d.gdpa(h,"vkAcquireNextImageKHR");uint64_t start=now_ns();VkResult r=fn(h,swap,timeout,sem,fence,index);record_acquire(swap,(now_ns()-start)/1000);return r;
}
static VkResult VKAPI_CALL acquire2(VkDevice h,const VkAcquireNextImageInfoKHR *ci,uint32_t *index) {
 Device d=device_for(h);PFN_vkAcquireNextImage2KHR fn=(void *)d.gdpa(h,"vkAcquireNextImage2KHR");uint64_t start=now_ns();VkResult r=fn(h,ci,index);record_acquire(ci->swapchain,(now_ns()-start)/1000);return r;
}
static VkResult VKAPI_CALL present(VkQueue h,const VkPresentInfoKHR *ci) {
 Device d=device_for(h);PFN_vkQueuePresentKHR fn=(void *)d.gdpa(d.device,"vkQueuePresentKHR");uint64_t start=now_ns();VkResult r=fn(h,ci);uint64_t elapsed=(now_ns()-start)/1000;
 pthread_mutex_lock(&mutex);
 for(uint32_t n=0;n<ci->swapchainCount;n++) {Swap *s=swap_for(ci->pSwapchains[n]);if(!s)continue;VkResult result=ci->pResults?ci->pResults[n]:r;
  s->attempts++; if(result==VK_SUCCESS || result==VK_SUBOPTIMAL_KHR)s->queued++;else s->failures++;
  s->last_result=result;s->present_us+=elapsed;if(elapsed>s->present_max_us)s->present_max_us=elapsed;publish(s,0);
 }
 pthread_mutex_unlock(&mutex);return r;
}
EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance h,const char *name);
EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetDeviceProcAddr(VkDevice h,const char *name) {
 if(!h || !name)return NULL;Device d=device_for(h);if(!d.gdpa)return NULL;PFN_vkVoidFunction fn=d.gdpa(h,name);if(!fn)return NULL;
 #define HOOK(n,f) if(!strcmp(name,n))return (PFN_vkVoidFunction)f
 HOOK("vkGetDeviceProcAddr",vkGetDeviceProcAddr);HOOK("vkDestroyDevice",destroy_device);HOOK("vkCreateSwapchainKHR",create_swap);HOOK("vkDestroySwapchainKHR",destroy_swap);HOOK("vkAcquireNextImageKHR",acquire);HOOK("vkAcquireNextImage2KHR",acquire2);HOOK("vkQueuePresentKHR",present);
 return fn;
}
EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance h,const char *name) {
 if(!name)return NULL;HOOK("vkGetInstanceProcAddr",vkGetInstanceProcAddr);HOOK("vkGetDeviceProcAddr",vkGetDeviceProcAddr);HOOK("vkCreateInstance",create_instance);
 if(!h)return NULL;Instance i=instance_for(h);if(!i.gipa)return NULL;PFN_vkVoidFunction fn=i.gipa(h,name);if(!fn)return NULL;
 HOOK("vkCreateDevice",create_device);HOOK("vkDestroyInstance",destroy_instance);HOOK("vkCreateXcbSurfaceKHR",create_xcb);HOOK("vkCreateXlibSurfaceKHR",create_xlib);HOOK("vkDestroySurfaceKHR",destroy_surface);
 HOOK("vkDestroyDevice",destroy_device);HOOK("vkCreateSwapchainKHR",create_swap);HOOK("vkDestroySwapchainKHR",destroy_swap);HOOK("vkAcquireNextImageKHR",acquire);HOOK("vkAcquireNextImage2KHR",acquire2);HOOK("vkQueuePresentKHR",present);
 return fn;
}
EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkNegotiateLoaderLayerInterfaceVersion(VkNegotiateLayerInterface *v) {
 if(!v || v->loaderLayerInterfaceVersion<2)return VK_ERROR_INITIALIZATION_FAILED;
 v->loaderLayerInterfaceVersion=2;v->pfnGetInstanceProcAddr=vkGetInstanceProcAddr;v->pfnGetDeviceProcAddr=vkGetDeviceProcAddr;v->pfnGetPhysicalDeviceProcAddr=NULL;return VK_SUCCESS;
}
