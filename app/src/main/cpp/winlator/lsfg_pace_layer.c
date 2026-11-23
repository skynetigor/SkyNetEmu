/* Spaces a frame-generation pair on the display clock and returns at once.
   Holding the present call keeps the game's image outstanding and the GPU drops its clocks. */
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>
#include <vulkan/vk_layer.h>
#include <android/log.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define EXPORT __attribute__((visibility("default")))
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "FgPace", __VA_ARGS__)
#define LIMIT 32

static pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;
static void *key_of(const void *handle) { return handle ? *(void *const *)handle : NULL; }
static uint64_t now_ns(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (uint64_t)t.tv_sec * 1000000000ull + (uint64_t)t.tv_nsec;
}

typedef struct { void *key; VkInstance instance; PFN_vkGetInstanceProcAddr gipa; } Instance;
typedef struct { void *key; VkDevice device; PFN_vkGetDeviceProcAddr gdpa; } Device;
typedef struct {
    VkSwapchainKHR swap;
    uint64_t last_arrival;
    uint64_t last_scheduled;
    uint64_t last_short;
    uint64_t pair_period;
    uint64_t logged;
    uint32_t presents;
    uint32_t held;
    int alive;
} Swap;

static Instance instances[LIMIT];
static Device devices[LIMIT];
static Swap swaps[LIMIT];
static int logged_mode;

static Instance instance_for(const void *handle) {
    Instance found = {0};
    pthread_mutex_lock(&mutex);
    for (int i = 0; i < LIMIT; i++) if (instances[i].key == key_of(handle)) { found = instances[i]; break; }
    pthread_mutex_unlock(&mutex);
    return found;
}

static Device device_for(const void *handle) {
    Device found = {0};
    pthread_mutex_lock(&mutex);
    void *key = key_of(handle);
    int count = 0;
    for (int i = 0; i < LIMIT; i++) if (devices[i].key) {
        count++;
        if (devices[i].key == key || !found.key) found = devices[i];
        if (devices[i].key == key) break;
    }
    if (count != 1 && found.key != key) memset(&found, 0, sizeof(found));
    pthread_mutex_unlock(&mutex);
    return found;
}

static Swap *swap_slot(VkSwapchainKHR swap, int create) {
    Swap *free_slot = NULL;
    for (int i = 0; i < LIMIT; i++) {
        if (swaps[i].alive && swaps[i].swap == swap) return &swaps[i];
        if (create && !free_slot && !swaps[i].alive) free_slot = &swaps[i];
    }
    if (!create || !free_slot) return NULL;
    memset(free_slot, 0, sizeof(*free_slot));
    free_slot->swap = swap;
    free_slot->alive = 1;
    return free_slot;
}

static VkResult VKAPI_CALL create_instance(const VkInstanceCreateInfo *ci, const VkAllocationCallbacks *alloc, VkInstance *out) {
    VkLayerInstanceCreateInfo *chain = (void *)ci->pNext;
    while (chain && !(chain->sType == VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO && chain->function == VK_LAYER_LINK_INFO))
        chain = (void *)chain->pNext;
    if (!chain || !chain->u.pLayerInfo) return VK_ERROR_INITIALIZATION_FAILED;
    PFN_vkGetInstanceProcAddr next = chain->u.pLayerInfo->pfnNextGetInstanceProcAddr;
    PFN_vkCreateInstance fn = (void *)next(VK_NULL_HANDLE, "vkCreateInstance");
    chain->u.pLayerInfo = chain->u.pLayerInfo->pNext;
    if (!fn) return VK_ERROR_INITIALIZATION_FAILED;
    VkResult result = fn(ci, alloc, out);
    if (result == VK_SUCCESS) {
        pthread_mutex_lock(&mutex);
        for (int i = 0; i < LIMIT; i++) if (!instances[i].key) {
            instances[i] = (Instance){key_of(*out), *out, next};
            break;
        }
        pthread_mutex_unlock(&mutex);
        LOG("layer loaded");
    }
    return result;
}

static void VKAPI_CALL destroy_instance(VkInstance handle, const VkAllocationCallbacks *alloc) {
    Instance inst = instance_for(handle);
    if (!inst.gipa) return;
    PFN_vkDestroyInstance fn = (void *)inst.gipa(handle, "vkDestroyInstance");
    pthread_mutex_lock(&mutex);
    for (int i = 0; i < LIMIT; i++) if (instances[i].instance == handle) memset(&instances[i], 0, sizeof(Instance));
    pthread_mutex_unlock(&mutex);
    if (fn) fn(handle, alloc);
}

