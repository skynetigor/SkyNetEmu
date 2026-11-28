/* The game returns from vkQueuePresentKHR immediately. A second VkDevice owns
   the window and presents one already-ready image per output slot. Presenting
   Turnip's single game queue from this thread SIGSEGV'd in FEXMemJIT.
   Link with -landroid -llog -Wl,-z,defs or the loader cannot resolve Choreographer. */
#define VK_NO_PROTOTYPES
#include "lsfg_host_scanout.h"
#include <vulkan/vulkan_android.h>
#include <vulkan/vulkan.h>
#include <vulkan/vk_layer.h>
#include <android/choreographer.h>
#include <android/looper.h>
#include <android/log.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/eventfd.h>
#include <sys/timerfd.h>
#include <time.h>
#include <unistd.h>

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
typedef struct {
    void *key;
    VkDevice device;
    VkPhysicalDevice physical;
    VkInstance instance;
    PFN_vkGetInstanceProcAddr gipa;
    PFN_vkGetDeviceProcAddr gdpa;
    PFN_vkCreateDevice next_create;
    VkQueue host;
    uint32_t host_family;
    uint32_t host_index;
    uint32_t game_family;
} Device;
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

typedef struct { void *key; VkDevice device; } QueueLink;

static Instance instances[LIMIT];
static Device devices[LIMIT];
static QueueLink queues[LIMIT];
static Swap swaps[LIMIT];
static int logged_mode;

static void remember_queue(VkQueue queue, VkDevice device) {
    if (!queue || !device) return;
    void *key = key_of(queue);
    pthread_mutex_lock(&mutex);
    for (int i = 0; i < LIMIT; i++) if (queues[i].key == key) {
        queues[i].device = device;
        pthread_mutex_unlock(&mutex);
        return;
    }
    for (int i = 0; i < LIMIT; i++) if (!queues[i].key) {
        queues[i] = (QueueLink){key, device};
        break;
    }
    pthread_mutex_unlock(&mutex);
}

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
    VkDevice linked = VK_NULL_HANDLE;
    for (int i = 0; i < LIMIT; i++) if (queues[i].key == key) { linked = queues[i].device; break; }
    if (linked) {
        for (int i = 0; i < LIMIT; i++) if (devices[i].device == linked) { found = devices[i]; break; }
    }
    if (!found.key) {
        int count = 0;
        for (int i = 0; i < LIMIT; i++) if (devices[i].key) {
            count++;
            if (devices[i].key == key || !found.key) found = devices[i];
            if (devices[i].key == key) break;
        }
        if (count != 1 && found.key != key) memset(&found, 0, sizeof(found));
    }
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

static void flush_swap(VkSwapchainKHR swap);
static void drain_retired(Device dev);

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

/* A spare graphics queue when the driver has one. Turnip advertises one graphics
   queue, so that path usually does not apply. The fallback still presents only
   from the Choreographer thread; game submits take the same lock and never wait
   on it, which is the external sync a shared queue requires. */
static int claim_host_queue(Instance inst, VkPhysicalDevice physical, const VkDeviceCreateInfo *ci,
        VkDeviceCreateInfo *rewritten, VkDeviceQueueCreateInfo *qcis, float priors[][4],
        uint32_t *family, uint32_t *index) {
    *family = UINT32_MAX;
    if (!ci->queueCreateInfoCount || ci->queueCreateInfoCount > 8 || !ci->pQueueCreateInfos) return 0;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties get_props = inst.gipa
            ? (void *)inst.gipa(inst.instance, "vkGetPhysicalDeviceQueueFamilyProperties") : NULL;
    if (!get_props) return 0;
    uint32_t families = 0;
    get_props(physical, &families, NULL);
    if (!families || families > 16) return 0;
    VkQueueFamilyProperties props[16];
    get_props(physical, &families, props);
    *rewritten = *ci;
    rewritten->pQueueCreateInfos = qcis;
    for (uint32_t i = 0; i < ci->queueCreateInfoCount; i++) {
        qcis[i] = ci->pQueueCreateInfos[i];
        uint32_t want = qcis[i].queueCount;
        uint32_t copy = want < 4 ? want : 4;
        for (uint32_t p = 0; p < copy; p++)
            priors[i][p] = qcis[i].pQueuePriorities ? qcis[i].pQueuePriorities[p] : 1.0f;
        qcis[i].pQueuePriorities = priors[i];
        uint32_t fam = qcis[i].queueFamilyIndex;
        if (*family == UINT32_MAX && fam < families && want < 4 && props[fam].queueCount > want
                && (props[fam].queueFlags & VK_QUEUE_GRAPHICS_BIT)) {
            priors[i][want] = 1.0f;
            qcis[i].queueCount = want + 1;
            *family = fam;
            *index = want;
        }
    }
    return *family != UINT32_MAX;
}

