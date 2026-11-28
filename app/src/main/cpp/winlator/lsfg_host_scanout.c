#include "lsfg_host_scanout.h"

#include <android/hardware_buffer.h>
#include <android/native_window.h>
#include <vulkan/vk_layer.h>
#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "FgPace", __VA_ARGS__)
#define IMAGES 6
#define SCANS 4
#define QUEUE 16

typedef struct {
    VkImage image;
    VkDeviceMemory memory;
    VkImage host_image;
    VkDeviceMemory host_memory;
    AHardwareBuffer *buffer;
    int state; /* 0 free, 1 acquired, 2 queued, 3 displaying */
} VirtImage;

typedef struct {
    int live;
    int closing;
    int in_host;
    uint32_t magic;
    VkDevice game;
    PFN_vkGetDeviceProcAddr gdpa;
    VkFormat format;
    uint32_t width;
    uint32_t height;
    uint32_t count;
    VirtImage images[IMAGES];
    VkSwapchainKHR host_swap;
    ANativeWindow *window;
    VkImage swap_images[IMAGES];
    uint32_t swap_count;
    uint8_t swap_used[IMAGES];
    uint32_t game_family;
    VkCommandPool release_pool;
    VkCommandBuffer release_cmds[QUEUE];
    int release_reserved[QUEUE];
    int release_count;
} Scan;

typedef struct {
    int live;
    Scan *scan;
    uint32_t index;
    VkFence fence;
    VkSemaphore semaphore;
    int sync_fd;
    int cmd_slot;
} Item;

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t ready_cv;
static int cond_ready;

static Scan scans[SCANS];
static Item items[QUEUE];
static int item_head;
static int item_count;
static VkFence retire_fences[QUEUE];
static int retire_count;

static struct {
    int live;
    VkInstance instance;
    VkPhysicalDevice physical;
    VkDevice device;
    VkQueue queue;
    uint32_t family;
    VkCommandPool pool;
    VkCommandBuffer cmd;
    VkSemaphore acquire_sem;
    VkSemaphore blit_sem;
    VkSemaphore wait_sem;
    VkFence blit_fence;
    int blit_busy;
    int blit_scan;
    uint32_t blit_index;
    uint32_t pending_index;
    VkSwapchainKHR pending_swap;
    int can_blit;
    PFN_vkGetDeviceProcAddr gdpa;
    PFN_vkCreateSwapchainKHR create_swap;
    PFN_vkDestroySwapchainKHR destroy_swap;
    PFN_vkGetSwapchainImagesKHR get_images;
    PFN_vkAcquireNextImageKHR acquire;
    PFN_vkCreateImage create_image;
    PFN_vkDestroyImage destroy_image;
    PFN_vkAllocateMemory alloc_mem;
    PFN_vkFreeMemory free_mem;
    PFN_vkBindImageMemory bind_mem;
    PFN_vkCreateCommandPool create_pool;
    PFN_vkAllocateCommandBuffers alloc_cmd;
    PFN_vkBeginCommandBuffer begin_cmd;
    PFN_vkEndCommandBuffer end_cmd;
    PFN_vkResetCommandBuffer reset_cmd;
    PFN_vkCmdPipelineBarrier barrier;
    PFN_vkCmdBlitImage blit;
    PFN_vkCmdCopyImage copy;
    PFN_vkCreateSemaphore create_sem;
    PFN_vkDestroySemaphore destroy_sem;
    PFN_vkCreateFence create_fence;
    PFN_vkDestroyFence destroy_fence;
    PFN_vkResetFences reset_fences;
    PFN_vkGetFenceStatus fence_status;
    PFN_vkQueueSubmit submit;
    PFN_vkQueuePresentKHR present;
    PFN_vkDeviceWaitIdle idle;
    PFN_vkGetAndroidHardwareBufferPropertiesANDROID ahb_props;
    PFN_vkImportSemaphoreFdKHR import_fd;
} host;

static void ensure_cond(void) {
    if (cond_ready) return;
    pthread_condattr_t attr;
    pthread_condattr_init(&attr);
    pthread_condattr_setclock(&attr, CLOCK_MONOTONIC);
    pthread_cond_init(&ready_cv, &attr);
    pthread_condattr_destroy(&attr);
    cond_ready = 1;
}

static Scan *scan_of(VkSwapchainKHR swap) {
    uintptr_t index = (uintptr_t)swap;
    if (index < 0x51000001u || index > 0x51000000u + SCANS) return NULL;
    Scan *scan = &scans[index - 0x51000001u];
    if (!scan->live || scan->magic != 0x4853434eu) return NULL;
    return scan;
}

int host_owns(VkSwapchainKHR swap) {
    pthread_mutex_lock(&lock);
    int owns = scan_of(swap) != NULL;
    pthread_mutex_unlock(&lock);
    return owns;
}

static int has_ext(PFN_vkEnumerateDeviceExtensionProperties enumerate, VkPhysicalDevice physical, const char *name) {
    uint32_t count = 0;
    if (!enumerate || enumerate(physical, NULL, &count, NULL) != VK_SUCCESS || !count || count > 512) return 0;
    VkExtensionProperties *props = calloc(count, sizeof(*props));
    if (!props) return 0;
    int found = 0;
    if (enumerate(physical, NULL, &count, props) == VK_SUCCESS) {
        for (uint32_t i = 0; i < count; i++) if (!strcmp(props[i].extensionName, name)) found = 1;
    }
    free(props);
    return found;
}

static uint32_t mem_index(PFN_vkGetPhysicalDeviceMemoryProperties get, VkPhysicalDevice physical, uint32_t bits) {
    VkPhysicalDeviceMemoryProperties props;
    get(physical, &props);
    for (uint32_t i = 0; i < props.memoryTypeCount; i++) if (bits & (1u << i)) return i;
    return UINT32_MAX;
}

static uint64_t now_deadline(uint64_t timeout) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (uint64_t)now.tv_sec * 1000000000ull + (uint64_t)now.tv_nsec + timeout;
}

