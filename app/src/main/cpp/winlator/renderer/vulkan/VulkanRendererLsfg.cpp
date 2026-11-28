#include "VulkanRendererContext.h"
#include "lsfg/vkr_lsfg.h"
#include "lsfg/lsfg_dll.h"

#include <android/log.h>
#include <algorithm>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

namespace {
constexpr const char* kLogTag = "Winlator_Renderer";

void destroyImage(VkTable& vk, VkDevice device, VkImage& image, VkDeviceMemory& memory, VkImageView& view) {
    if (view != VK_NULL_HANDLE) vk.DestroyImageView(device, view, nullptr);
    if (image != VK_NULL_HANDLE) vk.DestroyImage(device, image, nullptr);
    if (memory != VK_NULL_HANDLE) vk.FreeMemory(device, memory, nullptr);
    view = VK_NULL_HANDLE;
    image = VK_NULL_HANDLE;
    memory = VK_NULL_HANDLE;
}
}

void VulkanRendererContext::configureHostFrameGeneration(bool enabled, uint32_t multiplier, float flow,
    float refreshHz, const std::string& dllPath, const std::string& cachePath) {
    std::lock_guard<std::mutex> lk(renderMutex);
    hostLsfgRequested = enabled && multiplier >= 2 && !dllPath.empty() && !cachePath.empty();
    hostLsfgMultiplier = multiplier < 2 ? 2 : std::min(multiplier, 4u);
    hostLsfgFlow = std::max(0.25f, std::min(flow, 1.0f));
    hostLsfgRefresh = refreshHz >= 30.f && refreshHz <= 240.f ? refreshHz : 120.f;
    hostLsfgDll = dllPath;
    hostLsfgCache = cachePath;
    if (!hostLsfgRequested) hostLsfgFrameReady = false;
}

void VulkanRendererContext::noteHostSourceFrame() {
    hostLsfgSourceFrames.fetch_add(1, std::memory_order_relaxed);
}

void VulkanRendererContext::destroyHostFrameGenerationResources() {
    hostLsfgFrameReady = false;
    hostLsfgGenerations = 0;
    hostLsfgExtraCount = 0;
    for (uint32_t i = 0; i < MAX_FRAMES_IN_FLIGHT; i++)
        destroyImage(vk_, device, hostLsfgSourceImage[i], hostLsfgSourceMemory[i], hostLsfgSourceView[i]);
    for (uint32_t i = 0; i < HOST_LSFG_MAX_GENERATIONS; i++) {
        destroyImage(vk_, device, hostLsfgGenImage[i], hostLsfgGenMemory[i], hostLsfgGenView[i]);
        if (hostLsfgGenCmd[i] != VK_NULL_HANDLE) {
            vk_.FreeCommandBuffers(device, cmdPool, 1, &hostLsfgGenCmd[i]);
            hostLsfgGenCmd[i] = VK_NULL_HANDLE;
        }
        if (hostLsfgGenAvail[i] != VK_NULL_HANDLE) vk_.DestroySemaphore(device, hostLsfgGenAvail[i], nullptr);
        if (hostLsfgGenDone[i] != VK_NULL_HANDLE) vk_.DestroySemaphore(device, hostLsfgGenDone[i], nullptr);
        if (hostLsfgChain[i] != VK_NULL_HANDLE) vk_.DestroySemaphore(device, hostLsfgChain[i], nullptr);
        if (hostLsfgGenFence[i] != VK_NULL_HANDLE) vk_.DestroyFence(device, hostLsfgGenFence[i], nullptr);
        hostLsfgGenAvail[i] = hostLsfgGenDone[i] = hostLsfgChain[i] = VK_NULL_HANDLE;
        hostLsfgGenFence[i] = VK_NULL_HANDLE;
    }
    for (auto fb : hostLsfgFramebuffer) if (fb != VK_NULL_HANDLE) vk_.DestroyFramebuffer(device, fb, nullptr);
    hostLsfgFramebuffer.clear();
    if (hostLsfgRenderPass != VK_NULL_HANDLE) {
        vk_.DestroyRenderPass(device, hostLsfgRenderPass, nullptr);
        hostLsfgRenderPass = VK_NULL_HANDLE;
    }
    hostLsfgExtentW = hostLsfgExtentH = 0;
}

