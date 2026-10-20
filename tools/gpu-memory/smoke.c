#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
int main(void) {
    const char *loader=getenv("SKY_SMOKE_VULKAN");void *lib=dlopen(loader ? loader : "libvulkan.so.1",RTLD_NOW|RTLD_LOCAL);
    if(!lib){fprintf(stderr,"dlopen: %s\n",dlerror());return 1;}
    PFN_vkGetInstanceProcAddr gipa=(void *)dlsym(lib,"vkGetInstanceProcAddr");
    PFN_vkCreateInstance create=(void *)gipa(VK_NULL_HANDLE,"vkCreateInstance");
    VkApplicationInfo ai={.sType=VK_STRUCTURE_TYPE_APPLICATION_INFO,.pApplicationName="SkyNet memory smoke",.apiVersion=VK_API_VERSION_1_1};
    const char *instanceExtensions[]={VK_KHR_SURFACE_EXTENSION_NAME};
    VkInstanceCreateInfo ci={.sType=VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,.pApplicationInfo=&ai,.enabledExtensionCount=1,.ppEnabledExtensionNames=instanceExtensions};VkInstance instance;
    VkResult result=create(&ci,NULL,&instance);if(result){fprintf(stderr,"create=%d\n",result);return 2;}
    PFN_vkEnumeratePhysicalDevices enumerate=(void *)gipa(instance,"vkEnumeratePhysicalDevices");uint32_t count=16;VkPhysicalDevice devices[16];
    if(enumerate(instance,&count,devices) || !count)return 3;
    PFN_vkGetPhysicalDeviceMemoryProperties legacy=(void *)gipa(instance,"vkGetPhysicalDeviceMemoryProperties");
    PFN_vkGetPhysicalDeviceMemoryProperties2 query2=(void *)gipa(instance,"vkGetPhysicalDeviceMemoryProperties2");
    VkPhysicalDeviceMemoryProperties p;legacy(devices[0],&p);
    VkPhysicalDeviceMemoryBudgetPropertiesEXT b={.sType=VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MEMORY_BUDGET_PROPERTIES_EXT};
    VkPhysicalDeviceMemoryProperties2 p2={.sType=VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MEMORY_PROPERTIES_2,.pNext=&b};query2(devices[0],&p2);
    unsigned long long total=0;
    for(uint32_t n=0;n<p.memoryHeapCount;n++) {
        if(p.memoryHeaps[n].size!=p2.memoryProperties.memoryHeaps[n].size)return 4;
        if(p.memoryHeaps[n].flags&VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) {
            total+=p.memoryHeaps[n].size;
            if(b.heapBudget[n]>p.memoryHeaps[n].size)return 5;
        }
    }
    const char *value=getenv("SKY_GPU_MEMORY_MB");unsigned long long cap=value ? strtoull(value,NULL,10)<<20 : 0;
    if(cap && total>cap)return 6;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties queues=(void *)gipa(instance,"vkGetPhysicalDeviceQueueFamilyProperties");
    uint32_t families=0;queues(devices[0],&families,NULL);
    VkQueueFamilyProperties *q=calloc(families,sizeof(*q));queues(devices[0],&families,q);
    uint32_t family=0;while(family<families && !q[family].queueCount)family++;
    free(q);if(family==families)return 7;
    float priority=1;VkDeviceQueueCreateInfo qi={.sType=VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,.queueFamilyIndex=family,.queueCount=1,.pQueuePriorities=&priority};
    /* LSFG requires the swapchain entry points even for a device that only queries memory. */
    const char *deviceExtensions[]={VK_KHR_SWAPCHAIN_EXTENSION_NAME};
    VkDeviceCreateInfo di={.sType=VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,.queueCreateInfoCount=1,.pQueueCreateInfos=&qi,.enabledExtensionCount=1,.ppEnabledExtensionNames=deviceExtensions};VkDevice device;
    PFN_vkCreateDevice createDevice=(void *)gipa(instance,"vkCreateDevice");
    result=createDevice(devices[0],&di,NULL,&device);if(result){fprintf(stderr,"create device=%d\n",result);return 8;}
    PFN_vkGetDeviceProcAddr gdpa=(void *)gipa(instance,"vkGetDeviceProcAddr");
    ((PFN_vkDestroyDevice)gdpa(device,"vkDestroyDevice"))(device,NULL);
    printf("PASS actual Vulkan memory queries: local_bytes=%llu requested_mib=%s\n",total,value ? value : "0");
    ((PFN_vkDestroyInstance)gipa(instance,"vkDestroyInstance"))(instance,NULL);dlclose(lib);return 0;
}