static int import_image(PFN_vkGetDeviceProcAddr gdpa, VkDevice device, VkPhysicalDevice physical,
        PFN_vkGetPhysicalDeviceMemoryProperties mem_props, AHardwareBuffer *buffer, VkFormat format,
        uint32_t usage, VkImage *image, VkDeviceMemory *memory) {
    *image = VK_NULL_HANDLE;
    *memory = VK_NULL_HANDLE;
    PFN_vkGetAndroidHardwareBufferPropertiesANDROID get_props = (void *)gdpa(device, "vkGetAndroidHardwareBufferPropertiesANDROID");
    PFN_vkCreateImage create = (void *)gdpa(device, "vkCreateImage");
    PFN_vkDestroyImage destroy = (void *)gdpa(device, "vkDestroyImage");
    PFN_vkAllocateMemory alloc = (void *)gdpa(device, "vkAllocateMemory");
    PFN_vkFreeMemory free_mem = (void *)gdpa(device, "vkFreeMemory");
    PFN_vkBindImageMemory bind = (void *)gdpa(device, "vkBindImageMemory");
    if (!get_props || !create || !alloc || !bind) return 0;
    VkAndroidHardwareBufferFormatPropertiesANDROID format_props = {
        .sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID,
    };
    VkAndroidHardwareBufferPropertiesANDROID props = {
        .sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID,
        .pNext = &format_props,
    };
    if (get_props(device, buffer, &props) != VK_SUCCESS) return 0;
    VkFormat use = format;
    if (format_props.format != VK_FORMAT_UNDEFINED && format_props.format != format) {
        LOG("host image format %d driver %d", format, format_props.format);
        use = format;
    }
    VkExternalMemoryImageCreateInfo external = {
        .sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO,
        .handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID,
    };
    AHardwareBuffer_Desc desc;
    memset(&desc, 0, sizeof(desc));
    AHardwareBuffer_describe(buffer, &desc);
    VkImageCreateInfo info = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
        .pNext = &external,
        .imageType = VK_IMAGE_TYPE_2D,
        .format = use,
        .extent = {desc.width, desc.height, 1},
        .mipLevels = 1,
        .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT,
        .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = usage,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
    };
    if (create(device, &info, NULL, image) != VK_SUCCESS) {
        info.usage = usage & ~(VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        if (create(device, &info, NULL, image) != VK_SUCCESS) return 0;
    }
    uint32_t type = mem_index(mem_props, physical, props.memoryTypeBits);
    if (type == UINT32_MAX) {
        if (destroy) destroy(device, *image, NULL);
        *image = VK_NULL_HANDLE;
        return 0;
    }
    VkImportAndroidHardwareBufferInfoANDROID import = {
        .sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID,
        .buffer = buffer,
    };
    VkMemoryDedicatedAllocateInfo dedicated = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO,
        .pNext = &import,
        .image = *image,
    };
    VkMemoryAllocateInfo alloc_info = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .pNext = &dedicated,
        .allocationSize = props.allocationSize,
        .memoryTypeIndex = type,
    };
    if (alloc(device, &alloc_info, NULL, memory) != VK_SUCCESS || bind(device, *image, *memory, 0) != VK_SUCCESS) {
        if (*memory && free_mem) free_mem(device, *memory, NULL);
        *memory = VK_NULL_HANDLE;
        if (destroy) destroy(device, *image, NULL);
        *image = VK_NULL_HANDLE;
        return 0;
    }
    return 1;
}

static int load_host_procs(void) {
    PFN_vkGetDeviceProcAddr gdpa = host.gdpa;
    VkDevice device = host.device;
#define LOAD(field, name) host.field = (void *)gdpa(device, name); if (!host.field) { LOG("host missing %s", name); return 0; }
    LOAD(create_swap, "vkCreateSwapchainKHR");
    LOAD(destroy_swap, "vkDestroySwapchainKHR");
    LOAD(get_images, "vkGetSwapchainImagesKHR");
    LOAD(acquire, "vkAcquireNextImageKHR");
    LOAD(create_image, "vkCreateImage");
    LOAD(destroy_image, "vkDestroyImage");
    LOAD(alloc_mem, "vkAllocateMemory");
    LOAD(free_mem, "vkFreeMemory");
    LOAD(bind_mem, "vkBindImageMemory");
    LOAD(create_pool, "vkCreateCommandPool");
    LOAD(alloc_cmd, "vkAllocateCommandBuffers");
    LOAD(begin_cmd, "vkBeginCommandBuffer");
    LOAD(end_cmd, "vkEndCommandBuffer");
    LOAD(reset_cmd, "vkResetCommandBuffer");
    LOAD(barrier, "vkCmdPipelineBarrier");
    LOAD(blit, "vkCmdBlitImage");
    LOAD(copy, "vkCmdCopyImage");
    LOAD(create_sem, "vkCreateSemaphore");
    LOAD(destroy_sem, "vkDestroySemaphore");
    LOAD(create_fence, "vkCreateFence");
    LOAD(destroy_fence, "vkDestroyFence");
    LOAD(reset_fences, "vkResetFences");
    LOAD(fence_status, "vkGetFenceStatus");
    LOAD(submit, "vkQueueSubmit");
    LOAD(present, "vkQueuePresentKHR");
    LOAD(idle, "vkDeviceWaitIdle");
    host.ahb_props = (void *)gdpa(device, "vkGetAndroidHardwareBufferPropertiesANDROID");
    host.import_fd = (void *)gdpa(device, "vkImportSemaphoreFdKHR");
    if (!host.ahb_props) {
        LOG("host missing AHB import");
        return 0;
    }
#undef LOAD
    return 1;
}

/* Guest libvulkan 1.4.315. Layers receive a loader_physical_device_term.
   The terminator writes into a loader-owned device object and crashes if
   *pDevice is null, so a second vkCreateDevice cannot go through the chain. */
struct sky_loader_phys_term {
    void *disp;
    void *icd_term;
    VkPhysicalDevice phys_dev;
};