static VkResult VKAPI_CALL create_device(VkPhysicalDevice physical, const VkDeviceCreateInfo *ci,
        const VkAllocationCallbacks *alloc, VkDevice *out) {
    Instance inst = instance_for(physical);
    VkLayerDeviceCreateInfo *chain = (void *)ci->pNext;
    while (chain && !(chain->sType == VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO && chain->function == VK_LAYER_LINK_INFO))
        chain = (void *)chain->pNext;
    if (!chain || !chain->u.pLayerInfo || !inst.instance) return VK_ERROR_INITIALIZATION_FAILED;
    PFN_vkGetDeviceProcAddr gdpa = chain->u.pLayerInfo->pfnNextGetDeviceProcAddr;
    PFN_vkCreateDevice fn = (void *)chain->u.pLayerInfo->pfnNextGetInstanceProcAddr(inst.instance, "vkCreateDevice");
    chain->u.pLayerInfo = chain->u.pLayerInfo->pNext;
    if (!fn) return VK_ERROR_INITIALIZATION_FAILED;
    VkResult result = fn(physical, ci, alloc, out);
    if (result == VK_SUCCESS) {
        pthread_mutex_lock(&mutex);
        for (int i = 0; i < LIMIT; i++) if (!devices[i].key) {
            devices[i] = (Device){key_of(*out), *out, gdpa};
            break;
        }
        pthread_mutex_unlock(&mutex);
    }
    return result;
}

static void VKAPI_CALL destroy_device(VkDevice handle, const VkAllocationCallbacks *alloc) {
    Device dev = device_for(handle);
    if (!dev.gdpa) return;
    PFN_vkDestroyDevice fn = (void *)dev.gdpa(handle, "vkDestroyDevice");
    pthread_mutex_lock(&mutex);
    for (int i = 0; i < LIMIT; i++) if (devices[i].device == handle) memset(&devices[i], 0, sizeof(Device));
    pthread_mutex_unlock(&mutex);
    if (fn) fn(handle, alloc);
}

static VkResult VKAPI_CALL create_swap(VkDevice handle, const VkSwapchainCreateInfoKHR *ci,
        const VkAllocationCallbacks *alloc, VkSwapchainKHR *out) {
    Device dev = device_for(handle);
    PFN_vkCreateSwapchainKHR fn = (void *)dev.gdpa(handle, "vkCreateSwapchainKHR");
    if (!fn) return VK_ERROR_INITIALIZATION_FAILED;
    VkSwapchainCreateInfoKHR info = *ci;
    /* Mailbox replaces a frame whose display time has not arrived. FIFO keeps both.
       A third image lets the game acquire the next one while those two wait. */
    if (info.presentMode != VK_PRESENT_MODE_FIFO_KHR || info.minImageCount < 3) {
        if (!logged_mode) {
            logged_mode = 1;
            LOG("swapchain %u -> fifo images %u", info.presentMode, info.minImageCount < 3 ? 3 : info.minImageCount);
        }
        info.presentMode = VK_PRESENT_MODE_FIFO_KHR;
        if (info.minImageCount < 3) info.minImageCount = 3;
    }
    VkResult result = fn(handle, &info, alloc, out);
    if (result != VK_SUCCESS && (info.presentMode != ci->presentMode || info.minImageCount != ci->minImageCount)) {
        LOG("fifo swapchain failed (%d); using the requested mode", result);
        result = fn(handle, ci, alloc, out);
    }
    if (result == VK_SUCCESS) {
        pthread_mutex_lock(&mutex);
        swap_slot(*out, 1);
        pthread_mutex_unlock(&mutex);
    }
    return result;
}

static void VKAPI_CALL destroy_swap(VkDevice handle, VkSwapchainKHR swap, const VkAllocationCallbacks *alloc) {
    Device dev = device_for(handle);
    PFN_vkDestroySwapchainKHR fn = (void *)dev.gdpa(handle, "vkDestroySwapchainKHR");
    pthread_mutex_lock(&mutex);
    Swap *slot = swap_slot(swap, 0);
    if (slot) slot->alive = 0;
    pthread_mutex_unlock(&mutex);
    if (fn) fn(handle, swap, alloc);
}