static int device_has_ext(Instance inst, VkPhysicalDevice physical, const char *name) {
    PFN_vkEnumerateDeviceExtensionProperties enumerate = inst.gipa
            ? (void *)inst.gipa(inst.instance, "vkEnumerateDeviceExtensionProperties") : NULL;
    if (!enumerate || !name) return 0;
    uint32_t count = 0;
    if (enumerate(physical, NULL, &count, NULL) != VK_SUCCESS || !count || count > 512) return 0;
    VkExtensionProperties *props = calloc(count, sizeof(*props));
    if (!props) return 0;
    int found = 0;
    if (enumerate(physical, NULL, &count, props) == VK_SUCCESS) {
        for (uint32_t i = 0; i < count; i++) if (!strcmp(props[i].extensionName, name)) found = 1;
    }
    free(props);
    return found;
}

/* The game device must import the same AHB images the host device blits. Only
   extensions the physical device enumerates are added, and the loader chain stays
   on pNext. */
static const VkDeviceCreateInfo *inject_export(Instance inst, VkPhysicalDevice physical,
        const VkDeviceCreateInfo *ci, VkDeviceCreateInfo *out, const char **names,
        VkPhysicalDeviceSamplerYcbcrConversionFeatures *ycbcr) {
    static const char *required[] = {
        "VK_ANDROID_external_memory_android_hardware_buffer",
        "VK_KHR_external_memory",
        "VK_KHR_dedicated_allocation",
        "VK_KHR_get_memory_requirements2",
        "VK_KHR_bind_memory2",
        "VK_KHR_sampler_ycbcr_conversion",
        "VK_KHR_external_semaphore",
        "VK_KHR_external_semaphore_fd",
    };
    uint32_t count = ci->enabledExtensionCount;
    if (!ci->ppEnabledExtensionNames) count = 0;
    if (count > 48) return ci;
    for (uint32_t i = 0; i < count; i++) names[i] = ci->ppEnabledExtensionNames[i];
    int added = 0;
    for (uint32_t r = 0; r < sizeof(required) / sizeof(required[0]) && count < 56; r++) {
        int present = 0;
        for (uint32_t i = 0; i < count; i++) if (names[i] && !strcmp(names[i], required[r])) present = 1;
        if (present || !device_has_ext(inst, physical, required[r])) continue;
        names[count++] = required[r];
        added = 1;
    }
    int has_ycbcr = 0;
    for (const VkBaseInStructure *next = (const VkBaseInStructure *)ci->pNext; next; next = next->pNext) {
        if (next->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SAMPLER_YCBCR_CONVERSION_FEATURES) has_ycbcr = 1;
    }
    if (!added && has_ycbcr) return ci;
    *out = *ci;
    out->enabledExtensionCount = count;
    out->ppEnabledExtensionNames = names;
    if (!has_ycbcr) {
        *ycbcr = (VkPhysicalDeviceSamplerYcbcrConversionFeatures){
            .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SAMPLER_YCBCR_CONVERSION_FEATURES,
            .pNext = (void *)ci->pNext,
            .samplerYcbcrConversion = VK_TRUE,
        };
        out->pNext = ycbcr;
    }
    if (added) LOG("game device gained AHB export extensions");
    return out;
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
    /* The loader chain is only valid until this call returns. Copy the already
       advanced link so a second device can be created before we return. */
    void *next_link = chain->u.pLayerInfo;
    if (!fn) return VK_ERROR_INITIALIZATION_FAILED;
    VkDeviceCreateInfo rewritten;
    VkDeviceQueueCreateInfo qcis[8];
    float priors[8][4];
    uint32_t family = UINT32_MAX, index = 0;
    int extra = claim_host_queue(inst, physical, ci, &rewritten, qcis, priors, &family, &index);
    const char *export_names[56];
    VkPhysicalDeviceSamplerYcbcrConversionFeatures ycbcr;
    const VkDeviceCreateInfo *requested = extra ? &rewritten : ci;
    requested = inject_export(inst, physical, requested, &rewritten, export_names, &ycbcr);
    VkResult result = fn(physical, requested, alloc, out);
    if (result != VK_SUCCESS && requested != ci) {
        LOG("device create with export extensions failed (%d); retrying the app create info", result);
        result = fn(physical, ci, alloc, out);
        extra = 0;
    }
    if (result == VK_SUCCESS) {
        VkQueue host = VK_NULL_HANDLE;
        if (extra) {
            PFN_vkGetDeviceQueue get_queue = (void *)gdpa(*out, "vkGetDeviceQueue");
            if (get_queue) get_queue(*out, family, index, &host);
            if (host) remember_queue(host, *out);
            LOG("host present queue family %u index %u %s", family, index, host ? "ready" : "missing");
        } else {
            LOG("no spare graphics queue; host scanout uses a second device");
            int wants_present = 0;
            for (uint32_t i = 0; ci->ppEnabledExtensionNames && i < ci->enabledExtensionCount; i++) {
                if (ci->ppEnabledExtensionNames[i] && !strcmp(ci->ppEnabledExtensionNames[i], "VK_KHR_swapchain"))
                    wants_present = 1;
            }
            if (wants_present) {
                HostGameInfo game = {
                    .instance = inst.instance,
                    .physical = physical,
                    .device = *out,
                    .gipa = inst.gipa,
                    .gdpa = gdpa,
                    .next_create = fn,
                    .queue_family = ci->queueCreateInfoCount ? ci->pQueueCreateInfos[0].queueFamilyIndex : 0,
                };
                host_create_device(&game, next_link);
            }
        }
        pthread_mutex_lock(&mutex);
        for (int i = 0; i < LIMIT; i++) if (!devices[i].key) {
            devices[i] = (Device){
                .key = key_of(*out),
                .device = *out,
                .physical = physical,
                .instance = inst.instance,
                .gipa = inst.gipa,
                .gdpa = gdpa,
                .next_create = fn,
                .host = host,
                .host_family = family,
                .host_index = index,
                .game_family = ci->queueCreateInfoCount ? ci->pQueueCreateInfos[0].queueFamilyIndex : 0,
            };
            break;
        }
        pthread_mutex_unlock(&mutex);
    }
    return result;
}

