#ifndef SKY_INPUT_TRANSPORT_H
#define SKY_INPUT_TRANSPORT_H

#include <stddef.h>
#include <stdint.h>
#include <sys/types.h>

#define SKY_INPUT_VERSION 2u
#define SKY_INPUT_MAGIC 0x534b5949u
#define SKY_INPUT_CAPACITY 1024u
#define SKY_INPUT_SLOTS 4
#define SKY_INPUT_READERS 32

struct sky_input_event {
    uint32_t sec, usec, type_code, value;
};

struct sky_input_ring {
    uint32_t magic, version, bytes, capacity;
    uint32_t revision, generation, connected, head;
    uint32_t buttons[10], axes[8];
    struct sky_input_event events[SKY_INPUT_CAPACITY];
};

static inline uint32_t sky_load(const uint32_t *p) { return __atomic_load_n(p, __ATOMIC_ACQUIRE); }
static inline void sky_store(uint32_t *p, uint32_t v) { __atomic_store_n(p, v, __ATOMIC_RELEASE); }

#ifdef __cplusplus
extern "C" {
#endif
#pragma GCC visibility push(hidden)
int sky_input_start(const char *dir);
void sky_input_stop(void);
int sky_input_activate(int slot);
int sky_input_publish(int slot, const void *events, size_t bytes);
void sky_input_disconnect(int slot);

int sky_input_open(const char *dir, int slot, int flags, int xbox_axes);
int sky_input_owns(int fd);
int sky_input_slot(int fd);
ssize_t sky_input_read(int fd, void *data, size_t bytes);
int sky_input_ioctl(int fd, unsigned long request, void *arg);
void sky_input_close(int fd);
#pragma GCC visibility pop
#ifdef __cplusplus
}
#endif
#endif
