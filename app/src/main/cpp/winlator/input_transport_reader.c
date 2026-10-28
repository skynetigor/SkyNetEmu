#define _GNU_SOURCE
#include "input_transport.h"
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <poll.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define READER_FDS 256
struct shared_cursor {
    pthread_mutex_t lock;
    uint32_t generation, cursor;
    int initial, ended, flags, clock, xbox_axes, slot;
    struct input_event pending[32];
    size_t position, count;
};
struct reader {
    struct sky_input_ring *ring;
    struct shared_cursor *state;
    int control, notify, references;
};
static struct { int fd; struct reader *reader; } handles[READER_FDS];
static pthread_mutex_t handles_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_once_t init_once = PTHREAD_ONCE_INIT;
static int (*next_fcntl)(int, int, ...);
static int (*next_fcntl64)(int, int, ...);
static int (*next_dup)(int);
static int (*next_dup2)(int, int);
static int (*next_dup3)(int, int, int);
static const int button_codes[] = {BTN_A, BTN_B, BTN_X, BTN_Y, BTN_TL, BTN_TR,
                                  BTN_SELECT, BTN_START, BTN_THUMBL, BTN_THUMBR};
static const int axis_codes[] = {ABS_X, ABS_Y, ABS_RX, ABS_RY, ABS_BRAKE, ABS_GAS, ABS_HAT0X, ABS_HAT0Y};
static void raw_close(int fd) { if (fd >= 0) syscall(SYS_close, fd); }
static void before_fork(void) { pthread_mutex_lock(&handles_lock); }
static void after_fork(void) { pthread_mutex_unlock(&handles_lock); }
static void child_fork(void) {
    for (int i = 0; i < READER_FDS; i++) if (handles[i].fd >= 0) handles[i].reader->references = 0;
    for (int i = 0; i < READER_FDS; i++) if (handles[i].fd >= 0) handles[i].reader->references++;
    pthread_mutex_unlock(&handles_lock);
}
static void initialize(void) {
    next_fcntl = dlsym(RTLD_NEXT, "fcntl");
    next_fcntl64 = dlsym(RTLD_NEXT, "fcntl64");
    next_dup = dlsym(RTLD_NEXT, "dup");
    next_dup2 = dlsym(RTLD_NEXT, "dup2");
    next_dup3 = dlsym(RTLD_NEXT, "dup3");
    if (!next_fcntl || !next_dup || !next_dup2 || !next_dup3)
        fprintf(stderr, "SkyInput: libc descriptor entry points unavailable\n");
    for (int i = 0; i < READER_FDS; i++) handles[i].fd = -1;
    pthread_atfork(before_fork, after_fork, child_fork);
}
static struct reader *find_locked(int fd) {
    if (fd < 0) return NULL;
    for (int i = 0; i < READER_FDS; i++) if (handles[i].fd == fd) return handles[i].reader;
    return NULL;
}
static struct reader *acquire(int fd) {
    pthread_once(&init_once, initialize);
    pthread_mutex_lock(&handles_lock);
    struct reader *r = find_locked(fd);
    if (r) r->references++;
    pthread_mutex_unlock(&handles_lock);
    return r;
}
static void release_locked(struct reader *r) {
    if (--r->references != 0) return;
    raw_close(r->control);
    raw_close(r->notify);
    munmap(r->ring, sizeof(*r->ring));
    munmap(r->state, sizeof(*r->state));
    free(r);
}
static void release(struct reader *r) {
    pthread_mutex_lock(&handles_lock);
    release_locked(r);
    pthread_mutex_unlock(&handles_lock);
}
int sky_input_owns(int fd) {
    struct reader *r = acquire(fd);
    if (r) release(r);
    return r != NULL;
}
int sky_input_slot(int fd) {
    struct reader *r = acquire(fd);
    int slot = r ? r->state->slot : -1;
    if (r) release(r);
    return slot;
}
void sky_input_close(int fd) {
    if (fd < 0) return;
    pthread_once(&init_once, initialize);
    pthread_mutex_lock(&handles_lock);
    for (int i = 0; i < READER_FDS; i++) if (handles[i].fd == fd) {
        handles[i].fd = -1;
        release_locked(handles[i].reader);
        handles[i].reader = NULL;
        break;
    }
    pthread_mutex_unlock(&handles_lock);
}
static int remember(int fd, struct reader *r) {
    pthread_mutex_lock(&handles_lock);
    int i;
    for (i = 0; i < READER_FDS && handles[i].fd >= 0; i++) {}
    if (i < READER_FDS) {
        handles[i].fd = fd;
        handles[i].reader = r;
        r->references++;
    }
    pthread_mutex_unlock(&handles_lock);
    if (i == READER_FDS) { errno = EMFILE; return -1; }
    return fd;
}