static void VKAPI_CALL destroy_device(VkDevice handle, const VkAllocationCallbacks *alloc) {
    Device dev = device_for(handle);
    if (!dev.gdpa) return;
    flush_swap(VK_NULL_HANDLE);
    drain_retired(dev);
    PFN_vkDestroyDevice fn = (void *)dev.gdpa(handle, "vkDestroyDevice");
    pthread_mutex_lock(&mutex);
    for (int i = 0; i < LIMIT; i++) if (devices[i].device == handle) memset(&devices[i], 0, sizeof(Device));
    pthread_mutex_unlock(&mutex);
    if (fn) fn(handle, alloc);
}

static VkResult VKAPI_CALL create_swap(VkDevice handle, const VkSwapchainCreateInfoKHR *ci,
        const VkAllocationCallbacks *alloc, VkSwapchainKHR *out) {
    Device dev = device_for(handle);
    if (dev.device && dev.gipa && ci && ci->surface) {
        HostGameInfo game = {
            .instance = dev.instance,
            .physical = dev.physical,
            .device = dev.device,
            .gipa = dev.gipa,
            .gdpa = dev.gdpa,
            .next_create = dev.next_create,
            .queue_family = dev.game_family,
        };
        if (host_virtualize(&game, ci, out)) return VK_SUCCESS;
        LOG("host scanout unavailable; presenting on the calling thread");
    }
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
    if (host_owns(swap)) {
        host_destroy(swap);
        return;
    }
    flush_swap(swap);
    Device dev = device_for(handle);
    PFN_vkDestroySwapchainKHR fn = dev.gdpa ? (PFN_vkDestroySwapchainKHR)dev.gdpa(handle, "vkDestroySwapchainKHR") : NULL;
    pthread_mutex_lock(&mutex);
    Swap *slot = swap_slot(swap, 0);
    if (slot) slot->alive = 0;
    pthread_mutex_unlock(&mutex);
    if (fn) fn(handle, swap, alloc);
}

