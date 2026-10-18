#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
int main(void) {
 void *lib=dlopen("libvulkan.so.1",RTLD_NOW|RTLD_LOCAL);
 if(!lib){fprintf(stderr,"loader: %s\n",dlerror());return 1;}
 PFN_vkGetInstanceProcAddr gipa=(void *)dlsym(lib,"vkGetInstanceProcAddr");
 const char *layer="VK_LAYER_SKYNET_frame_probe";
 VkApplicationInfo ai={.sType=VK_STRUCTURE_TYPE_APPLICATION_INFO,.apiVersion=VK_API_VERSION_1_1};
 VkInstanceCreateInfo ci={.sType=VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,.pApplicationInfo=&ai,.enabledLayerCount=1,.ppEnabledLayerNames=&layer};VkInstance instance;
 VkResult r=((PFN_vkCreateInstance)gipa(NULL,"vkCreateInstance"))(&ci,NULL,&instance);
 if(r){fprintf(stderr,"create instance: %d\n",r);return 2;}
 PFN_vkEnumeratePhysicalDevices enumerate=(void *)gipa(instance,"vkEnumeratePhysicalDevices");uint32_t count=0;enumerate(instance,&count,NULL);
 if(!count)return 3;VkPhysicalDevice devices[16];count=count>16?16:count;enumerate(instance,&count,devices);
 VkPhysicalDeviceProperties props;((PFN_vkGetPhysicalDeviceProperties)gipa(instance,"vkGetPhysicalDeviceProperties"))(devices[0],&props);
 PFN_vkGetPhysicalDeviceQueueFamilyProperties queues=(void *)gipa(instance,"vkGetPhysicalDeviceQueueFamilyProperties");uint32_t n=0;queues(devices[0],&n,NULL);VkQueueFamilyProperties *families=calloc(n,sizeof(*families));queues(devices[0],&n,families);uint32_t family=0;
 for(uint32_t i=0;i<n;i++)if(families[i].queueFlags&VK_QUEUE_GRAPHICS_BIT){family=i;break;}free(families);
 float priority=1;VkDeviceQueueCreateInfo qi={.sType=VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,.queueFamilyIndex=family,.queueCount=1,.pQueuePriorities=&priority};
 VkDeviceCreateInfo dc={.sType=VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,.queueCreateInfoCount=1,.pQueueCreateInfos=&qi};VkDevice device;
 r=((PFN_vkCreateDevice)gipa(instance,"vkCreateDevice"))(devices[0],&dc,NULL,&device);if(r){fprintf(stderr,"create device: %d\n",r);return 4;}
 PFN_vkGetDeviceProcAddr gdpa=(void*)gipa(instance,"vkGetDeviceProcAddr");VkQueue queue;((PFN_vkGetDeviceQueue)gdpa(device,"vkGetDeviceQueue"))(device,family,0,&queue);
 ((PFN_vkDestroyDevice)gdpa(device,"vkDestroyDevice"))(device,NULL);
 ((PFN_vkDestroyInstance)gipa(instance,"vkDestroyInstance"))(instance,NULL);
 printf("PASS actual Linux loader accepted frame probe and created device: %s\n",props.deviceName);
 return 0;
}