int host_create_device(const HostGameInfo *game, void *next_link) {
    (void)next_link;
    if (host.live || !game || !game->physical) return host.live;
    PFN_vkEnumerateDeviceExtensionProperties enumerate =
            (void *)game->gipa(game->instance, "vkEnumerateDeviceExtensionProperties");
    PFN_vkGetPhysicalDeviceFeatures2 features2 =
            (void *)game->gipa(game->instance, "vkGetPhysicalDeviceFeatures2");
    if (!features2) features2 = (void *)game->gipa(game->instance, "vkGetPhysicalDeviceFeatures2KHR");
    int ycbcr_ok = 0;
    if (features2) {
        VkPhysicalDeviceSamplerYcbcrConversionFeatures queried = {
            .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SAMPLER_YCBCR_CONVERSION_FEATURES,
        };
        VkPhysicalDeviceFeatures2 features = {
            .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2,
            .pNext = &queried,
        };
        features2(game->physical, &features);
        ycbcr_ok = queried.samplerYcbcrConversion == VK_TRUE;
    }
    const char *required[] = {
        "VK_KHR_swapchain",
        "VK_ANDROID_external_memory_android_hardware_buffer",
        "VK_KHR_external_memory",
        "VK_KHR_dedicated_allocation",
        "VK_KHR_get_memory_requirements2",
        "VK_KHR_bind_memory2",
        "VK_KHR_sampler_ycbcr_conversion",
        "VK_KHR_external_semaphore",
        "VK_KHR_external_semaphore_fd",
    };
    const char *enabled[12];
    uint32_t enabled_count = 0;
    for (uint32_t i = 0; i < sizeof(required) / sizeof(required[0]) && enabled_count < 12; i++) {
        if (i == 6 && !ycbcr_ok) continue;
        if (has_ext(enumerate, game->physical, required[i])) enabled[enabled_count++] = required[i];
        else if (i < 2) {
            LOG("host missing extension %s", required[i]);
            return 0;
        }
    }
    float priority = 1.0f;
    VkDeviceQueueCreateInfo queue_info = {
        .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
        .queueFamilyIndex = game->queue_family,
        .queueCount = 1,
        .pQueuePriorities = &priority,
    };
    struct sky_loader_phys_term *term = (struct sky_loader_phys_term *)game->physical;
    unsigned char *icd = term ? term->icd_term : NULL;
    /* loader_icd_term_dispatch starts at 0x20. GetDeviceProcAddr is entry 9
       and CreateDevice is entry 10 in Vulkan-Loader 1.4.315. */
    PFN_vkGetDeviceProcAddr icd_gdpa = icd ? *(PFN_vkGetDeviceProcAddr *)(icd + 0x68) : NULL;
    PFN_vkCreateDevice icd_create = icd ? *(PFN_vkCreateDevice *)(icd + 0x70) : NULL;
    if (!icd_create || !icd_gdpa || !term->phys_dev) {
        LOG("host ICD entry unavailable");
        return 0;
    }
    VkPhysicalDeviceSamplerYcbcrConversionFeatures ycbcr = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SAMPLER_YCBCR_CONVERSION_FEATURES,
        .samplerYcbcrConversion = VK_TRUE,
    };
    VkDeviceCreateInfo info = {
        .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
        .pNext = ycbcr_ok ? (void *)&ycbcr : NULL,
        .queueCreateInfoCount = 1,
        .pQueueCreateInfos = &queue_info,
        .enabledExtensionCount = enabled_count,
        .ppEnabledExtensionNames = enabled,
    };
    VkDevice device = VK_NULL_HANDLE;
    VkResult result = icd_create(term->phys_dev, &info, NULL, &device);
    if (result != VK_SUCCESS && ycbcr_ok) {
        info.pNext = NULL;
        result = icd_create(term->phys_dev, &info, NULL, &device);
    }
    if (result != VK_SUCCESS) {
        LOG("host device failed %d", result);
        return 0;
    }
    host.device = device;
    host.physical = game->physical;
    host.instance = game->instance;
    host.family = game->queue_family;
    host.gdpa = icd_gdpa;
    if (!load_host_procs()) return 0;
    host.live = 1;
    LOG("host scanout device created family %u ycbcr %d", game->queue_family, ycbcr_ok);
    return 1;
}