#define JOBS 16
#define MAX_WAITS 8
#define MAX_CHAIN 4

static pthread_mutex_t host_mutex = PTHREAD_MUTEX_INITIALIZER;

typedef struct {
    int live;
    VkQueue queue;
    VkSemaphore release;
    uint32_t waitCount;
    VkSemaphore waits[MAX_WAITS];
    uint32_t swapCount;
    VkSwapchainKHR swaps[MAX_CHAIN];
    uint32_t images[MAX_CHAIN];
    int hasId;
    uint64_t presentId;
    VkFence ready;
    int generated;
    int wait_vsyncs;
} Job;

/* Private pNext from the framegen layer. Stripped before the driver sees the present. */
#define SKY_FG_READY_STYPE ((VkStructureType)1000464001)
typedef struct {
    VkStructureType sType;
    const void *pNext;
    VkFence fence;
    uint32_t generated;
} SkyFgReadyInfo;

static Job jobs[JOBS];
static int job_head;
static int job_count;
static pthread_t pacer_thread;
static int pacer_started;
static uint64_t vsync_ns = 5405405ull;
static int vsync_index;
static int last_present_vsync = -1000;
static int64_t last_frame_time;
static int timer_fd = -1;
static int kick_fd = -1;
static int wake_armed;
static int scheduling;
static int target_vsync;
static uint64_t target_vsync_ns;
static int64_t present_phase_ns;
static uint32_t presented;
static uint64_t pace_logged;

/* Half the measured source period, in display vsyncs. Until a source period is
   seen, hold one extra image for two display ticks: 16.7 ms on 120 Hz, 10.8 ms
   on 185 Hz. One tick is the adjacent-refresh pair. */
static int slots_needed(void) {
    uint64_t half = 0;
    uint64_t vsync;
    pthread_mutex_lock(&mutex);
    for (int i = 0; i < LIMIT; i++) if (swaps[i].alive && swaps[i].pair_period) {
        half = swaps[i].pair_period / 2;
        break;
    }
    vsync = vsync_ns;
    pthread_mutex_unlock(&mutex);
    if (vsync < 4000000ull || vsync > 17000000ull) vsync = 8333333ull;
    if (half == 0) half = vsync * 2;
    int slots = (int)((half + vsync / 2) / vsync);
    if (slots < 2) slots = 2;
    if (slots > 8) slots = 8;
    return slots;
}

static void note_arrival(VkSwapchainKHR swap, uint64_t now) {
    pthread_mutex_lock(&mutex);
    Swap *slot = swap_slot(swap, 1);
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
    }
    pthread_mutex_unlock(&mutex);
}

static void present_job(Job job);

static void flush_swap(VkSwapchainKHR swap) {
    for (;;) {
        Job job;
        int have = 0;
        pthread_mutex_lock(&mutex);
        for (int n = 0; n < JOBS && job_count > 0; n++) {
            int i = (job_head + n) % JOBS;
            if (!jobs[i].live) continue;
            int match = !swap;
            for (uint32_t s = 0; !match && s < jobs[i].swapCount; s++) if (jobs[i].swaps[s] == swap) match = 1;
            if (!match) break;
            job = jobs[i];
            jobs[i].live = 0;
            job_head = (i + 1) % JOBS;
            job_count--;
            have = 1;
            break;
        }
        pthread_mutex_unlock(&mutex);
        if (!have) return;
        present_job(job);
    }
}