int sky_input_open(const char *dir, int slot, int flags, int xbox_axes) {
    pthread_once(&init_once, initialize);
    if (!next_fcntl) { errno = ENOSYS; return -1; }
    if (!dir || slot < 0 || slot >= SKY_INPUT_SLOTS) { errno = ENODEV; return -1; }
    struct sockaddr_un address = {.sun_family = AF_UNIX};
    if (snprintf(address.sun_path, sizeof(address.sun_path), "%s/.input-v2", dir) >= (int)sizeof(address.sun_path)) {
        errno = ENAMETOOLONG; return -1;
    }
    int control = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    int map_fd = -1, notify = -1, result = -1;
    if (control < 0) return -1;
    struct timeval limit = {1, 0};
    struct reader *r = calloc(1, sizeof(*r));
    if (!r) goto fail;
    r->control = control;
    r->notify = -1;
    if (setsockopt(control, SOL_SOCKET, SO_RCVTIMEO, &limit, sizeof(limit)) < 0
            || setsockopt(control, SOL_SOCKET, SO_SNDTIMEO, &limit, sizeof(limit)) < 0) goto fail;
    if (connect(control, (struct sockaddr *)&address, sizeof(address)) < 0) goto fail;
    struct ucred peer;
    socklen_t peer_bytes = sizeof(peer);
    if (getsockopt(control, SOL_SOCKET, SO_PEERCRED, &peer, &peer_bytes) < 0) goto fail;
    if (peer_bytes != sizeof(peer) || peer.uid != getuid()) { errno = EACCES; goto fail; }
    uint32_t request[] = {SKY_INPUT_VERSION, (uint32_t)slot};
    if (send(control, request, sizeof(request), MSG_NOSIGNAL) != sizeof(request)) goto fail;
    uint32_t version = 0;
    struct iovec io = {&version, sizeof(version)};
    union { struct cmsghdr align; char data[CMSG_SPACE(2 * sizeof(int))]; } ancillary;
    memset(&ancillary, 0, sizeof(ancillary));
    struct msghdr message = {.msg_iov = &io, .msg_iovlen = 1,
                             .msg_control = ancillary.data, .msg_controllen = sizeof(ancillary.data)};
    ssize_t got = recvmsg(control, &message, MSG_CMSG_CLOEXEC);
    if (got < 0) goto fail;
    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&message);
    int received[2] = {-1, -1}, received_count = 0;
    for (struct cmsghdr *entry = cmsg; entry; entry = CMSG_NXTHDR(&message, entry)) {
        if (entry->cmsg_level != SOL_SOCKET || entry->cmsg_type != SCM_RIGHTS
                || entry->cmsg_len < CMSG_LEN(0)) continue;
        size_t count = (entry->cmsg_len - CMSG_LEN(0)) / sizeof(int);
        for (size_t i = 0; i < count; i++) {
            int descriptor;
            memcpy(&descriptor, (char *)CMSG_DATA(entry) + i * sizeof(int), sizeof(descriptor));
            if (received_count < 2) received[received_count++] = descriptor;
            else raw_close(descriptor);
        }
    }
    map_fd = received[0]; notify = received[1];
    if (got != sizeof(version) || (message.msg_flags & (MSG_CTRUNC | MSG_TRUNC))
            || !cmsg || cmsg->cmsg_level != SOL_SOCKET || cmsg->cmsg_type != SCM_RIGHTS
            || cmsg->cmsg_len != CMSG_LEN(2 * sizeof(int)) || CMSG_NXTHDR(&message, cmsg)
            || received_count != 2) { errno = EPROTO; goto fail; }
    struct stat st;
    if (version != SKY_INPUT_VERSION || fstat(map_fd, &st) < 0 || st.st_size != sizeof(*r->ring)) {
        errno = EPROTO; goto fail;
    }
    r->ring = mmap(NULL, sizeof(*r->ring), PROT_READ, MAP_SHARED, map_fd, 0);
    if (r->ring == MAP_FAILED) { r->ring = NULL; goto fail; }
    if (r->ring->magic != SKY_INPUT_MAGIC || r->ring->version != SKY_INPUT_VERSION
            || r->ring->bytes != sizeof(*r->ring) || r->ring->capacity != SKY_INPUT_CAPACITY) { errno = EPROTO; goto fail; }
    r->state = mmap(NULL, sizeof(*r->state), PROT_READ | PROT_WRITE, MAP_SHARED | MAP_ANONYMOUS, -1, 0);
    if (r->state == MAP_FAILED) { r->state = NULL; goto fail; }
    pthread_mutexattr_t attributes;
    pthread_mutexattr_init(&attributes);
    pthread_mutexattr_setpshared(&attributes, PTHREAD_PROCESS_SHARED);