bool VulkanRendererContext::createHostLsfgImage(VkImage& image, VkDeviceMemory& memory, VkImageView& view) {
    VkImageCreateInfo ii{};
    ii.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    ii.imageType = VK_IMAGE_TYPE_2D;
    ii.format = swapchainFmt;
    ii.extent = {swapchainExt.width, swapchainExt.height, 1};
    ii.mipLevels = 1;
    ii.arrayLayers = 1;
    ii.samples = VK_SAMPLE_COUNT_1_BIT;
    ii.tiling = VK_IMAGE_TILING_OPTIMAL;
    ii.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_STORAGE_BIT |
        VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    ii.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vk_.CreateImage(device, &ii, nullptr, &image) != VK_SUCCESS) return false;
    VkMemoryRequirements req{};
    vk_.GetImageMemoryRequirements(device, image, &req);
    VkMemoryAllocateInfo ai{};
    ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    ai.allocationSize = req.size;
    ai.memoryTypeIndex = findMemType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (vk_.AllocateMemory(device, &ai, nullptr, &memory) != VK_SUCCESS) {
        vk_.DestroyImage(device, image, nullptr);
        image = VK_NULL_HANDLE;
        return false;
    }
    vk_.BindImageMemory(device, image, memory, 0);
    VkImageViewCreateInfo vi{};
    vi.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    vi.image = image;
    vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vi.format = swapchainFmt;
    vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    if (vk_.CreateImageView(device, &vi, nullptr, &view) != VK_SUCCESS) {
        destroyImage(vk_, device, image, memory, view);
        return false;
    }
    return true;
}

