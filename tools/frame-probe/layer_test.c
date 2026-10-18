/* Mock Vulkan next-layer integration test; no GPU, no driver timing changes. */
#include "frame_probe.c"
#include <assert.h>
static int ik,dk;static void *ih[]={&ik},*ph[]={&ik},*dh[]={&dk},*qh[]={&dk};
static const VkPresentInfoKHR *expected_present;static int calls;static VkResult present_result=VK_SUCCESS;
static VkResult mock_instance(const VkInstanceCreateInfo *ci,const VkAllocationCallbacks *a,VkInstance *out){(void)ci;(void)a;*out=(VkInstance)ih;return VK_SUCCESS;}
static void mock_destroy_instance(VkInstance i,const VkAllocationCallbacks *a){(void)i;(void)a;}
static VkResult mock_device(VkPhysicalDevice p,const VkDeviceCreateInfo *ci,const VkAllocationCallbacks *a,VkDevice *out){(void)ci;(void)a;assert(p==(VkPhysicalDevice)ph);*out=(VkDevice)dh;return VK_SUCCESS;}
static void mock_destroy_device(VkDevice d,const VkAllocationCallbacks *a){(void)d;(void)a;}
static VkResult mock_surface(VkInstance i,const XcbInfo *ci,const VkAllocationCallbacks *a,VkSurfaceKHR *out){(void)i;(void)a;assert(ci->window==42);*out=(VkSurfaceKHR)(uintptr_t)1;return VK_SUCCESS;}
static VkResult mock_swap(VkDevice d,const VkSwapchainCreateInfoKHR *ci,const VkAllocationCallbacks *a,VkSwapchainKHR *out){(void)d;(void)ci;(void)a;*out=(VkSwapchainKHR)(uintptr_t)2;return VK_SUCCESS;}
static void mock_destroy_swap(VkDevice d,VkSwapchainKHR s,const VkAllocationCallbacks *a){(void)d;(void)s;(void)a;}
static VkResult mock_acquire(VkDevice d,VkSwapchainKHR s,uint64_t timeout,VkSemaphore sem,VkFence f,uint32_t *index){(void)d;(void)s;(void)sem;(void)f;assert(timeout==123);*index=2;return VK_SUBOPTIMAL_KHR;}
static VkResult mock_present(VkQueue q,const VkPresentInfoKHR *ci){assert(q==(VkQueue)qh && ci==expected_present);calls++;if(ci->pResults)ci->pResults[0]=present_result;return present_result;}
static PFN_vkVoidFunction mock_gdpa(VkDevice d,const char *name){(void)d;
 #define MAP(n,f) if(!strcmp(name,n))return (PFN_vkVoidFunction)f
 MAP("vkGetDeviceProcAddr",mock_gdpa);MAP("vkDestroyDevice",mock_destroy_device);MAP("vkCreateSwapchainKHR",mock_swap);MAP("vkDestroySwapchainKHR",mock_destroy_swap);MAP("vkAcquireNextImageKHR",mock_acquire);MAP("vkQueuePresentKHR",mock_present);return NULL;
}
static PFN_vkVoidFunction mock_gipa(VkInstance i,const char *name){(void)i;MAP("vkCreateInstance",mock_instance);MAP("vkCreateDevice",mock_device);MAP("vkDestroyInstance",mock_destroy_instance);MAP("vkCreateXcbSurfaceKHR",mock_surface);return mock_gdpa((VkDevice)dh,name);}
int main(void){
 VkNegotiateLayerInterface negotiate={.loaderLayerInterfaceVersion=2};assert(vkNegotiateLoaderLayerInterfaceVersion(&negotiate)==VK_SUCCESS && negotiate.pfnGetInstanceProcAddr);
 VkLayerInstanceLink link={.pfnNextGetInstanceProcAddr=mock_gipa};VkLayerInstanceCreateInfo chain={.sType=VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO,.function=VK_LAYER_LINK_INFO,.u.pLayerInfo=&link};
 VkInstanceCreateInfo ci={.sType=VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,.pNext=&chain};VkInstance instance;
 assert(((PFN_vkCreateInstance)vkGetInstanceProcAddr(NULL,"vkCreateInstance"))(&ci,NULL,&instance)==VK_SUCCESS);
 VkLayerDeviceLink dl={.pfnNextGetInstanceProcAddr=mock_gipa,.pfnNextGetDeviceProcAddr=mock_gdpa};VkLayerDeviceCreateInfo dc={.sType=VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO,.function=VK_LAYER_LINK_INFO,.u.pLayerInfo=&dl};
 VkDeviceCreateInfo dci={.sType=VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,.pNext=&dc};VkDevice device;
 assert(((PFN_vkCreateDevice)vkGetInstanceProcAddr(instance,"vkCreateDevice"))((VkPhysicalDevice)ph,&dci,NULL,&device)==VK_SUCCESS);
 assert(vkGetDeviceProcAddr(device,"vkAcquireNextImage2KHR")==NULL); // Never advertise an unsupported entrypoint.
 XcbInfo sci={.sType=VK_STRUCTURE_TYPE_XCB_SURFACE_CREATE_INFO_KHR,.window=42};VkSurfaceKHR surface;typedef VkResult(*CreateXcb)(VkInstance,const XcbInfo*,const VkAllocationCallbacks*,VkSurfaceKHR*);
 assert(((CreateXcb)vkGetInstanceProcAddr(instance,"vkCreateXcbSurfaceKHR"))(instance,&sci,NULL,&surface)==VK_SUCCESS);
 VkSwapchainCreateInfoKHR swci={.sType=VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR,.surface=surface,.imageExtent={64,64},.minImageCount=3,.presentMode=VK_PRESENT_MODE_FIFO_KHR};VkSwapchainKHR swap;
 assert(((PFN_vkCreateSwapchainKHR)vkGetDeviceProcAddr(device,"vkCreateSwapchainKHR"))(device,&swci,NULL,&swap)==VK_SUCCESS);
 uint32_t index;assert(((PFN_vkAcquireNextImageKHR)vkGetDeviceProcAddr(device,"vkAcquireNextImageKHR"))(device,swap,123,VK_NULL_HANDLE,VK_NULL_HANDLE,&index)==VK_SUBOPTIMAL_KHR && index==2);
 VkResult result;VkPresentInfoKHR pi={.sType=VK_STRUCTURE_TYPE_PRESENT_INFO_KHR,.swapchainCount=1,.pSwapchains=&swap,.pImageIndices=&index,.pResults=&result};expected_present=&pi;
 PFN_vkQueuePresentKHR p=(void*)vkGetDeviceProcAddr(device,"vkQueuePresentKHR");
 assert(p((VkQueue)qh,&pi)==VK_SUCCESS && result==VK_SUCCESS);
 present_result=VK_SUBOPTIMAL_KHR;assert(p((VkQueue)qh,&pi)==VK_SUBOPTIMAL_KHR);
 present_result=VK_ERROR_OUT_OF_DATE_KHR;assert(p((VkQueue)qh,&pi)==VK_ERROR_OUT_OF_DATE_KHR);
 Swap *s=swap_for(swap);assert(s && s->window==42 && s->queued==2 && s->attempts==3 && s->failures==1 && s->acquires==1 && calls==3);
 ((PFN_vkDestroySwapchainKHR)vkGetDeviceProcAddr(device,"vkDestroySwapchainKHR"))(device,swap,NULL);
 ((PFN_vkDestroyDevice)vkGetDeviceProcAddr(device,"vkDestroyDevice"))(device,NULL);
 ((PFN_vkDestroyInstance)vkGetInstanceProcAddr(instance,"vkDestroyInstance"))(instance,NULL);
 puts("PASS real layer dispatch-chain forwarding, queue routing, unsupported functions, results, surface identity, and reports");
}