#ifndef __ANDROID__
    pthread_mutexattr_setrobust(&attributes, PTHREAD_MUTEX_ROBUST);
#endif
    int error = pthread_mutex_init(&r->state->lock, &attributes);
    pthread_mutexattr_destroy(&attributes);
    if (error) { errno = error; goto fail; }
    r->state->initial = 1;
    r->state->flags = flags;
    r->state->clock = CLOCK_REALTIME;
    r->state->xbox_axes = xbox_axes;
    r->state->slot = slot;
    r->state->generation = sky_load(&r->ring->generation);
    r->notify = next_fcntl(notify, F_DUPFD_CLOEXEC, 0);
    if (r->notify < 0) goto fail;
    result = remember(notify, r);
    if (result < 0) goto fail;
    if (!(flags & O_CLOEXEC)) next_fcntl(notify, F_SETFD, 0);
    raw_close(map_fd);
    fprintf(stderr, "SkyInput: protocol=2 slot=%d generation=%u fd=%d\n", slot, r->state->generation, notify);
    return result;
fail: {
    int error = errno;
    fprintf(stderr, "SkyInput: protocol=2 open failed slot=%d: %s\n", slot, strerror(error));
    raw_close(map_fd); raw_close(notify); raw_close(control);
    if (r) {
        raw_close(r->notify);
        if (r->ring) munmap(r->ring, sizeof(*r->ring));
        if (r->state) munmap(r->state, sizeof(*r->state));
        free(r);
    }
    errno = error;
    return -1;
}}

