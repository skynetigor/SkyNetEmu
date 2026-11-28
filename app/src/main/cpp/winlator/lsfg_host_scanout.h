#ifndef SKY_LSFG_HOST_SCANOUT_H
#define SKY_LSFG_HOST_SCANOUT_H

#define VK_USE_PLATFORM_ANDROID_KHR 1
#include <vulkan/vulkan.h>
#include <stdint.h>

/* The activity window is owned by a second VkDevice. Turnip exposes one
   graphics queue, and presenting that queue from the vsync thread SIGSEGV'd
   in FEXMemJIT. The game device only renders into exported images. */

typedef struct {
    VkInstance instance;
    VkPhysicalDevice physical;
    VkDevice device;
    PFN_vkGetInstanceProcAddr gipa;
    PFN_vkGetDeviceProcAddr gdpa;
    PFN_vkCreateDevice next_create;
    uint32_t queue_family;
} HostGameInfo;

/* Called from inside the app vkCreateDevice, while next_link is still valid. */
int host_create_device(const HostGameInfo *game, void *next_link);
void host_remember_window(VkSurfaceKHR surface, void *window);
int host_virtualize(const HostGameInfo *game, const VkSwapchainCreateInfoKHR *info, VkSwapchainKHR *out);
void host_destroy(VkSwapchainKHR swap);
int host_owns(VkSwapchainKHR swap);
VkResult host_images(VkSwapchainKHR swap, uint32_t *count, VkImage *images);
VkResult host_acquire(VkSwapchainKHR swap, uint64_t timeout, VkSemaphore semaphore, VkFence fence,
        uint32_t *index, VkQueue queue);
/* Consumes the present waits on the calling thread, then queues the image.
   Returns 1 when the driver present must not run. */
int host_accept_present(VkSwapchainKHR swap, VkQueue queue, const VkPresentInfoKHR *info);
int host_queued(void);
int host_ready(void);
/* A copy is already submitted and waiting for its display slot. */
int host_blit_pending(void);
/* Pacer thread only. Starts the next copy as soon as a game image is ready,
   without presenting it. */
int host_prepare(void);
/* Pacer thread only. Presents the copy started on an earlier slot, and only
   after its fence is already signaled. Returns 1 when a present was submitted. */
int host_present_one(void);

#endif