bool VulkanRendererContext::ensureHostFrameGeneration() {
    hostLsfgFrameReady = false;
    hostLsfgGenerations = 0;
    hostLsfgExtraCount = 0;
    std::string dll;
    std::string cache;
    uint32_t multiplier = 2;
    float flow = 1.f;
    float refresh = 120.f;
    bool requested = false;
    {
        std::lock_guard<std::mutex> lk(renderMutex);
        requested = hostLsfgRequested;
        dll = hostLsfgDll;
        cache = hostLsfgCache;
        multiplier = hostLsfgMultiplier;
        flow = hostLsfgFlow;
        refresh = hostLsfgRefresh;
    }
    if (!requested || !hostLsfgDeviceReady || !hostLsfgQueueCompute || swapchain == VK_NULL_HANDLE)
        return false;
    if (swapchainFmt != VK_FORMAT_R8G8B8A8_UNORM || swapchainExt.width == 0 || swapchainExt.height == 0)
        return false;

    if (!hostLsfgDispatchReady) {
        if (!vkd_init_from_gipa(gipa) || !vkd_load_instance(instance)) return false;
        hostLsfgDispatchReady = true;
    }
    if (!hostLsfg) {
        LsfgStatus built = lsfg_build_cache(dll.c_str(), cache.c_str(), hostLsfgFloat16);
        if (built != LSFG_OK) {
            __android_log_print(ANDROID_LOG_ERROR, kLogTag, "host LSFG cache build failed: %d", (int)built);
            std::lock_guard<std::mutex> lk(renderMutex);
            hostLsfgRequested = false;
            return false;
        }
        hostLsfg = vkr_lsfg_create(device, physicalDevice, cache.c_str(), hostLsfgFloat16);
        if (!hostLsfg) {
            __android_log_print(ANDROID_LOG_ERROR, kLogTag, "host LSFG engine create failed");
            std::lock_guard<std::mutex> lk(renderMutex);
            hostLsfgRequested = false;
            return false;
        }
    }
    vkr_lsfg_configure(hostLsfg, multiplier, 0, flow, refresh);
    vkr_lsfg_set_refresh_rate(hostLsfg, refresh);

    if (hostLsfgExtentW != swapchainExt.width || hostLsfgExtentH != swapchainExt.height ||
        hostLsfgRenderPass == VK_NULL_HANDLE) {
        for (uint32_t i = 0; i < MAX_FRAMES_IN_FLIGHT; i++)
            destroyImage(vk_, device, hostLsfgSourceImage[i], hostLsfgSourceMemory[i], hostLsfgSourceView[i]);
        for (uint32_t i = 0; i < HOST_LSFG_MAX_GENERATIONS; i++)
            destroyImage(vk_, device, hostLsfgGenImage[i], hostLsfgGenMemory[i], hostLsfgGenView[i]);
        for (auto fb : hostLsfgFramebuffer) if (fb != VK_NULL_HANDLE) vk_.DestroyFramebuffer(device, fb, nullptr);
        hostLsfgFramebuffer.clear();
        if (hostLsfgRenderPass != VK_NULL_HANDLE) vk_.DestroyRenderPass(device, hostLsfgRenderPass, nullptr);

        VkAttachmentDescription att{};
        att.format = swapchainFmt;
        att.samples = VK_SAMPLE_COUNT_1_BIT;
        att.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
        att.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        att.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        att.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        att.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        att.finalLayout = VK_IMAGE_LAYOUT_GENERAL;
        VkAttachmentReference ref{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
        VkSubpassDescription sub{};
        sub.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        sub.colorAttachmentCount = 1;
        sub.pColorAttachments = &ref;
        VkSubpassDependency dep{};
        dep.srcSubpass = VK_SUBPASS_EXTERNAL;
        dep.dstSubpass = 0;
        dep.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dep.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dep.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        VkRenderPassCreateInfo rp{};
        rp.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
        rp.attachmentCount = 1;
        rp.pAttachments = &att;
        rp.subpassCount = 1;
        rp.pSubpasses = &sub;
        rp.dependencyCount = 1;
        rp.pDependencies = &dep;
        if (vk_.CreateRenderPass(device, &rp, nullptr, &hostLsfgRenderPass) != VK_SUCCESS) return false;

        for (uint32_t i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
            if (!createHostLsfgImage(hostLsfgSourceImage[i], hostLsfgSourceMemory[i], hostLsfgSourceView[i]))
                return false;
            VkFramebufferCreateInfo fb{};
            fb.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
            fb.renderPass = hostLsfgRenderPass;
            fb.attachmentCount = 1;
            fb.pAttachments = &hostLsfgSourceView[i];
            fb.width = swapchainExt.width;
            fb.height = swapchainExt.height;
            fb.layers = 1;
            VkFramebuffer framebuffer = VK_NULL_HANDLE;
            if (vk_.CreateFramebuffer(device, &fb, nullptr, &framebuffer) != VK_SUCCESS) return false;
            hostLsfgFramebuffer.push_back(framebuffer);
        }
        for (uint32_t i = 0; i < HOST_LSFG_MAX_GENERATIONS; i++) {
            if (!createHostLsfgImage(hostLsfgGenImage[i], hostLsfgGenMemory[i], hostLsfgGenView[i]))
                return false;
        }
        hostLsfgExtentW = swapchainExt.width;
        hostLsfgExtentH = swapchainExt.height;
        vkr_lsfg_forget_targets(hostLsfg);
    }

    if (!vkr_lsfg_prepare(hostLsfg, swapchainExt.width, swapchainExt.height, swapchainFmt)) return false;

    VkSemaphoreCreateInfo si{};
    si.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    VkFenceCreateInfo fi{};
    fi.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    fi.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    if (hostLsfgGenCmd[0] == VK_NULL_HANDLE) {
        VkCommandBufferAllocateInfo ai{};
        ai.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        ai.commandPool = cmdPool;
        ai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        ai.commandBufferCount = HOST_LSFG_MAX_GENERATIONS;
        VkCommandBuffer buffers[HOST_LSFG_MAX_GENERATIONS] = {};
        if (vk_.AllocateCommandBuffers(device, &ai, buffers) != VK_SUCCESS) return false;
        for (uint32_t i = 0; i < HOST_LSFG_MAX_GENERATIONS; i++) hostLsfgGenCmd[i] = buffers[i];
    }
    for (uint32_t i = 0; i < HOST_LSFG_MAX_GENERATIONS; i++) {
        if (hostLsfgGenAvail[i] == VK_NULL_HANDLE &&
            vk_.CreateSemaphore(device, &si, nullptr, &hostLsfgGenAvail[i]) != VK_SUCCESS) return false;
        if (hostLsfgGenDone[i] == VK_NULL_HANDLE &&
            vk_.CreateSemaphore(device, &si, nullptr, &hostLsfgGenDone[i]) != VK_SUCCESS) return false;
        if (hostLsfgChain[i] == VK_NULL_HANDLE &&
            vk_.CreateSemaphore(device, &si, nullptr, &hostLsfgChain[i]) != VK_SUCCESS) return false;
        if (hostLsfgGenFence[i] == VK_NULL_HANDLE &&
            vk_.CreateFence(device, &fi, nullptr, &hostLsfgGenFence[i]) != VK_SUCCESS) return false;
    }

    uint32_t capacity = swapchainImages.size() > 1 ? (uint32_t)swapchainImages.size() - 1 : 0;
    if (capacity > HOST_LSFG_MAX_GENERATIONS) capacity = HOST_LSFG_MAX_GENERATIONS;
    hostLsfgGenerations = vkr_lsfg_plan(hostLsfg, capacity, hostLsfgSourceFrames.load(std::memory_order_relaxed));
    if (hostLsfgGenerations > HOST_LSFG_MAX_GENERATIONS) hostLsfgGenerations = HOST_LSFG_MAX_GENERATIONS;
    hostLsfgFrameReady = true;
    return true;
}

void VulkanRendererContext::copyHostFrameToSwapchain(VkCommandBuffer cb, VkImage source, uint32_t imgIdx) {
    VkImageMemoryBarrier toDst{};
    toDst.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    toDst.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toDst.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toDst.image = swapchainImages[imgIdx];
    toDst.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    toDst.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    toDst.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    toDst.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
        0, 0, nullptr, 0, nullptr, 1, &toDst);

    VkImageCopy region{};
    region.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    region.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    region.extent = {swapchainExt.width, swapchainExt.height, 1};
    vk_.CmdCopyImage(cb, source, VK_IMAGE_LAYOUT_GENERAL, swapchainImages[imgIdx],
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);

    VkImageMemoryBarrier toPresent = toDst;
    toPresent.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    toPresent.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    toPresent.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    toPresent.dstAccessMask = 0;
    vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
        0, 0, nullptr, 0, nullptr, 1, &toPresent);
}