static int lock_state(struct shared_cursor *state) {
    struct timespec deadline;
    if (clock_gettime(CLOCK_REALTIME, &deadline) < 0) return -1;
    deadline.tv_nsec += 100000000;
    if (deadline.tv_nsec >= 1000000000) { deadline.tv_sec++; deadline.tv_nsec -= 1000000000; }
    int error = pthread_mutex_timedlock(&state->lock, &deadline);
#ifndef __ANDROID__
    if (error == EOWNERDEAD) {
        state->initial = 1;
        state->count = state->position = 0;
        error = pthread_mutex_consistent(&state->lock);
    }
#endif
    if (error) { errno = error; return -1; }
    return 0;
}
static void add_event(struct shared_cursor *s, int type, int code, int value) {
    struct input_event *event = &s->pending[s->count++];
    memset(event, 0, sizeof(*event));
    event->type = type; event->code = code; event->value = value;
}
static int refill(struct reader *r) {
    struct shared_cursor *s = r->state;
    struct sky_input_ring *ring = r->ring;
    if (s->ended) return -1;
    for (int attempt = 0; attempt < 32; attempt++) {
        uint32_t revision = sky_load(&ring->revision);
        if (revision & 1) continue;
        uint32_t head = sky_load(&ring->head);
        uint32_t generation = sky_load(&ring->generation);
        int gone = !sky_load(&ring->connected) || generation != s->generation;
        int overflow = (uint32_t)(head - s->cursor) > SKY_INPUT_CAPACITY;
        s->position = s->count = 0;
        if (s->initial || gone || overflow) {
            if (overflow) { add_event(s, EV_SYN, SYN_DROPPED, 0); add_event(s, EV_SYN, SYN_REPORT, 0); }
            for (int i = 0; i < 10; i++) add_event(s, EV_KEY, button_codes[i], gone ? 0 : (int)sky_load(&ring->buttons[i]));
            for (int i = 0; i < 8; i++) add_event(s, EV_ABS, axis_codes[i], gone ? 0 : (int)sky_load(&ring->axes[i]));
            add_event(s, EV_SYN, SYN_REPORT, 0);
        } else {
            uint32_t remaining = head - s->cursor;
            if (remaining > 32) remaining = 32;
            for (uint32_t i = 0; i < remaining; i++) {
                struct sky_input_event *event = &ring->events[(s->cursor + i) % SKY_INPUT_CAPACITY];
                uint32_t code = sky_load(&event->type_code);
                add_event(s, code & 0xffff, code >> 16, (int)sky_load(&event->value));
            }
        }
        if (revision != sky_load(&ring->revision)) continue;
        s->cursor = s->initial || gone || overflow ? head : s->cursor + (uint32_t)s->count;
        s->initial = 0;
        s->ended = gone;
        return (int)s->count;
    }
    s->count = s->position = 0;
    errno = EAGAIN;
    return -2;
}
static void update_readiness(struct reader *r) {
    uint64_t value;
    if (syscall(SYS_read, r->notify, &value, sizeof(value)) < 0 && errno != EAGAIN)
        fprintf(stderr, "SkyInput: eventfd drain failed: %s\n", strerror(errno));
    struct shared_cursor *s = r->state;
    if (s->position < s->count || s->ended || s->cursor != sky_load(&r->ring->head)
            || !sky_load(&r->ring->connected) || s->generation != sky_load(&r->ring->generation)) {
        value = 1;
        if (syscall(SYS_write, r->notify, &value, sizeof(value)) < 0 && errno != EAGAIN)
            fprintf(stderr, "SkyInput: eventfd rearm failed: %s\n", strerror(errno));
    }
}
ssize_t sky_input_read(int fd, void *data, size_t bytes) {
    struct reader *r = acquire(fd);
    if (!r) { errno = EBADF; return -1; }
    if (bytes == 0) { release(r); return 0; }
    if (bytes < sizeof(struct input_event)) { release(r); errno = EINVAL; return -1; }
    ssize_t result = -1;
    for (;;) {
        if (lock_state(r->state) < 0) break;
        struct shared_cursor *s = r->state;
        int ready = s->position < s->count ? 1 : refill(r);
        if (ready == -1) errno = ENODEV;
        if (ready > 0) {
            size_t count = bytes / sizeof(struct input_event);
            if (count > s->count - s->position) count = s->count - s->position;
            struct timespec now;
            if (clock_gettime(s->clock, &now) < 0) {
                pthread_mutex_unlock(&s->lock);
                break;
            }
            struct input_event *out = data;
            memcpy(out, s->pending + s->position, count * sizeof(*out));
            for (size_t i = 0; i < count; i++) {
                out[i].time.tv_sec = now.tv_sec;
                out[i].time.tv_usec = now.tv_nsec / 1000;
                if (s->xbox_axes && out[i].type == EV_ABS) {
                    if (out[i].code == ABS_BRAKE) out[i].code = ABS_Z;
                    if (out[i].code == ABS_GAS) out[i].code = ABS_RZ;
                }
            }
            s->position += count;
            result = (ssize_t)(count * sizeof(*out));
        }
        int nonblock = s->flags & O_NONBLOCK;
        if (ready != -2) update_readiness(r);
        pthread_mutex_unlock(&s->lock);
        if (ready > 0 || ready < 0) break;
        if (nonblock) { errno = EAGAIN; break; }
        struct pollfd wait = {r->notify, POLLIN, 0};
        if (poll(&wait, 1, -1) < 0) break;
    }
    int error = errno;
    release(r);
    errno = error;
    return result;
}

int sky_input_ioctl(int fd, unsigned long request, void *arg) {
    struct reader *r = acquire(fd);
    if (!r) return -2;
    unsigned nr = request & 0xff, type = (request >> 8) & 0xff;
    size_t bytes = (request >> 16) & 0x3fff;
    int result = -2;
    if (type == 'E' && (nr == 0x18 || (nr >= 0x40 && nr < 0x80) || nr == 0xa0)) {
        if (lock_state(r->state) < 0) { release(r); return -1; }
        struct shared_cursor *s = r->state;
        if (nr == 0xa0) {
            int clock;
            memcpy(&clock, arg, sizeof(clock));
            struct timespec now;
            if (clock != CLOCK_REALTIME && clock != CLOCK_MONOTONIC && clock != CLOCK_BOOTTIME) {
                errno = EINVAL;
                result = -1;
            } else if (clock_gettime(clock, &now) < 0) result = -1;
            else { s->clock = clock; result = 0; }
        } else {
            result = -1;
            errno = EAGAIN;
            for (int attempt = 0; attempt < 32; attempt++) {
                uint32_t revision = sky_load(&r->ring->revision);
                if (revision & 1) continue;
                int gone = !sky_load(&r->ring->connected) || s->generation != sky_load(&r->ring->generation);
                if (nr == 0x18) {
                    memset(arg, 0, bytes);
                    for (int i = 0; i < 10; i++)
                        if (!gone && sky_load(&r->ring->buttons[i]) && (size_t)(button_codes[i] / 8) < bytes)
                            ((unsigned char *)arg)[button_codes[i] / 8] |= 1 << (button_codes[i] % 8);
                } else {
                    struct input_absinfo info = {0};
                    int axis = nr - 0x40;
                    if (s->xbox_axes && axis == ABS_Z) axis = ABS_BRAKE;
                    if (s->xbox_axes && axis == ABS_RZ) axis = ABS_GAS;
                    int index;
                    for (index = 0; index < 8 && axis_codes[index] != axis; index++) {}
                    if (index == 8) { errno = EINVAL; break; }
                    info.value = gone ? 0 : (int)sky_load(&r->ring->axes[index]);
                    info.minimum = index < 4 ? -32767 : index >= 6 ? -1 : 0;
                    info.maximum = index < 4 ? 32767 : index >= 6 ? 1 : 255;
                    memcpy(arg, &info, bytes < sizeof(info) ? bytes : sizeof(info));
                }
                if (revision == sky_load(&r->ring->revision)) {
                    result = nr == 0x18 ? (int)(bytes < (KEY_MAX + 8) / 8 ? bytes : (KEY_MAX + 8) / 8) : 0;
                    break;
                }
                errno = EAGAIN;
            }
        }
        pthread_mutex_unlock(&s->lock);
    }
    release(r);
    return result;
}

