/* FEX regression: proc-address lookups must not hand x86 callbacks to an ARM Vulkan driver. */
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
static VkBool32 VKAPI_CALL guest_callback(VkDebugUtilsMessageSeverityFlagBitsEXT a,
    VkDebugUtilsMessageTypeFlagsEXT b,const VkDebugUtilsMessengerCallbackDataEXT *c,void *d) {
    (void)a;(void)b;(void)c;(void)d;
    /* FEX intentionally suppresses these diagnostic callbacks instead of executing them. */
    fputs("FAIL unexpected guest debug callback\n",stderr);_exit(91);
}
int main(int argc,char **argv) {
    int direct=argc>1 && !strcmp(argv[1],"direct");
    void *lib=dlopen("libvulkan.so.1",RTLD_NOW|RTLD_LOCAL);
    if(!lib){fprintf(stderr,"dlopen: %s\n",dlerror());return 1;}
    PFN_vkGetInstanceProcAddr gipa=(void*)dlsym(lib,"vkGetInstanceProcAddr");
    PFN_vkCreateInstance create=(void*)gipa(VK_NULL_HANDLE,"vkCreateInstance");
    const char *extension=VK_EXT_DEBUG_UTILS_EXTENSION_NAME;
    VkApplicationInfo ai={.sType=VK_STRUCTURE_TYPE_APPLICATION_INFO,.pApplicationName="FEX Vulkan callback regression",.apiVersion=VK_API_VERSION_1_1};
    VkInstanceCreateInfo ci={.sType=VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,.pApplicationInfo=&ai,.enabledExtensionCount=1,.ppEnabledExtensionNames=&extension};
    VkInstance instance;VkResult result=create(&ci,NULL,&instance);
    if(result){fprintf(stderr,"create instance=%d\n",result);return 2;}
    PFN_vkCreateDebugUtilsMessengerEXT createMessenger=(void*)(direct ? dlsym(lib,"vkCreateDebugUtilsMessengerEXT") : (void*)gipa(instance,"vkCreateDebugUtilsMessengerEXT"));
    PFN_vkSubmitDebugUtilsMessageEXT submit=(void*)gipa(instance,"vkSubmitDebugUtilsMessageEXT");
    PFN_vkDestroyDebugUtilsMessengerEXT destroy=(void*)gipa(instance,"vkDestroyDebugUtilsMessengerEXT");
    if(!createMessenger||!submit||!destroy)return 3;
    VkDebugUtilsMessengerCreateInfoEXT debug={.sType=VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT,
        .messageSeverity=VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT,.messageType=VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT,
        .pfnUserCallback=guest_callback};
    VkDebugUtilsMessengerEXT messenger;result=createMessenger(instance,&debug,NULL,&messenger);
    if(result){fprintf(stderr,"create messenger=%d\n",result);return 4;}
    VkDebugUtilsMessengerCallbackDataEXT message={.sType=VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CALLBACK_DATA_EXT,
        .pMessageIdName="FEX-CALLBACK-REGRESSION",.pMessage="Host driver must not execute an unconverted x86 callback"};
    submit(instance,VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT,VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT,&message);
    destroy(instance,messenger,NULL);
    ((PFN_vkDestroyInstance)gipa(instance,"vkDestroyInstance"))(instance,NULL);
    printf("PASS FEX Vulkan %s callback routing and messenger cleanup\n",direct ? "direct" : "proc-address");
    dlclose(lib);return 0;
}