static void destroy_sync(VkQueue queue, VkSemaphore release, VkFence ready) {
    Device dev = device_for(queue);
    if (!dev.gdpa) return;
    if (release) {
        PFN_vkDestroySemaphore fn = (void *)dev.gdpa(dev.device, "vkDestroySemaphore");
        if (fn) fn(dev.device, release, NULL);
    }
    if (ready) {
        PFN_vkDestroyFence fn = (void *)dev.gdpa(dev.device, "vkDestroyFence");
        if (fn) fn(dev.device, ready, NULL);
    }
}

/* Present submits the wait. The semaphore stays referenced until that present
   finishes, so it is destroyed only after several later presents. */
#define RETIRE 24
static struct { VkQueue queue; VkSemaphore release; VkFence ready; } retired[RETIRE];
static int retire_next;

static void retire_sync(VkQueue queue, VkSemaphore release, VkFence ready) {
    if (!release && !ready) return;
    destroy_sync(retired[retire_next].queue, retired[retire_next].release, retired[retire_next].ready);
    retired[retire_next].queue = queue;
    retired[retire_next].release = release;
    retired[retire_next].ready = ready;
    retire_next = (retire_next + 1) % RETIRE;
}

static void drain_retired(Device dev) {
    if (!dev.gdpa) return;
    PFN_vkDeviceWaitIdle idle = (void *)dev.gdpa(dev.device, "vkDeviceWaitIdle");
    if (idle) idle(dev.device);
    for (int i = 0; i < RETIRE; i++) {
        destroy_sync(retired[i].queue, retired[i].release, retired[i].ready);
        memset(&retired[i], 0, sizeof(retired[i]));
    }
}

static void present_job(Job job) {
    Device dev = device_for(job.queue);
    PFN_vkQueuePresentKHR fn = dev.gdpa ? (PFN_vkQueuePresentKHR)dev.gdpa(dev.device, "vkQueuePresentKHR") : NULL;
    if (!fn || !job.queue) {
        LOG("deferred present dropped; host queue missing");
        retire_sync(job.queue, job.release, job.ready);
        return;
    }
    VkSemaphore release = job.release;
    VkPresentIdKHR id = {
        .sType = VK_STRUCTURE_TYPE_PRESENT_ID_KHR,
        .swapchainCount = 1,
        .pPresentIds = &job.presentId,
    };
    VkPresentInfoKHR info = {
        .sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR,
        .pNext = job.hasId ? &id : NULL,
        .waitSemaphoreCount = release ? 1 : 0,
        .pWaitSemaphores = release ? &release : NULL,
        .swapchainCount = job.swapCount,
        .pSwapchains = job.swaps,
        .pImageIndices = job.images,
    };
    pthread_mutex_lock(&host_mutex);
    VkResult result = fn(job.queue, &info);
    pthread_mutex_unlock(&host_mutex);
    retire_sync(job.queue, job.release, job.ready);
    if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR)
        LOG("deferred present failed %d", result);
}

static void arm_timer(uint64_t when) {
    if (timer_fd < 0) return;
    struct itimerspec spec;
    memset(&spec, 0, sizeof(spec));
    if (when) {
        spec.it_value.tv_sec = (time_t)(when / 1000000000ull);
        spec.it_value.tv_nsec = (long)(when % 1000000000ull);
    }
    timerfd_settime(timer_fd, TFD_TIMER_ABSTIME, &spec, NULL);
    wake_armed = when != 0;
}

static void kick_pacer(void) {
    if (kick_fd < 0) return;
    uint64_t one = 1;
    ssize_t wrote = write(kick_fd, &one, sizeof(one));
    (void)wrote;
}

/* Present one already-ready host image. Never touches the game queue.
   A second ready image stays queued until another display slot elapses. */