static int ensure_host(const HostGameInfo *game, VkSurfaceKHR surface) {
    if (!host.live || !game) return 0;
    if (host.queue) return 1;
    PFN_vkGetPhysicalDeviceSurfaceSupportKHR surface_support =
            (void *)game->gipa(game->instance, "vkGetPhysicalDeviceSurfaceSupportKHR");
    if (!surface_support) return 0;
    VkBool32 present = VK_FALSE;
    if (surface_support(host.physical, host.family, surface, &present) != VK_SUCCESS || !present) {
        LOG("host queue cannot present");
        return 0;
    }
    VkDevice device = host.device;
    uint32_t family = host.family;
    PFN_vkGetDeviceQueue get_queue = (void *)host.gdpa(device, "vkGetDeviceQueue");
    if (!get_queue) return 0;
    get_queue(device, family, 0, &host.queue);
    if (!host.queue) return 0;
    VkCommandPoolCreateInfo pool_info = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
        .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,
        .queueFamilyIndex = family,
    };
    if (host.create_pool(device, &pool_info, NULL, &host.pool) != VK_SUCCESS) return 0;
    VkCommandBufferAllocateInfo cmd_info = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
        .commandPool = host.pool,
        .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
        .commandBufferCount = 1,
    };
    if (host.alloc_cmd(device, &cmd_info, &host.cmd) != VK_SUCCESS) return 0;
    VkSemaphoreCreateInfo sem_info = {.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    VkExportSemaphoreCreateInfo export_info = {
        .sType = VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO,
        .handleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT,
    };
    VkSemaphoreCreateInfo wait_info = {
        .sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO,
        .pNext = &export_info,
    };
    if (host.create_sem(device, &sem_info, NULL, &host.acquire_sem) != VK_SUCCESS) return 0;
    if (host.create_sem(device, &sem_info, NULL, &host.blit_sem) != VK_SUCCESS) return 0;
    if (host.create_sem(device, &wait_info, NULL, &host.wait_sem) != VK_SUCCESS) host.wait_sem = VK_NULL_HANDLE;
    VkFenceCreateInfo fence_info = {.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    if (host.create_fence(device, &fence_info, NULL, &host.blit_fence) != VK_SUCCESS) return 0;
    LOG("host scanout queue ready family %u", family);
    return 1;
}

static void destroy_scan_images(Scan *scan) {
    PFN_vkDestroyImage destroy_image = (void *)scan->gdpa(scan->game, "vkDestroyImage");
    PFN_vkFreeMemory free_mem = (void *)scan->gdpa(scan->game, "vkFreeMemory");
    for (uint32_t i = 0; i < scan->count; i++) {
        if (scan->images[i].memory && free_mem) free_mem(scan->game, scan->images[i].memory, NULL);
        if (scan->images[i].image && destroy_image) destroy_image(scan->game, scan->images[i].image, NULL);
        if (host.live) {
            if (scan->images[i].host_memory) host.free_mem(host.device, scan->images[i].host_memory, NULL);
            if (scan->images[i].host_image) host.destroy_image(host.device, scan->images[i].host_image, NULL);
        }
        if (scan->images[i].buffer) AHardwareBuffer_release(scan->images[i].buffer);
        memset(&scan->images[i], 0, sizeof(scan->images[i]));
    }
}

void host_destroy(VkSwapchainKHR swap) {
    pthread_mutex_lock(&lock);
    Scan *scan = scan_of(swap);
    if (!scan) {
        pthread_mutex_unlock(&lock);
        return;
    }
    scan->closing = 1;
    pthread_cond_broadcast(&ready_cv);
    pthread_mutex_unlock(&lock);
    for (;;) {
        pthread_mutex_lock(&lock);
        int busy = scan->in_host;
        pthread_mutex_unlock(&lock);
        if (!busy) break;
        usleep(1000);
    }
    pthread_mutex_lock(&lock);
    for (int i = 0; i < QUEUE; i++) if (items[i].live && items[i].scan == scan) {
        if (items[i].sync_fd >= 0) close(items[i].sync_fd);
        if (items[i].semaphore && scan->gdpa) {
            PFN_vkDestroySemaphore destroy_sem = (void *)scan->gdpa(scan->game, "vkDestroySemaphore");
            if (destroy_sem) destroy_sem(scan->game, items[i].semaphore, NULL);
        }
        if (items[i].fence && retire_count < QUEUE) retire_fences[retire_count++] = items[i].fence;
        items[i].live = 0;
        item_count--;
    }
    if (host.blit_busy && host.blit_scan == (int)(scan - scans)) {
        host.blit_busy = 0;
        host.pending_swap = VK_NULL_HANDLE;
    }
    pthread_mutex_unlock(&lock);
    if (host.live && scan->host_swap) {
        host.idle(host.device);
        host.destroy_swap(host.device, scan->host_swap, NULL);
    }
    destroy_scan_images(scan);
    if (scan->gdpa && scan->game) {
        PFN_vkDeviceWaitIdle idle = (void *)scan->gdpa(scan->game, "vkDeviceWaitIdle");
        PFN_vkDestroyCommandPool destroy_pool = (void *)scan->gdpa(scan->game, "vkDestroyCommandPool");
        if (idle) idle(scan->game);
        if (scan->release_pool && destroy_pool) destroy_pool(scan->game, scan->release_pool, NULL);
    }
    pthread_mutex_lock(&lock);
    memset(scan, 0, sizeof(*scan));
    pthread_mutex_unlock(&lock);
}

static struct { VkSurfaceKHR surface; ANativeWindow *window; } windows[8];

void host_remember_window(VkSurfaceKHR surface, void *window) {
    if (!surface || !window) return;
    pthread_mutex_lock(&lock);
    int slot = -1;
    for (int i = 0; i < 8; i++) {
        if (windows[i].surface == surface) {
            slot = i;
            break;
        }
        if (slot < 0 && !windows[i].surface) slot = i;
    }
    if (slot >= 0) {
        windows[slot].surface = surface;
        windows[slot].window = window;
    }
    pthread_mutex_unlock(&lock);
    LOG("host present window recorded");
}

static int page_mapped(const void *p, size_t n) {
    uintptr_t addr = (uintptr_t)p;
    if (addr < 0x10000ul || n == 0 || addr + n < addr) return 0;
    uintptr_t page = addr & ~4095ul;
    if ((addr & 4095ul) + n > 4096ul) return 0;
    unsigned char vec = 0;
    return mincore((void *)page, 4096, &vec) == 0;
}

/* VK_ICD_WSI_PLATFORM_ANDROID is 5. ANativeWindow starts with '_wnd' (0x5f776e64). */
static ANativeWindow *window_in(const void *object) {
    if (!page_mapped(object, 64)) return NULL;
    const unsigned long *words = object;
    for (int i = 0; i < 6; i++) {
        if (words[i] < 0x10000ul) continue;
        const void *candidate = (const void *)words[i];
        if (!page_mapped(candidate, 4)) continue;
        if (*(const uint32_t *)candidate == 0x5f776e64u)
            return (ANativeWindow *)candidate;
    }
    if (page_mapped(object, 24)) {
        const unsigned char *bytes = object;
        uint32_t platform = *(const uint32_t *)bytes;
        if (platform == 5u) {
            const void *candidate = *(const void *const *)(bytes + 8);
            if (page_mapped(candidate, 4) && *(const uint32_t *)candidate == 0x5f776e64u)
                return (ANativeWindow *)candidate;
        }
        if ((*(const uint32_t *)bytes) == 0x01CDC0DEu) {
            platform = *(const uint32_t *)(bytes + 8);
            if (platform == 5u) {
                const void *candidate = *(const void *const *)(bytes + 16);
                if (page_mapped(candidate, 4) && *(const uint32_t *)candidate == 0x5f776e64u)
                    return (ANativeWindow *)candidate;
            }
        }
    }
    return NULL;
}

static ANativeWindow *window_for(VkSurfaceKHR surface) {
    ANativeWindow *window = NULL;
    pthread_mutex_lock(&lock);
    for (int i = 0; i < 8; i++) if (windows[i].surface == surface) window = windows[i].window;
    pthread_mutex_unlock(&lock);
    return window;
}

static int32_t (*buffer_timestamp)(ANativeWindow *, int64_t);

static void stamp_present(ANativeWindow *window) {
    if (!buffer_timestamp) {
        static int looked;
        if (!looked) {
            looked = 1;
            buffer_timestamp = dlsym(RTLD_DEFAULT, "ANativeWindow_setBuffersTimestamp");
            if (!buffer_timestamp) {
                void *lib = dlopen("libnativewindow.so", RTLD_NOW);
                if (lib) buffer_timestamp = dlsym(lib, "ANativeWindow_setBuffersTimestamp");
            }
            LOG("host timestamp fn %p", (void *)buffer_timestamp);
        }
    }
    if (!window || !buffer_timestamp) {
        static int missing;
        if (!missing) {
            missing = 1;
            LOG("host present has no window timestamp");
        }
        return;
    }
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    uint64_t now = (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
    static uint64_t next_ns;
    const uint64_t period = 16666666ull;
    if (next_ns < now + 1000000ull) next_ns = now + 2000000ull;
    int rc = buffer_timestamp(window, (int64_t)next_ns);
    static int logged;
    if (!logged) {
        logged = 1;
        LOG("host present timestamp rc %d", rc);
    }
    next_ns += period;
}

int host_virtualize(const HostGameInfo *game, const VkSwapchainCreateInfoKHR *info, VkSwapchainKHR *out) {
    if (!game || !info || !out || !info->imageExtent.width || !info->imageExtent.height) return 0;
    ensure_cond();
    if (!ensure_host(game, info->surface)) return 0;
    PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR caps_fn =
            (void *)game->gipa(game->instance, "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");
    PFN_vkGetPhysicalDeviceMemoryProperties mem_props =
            (void *)game->gipa(game->instance, "vkGetPhysicalDeviceMemoryProperties");
    PFN_vkGetPhysicalDeviceFormatProperties format_props =
            (void *)game->gipa(game->instance, "vkGetPhysicalDeviceFormatProperties");
    if (!caps_fn || !mem_props) return 0;
    VkSurfaceCapabilitiesKHR caps;
    memset(&caps, 0, sizeof(caps));
    if (caps_fn(game->physical, info->surface, &caps) != VK_SUCCESS) return 0;
    Scan *scan = NULL;
    pthread_mutex_lock(&lock);
    for (int i = 0; i < SCANS; i++) if (!scans[i].live) {
        scan = &scans[i];
        memset(scan, 0, sizeof(*scan));
        scan->live = 1;
        scan->magic = 0x4853434eu;
        break;
    }
    pthread_mutex_unlock(&lock);
    if (!scan) return 0;
    scan->game = game->device;
    scan->window = window_for(info->surface);
    if (!scan->window && info->surface) {
        scan->window = window_in((const void *)(uintptr_t)info->surface);
        if (!scan->window && page_mapped((const void *)(uintptr_t)info->surface, 32)) {
            const unsigned long *words = (const unsigned long *)(uintptr_t)info->surface;
            for (int hop = 1; hop < 4 && !scan->window; hop++) {
                if (!page_mapped((const void *)words[hop], 32)) continue;
                scan->window = window_in((const void *)words[hop]);
            }
            if (!scan->window)
                LOG("surface %p %lx %lx %lx %lx", (const void *)words, words[0], words[1], words[2], words[3]);
        } else if (!scan->window) {
            LOG("surface handle %lx not mapped", (unsigned long)info->surface);
        }
        if (scan->window) LOG("host window from icd surface %p", (void *)scan->window);
    }
    scan->gdpa = game->gdpa;
    scan->game_family = game->queue_family;
    scan->format = info->imageFormat;
    scan->width = info->imageExtent.width;
    scan->height = info->imageExtent.height;
    uint32_t want = info->minImageCount < 4 ? 4 : info->minImageCount;
    if (want > IMAGES) want = IMAGES;
    if (caps.maxImageCount && want > caps.maxImageCount) want = caps.maxImageCount;
    uint32_t ahb_format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    if (info->imageFormat == VK_FORMAT_B8G8R8A8_UNORM || info->imageFormat == VK_FORMAT_B8G8R8A8_SRGB)
        ahb_format = 5;
    for (uint32_t i = 0; i < want; i++) {
        AHardwareBuffer_Desc desc = {
            .width = scan->width,
            .height = scan->height,
            .layers = 1,
            .format = ahb_format,
            .usage = AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE
                    | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT,
        };
        AHardwareBuffer *buffer = NULL;
        if (AHardwareBuffer_allocate(&desc, &buffer) != 0) break;
        uint32_t game_usage = info->imageUsage | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
                | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        uint32_t host_usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        if (!import_image(game->gdpa, game->device, game->physical, mem_props, buffer, info->imageFormat,
                    game_usage, &scan->images[i].image, &scan->images[i].memory)
                || !import_image(host.gdpa, host.device, host.physical, mem_props, buffer, info->imageFormat,
                    host_usage, &scan->images[i].host_image, &scan->images[i].host_memory)) {
            AHardwareBuffer_release(buffer);
            break;
        }
        scan->images[i].buffer = buffer;
        scan->count++;
    }
    PFN_vkCreateCommandPool create_pool = (void *)game->gdpa(game->device, "vkCreateCommandPool");
    PFN_vkAllocateCommandBuffers alloc_cmd = (void *)game->gdpa(game->device, "vkAllocateCommandBuffers");
    if (create_pool && alloc_cmd) {
        VkCommandPoolCreateInfo pool_info = {
            .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
            .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,
            .queueFamilyIndex = game->queue_family,
        };
        if (create_pool(game->device, &pool_info, NULL, &scan->release_pool) == VK_SUCCESS) {
            VkCommandBufferAllocateInfo cmd_info = {
                .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
                .commandPool = scan->release_pool,
                .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
                .commandBufferCount = 1,
            };
            cmd_info.commandBufferCount = QUEUE;
            if (alloc_cmd(game->device, &cmd_info, scan->release_cmds) == VK_SUCCESS) scan->release_count = QUEUE;
        }
    }
    if (scan->count < 3 || scan->release_count < 1) {
        LOG("host images failed (%u)", scan->count);
        host_destroy((VkSwapchainKHR)(uintptr_t)(0x51000001u + (unsigned)(scan - scans)));
        return 0;
    }
    uint32_t swap_images = caps.minImageCount < 4 ? 4 : caps.minImageCount;
    if (caps.maxImageCount && swap_images > caps.maxImageCount) swap_images = caps.maxImageCount;
    VkCompositeAlphaFlagBitsKHR alpha = info->compositeAlpha;
    if (!alpha || !(caps.supportedCompositeAlpha & alpha)) {
        if (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
            alpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
        else if (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR)
            alpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
        else if (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR)
            alpha = VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR;
        else
            alpha = VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR;
    }
    VkSwapchainCreateInfoKHR swap_info = {
        .sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR,
        .surface = info->surface,
        .minImageCount = swap_images,
        .imageFormat = info->imageFormat,
        .imageColorSpace = info->imageColorSpace,
        .imageExtent = info->imageExtent,
        .imageArrayLayers = 1,
        .imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
        .imageSharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .preTransform = caps.currentTransform ? caps.currentTransform : VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR,
        .compositeAlpha = alpha,
        .presentMode = VK_PRESENT_MODE_FIFO_KHR,
        .clipped = VK_TRUE,
    };
    VkResult created = host.create_swap(host.device, &swap_info, NULL, &scan->host_swap);
    if (created != VK_SUCCESS) {
        swap_info.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        created = host.create_swap(host.device, &swap_info, NULL, &scan->host_swap);
    }
    if (created != VK_SUCCESS) {
        LOG("host swapchain failed %d", created);
        host_destroy((VkSwapchainKHR)(uintptr_t)(0x51000001u + (unsigned)(scan - scans)));
        return 0;
    }
    scan->swap_count = IMAGES;
    if (host.get_images(host.device, scan->host_swap, &scan->swap_count, NULL) != VK_SUCCESS
            || scan->swap_count == 0 || scan->swap_count > IMAGES
            || host.get_images(host.device, scan->host_swap, &scan->swap_count, scan->swap_images) != VK_SUCCESS) {
        host_destroy((VkSwapchainKHR)(uintptr_t)(0x51000001u + (unsigned)(scan - scans)));
        return 0;
    }
    host.can_blit = 1;
    if (format_props) {
        VkFormatProperties fp;
        format_props(game->physical, info->imageFormat, &fp);
        if (!(fp.optimalTilingFeatures & VK_FORMAT_FEATURE_BLIT_SRC_BIT)
                || !(fp.optimalTilingFeatures & VK_FORMAT_FEATURE_BLIT_DST_BIT))
            host.can_blit = 0;
    }
    *out = (VkSwapchainKHR)(uintptr_t)(0x51000001u + (unsigned)(scan - scans));
    LOG("host scanout owns %ux%u format %d images %u blit %d", scan->width, scan->height,
            info->imageFormat, scan->count, host.can_blit);
    return 1;
}

VkResult host_images(VkSwapchainKHR swap, uint32_t *count, VkImage *images) {
    pthread_mutex_lock(&lock);
    Scan *scan = scan_of(swap);
    if (!scan || !count) {
        pthread_mutex_unlock(&lock);
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    if (!images) {
        *count = scan->count;
        pthread_mutex_unlock(&lock);
        return VK_SUCCESS;
    }
    if (*count < scan->count) {
        *count = scan->count;
        pthread_mutex_unlock(&lock);
        return VK_INCOMPLETE;
    }
    for (uint32_t i = 0; i < scan->count; i++) images[i] = scan->images[i].image;
    *count = scan->count;
    pthread_mutex_unlock(&lock);
    return VK_SUCCESS;
}

static void retire_on_game_thread(Scan *scan) {
    if (!scan || !scan->gdpa) return;
    PFN_vkDestroyFence destroy = (void *)scan->gdpa(scan->game, "vkDestroyFence");
    PFN_vkDestroySemaphore destroy_sem = (void *)scan->gdpa(scan->game, "vkDestroySemaphore");
    (void)destroy_sem;
    VkFence fences[QUEUE];
    int count = 0;
    pthread_mutex_lock(&lock);
    count = retire_count;
    if (count > QUEUE) count = QUEUE;
    memcpy(fences, retire_fences, (size_t)count * sizeof(VkFence));
    retire_count = 0;
    pthread_mutex_unlock(&lock);
    if (!destroy) return;
    for (int i = 0; i < count; i++) if (fences[i]) destroy(scan->game, fences[i], NULL);
}

VkResult host_acquire(VkSwapchainKHR swap, uint64_t timeout, VkSemaphore semaphore, VkFence fence,
        uint32_t *index, VkQueue queue) {
    pthread_mutex_lock(&lock);
    Scan *scan = scan_of(swap);
    pthread_mutex_unlock(&lock);
    if (!scan || !index) return VK_ERROR_SURFACE_LOST_KHR;
    retire_on_game_thread(scan);
    pthread_mutex_lock(&lock);
    scan = scan_of(swap);
    if (!scan || scan->closing) {
        pthread_mutex_unlock(&lock);
        return VK_ERROR_SURFACE_LOST_KHR;
    }
    uint64_t deadline = timeout == UINT64_MAX ? UINT64_MAX : now_deadline(timeout);
    int found = -1;
    for (;;) {
        for (uint32_t i = 0; i < scan->count; i++) if (scan->images[i].state == 0) {
            found = (int)i;
            scan->images[i].state = 1;
            break;
        }
        if (found >= 0 || timeout == 0) break;
        if (deadline != UINT64_MAX) {
            struct timespec now;
            clock_gettime(CLOCK_MONOTONIC, &now);
            uint64_t now_ns = (uint64_t)now.tv_sec * 1000000000ull + (uint64_t)now.tv_nsec;
            if (now_ns >= deadline) break;
        }
        struct timespec wait;
        clock_gettime(CLOCK_MONOTONIC, &wait);
        wait.tv_nsec += 2000000;
        if (wait.tv_nsec >= 1000000000) {
            wait.tv_sec++;
            wait.tv_nsec -= 1000000000;
        }
        pthread_cond_timedwait(&ready_cv, &lock, &wait);
        if (scan->closing) break;
    }
    pthread_mutex_unlock(&lock);
    if (found < 0) return timeout == 0 ? VK_NOT_READY : VK_TIMEOUT;
    if ((semaphore || fence) && queue && scan->gdpa) {
        PFN_vkQueueSubmit submit = (void *)scan->gdpa(scan->game, "vkQueueSubmit");
        if (submit) {
            VkSubmitInfo submit_info = {
                .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
                .signalSemaphoreCount = semaphore ? 1 : 0,
                .pSignalSemaphores = semaphore ? &semaphore : NULL,
            };
            if (submit(queue, 1, &submit_info, fence) != VK_SUCCESS)
                LOG("acquire signal failed");
        }
    }
    *index = (uint32_t)found;
    return VK_SUCCESS;
}

static int record_release(Scan *scan, uint32_t index, int slot) {
    PFN_vkResetCommandBuffer reset = (void *)scan->gdpa(scan->game, "vkResetCommandBuffer");
    PFN_vkBeginCommandBuffer begin = (void *)scan->gdpa(scan->game, "vkBeginCommandBuffer");
    PFN_vkEndCommandBuffer end = (void *)scan->gdpa(scan->game, "vkEndCommandBuffer");
    PFN_vkCmdPipelineBarrier barrier = (void *)scan->gdpa(scan->game, "vkCmdPipelineBarrier");
    if (slot < 0 || slot >= scan->release_count || !reset || !begin || !end || !barrier) return 0;
    VkCommandBuffer cmd = scan->release_cmds[slot];
    if (reset(cmd, 0) != VK_SUCCESS) return 0;
    VkCommandBufferBeginInfo begin_info = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
        .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
    };
    if (begin(cmd, &begin_info) != VK_SUCCESS) return 0;
    /* Swapchain images are in PRESENT_SRC at present time. Release that layout
       to the foreign host device before the sync-fd handoff. */
    VkImageMemoryBarrier bar = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
        .srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT,
        .dstAccessMask = 0,
        .oldLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
        .newLayout = VK_IMAGE_LAYOUT_GENERAL,
        .srcQueueFamilyIndex = scan->game_family,
        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT,
        .image = scan->images[index].image,
        .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1},
    };
    barrier(cmd, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
            VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, NULL, 0, NULL, 1, &bar);
    return end(cmd) == VK_SUCCESS;
}

int host_accept_present(VkSwapchainKHR swap, VkQueue queue, const VkPresentInfoKHR *info) {
    pthread_mutex_lock(&lock);
    Scan *scan = scan_of(swap);
    pthread_mutex_unlock(&lock);
    if (!scan || !info || info->swapchainCount < 1 || !info->pImageIndices) return 0;
    uint32_t index = info->pImageIndices[0];
    if (index >= scan->count) return 0;
    retire_on_game_thread(scan);
    VkFence fence = VK_NULL_HANDLE;
    int sync_fd = -1;
    PFN_vkCreateFence create_fence = (void *)scan->gdpa(scan->game, "vkCreateFence");
    PFN_vkCreateSemaphore create_sem = (void *)scan->gdpa(scan->game, "vkCreateSemaphore");
    PFN_vkDestroySemaphore destroy_sem = (void *)scan->gdpa(scan->game, "vkDestroySemaphore");
    PFN_vkQueueSubmit submit = (void *)scan->gdpa(scan->game, "vkQueueSubmit");
    PFN_vkGetSemaphoreFdKHR get_fd = (void *)scan->gdpa(scan->game, "vkGetSemaphoreFdKHR");
    if (!create_fence || !submit) return 0;
    VkFenceCreateInfo fence_info = {.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    if (create_fence(scan->game, &fence_info, NULL, &fence) != VK_SUCCESS) return 0;
    VkSemaphore semaphore = VK_NULL_HANDLE;
    VkExportSemaphoreCreateInfo export_info = {
        .sType = VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO,
        .handleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT,
    };
    VkSemaphoreCreateInfo sem_info = {
        .sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO,
        .pNext = get_fd ? &export_info : NULL,
    };
    if (create_sem) create_sem(scan->game, &sem_info, NULL, &semaphore);
    uint32_t waits = info->waitSemaphoreCount;
    if (waits > 8) waits = 8;
    VkPipelineStageFlags stages[8];
    for (uint32_t i = 0; i < waits; i++) stages[i] = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    int cmd_slot = -1;
    pthread_mutex_lock(&lock);
    for (int i = 0; i < scan->release_count; i++) if (!scan->release_reserved[i]) {
        scan->release_reserved[i] = 1;
        cmd_slot = i;
        break;
    }
    pthread_mutex_unlock(&lock);
    int released = cmd_slot >= 0 && record_release(scan, index, cmd_slot);
    if (cmd_slot >= 0 && !released) {
        pthread_mutex_lock(&lock);
        scan->release_reserved[cmd_slot] = 0;
        pthread_mutex_unlock(&lock);
        cmd_slot = -1;
    }
    VkSubmitInfo submit_info = {
        .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
        .waitSemaphoreCount = waits,
        .pWaitSemaphores = waits ? info->pWaitSemaphores : NULL,
        .pWaitDstStageMask = waits ? stages : NULL,
        .commandBufferCount = released ? 1 : 0,
        .pCommandBuffers = released ? &scan->release_cmds[cmd_slot] : NULL,
        .signalSemaphoreCount = semaphore ? 1 : 0,
        .pSignalSemaphores = semaphore ? &semaphore : NULL,
    };
    if (submit(queue, 1, &submit_info, fence) != VK_SUCCESS) {
        if (cmd_slot >= 0) {
            pthread_mutex_lock(&lock);
            scan->release_reserved[cmd_slot] = 0;
            pthread_mutex_unlock(&lock);
        }
        if (semaphore && destroy_sem) destroy_sem(scan->game, semaphore, NULL);
        PFN_vkDestroyFence destroy_fence = (void *)scan->gdpa(scan->game, "vkDestroyFence");
        if (destroy_fence) destroy_fence(scan->game, fence, NULL);
        return 0;
    }
    /* Exporting a sync fd before the semaphore is signaled can block this
       thread until the GPU finishes. The pacer exports after the fence. */
    pthread_mutex_lock(&lock);
    int stored = 0;
    if (!scan->closing && item_count < QUEUE) {
        for (int n = 0; n < QUEUE; n++) {
            int slot = (item_head + n) % QUEUE;
            if (items[slot].live) continue;
            items[slot] = (Item){
                .live = 1, .scan = scan, .index = index, .fence = fence,
                .semaphore = semaphore, .sync_fd = sync_fd, .cmd_slot = cmd_slot,
            };
            item_count++;
            if (index < scan->count) scan->images[index].state = 2;
            stored = 1;
            break;
        }
    }
    pthread_mutex_unlock(&lock);
    if (!stored) {
        if (sync_fd >= 0) close(sync_fd);
        if (semaphore && destroy_sem) destroy_sem(scan->game, semaphore, NULL);
        PFN_vkWaitForFences wait_fences = (void *)scan->gdpa(scan->game, "vkWaitForFences");
        if (cmd_slot >= 0 && wait_fences)
            wait_fences(scan->game, 1, &fence, VK_TRUE, 1000000000ull);
        pthread_mutex_lock(&lock);
        if (cmd_slot >= 0) scan->release_reserved[cmd_slot] = 0;
        if (retire_count < QUEUE) retire_fences[retire_count++] = fence;
        pthread_mutex_unlock(&lock);
        return 0;
    }
    return 1;
}

int host_queued(void) {
    pthread_mutex_lock(&lock);
    int count = item_count;
    pthread_mutex_unlock(&lock);
    return count;
}

int host_ready(void) {
    pthread_mutex_lock(&lock);
    int ready = 0;
    if (item_count > 0) {
        Item *item = NULL;
        for (int n = 0; n < QUEUE; n++) {
            int slot = (item_head + n) % QUEUE;
            if (items[slot].live) {
                item = &items[slot];
                break;
            }
        }
        if (item && item->scan && item->scan->gdpa) {
            PFN_vkGetFenceStatus status = (void *)item->scan->gdpa(item->scan->game, "vkGetFenceStatus");
            ready = !item->fence || (status && status(item->scan->game, item->fence) == VK_SUCCESS);
        }
    }
    pthread_mutex_unlock(&lock);
    return ready;
}

int host_blit_pending(void) {
    pthread_mutex_lock(&lock);
    int pending = host.blit_busy;
    pthread_mutex_unlock(&lock);
    return pending;
}

static int record_copy(Scan *scan, VkImage src, VkImage dst, int dst_fresh) {
    if (host.reset_cmd(host.cmd, 0) != VK_SUCCESS) return 0;
    VkCommandBufferBeginInfo begin = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
        .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
    };
    if (host.begin_cmd(host.cmd, &begin) != VK_SUCCESS) return 0;
    VkImageMemoryBarrier bars[3];
    memset(bars, 0, sizeof(bars));
    bars[0] = (VkImageMemoryBarrier){
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
        .srcAccessMask = 0,
        .dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT,
        .oldLayout = VK_IMAGE_LAYOUT_GENERAL,
        .newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT,
        .dstQueueFamilyIndex = host.family,
        .image = src,
        .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1},
    };
    bars[1] = (VkImageMemoryBarrier){
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
        .srcAccessMask = 0,
        .dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
        .oldLayout = dst_fresh ? VK_IMAGE_LAYOUT_UNDEFINED : VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
        .newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .image = dst,
        .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1},
    };
    host.barrier(host.cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
            0, 0, NULL, 0, NULL, 2, bars);
    if (host.can_blit) {
        VkImageBlit region = {
            .srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
            .srcOffsets = {{0, 0, 0}, {(int32_t)scan->width, (int32_t)scan->height, 1}},
            .dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
            .dstOffsets = {{0, 0, 0}, {(int32_t)scan->width, (int32_t)scan->height, 1}},
        };
        host.blit(host.cmd, src, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, dst,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region, VK_FILTER_NEAREST);
    } else {
        VkImageCopy region = {
            .srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
            .dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
            .extent = {scan->width, scan->height, 1},
        };
        host.copy(host.cmd, src, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, dst,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);
    }
    bars[1].srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    bars[1].dstAccessMask = 0;
    bars[1].oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    bars[1].newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    bars[0].srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    bars[0].dstAccessMask = 0;
    bars[0].oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    bars[0].newLayout = VK_IMAGE_LAYOUT_GENERAL;
    bars[0].srcQueueFamilyIndex = host.family;
    bars[0].dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    host.barrier(host.cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
            0, 0, NULL, 0, NULL, 2, bars);
    return host.end_cmd(host.cmd) == VK_SUCCESS;
}

