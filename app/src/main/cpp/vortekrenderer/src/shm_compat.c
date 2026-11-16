#include <fcntl.h>
#include <stdint.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <linux/ashmem.h>

#include "sysvshared_memory.h"

/* The ring buffers shared with the Vortek client are ashmem regions. The copy in sysvshared_memory.c is private to the
 * winlator library, so the renderer carries its own. */
int ashmemCreateRegion(const char* name, int64_t size) {
    int fd = open("/dev/ashmem", O_RDWR);
    if (fd < 0) return -1;

    char nameBuffer[ASHMEM_NAME_LEN] = {0};
    strncpy(nameBuffer, name, sizeof(nameBuffer) - 1);

    if (ioctl(fd, ASHMEM_SET_NAME, nameBuffer) < 0 || ioctl(fd, ASHMEM_SET_SIZE, size) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}
