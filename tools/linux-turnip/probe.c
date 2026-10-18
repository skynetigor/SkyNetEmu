// Load/link smoke test only. This does not validate a GPU or a presentation path.
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>

int main(int argc, char **argv) {
    if (argc != 2) return 2;
    void *driver = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!driver) { fprintf(stderr, "%s\n", dlerror()); return 1; }
    int32_t (*negotiate)(uint32_t *) = dlsym(driver, "vk_icdNegotiateLoaderICDInterfaceVersion");
    void *(*gipa)(void *, const char *) = dlsym(driver, "vk_icdGetInstanceProcAddr");
    uint32_t version = 7;
    if (!negotiate || !gipa || negotiate(&version) != 0 || version == 0 || version > 7
            || !gipa(NULL, "vkCreateInstance") || !gipa(NULL, "vkEnumerateInstanceExtensionProperties")) return 1;
    printf("PASS driver load, dependency resolution and ICD interface negotiation (v%u)\n", version);
    dlclose(driver);
    return 0;
}