/* The fence and the exportable semaphore are signaled by the same submit.
   Export only after the fence, so GetSemaphoreFdKHR cannot stall the game. */
static void export_ready_fd(Item *item) {
    if (!item->semaphore || !item->scan || !item->scan->gdpa) return;
    PFN_vkGetSemaphoreFdKHR get_fd = (void *)item->scan->gdpa(item->scan->game, "vkGetSemaphoreFdKHR");
    PFN_vkDestroySemaphore destroy_sem = (void *)item->scan->gdpa(item->scan->game, "vkDestroySemaphore");
    int fd = -1;
    if (get_fd) {
        VkSemaphoreGetFdInfoKHR get = {
            .sType = VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR,
            .semaphore = item->semaphore,
            .handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT,
        };
        if (get_fd(item->scan->game, &get, &fd) != VK_SUCCESS) fd = -1;
    }
    if (destroy_sem) destroy_sem(item->scan->game, item->semaphore, NULL);
    item->semaphore = VK_NULL_HANDLE;
    if (item->sync_fd >= 0) close(item->sync_fd);
    item->sync_fd = fd;
}

/* Submit the copy as soon as the game image is ready. Do not present here:
   SurfaceFlinger latches on the blit fence, and a blit started at present
   time finishes a display slot late whenever the game still owns the GPU. */