static void present_one(int from_timer) {
    (void)from_timer;
    int slots = slots_needed();
    pthread_mutex_lock(&mutex);
    int elapsed = vsync_index - last_present_vsync;
    int due = last_present_vsync < 0 || elapsed >= slots;
    uint64_t vsync = vsync_ns;
    pthread_mutex_unlock(&mutex);
    int depth = host_queued();
    if (due && host_present_one()) {
        pthread_mutex_lock(&mutex);
        last_present_vsync = vsync_index;
        present_phase_ns = (int64_t)now_ns() - (int64_t)last_frame_time;
        presented++;
        pthread_mutex_unlock(&mutex);
    }
    pthread_mutex_lock(&mutex);
    uint64_t now = now_ns();
    if (now - pace_logged > 1000000000ull) {
        pace_logged = now;
        LOG("host presented %u queued %d slots %d vsync %llu us phase %lld us",
                presented, depth, slots, (unsigned long long)(vsync / 1000ull),
                (long long)(present_phase_ns / 1000));
        presented = 0;
    }
    pthread_mutex_unlock(&mutex);
    host_prepare();
}

static void schedule_wake(void) {
    if (scheduling || timer_fd < 0) return;
    scheduling = 1;
    int depth;
    int last;
    int index;
    uint64_t period;
    int64_t frame;
    pthread_mutex_lock(&mutex);
    depth = host_queued();
    last = last_present_vsync;
    index = vsync_index;
    period = vsync_ns;
    frame = last_frame_time;
    pthread_mutex_unlock(&mutex);
    if (period < 4000000ull || period > 17000000ull) period = 8333333ull;
    if ((depth == 0 && !host_blit_pending()) || frame == 0) {
        arm_timer(0);
        scheduling = 0;
        return;
    }
    int slots = slots_needed();
    int elapsed = last < 0 ? slots : index - last;
    if (elapsed >= slots) present_one(1);
    pthread_mutex_lock(&mutex);
    last = last_present_vsync;
    index = vsync_index;
    frame = last_frame_time;
    depth = host_queued();
    pthread_mutex_unlock(&mutex);
    if ((depth == 0 && !host_blit_pending()) || frame == 0) {
        arm_timer(0);
        scheduling = 0;
        return;
    }
    elapsed = last < 0 ? 0 : index - last;
    int wait_slots = slots - elapsed;
    if (wait_slots < 1) wait_slots = 1;
    uint64_t target_time = (uint64_t)frame + (uint64_t)wait_slots * period;
    target_vsync = index + wait_slots;
    target_vsync_ns = target_time;
    /* The display callback presents. The timer only covers a missed callback. */
    uint64_t wake = target_time + period / 2;
    uint64_t now = now_ns();
    arm_timer(wake > now ? wake : now + period);
    scheduling = 0;
}

static int on_timer(int fd, int events, void *data) {
    (void)data;
    if (events & ALOOPER_EVENT_INPUT) {
        uint64_t expirations = 0;
        ssize_t n = read(fd, &expirations, sizeof(expirations));
        (void)n;
    }
    wake_armed = 0;
    schedule_wake();
    return 1;
}

static int on_kick(int fd, int events, void *data) {
    (void)data;
    if (events & ALOOPER_EVENT_INPUT) {
        uint64_t pending = 0;
        ssize_t n = read(fd, &pending, sizeof(pending));
        (void)n;
    }
    schedule_wake();
    return 1;
}

