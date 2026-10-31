#include <arpa/inet.h>
#include <errno.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

static int transfer(int fd, void *buffer, size_t length, int sending) {
    char *bytes = buffer;
    while (length) {
        ssize_t count = sending ? send(fd, bytes, length, MSG_NOSIGNAL) : read(fd, bytes, length);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return -1;
        bytes += count;
        length -= (size_t)count;
    }
    return 0;
}

static int number(int fd, uint32_t value) {
    value = htonl(value);
    return transfer(fd, &value, sizeof value, 1);
}

static int text(int fd, const char *value) {
    size_t length = strlen(value);
    if (length > 4096) { errno = E2BIG; return -1; }
    return number(fd, (uint32_t)length) || transfer(fd, (void *)value, length, 1);
}

int main(int argc, char **argv) {
    static const char *variables[] = {
        "FEX_TSOENABLED", "FEX_VECTORTSOENABLED", "FEX_MEMCPYSETTSOENABLED",
        "FEX_HALFBARRIERTSOENABLED", "FEX_X87REDUCEDPRECISION", "FEX_MULTIBLOCK",
        "FEX_DYNAMICL1CACHE", "TU_DEBUG", "MESA_VK_WSI_DEBUG", "LD_LIBRARY_PATH"
    };
    const char *name = getenv("SKY_DIRECT_FEX_SOCKET");
    struct sockaddr_un address = {.sun_family = AF_UNIX};
    int fd = -1;
    if (!name || !*name || strlen(name) >= sizeof address.sun_path - 1 ||
            argc < 2 || argc > 65) { errno = EINVAL; goto failed; }
    memcpy(address.sun_path + 1, name, strlen(name));
    fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0 || connect(fd, (struct sockaddr *)&address,
            (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + strlen(name))) < 0) goto failed;
    if (number(fd, 1) || text(fd, "391220") || number(fd, (uint32_t)(argc - 1))) goto failed;
    for (int index = 1; index < argc; ++index) if (text(fd, argv[index])) goto failed;
    uint32_t count = 0;
    for (size_t index = 0; index < sizeof variables / sizeof *variables; ++index)
        if (getenv(variables[index])) ++count;
    if (number(fd, count)) goto failed;
    for (size_t index = 0; index < sizeof variables / sizeof *variables; ++index) {
        const char *value = getenv(variables[index]);
        if (value && (text(fd, variables[index]) || text(fd, value))) goto failed;
    }
    uint32_t status;
    if (transfer(fd, &status, sizeof status, 0)) goto failed;
    close(fd);
    fd = -1;
    status = ntohl(status);
    if (status > 255) { errno = EPROTO; goto failed; }
    return (int)status;
failed:
    perror("SkyNetEmu direct FEX launch");
    if (fd >= 0) close(fd);
    return 126;
}