int host_prepare(void) {
    if (!host.live) return 0;
    pthread_mutex_lock(&lock);
    if (host.blit_busy) {
        pthread_mutex_unlock(&lock);
        return 0;
    }
    Item item = {0};
    int have = 0;
    if (item_count > 0) {
        for (int n = 0; n < QUEUE; n++) {
            int slot = (item_head + n) % QUEUE;
            if (!items[slot].live) continue;
            Scan *scan = items[slot].scan;
            PFN_vkGetFenceStatus status = scan && scan->gdpa
                    ? (PFN_vkGetFenceStatus)scan->gdpa(scan->game, "vkGetFenceStatus") : NULL;
            int signaled = !items[slot].fence || (status && status(scan->game, items[slot].fence) == VK_SUCCESS);
            if (!signaled) break;
            item = items[slot];
            items[slot].live = 0;
            item_head = (slot + 1) % QUEUE;
            item_count--;
            if (item.cmd_slot >= 0 && item.cmd_slot < item.scan->release_count)
                item.scan->release_reserved[item.cmd_slot] = 0;
            have = 1;
            if (item.index < scan->count) scan->images[item.index].state = 3;
            scan->in_host = 1;
            break;
        }
    }
    pthread_mutex_unlock(&lock);
    if (!have) return 0;
    uint32_t swap_index = 0;
    VkResult acquired = host.acquire(host.device, item.scan->host_swap, 0, host.acquire_sem,
            VK_NULL_HANDLE, &swap_index);
    if (acquired != VK_SUCCESS && acquired != VK_SUBOPTIMAL_KHR) {
        pthread_mutex_lock(&lock);
        if (item_count < QUEUE) {
            for (int n = 0; n < QUEUE; n++) {
                int slot = (item_head + QUEUE - 1) % QUEUE;
                if (!items[slot].live) {
                    item_head = slot;
                    items[slot] = item;
                    items[slot].live = 1;
                    item_count++;
                    if (item.index < item.scan->count) item.scan->images[item.index].state = 2;
                    break;
                }
            }
        }
        item.scan->in_host = 0;
        pthread_cond_broadcast(&ready_cv);
        pthread_mutex_unlock(&lock);
        return 0;
    }
    export_ready_fd(&item);
    int fresh = !item.scan->swap_used[swap_index];
    item.scan->swap_used[swap_index] = 1;
    int recorded = record_copy(item.scan, item.scan->images[item.index].host_image,
            item.scan->swap_images[swap_index], fresh);
    int waited = 0;
    if (recorded && item.sync_fd >= 0 && host.import_fd && host.wait_sem) {
        VkImportSemaphoreFdInfoKHR import = {
            .sType = VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_FD_INFO_KHR,
            .semaphore = host.wait_sem,
            .flags = VK_SEMAPHORE_IMPORT_TEMPORARY_BIT,
            .handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT,
            .fd = item.sync_fd,
        };
        if (host.import_fd(host.device, &import) == VK_SUCCESS) waited = 1;
        else close(item.sync_fd);
        item.sync_fd = -1;
    } else if (item.sync_fd >= 0) {
        close(item.sync_fd);
        item.sync_fd = -1;
    }
    int submitted = 0;
    if (recorded) {
        VkSemaphore waits[2];
        VkPipelineStageFlags stages[2];
        uint32_t wait_count = 0;
        if (waited) {
            waits[wait_count] = host.wait_sem;
            stages[wait_count] = VK_PIPELINE_STAGE_TRANSFER_BIT;
            wait_count++;
        }
        waits[wait_count] = host.acquire_sem;
        stages[wait_count] = VK_PIPELINE_STAGE_TRANSFER_BIT;
        wait_count++;
        VkSubmitInfo submit_info = {
            .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
            .waitSemaphoreCount = wait_count,
            .pWaitSemaphores = waits,
            .pWaitDstStageMask = stages,
            .commandBufferCount = 1,
            .pCommandBuffers = &host.cmd,
        };
        submitted = host.submit(host.queue, 1, &submit_info, host.blit_fence) == VK_SUCCESS;
        if (!submitted) LOG("host blit submit failed");
    }
    pthread_mutex_lock(&lock);
    if (submitted) {
        host.blit_busy = 1;
        host.blit_scan = (int)(item.scan - scans);
        host.blit_index = item.index;
        host.pending_index = swap_index;
        host.pending_swap = item.scan->host_swap;
    } else if (item.index < item.scan->count) {
        item.scan->images[item.index].state = 0;
        pthread_cond_broadcast(&ready_cv);
    }
    item.scan->in_host = 0;
    if (item.fence && retire_count < QUEUE) retire_fences[retire_count++] = item.fence;
    pthread_cond_broadcast(&ready_cv);
    pthread_mutex_unlock(&lock);
    return submitted;
}

