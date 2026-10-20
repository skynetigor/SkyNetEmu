#include "gpu_memory.c"
#include <assert.h>
static int ik,dk,other_ik;static void *ih[]={&ik},*ph[]={&ik},*dh[]={&dk},*qh[]={&dk},*other[]={&other_ik};
static int failures,destructions,submits;static int support_khr=1;
static VkPhysicalDeviceMemoryProperties native;
static VkResult mock_instance(const VkInstanceCreateInfo *c,const VkAllocationCallbacks *a,VkInstance *out) {(void)c;(void)a;*out=(VkInstance)ih;return failures ? VK_ERROR_INITIALIZATION_FAILED : VK_SUCCESS;}
static void mock_destroy_instance(VkInstance i,const VkAllocationCallbacks *a) {(void)a;assert(i==(VkInstance)ih);destructions++;}
static void mock_memory(VkPhysicalDevice h,VkPhysicalDeviceMemoryProperties *p) {assert(h==(VkPhysicalDevice)ph);*p=native;}
static void mock_memory2(VkPhysicalDevice h,VkPhysicalDeviceMemoryProperties2 *p) {
    mock_memory(h,&p->memoryProperties);
    for (VkBaseOutStructure *s=p->pNext;s;s=s->pNext) if(s->sType==VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MEMORY_BUDGET_PROPERTIES_EXT) {
        VkPhysicalDeviceMemoryBudgetPropertiesEXT *b=(void *)s;
        b->heapBudget[0]=8ull<<30;b->heapBudget[1]=5ull<<30;b->heapBudget[2]=128ull<<20;
        b->heapUsage[0]=3ull<<30;b->heapUsage[1]=123;b->heapUsage[2]=45;
    }
}
static VkResult mock_device(VkPhysicalDevice h,const VkDeviceCreateInfo *ci,const VkAllocationCallbacks *a,VkDevice *out) {(void)ci;(void)a;assert(h==(VkPhysicalDevice)ph);*out=(VkDevice)dh;return VK_SUCCESS;}
static void mock_destroy_device(VkDevice d,const VkAllocationCallbacks *a) {(void)a;assert(d==(VkDevice)dh);destructions++;}
static VkResult mock_submit(VkQueue q,uint32_t count,const VkSubmitInfo *s,VkFence f) {(void)s;(void)f;assert(q==(VkQueue)qh && count==0);submits++;return VK_SUCCESS;}
static PFN_vkVoidFunction mock_gdpa(VkDevice h,const char *name) {
    (void)h;
    #define MAP(n,f) if(!strcmp(name,n))return (PFN_vkVoidFunction)f
    MAP("vkGetDeviceProcAddr",mock_gdpa);MAP("vkDestroyDevice",mock_destroy_device);MAP("vkQueueSubmit",mock_submit);return NULL;
}
static PFN_vkVoidFunction mock_gipa(VkInstance h,const char *name) {
    (void)h;MAP("vkCreateInstance",mock_instance);MAP("vkDestroyInstance",mock_destroy_instance);MAP("vkCreateDevice",mock_device);
    MAP("vkGetPhysicalDeviceMemoryProperties",mock_memory);MAP("vkGetPhysicalDeviceMemoryProperties2",mock_memory2);
    if(support_khr) {MAP("vkGetPhysicalDeviceMemoryProperties2KHR",mock_memory2);}
    return mock_gdpa(VK_NULL_HANDLE,name);
}
static VkInstance open_instance(const char *value) {
    if(value)setenv("SKY_GPU_MEMORY_MB",value,1);else unsetenv("SKY_GPU_MEMORY_MB");
    VkLayerInstanceLink link={.pfnNextGetInstanceProcAddr=mock_gipa,.pfnNextGetPhysicalDeviceProcAddr=mock_gipa};
    VkLayerInstanceCreateInfo chain={.sType=VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO,.function=VK_LAYER_LINK_INFO,.u.pLayerInfo=&link};
    VkInstanceCreateInfo ci={.sType=VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,.pNext=&chain};VkInstance out;
    assert(create_instance(&ci,NULL,&out)==VK_SUCCESS);return out;
}
int main(void) {
    native.memoryHeapCount=3;native.memoryTypeCount=3;
    native.memoryHeaps[0]=(VkMemoryHeap){8ull<<30,VK_MEMORY_HEAP_DEVICE_LOCAL_BIT};
    native.memoryHeaps[1]=(VkMemoryHeap){4ull<<30,0};
    native.memoryHeaps[2]=(VkMemoryHeap){2ull<<30,VK_MEMORY_HEAP_DEVICE_LOCAL_BIT};
    for(int n=0;n<3;n++)native.memoryTypes[n]=(VkMemoryType){VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,(uint32_t)n};
    VkNegotiateLayerInterface v={.loaderLayerInterfaceVersion=2};assert(vkNegotiateLoaderLayerInterfaceVersion(&v)==VK_SUCCESS && v.pfnGetPhysicalDeviceProcAddr);
    VkInstance i=open_instance("2048");
    PFN_vkGetPhysicalDeviceMemoryProperties fn=(void *)vkGetInstanceProcAddr(i,"vkGetPhysicalDeviceMemoryProperties");
    VkPhysicalDeviceMemoryProperties p;fn((VkPhysicalDevice)ph,&p);
    assert(p.memoryHeaps[0].size+p.memoryHeaps[2].size==(2ull<<30));
    assert(p.memoryHeaps[1].size==native.memoryHeaps[1].size && p.memoryTypeCount==native.memoryTypeCount);
    assert(!memcmp(p.memoryTypes,native.memoryTypes,sizeof(p.memoryTypes)) && p.memoryHeaps[0].flags==native.memoryHeaps[0].flags);
    for(int alias=0;alias<2;alias++) {
        VkPhysicalDeviceMemoryBudgetPropertiesEXT b={.sType=VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MEMORY_BUDGET_PROPERTIES_EXT};
        VkBaseOutStructure unrelated={.sType=VK_STRUCTURE_TYPE_MAX_ENUM,.pNext=(void *)&b};
        VkPhysicalDeviceMemoryProperties2 p2={.sType=VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MEMORY_PROPERTIES_2,.pNext=&unrelated};
        PFN_vkGetPhysicalDeviceMemoryProperties2 f=(void *)v.pfnGetPhysicalDeviceProcAddr(i,alias ? "vkGetPhysicalDeviceMemoryProperties2KHR":"vkGetPhysicalDeviceMemoryProperties2");
        f((VkPhysicalDevice)ph,&p2);
        assert(b.heapBudget[0]==p2.memoryProperties.memoryHeaps[0].size && b.heapBudget[1]==(5ull<<30) && b.heapBudget[2]==(128ull<<20));
        assert(b.heapUsage[0]==(3ull<<30) && b.heapUsage[1]==123 && b.heapUsage[2]==45 && p2.pNext==&unrelated);
    }
    setenv("SKY_GPU_MEMORY_MB","4096",1);fn((VkPhysicalDevice)ph,&p);assert(p.memoryHeaps[0].size+p.memoryHeaps[2].size==(2ull<<30)); // immutable for an instance
    support_khr=0;assert(vkGetInstanceProcAddr(i,"vkGetPhysicalDeviceMemoryProperties2KHR")==NULL);support_khr=1;
    assert(!vkGetInstanceProcAddr((VkInstance)other,"vkGetPhysicalDeviceMemoryProperties"));
    VkLayerDeviceLink dl={.pfnNextGetInstanceProcAddr=mock_gipa,.pfnNextGetDeviceProcAddr=mock_gdpa};
    VkLayerDeviceCreateInfo dc={.sType=VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO,.function=VK_LAYER_LINK_INFO,.u.pLayerInfo=&dl};
    VkDeviceCreateInfo ci={.sType=VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,.pNext=&dc};VkDevice device;
    assert(create_device((VkPhysicalDevice)ph,&ci,NULL,&device)==VK_SUCCESS);
    PFN_vkQueueSubmit submit=(void *)vkGetDeviceProcAddr(device,"vkQueueSubmit");assert(submit((VkQueue)qh,0,NULL,VK_NULL_HANDLE)==VK_SUCCESS && submits==1);
    assert(!vkGetDeviceProcAddr(device,"vkUnknownFunction"));
    ((PFN_vkDestroyDevice)vkGetInstanceProcAddr(i,"vkDestroyDevice"))(device,NULL);
    destroy_instance(i,NULL);assert(destructions==2 && !instance_for(ph).handle && !device_for(dh).handle);
    for(unsigned n=0;n<7;n++) {
        const char *values[]={NULL,"0","garbage","-2048","2048x","999999999999999999999999999","16384"};
        i=open_instance(values[n]);memory_properties((VkPhysicalDevice)ph,&p);assert(!memcmp(&p,&native,sizeof(p)));destroy_instance(i,NULL);
    }
    // Degenerate/large multi-heap accounting still preserves an aggregate cap without integer overflow.
    native.memoryHeaps[0].size=UINT64_MAX;native.memoryHeaps[2].size=UINT64_MAX;
    p=native;cap_heaps(&p,512ull<<20);assert(p.memoryHeaps[0].size+p.memoryHeaps[2].size==(512ull<<20));
    native.memoryHeaps[0].size=1;native.memoryHeaps[2].size=8ull<<30;p=native;cap_heaps(&p,2ull<<30);
    assert(p.memoryHeaps[0].size==1 && p.memoryHeaps[2].size==(2ull<<30)-1);
    puts("PASS heap/budget consistency, aliases, untouched usage/types/host heaps, aggregate caps, overflow, defaults, instance isolation, dispatch and cleanup");
}