static void on_frame(long frameTimeNanos, void *data) {
    (void)data;
    int extra = 0;
    if (last_frame_time) {
        int64_t delta = frameTimeNanos - last_frame_time;
        pthread_mutex_lock(&mutex);
        if (delta >= 4000000LL && delta <= 17000000LL) {
            /* Snap off the 185 Hz default. A 120 Hz callback is 8.33 ms. */
            if (vsync_ns < 4000000ull || vsync_ns > 17000000ull
                    || delta > (int64_t)vsync_ns * 6 / 5
                    || delta < (int64_t)vsync_ns * 4 / 5)
                vsync_ns = (uint64_t)delta;
            else
                vsync_ns = (vsync_ns * 7 + (uint64_t)delta) / 8;
        } else if (delta > 17000000LL && vsync_ns >= 4000000ull && vsync_ns <= 17000000ull) {
            extra = (int)(delta / (int64_t)vsync_ns) - 1;
            if (extra < 0) extra = 0;
            if (extra > 8) extra = 8;
        }
        pthread_mutex_unlock(&mutex);
    }
    last_frame_time = frameTimeNanos;
    pthread_mutex_lock(&mutex);
    vsync_index += 1 + extra;
    for (int n = 0; n < JOBS; n++) {
        int i = (job_head + n) % JOBS;
        if (!jobs[i].live) continue;
        jobs[i].wait_vsyncs++;
        break;
    }
    pthread_mutex_unlock(&mutex);
    /* arm64 long is 64-bit. minSdk 28 cannot call the API 29 64-bit entry. */
    AChoreographer_postFrameCallback(AChoreographer_getInstance(), on_frame, NULL);
    host_prepare();
    schedule_wake();
}

static void *pacer_main(void *arg) {
    (void)arg;
    ALooper_prepare(ALOOPER_PREPARE_ALLOW_NON_CALLBACKS);
    ALooper *looper = ALooper_forThread();
    if (timer_fd >= 0)
        ALooper_addFd(looper, timer_fd, ALOOPER_POLL_CALLBACK, ALOOPER_EVENT_INPUT, on_timer, NULL);
    if (kick_fd >= 0)
        ALooper_addFd(looper, kick_fd, ALOOPER_POLL_CALLBACK, ALOOPER_EVENT_INPUT, on_kick, NULL);
    AChoreographer_postFrameCallback(AChoreographer_getInstance(), on_frame, NULL);
    LOG("host scanout pacer started");
    schedule_wake();
    for (;;) ALooper_pollOnce(-1, NULL, NULL, NULL);
    return NULL;
}

static void start_pacer(void) {
    pthread_mutex_lock(&mutex);
    if (!pacer_started) {
        if (timer_fd < 0)
            timer_fd = timerfd_create(CLOCK_MONOTONIC, TFD_NONBLOCK | TFD_CLOEXEC);
        if (kick_fd < 0)
            kick_fd = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
        pacer_started = 1;
        if (timer_fd < 0 || kick_fd < 0 || pthread_create(&pacer_thread, NULL, pacer_main, NULL) != 0) {
            pacer_started = 0;
            LOG("host scanout pacer failed to start");
        } else {
            pthread_detach(pacer_thread);
        }
    }
    pthread_mutex_unlock(&mutex);
}

static void VKAPI_CALL get_device_queue(VkDevice device, uint32_t family, uint32_t index, VkQueue *out) {
    Device dev = device_for(device);
    PFN_vkGetDeviceQueue fn = dev.gdpa ? (void *)dev.gdpa(device, "vkGetDeviceQueue") : NULL;
    if (!fn || !out) return;
    fn(device, family, index, out);
    if (*out) remember_queue(*out, device);
}

static VkResult VKAPI_CALL acquire_next(VkDevice device, VkSwapchainKHR swap, uint64_t timeout,
        VkSemaphore semaphore, VkFence fence, uint32_t *index) {
    if (host_owns(swap)) {
        Device dev = device_for(device);
        VkQueue queue = VK_NULL_HANDLE;
        PFN_vkGetDeviceQueue get_queue = dev.gdpa ? (void *)dev.gdpa(device, "vkGetDeviceQueue") : NULL;
        if (get_queue) get_queue(device, dev.game_family, 0, &queue);
        return host_acquire(swap, timeout, semaphore, fence, index, queue);
    }
    Device dev = device_for(device);
    PFN_vkAcquireNextImageKHR fn = dev.gdpa ? (void *)dev.gdpa(device, "vkAcquireNextImageKHR") : NULL;
    return fn ? fn(device, swap, timeout, semaphore, fence, index) : VK_ERROR_INITIALIZATION_FAILED;
}