/* When this present should be shown. 0 means as soon as the display can take it. */
static uint64_t pace(VkSwapchainKHR swap, uint64_t now) {
    pthread_mutex_lock(&mutex);
    Swap *slot = swap_slot(swap, 1);
    uint64_t desired = 0;
    if (slot) {
        uint64_t interval = slot->last_arrival ? now - slot->last_arrival : 0;
        if (interval > 100000000ull) {
            slot->last_short = 0;
            slot->pair_period = 0;
        } else if (slot->last_short >= 4000000ull && slot->last_short < 18000000ull
                && interval > 16000000ull && interval < 45000000ull) {
            uint64_t period = slot->last_short + interval;
            if (period >= 20000000ull && period <= 60000000ull
                    && (slot->pair_period == 0 || (period > slot->pair_period / 2 && period < slot->pair_period * 3 / 2)))
                slot->pair_period = slot->pair_period ? (slot->pair_period * 7 + period) / 8 : period;
        }
        if (interval && interval <= 100000000ull) slot->last_short = interval;
        slot->last_arrival = now;
        if (slot->presents < 8)
            LOG("present %u interval %llu ms", slot->presents, (unsigned long long)(interval / 1000000ull));
        if (interval >= 4000000ull && interval < 18000000ull && slot->pair_period) {
            uint64_t half = slot->pair_period / 2;
            uint64_t target = slot->last_scheduled + half;
            if (target > now && target < now + slot->pair_period) desired = target;
        }
        slot->last_scheduled = desired ? desired : now;
        slot->presents++;
        if (desired) slot->held++;
        if (now - slot->logged > 1000000000ull) {
            slot->logged = now;
            LOG("presents %u held %u pair %llu ms", slot->presents, slot->held,
                    (unsigned long long)(slot->pair_period / 1000000ull));
            slot->presents = 0;
            slot->held = 0;
        }
    }
    pthread_mutex_unlock(&mutex);
    return desired;
}

static VkResult VKAPI_CALL present(VkQueue queue, const VkPresentInfoKHR *ci) {
    Device dev = device_for(queue);
    PFN_vkQueuePresentKHR fn = dev.gdpa ? (void *)dev.gdpa(dev.device, "vkQueuePresentKHR") : NULL;
    if (!fn || !ci || ci->swapchainCount == 0 || ci->swapchainCount > 8) return fn ? fn(queue, ci) : VK_ERROR_INITIALIZATION_FAILED;
    uint64_t now = now_ns();
    VkPresentTimeGOOGLE times[8];
    int stamp = 0;
    for (uint32_t i = 0; i < ci->swapchainCount; i++) {
        uint64_t desired = pace(ci->pSwapchains[i], now);
        times[i].presentID = 0;
        times[i].desiredPresentTime = desired;
        if (desired) stamp = 1;
    }
    if (!stamp) return fn(queue, ci);
    VkPresentTimesInfoGOOGLE info = {0};
    info.sType = VK_STRUCTURE_TYPE_PRESENT_TIMES_INFO_GOOGLE;
    info.pNext = ci->pNext;
    info.swapchainCount = ci->swapchainCount;
    info.pTimes = times;
    VkPresentInfoKHR paced = *ci;
    paced.pNext = &info;
    return fn(queue, &paced);
}

EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance handle, const char *name);
EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetDeviceProcAddr(VkDevice handle, const char *name) {
    if (!handle || !name) return NULL;
    Device dev = device_for(handle);
    if (!dev.gdpa) return NULL;
    PFN_vkVoidFunction fn = dev.gdpa(handle, name);
    if (!fn) return NULL;
#define HOOK(n, f) if (!strcmp(name, n)) return (PFN_vkVoidFunction)(f)
    HOOK("vkGetDeviceProcAddr", vkGetDeviceProcAddr);
    HOOK("vkDestroyDevice", destroy_device);
    HOOK("vkCreateSwapchainKHR", create_swap);
    HOOK("vkDestroySwapchainKHR", destroy_swap);
    HOOK("vkQueuePresentKHR", present);
    return fn;
}

EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance handle, const char *name) {
    if (!name) return NULL;
    HOOK("vkGetInstanceProcAddr", vkGetInstanceProcAddr);
    HOOK("vkGetDeviceProcAddr", vkGetDeviceProcAddr);
    HOOK("vkCreateInstance", create_instance);
    if (!handle) return NULL;
    Instance inst = instance_for(handle);
    if (!inst.gipa) return NULL;
    PFN_vkVoidFunction fn = inst.gipa(handle, name);
    if (!fn) return NULL;
    HOOK("vkCreateDevice", create_device);
    HOOK("vkDestroyInstance", destroy_instance);
    HOOK("vkDestroyDevice", destroy_device);
    HOOK("vkCreateSwapchainKHR", create_swap);
    HOOK("vkDestroySwapchainKHR", destroy_swap);
    HOOK("vkQueuePresentKHR", present);
    return fn;
#undef HOOK
}

EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkNegotiateLoaderLayerInterfaceVersion(VkNegotiateLayerInterface *version) {
    if (!version || version->loaderLayerInterfaceVersion < 2) return VK_ERROR_INITIALIZATION_FAILED;
    version->loaderLayerInterfaceVersion = 2;
    version->pfnGetInstanceProcAddr = vkGetInstanceProcAddr;
    version->pfnGetDeviceProcAddr = vkGetDeviceProcAddr;
    version->pfnGetPhysicalDeviceProcAddr = NULL;
    return VK_SUCCESS;
}
