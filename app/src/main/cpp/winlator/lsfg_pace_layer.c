/* Queues each present and returns at once. A Choreographer callback then submits
   at most one image per vsync. A spare graphics queue is used when the driver
   has one. Turnip advertises one, so the fallback presents that queue only from
   the vsync thread; game submits take the same lock and never wait on it.
   The game's wait semaphores are consumed on its own thread before the call
   returns, so the next frame can signal them. The ready fence is created here:
   the frame-gen layer reuses its own fence on the next frame. Turnip ignores
   display timing. This layer never sleeps. A 0.6-vsync delay on this thread
   made desired times uneven and left the 16.2/27.0 scanout; do not put it back.
   Link with -landroid -llog -Wl,-z,defs or the loader cannot resolve Choreographer. */
#define VK_NO_PROTOTYPES
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
typedef struct {
    void *key;
    VkDevice device;
    PFN_vkGetDeviceProcAddr gdpa;
    VkQueue host;
    uint32_t host_family;
    uint32_t host_index;
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
static VkSwapchainKHR paced_swaps[LIMIT];
static int paced_count;
static int logged_mode;

static int swap_is_paced(VkSwapchainKHR swap) {
    for (int i = 0; i < paced_count; i++) if (paced_swaps[i] == swap) return 1;
    return 0;
}

static void mark_paced(VkSwapchainKHR swap) {
    if (!swap || swap_is_paced(swap) || paced_count >= LIMIT) return;
    paced_swaps[paced_count++] = swap;
    LOG("pacing framegen swapchain");
}

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
    VkDeviceCreateInfo rewritten;
    VkDeviceQueueCreateInfo qcis[8];
    float priors[8][4];
    uint32_t family = UINT32_MAX, index = 0;
    int extra = claim_host_queue(inst, physical, ci, &rewritten, qcis, priors, &family, &index);
    VkResult result = fn(physical, extra ? &rewritten : ci, alloc, out);
    if (result != VK_SUCCESS && extra) {
        LOG("spare queue rejected (%d); presenting on the game queue is disabled", result);
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
            LOG("no spare graphics queue; vsync thread paces the game queue");
        }
        pthread_mutex_lock(&mutex);
        for (int i = 0; i < LIMIT; i++) if (!devices[i].key) {
            devices[i] = (Device){key_of(*out), *out, gdpa, host, family, index};
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
static uint32_t presented;
static uint32_t catchups;
static uint64_t pace_logged;

static int queued_jobs(void) {
    int count = 0;
    for (int i = 0; i < JOBS; i++) if (jobs[i].live) count++;
    return count;
}

/* Half the measured source period, in vsyncs. Four 5.405 ms ticks is 21.6 ms,
   the 25 -> 50 slot, until a real period has been seen. Never one tick: that
   is the 5.4 ms pair. */
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
    if (half == 0) half = 21621620ull;
    if (vsync < 2000000ull) vsync = 5405405ull;
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

static int fence_signaled(VkQueue queue, VkFence fence) {
    if (!fence) return 1;
    Device dev = device_for(queue);
    PFN_vkGetFenceStatus fn = dev.gdpa ? (PFN_vkGetFenceStatus)dev.gdpa(dev.device, "vkGetFenceStatus") : NULL;
    if (!fn) return 1;
    return fn(dev.device, fence) == VK_SUCCESS;
}

/* One image per callback, and only after its copy fence has signaled. A full
   queue presents anyway so acquire cannot stall for a whole frame; that
   catch-up is logged because it can re-pair. */
static void present_one(int force) {
    int slots = slots_needed();
    Job job;
    int have = 0;
    int peek = 0;
    VkQueue peek_queue = VK_NULL_HANDLE;
    VkFence peek_fence = VK_NULL_HANDLE;
    pthread_mutex_lock(&mutex);
    int depth = queued_jobs();
    int due = last_present_vsync < 0 || vsync_index >= last_present_vsync + slots;
    if (depth > 0 && (due || force || depth >= 6)) {
        for (int n = 0; n < JOBS; n++) {
            int i = (job_head + n) % JOBS;
            if (!jobs[i].live) continue;
            peek = 1;
            peek_queue = jobs[i].queue;
            peek_fence = jobs[i].ready;
            jobs[i].wait_vsyncs++;
            if (jobs[i].wait_vsyncs > slots * 2) force = 1;
            break;
        }
    }
    pthread_mutex_unlock(&mutex);
    int ready = !peek || force || fence_signaled(peek_queue, peek_fence);
    pthread_mutex_lock(&mutex);
    if (peek && ready && (due || force || depth >= 6)) {
        for (int n = 0; n < JOBS; n++) {
            int i = (job_head + n) % JOBS;
            if (!jobs[i].live) continue;
            job = jobs[i];
            jobs[i].live = 0;
            job_head = (i + 1) % JOBS;
            job_count--;
            have = 1;
            if (!due) catchups++;
            last_present_vsync = vsync_index;
            presented++;
            break;
        }
    }
    uint64_t now = now_ns();
    if (now - pace_logged > 1000000000ull) {
        pace_logged = now;
        LOG("presented %u queued %d slots %d vsync %llu us catchup %u",
                presented, depth, slots, (unsigned long long)(vsync_ns / 1000ull), catchups);
        presented = 0;
        catchups = 0;
    }
    pthread_mutex_unlock(&mutex);
    if (have) present_job(job);
}

static void on_frame(long frameTimeNanos, void *data) {
    (void)data;
    static long last_frame;
    if (last_frame) {
        long delta = frameTimeNanos - last_frame;
        if (delta > 2000000L && delta < 20000000L) {
            pthread_mutex_lock(&mutex);
            vsync_ns = (vsync_ns * 7 + (uint64_t)delta) / 8;
            pthread_mutex_unlock(&mutex);
        }
    }
    last_frame = frameTimeNanos;
    pthread_mutex_lock(&mutex);
    vsync_index++;
    pthread_mutex_unlock(&mutex);
    AChoreographer_postFrameCallback(AChoreographer_getInstance(), on_frame, NULL);
    present_one(0);
}

static void *pacer_main(void *arg) {
    (void)arg;
    ALooper_prepare(ALOOPER_PREPARE_ALLOW_NON_CALLBACKS);
    AChoreographer_postFrameCallback(AChoreographer_getInstance(), on_frame, NULL);
    LOG("choreographer pacer started");
    for (;;) ALooper_pollOnce(-1, NULL, NULL, NULL);
    return NULL;
}

static void start_pacer(void) {
    pthread_mutex_lock(&mutex);
    if (!pacer_started) {
        pacer_started = 1;
        if (pthread_create(&pacer_thread, NULL, pacer_main, NULL) != 0) {
            pacer_started = 0;
            LOG("choreographer pacer failed to start");
        } else {
            pthread_detach(pacer_thread);
        }
    }
    pthread_mutex_unlock(&mutex);
}

static int enqueue_present(VkQueue queue, const VkPresentInfoKHR *ci, VkSemaphore release, VkFence ready) {
    if (!ci || ci->swapchainCount == 0 || ci->swapchainCount > MAX_CHAIN
            || ci->waitSemaphoreCount > MAX_WAITS || !ci->pSwapchains || !ci->pImageIndices)
        return 0;
    Job job;
    memset(&job, 0, sizeof(job));
    job.live = 1;
    job.queue = queue;
    job.release = release;
    job.ready = ready;
    job.waitCount = ci->waitSemaphoreCount;
    if (job.waitCount && ci->pWaitSemaphores)
        memcpy(job.waits, ci->pWaitSemaphores, job.waitCount * sizeof(VkSemaphore));
    job.swapCount = ci->swapchainCount;
    memcpy(job.swaps, ci->pSwapchains, job.swapCount * sizeof(VkSwapchainKHR));
    memcpy(job.images, ci->pImageIndices, job.swapCount * sizeof(uint32_t));
    for (const VkBaseInStructure *next = ci->pNext; next; next = next->pNext) {
        if (next->sType == VK_STRUCTURE_TYPE_PRESENT_ID_KHR) {
            const VkPresentIdKHR *id = (const VkPresentIdKHR *)next;
            if (id->swapchainCount > 0 && id->pPresentIds) {
                job.hasId = 1;
                job.presentId = id->pPresentIds[0];
            }
        } else if (next->sType == SKY_FG_READY_STYPE) {
            const SkyFgReadyInfo *info = (const SkyFgReadyInfo *)next;
            job.generated = info->generated ? 1 : 0;
        }
    }
    pthread_mutex_lock(&mutex);
    if (job_count >= JOBS) {
        pthread_mutex_unlock(&mutex);
        LOG("present queue full");
        return 0;
    }
    int stored = 0;
    for (int n = 0; n < JOBS; n++) {
        int i = (job_head + n) % JOBS;
        if (jobs[i].live) continue;
        jobs[i] = job;
        job_count++;
        stored = 1;
        break;
    }
    pthread_mutex_unlock(&mutex);
    if (!stored) {
        LOG("present queue full");
        return 0;
    }
    note_arrival(job.swaps[0], now_ns());
    return 1;
}

typedef struct { VkSemaphore release; VkFence ready; } Handoff;

/* Consume the game's present waits on its own queue, then a later present waits
   only on the semaphore this submit signals. The fence is ours: the frame-gen
   layer destroys its fence when the next frame reuses that slot. */
static Handoff handoff(Device dev, VkQueue game, const VkPresentInfoKHR *ci) {
    Handoff held = {0};
    if (!dev.gdpa) return held;
    PFN_vkCreateSemaphore create = (void *)dev.gdpa(dev.device, "vkCreateSemaphore");
    PFN_vkCreateFence create_fence = (void *)dev.gdpa(dev.device, "vkCreateFence");
    PFN_vkQueueSubmit submit = (void *)dev.gdpa(dev.device, "vkQueueSubmit");
    if (!create || !submit) return held;
    VkSemaphoreCreateInfo sci = { .sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO };
    if (create(dev.device, &sci, NULL, &held.release) != VK_SUCCESS) return held;
    if (create_fence) {
        VkFenceCreateInfo fci = { .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO };
        if (create_fence(dev.device, &fci, NULL, &held.ready) != VK_SUCCESS) held.ready = VK_NULL_HANDLE;
    }
    VkPipelineStageFlags stages[MAX_WAITS];
    uint32_t waits = ci->waitSemaphoreCount;
    if (waits > MAX_WAITS) waits = MAX_WAITS;
    for (uint32_t i = 0; i < waits; i++) stages[i] = VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;
    VkSubmitInfo submit_info = {
        .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
        .waitSemaphoreCount = waits,
        .pWaitSemaphores = waits ? ci->pWaitSemaphores : NULL,
        .pWaitDstStageMask = waits ? stages : NULL,
        .signalSemaphoreCount = 1,
        .pSignalSemaphores = &held.release,
    };
    /* Do not take host_mutex here. A present on the other thread must not wait
       for this submit, and this submit must not wait for that present. */
    VkResult submitted = submit(game, 1, &submit_info, held.ready);
    if (submitted != VK_SUCCESS) {
        destroy_sync(game, held.release, held.ready);
        return (Handoff){0};
    }
    return held;
}

static void VKAPI_CALL get_device_queue(VkDevice device, uint32_t family, uint32_t index, VkQueue *out) {
    Device dev = device_for(device);
    PFN_vkGetDeviceQueue fn = dev.gdpa ? (void *)dev.gdpa(device, "vkGetDeviceQueue") : NULL;
    if (!fn || !out) return;
    fn(device, family, index, out);
    if (*out) remember_queue(*out, device);
}

static int present_should_pace(const VkPresentInfoKHR *ci) {
    int tagged = 0;
    for (const VkBaseInStructure *next = ci->pNext; next; next = next->pNext)
        if (next->sType == SKY_FG_READY_STYPE) tagged = 1;
    pthread_mutex_lock(&mutex);
    if (tagged) for (uint32_t i = 0; i < ci->swapchainCount; i++) mark_paced(ci->pSwapchains[i]);
    int pace = 0;
    for (uint32_t i = 0; i < ci->swapchainCount && !pace; i++) pace = swap_is_paced(ci->pSwapchains[i]);
    pthread_mutex_unlock(&mutex);
    return pace;
}

static VkResult VKAPI_CALL present(VkQueue queue, const VkPresentInfoKHR *ci) {
    Device dev = device_for(queue);
    PFN_vkQueuePresentKHR fn = dev.gdpa ? (PFN_vkQueuePresentKHR)dev.gdpa(dev.device, "vkQueuePresentKHR") : NULL;
    if (!fn || !ci || ci->swapchainCount == 0) return fn ? fn(queue, ci) : VK_ERROR_INITIALIZATION_FAILED;
    /* CEF and the splash present on other swapchains. Delaying those acquires
       stalls the UI and drops the GPU to idle. */
    if (!present_should_pace(ci)) return fn(queue, ci);
    if (dev.device) remember_queue(queue, dev.device);
    VkQueue paced = dev.host ? dev.host : queue;
    Handoff held = handoff(dev, queue, ci);
    if (!held.release) return fn(queue, ci);
    start_pacer();
    if (!enqueue_present(paced, ci, held.release, held.ready)) {
        Job now;
        memset(&now, 0, sizeof(now));
        now.queue = paced;
        now.release = held.release;
        now.ready = held.ready;
        now.swapCount = ci->swapchainCount < MAX_CHAIN ? ci->swapchainCount : MAX_CHAIN;
        memcpy(now.swaps, ci->pSwapchains, now.swapCount * sizeof(VkSwapchainKHR));
        memcpy(now.images, ci->pImageIndices, now.swapCount * sizeof(uint32_t));
        present_job(now);
    }
    if (ci->pResults) {
        for (uint32_t i = 0; i < ci->swapchainCount; i++) ci->pResults[i] = VK_SUCCESS;
    }
    return VK_SUCCESS;
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
    HOOK("vkGetDeviceQueue", get_device_queue);
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
