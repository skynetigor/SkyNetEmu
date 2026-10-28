#pragma once
#include <vulkan/vulkan.h>

template<class Wait, class Cancel>
VkResult wait_renderer_fence(Wait wait, Cancel cancelled) {
    for (unsigned slice = 0; slice < 20; ++slice) {
        if (cancelled()) return VK_NOT_READY;
        VkResult result = wait(100000000ULL);
        if (result != VK_TIMEOUT) return result;
    }
    return VK_TIMEOUT;
}