int host_present_one(void) {
    if (!host.live) return 0;
    pthread_mutex_lock(&lock);
    if (!host.blit_busy || host.pending_swap == VK_NULL_HANDLE) {
        pthread_mutex_unlock(&lock);
        return 0;
    }
    VkResult done = host.fence_status(host.device, host.blit_fence);
    if (done != VK_SUCCESS) {
        pthread_mutex_unlock(&lock);
        return 0;
    }
    VkSwapchainKHR swap = host.pending_swap;
    uint32_t swap_index = host.pending_index;
    int scan_index = host.blit_scan;
    uint32_t image_index = host.blit_index;
    pthread_mutex_unlock(&lock);
    ANativeWindow *window = NULL;
    if (scan_index >= 0 && scan_index < SCANS) window = scans[scan_index].window;
    stamp_present(window);
    VkPresentInfoKHR present = {
        .sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR,
        .swapchainCount = 1,
        .pSwapchains = &swap,
        .pImageIndices = &swap_index,
    };
    VkResult result = host.present(host.queue, &present);
    int presented = result == VK_SUCCESS || result == VK_SUBOPTIMAL_KHR;
    if (!presented) LOG("host present failed %d", result);
    else {
        static int logged_nowait;
        if (!logged_nowait) {
            logged_nowait = 1;
            LOG("host present stamped");
        }
    }
    pthread_mutex_lock(&lock);
    if (scan_index >= 0 && scan_index < SCANS && scans[scan_index].live
            && image_index < scans[scan_index].count) {
        scans[scan_index].images[image_index].state = 0;
        pthread_cond_broadcast(&ready_cv);
    }
    host.blit_busy = 0;
    host.pending_swap = VK_NULL_HANDLE;
    host.reset_fences(host.device, 1, &host.blit_fence);
    pthread_mutex_unlock(&lock);
    return presented;
}
