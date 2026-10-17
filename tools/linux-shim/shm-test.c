/* Run under glibc with libskypath preloaded and /dev/shm mapped to a writable test directory. */
#define _GNU_SOURCE
#undef NDEBUG
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

int main(void) {
    char name[80];
    snprintf(name, sizeof name, "/skypath-shm-test-%ld", (long)getpid());
    int fd = shm_open(name, O_CREAT | O_EXCL | O_RDWR, 0600);
    if (fd < 0) { perror("shm_open"); return 1; }
    assert(fcntl(fd, F_GETFD) & FD_CLOEXEC);
    assert(ftruncate(fd, 4096) == 0);
    char *memory = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    assert(memory != MAP_FAILED);
    strcpy(memory, "shared memory works");
    int reader = shm_open(name, O_RDONLY, 0);
    assert(reader >= 0);
    char text[80] = {0};
    assert(read(reader, text, sizeof text) > 0);
    assert(!strcmp(text, "shared memory works"));
    close(reader);
    errno = 0;
    assert(shm_open(name, O_CREAT | O_EXCL | O_RDWR, 0600) == -1 && errno == EEXIST);
    assert(shm_unlink(name) == 0);
    errno = 0;
    assert(shm_open(name, O_RDONLY, 0) == -1 && errno == ENOENT);
    assert(!strcmp(memory, "shared memory works"));
    munmap(memory, 4096);
    close(fd);
    const char *invalid[] = {"", "/", "/../escape", "/nested/name", ".", ".."};
    for (unsigned i = 0; i < sizeof invalid / sizeof invalid[0]; i++) {
        errno = 0;
        assert(shm_open(invalid[i], O_CREAT | O_RDWR, 0600) == -1 && errno == EINVAL);
        errno = 0;
        assert(shm_unlink(invalid[i]) == -1 && errno == EINVAL);
    }
    puts("PASS: mapped shm create/open/mmap/unlink, CLOEXEC, exclusive creation and invalid names");
    return 0;
}