void VulkanRendererContext::recordHostFrameGeneration(VkCommandBuffer cb, uint32_t imgIdx) {
    if (!hostLsfgFrameReady || currentFrame >= hostLsfgFramebuffer.size()) return;
    vkr_lsfg_process(hostLsfg, cb, hostLsfgSourceImage[currentFrame], swapchainExt.width, swapchainExt.height,
        hostLsfgGenerations);
    for (uint32_t generation = 0; generation < hostLsfgGenerations; generation++) {
        vkr_lsfg_generate_into(hostLsfg, cb, generation, generation, hostLsfgGenImage[generation],
            hostLsfgGenView[generation], swapchainExt.width, swapchainExt.height);
    }
    VkImage first = hostLsfgGenerations == 0 ? hostLsfgSourceImage[currentFrame] : hostLsfgGenImage[0];
    copyHostFrameToSwapchain(cb, first, imgIdx);
    hostLsfgExtraCount = hostLsfgGenerations;
    for (uint32_t i = 0; i < hostLsfgExtraCount; i++) {
        hostLsfgExtraImage[i] = (i + 1 < hostLsfgGenerations) ? hostLsfgGenImage[i + 1]
            : hostLsfgSourceImage[currentFrame];
    }
}

void VulkanRendererContext::presentHostGeneratedFrames() {
    if (!hostLsfgFrameReady || hostLsfgExtraCount == 0) return;
    auto abandon = [&]() {
        // A signaled chain or acquire semaphore cannot be signaled again. Recreate after idle.
        fbResized.store(true);
    };
    for (uint32_t i = 0; i < hostLsfgExtraCount; i++) {
        uint32_t imageIndex = 0;
        VkResult acquired = vk_.AcquireNextImageKHR(device, swapchain, 100000000ULL, hostLsfgGenAvail[i],
            VK_NULL_HANDLE, &imageIndex);
        if (acquired != VK_SUCCESS && acquired != VK_SUBOPTIMAL_KHR) {
            __android_log_print(ANDROID_LOG_WARN, kLogTag, "host LSFG acquire failed: %d", (int)acquired);
            abandon();
            return;
        }
        if (vk_.WaitForFences(device, 1, &hostLsfgGenFence[i], VK_TRUE, 1000000000LL) != VK_SUCCESS) {
            abandon();
            return;
        }
        vk_.ResetFences(device, 1, &hostLsfgGenFence[i]);
        VkCommandBuffer cmd = hostLsfgGenCmd[i];
        vk_.ResetCommandBuffer(cmd, 0);
        VkCommandBufferBeginInfo begin{};
        begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        if (vk_.BeginCommandBuffer(cmd, &begin) != VK_SUCCESS) {
            abandon();
            return;
        }
        copyHostFrameToSwapchain(cmd, hostLsfgExtraImage[i], imageIndex);
        if (vk_.EndCommandBuffer(cmd) != VK_SUCCESS) {
            abandon();
            return;
        }

        VkSemaphore waits[] = {i == 0 ? hostLsfgChain[0] : hostLsfgChain[i], hostLsfgGenAvail[i]};
        VkPipelineStageFlags stages[] = {VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT};
        VkSemaphore presentDone = hostLsfgGenDone[i];
        VkSemaphore signals[2] = {presentDone, VK_NULL_HANDLE};
        VkSubmitInfo submit{};
        submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submit.waitSemaphoreCount = 2;
        submit.pWaitSemaphores = waits;
        submit.pWaitDstStageMask = stages;
        submit.commandBufferCount = 1;
        submit.pCommandBuffers = &cmd;
        submit.signalSemaphoreCount = 1;
        submit.pSignalSemaphores = &presentDone;
        if (i + 1 < hostLsfgExtraCount) {
            signals[1] = hostLsfgChain[i + 1];
            submit.signalSemaphoreCount = 2;
            submit.pSignalSemaphores = signals;
        }
        if (vk_.QueueSubmit(graphicsQueue, 1, &submit, hostLsfgGenFence[i]) != VK_SUCCESS) {
            abandon();
            return;
        }
        VkSwapchainKHR swapchains[] = {swapchain};
        VkPresentInfoKHR present{};
        present.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
        present.waitSemaphoreCount = 1;
        present.pWaitSemaphores = &hostLsfgGenDone[i];
        present.swapchainCount = 1;
        present.pSwapchains = swapchains;
        present.pImageIndices = &imageIndex;
        VkResult presented = vk_.QueuePresentKHR(graphicsQueue, &present);
        if (presented != VK_SUCCESS && presented != VK_SUBOPTIMAL_KHR) {
            abandon();
            return;
        }
        if (presented == VK_SUBOPTIMAL_KHR) fbResized.store(true);
        ++rendererPresents;
    }
    for (uint32_t i = 0; i < hostLsfgExtraCount; i++) {
        if (vk_.WaitForFences(device, 1, &hostLsfgGenFence[i], VK_TRUE, 1000000000LL) != VK_SUCCESS) {
            abandon();
            return;
        }
    }
}