static int copy_handle(int oldfd, int newfd) {
    if (newfd < 0 || oldfd == newfd) return newfd;
    struct reader *r = acquire(oldfd);
    sky_input_close(newfd);
    if (r) {
        if (remember(newfd, r) < 0) { raw_close(newfd); newfd = -1; }
        release(r);
    }
    return newfd;
}

__attribute__((visibility("default"))) int dup(int fd) {
    pthread_once(&init_once, initialize);
    if (!next_dup) { errno = ENOSYS; return -1; }
    return copy_handle(fd, next_dup(fd));
}
__attribute__((visibility("default"))) int dup2(int fd, int target) {
    pthread_once(&init_once, initialize);
    if (!next_dup2) { errno = ENOSYS; return -1; }
    return copy_handle(fd, next_dup2(fd, target));
}
__attribute__((visibility("default"))) int dup3(int fd, int target, int flags) {
    pthread_once(&init_once, initialize);
    if (!next_dup3) { errno = ENOSYS; return -1; }
    return copy_handle(fd, next_dup3(fd, target, flags));
}
static int descriptor_control(int fd, int command, va_list ap, int large_file) {
    pthread_once(&init_once, initialize);
    int (*dispatch)(int, int, ...) = large_file ? next_fcntl64 : next_fcntl;
    if (!dispatch) { errno = ENOSYS; return -1; }
    long argument = 0;
    switch (command) {
        case F_GETFD: case F_GETFL: case F_GETOWN: case F_GETSIG: case F_GETLEASE:
        case F_GETPIPE_SZ: case F_GET_SEALS: break;
        case F_DUPFD: case F_DUPFD_CLOEXEC: case F_SETFD: case F_SETFL: case F_SETOWN:
        case F_SETSIG: case F_SETLEASE: case F_NOTIFY: case F_SETPIPE_SZ: case F_ADD_SEALS:
            argument = va_arg(ap, int); break;
        default: argument = (long)va_arg(ap, void *); break;
    }
    if (command == F_GETFL || command == F_SETFL) {
        struct reader *r = acquire(fd);
        if (r) {
            int result = -1;
            if (lock_state(r->state) == 0) {
                if (command == F_SETFL) r->state->flags = (r->state->flags & ~O_NONBLOCK) | ((int)argument & O_NONBLOCK);
                result = command == F_GETFL ? r->state->flags : 0;
                pthread_mutex_unlock(&r->state->lock);
            }
            release(r);
            return result;
        }
    }
    int result = dispatch(fd, command, argument);
    return command == F_DUPFD || command == F_DUPFD_CLOEXEC ? copy_handle(fd, result) : result;
}
__attribute__((visibility("default"))) int fcntl(int fd, int command, ...) {
    va_list ap;
    va_start(ap, command);
    int result = descriptor_control(fd, command, ap, 0);
    va_end(ap);
    return result;
}
#ifndef __ANDROID__
__attribute__((visibility("default"))) int fcntl64(int fd, int command, ...) {
    va_list ap;
    va_start(ap, command);
    int result = descriptor_control(fd, command, ap, 1);
    va_end(ap);
    return result;
}
#endif