static VkResult VKAPI_CALL acquire_next2(VkDevice device, const VkAcquireNextImageInfoKHR *info, uint32_t *index) {
    if (info && host_owns(info->swapchain))
        return acquire_next(device, info->swapchain, info->timeout, info->semaphore, info->fence, index);
    Device dev = device_for(device);
    PFN_vkAcquireNextImage2KHR fn = dev.gdpa ? (void *)dev.gdpa(device, "vkAcquireNextImage2KHR") : NULL;
    return fn ? fn(device, info, index) : VK_ERROR_INITIALIZATION_FAILED;
}

static VkResult VKAPI_CALL get_swap_images(VkDevice device, VkSwapchainKHR swap, uint32_t *count, VkImage *images) {
    if (host_owns(swap)) return host_images(swap, count, images);
    Device dev = device_for(device);
    PFN_vkGetSwapchainImagesKHR fn = dev.gdpa ? (void *)dev.gdpa(device, "vkGetSwapchainImagesKHR") : NULL;
    return fn ? fn(device, swap, count, images) : VK_ERROR_INITIALIZATION_FAILED;
}

static VkResult VKAPI_CALL present(VkQueue queue, const VkPresentInfoKHR *ci) {
    Device dev = device_for(queue);
    PFN_vkQueuePresentKHR fn = dev.gdpa ? (PFN_vkQueuePresentKHR)dev.gdpa(dev.device, "vkQueuePresentKHR") : NULL;
    if (!ci || ci->swapchainCount == 0) return fn ? fn(queue, ci) : VK_ERROR_INITIALIZATION_FAILED;
    if (ci->pSwapchains && host_owns(ci->pSwapchains[0])) {
        if (!host_accept_present(ci->pSwapchains[0], queue, ci)) return VK_ERROR_OUT_OF_DATE_KHR;
        note_arrival(ci->pSwapchains[0], now_ns());
        start_pacer();
        kick_pacer();
        if (ci->pResults) {
            for (uint32_t i = 0; i < ci->swapchainCount; i++) ci->pResults[i] = VK_SUCCESS;
        }
        return VK_SUCCESS;
    }
    if (!fn) return VK_ERROR_INITIALIZATION_FAILED;
    /* A virtual swapchain is the only paced path. Presenting this queue from
       the vsync thread SIGSEGV'd in FEXMemJIT. */
    return fn(queue, ci);
}

static VkResult VKAPI_CALL create_android_surface(VkInstance instance, const VkAndroidSurfaceCreateInfoKHR *ci,
        const VkAllocationCallbacks *alloc, VkSurfaceKHR *surface) {
    Instance inst = instance_for(instance);
    PFN_vkCreateAndroidSurfaceKHR fn = inst.gipa
            ? (PFN_vkCreateAndroidSurfaceKHR)inst.gipa(instance, "vkCreateAndroidSurfaceKHR") : NULL;
    if (!fn) return VK_ERROR_INITIALIZATION_FAILED;
    VkResult result = fn(instance, ci, alloc, surface);
    if (result == VK_SUCCESS && ci && surface) host_remember_window(*surface, ci->window);
    return result;
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
    HOOK("vkGetDeviceQueue", get_device_queue);
    HOOK("vkGetSwapchainImagesKHR", get_swap_images);
    HOOK("vkAcquireNextImageKHR", acquire_next);
    HOOK("vkAcquireNextImage2KHR", acquire_next2);
    return fn;
}

EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance handle, const char *name) {
    if (!name) return NULL;
    HOOK("vkGetInstanceProcAddr", vkGetInstanceProcAddr);
    HOOK("vkGetDeviceProcAddr", vkGetDeviceProcAddr);
    HOOK("vkCreateInstance", create_instance);
    HOOK("vkCreateAndroidSurfaceKHR", create_android_surface);
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
    HOOK("vkGetDeviceQueue", get_device_queue);
    HOOK("vkGetSwapchainImagesKHR", get_swap_images);
    HOOK("vkAcquireNextImageKHR", acquire_next);
    HOOK("vkAcquireNextImage2KHR", acquire_next2);
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
