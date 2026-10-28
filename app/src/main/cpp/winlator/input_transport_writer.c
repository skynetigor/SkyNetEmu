#define _GNU_SOURCE
#include "input_transport.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <poll.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <sys/eventfd.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

static pthread_mutex_t writer_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_t broker;
static int listener = -1, stop_fd = -1;
static char endpoint[108];
static char backing[SKY_INPUT_SLOTS][128];
static int ring_fds[SKY_INPUT_SLOTS];
static struct sky_input_ring *rings[SKY_INPUT_SLOTS];
static struct { int control, notify, slot; } subscribers[SKY_INPUT_READERS];
static const int buttons[] = {BTN_A, BTN_B, BTN_X, BTN_Y, BTN_TL, BTN_TR,
                             BTN_SELECT, BTN_START, BTN_THUMBL, BTN_THUMBR};
static const int axes[] = {ABS_X, ABS_Y, ABS_RX, ABS_RY, ABS_BRAKE, ABS_GAS, ABS_HAT0X, ABS_HAT0Y};

static void raw_close(int fd) { if (fd >= 0) syscall(SYS_close, fd); }
static void wake_slot(int slot) {
    uint64_t one = 1;
    for (int i = 0; i < SKY_INPUT_READERS; i++)
        if (subscribers[i].control >= 0 && subscribers[i].slot == slot)
            if (syscall(SYS_write, subscribers[i].notify, &one, sizeof(one)) < 0 && errno != EAGAIN)
                fprintf(stderr, "SkyInput: notification failed: %s\n", strerror(errno));
}

static void *serve(void *unused) {
    (void)unused;
    for (;;) {
        struct pollfd fds[SKY_INPUT_READERS + 2] = {{listener, POLLIN, 0}, {stop_fd, POLLIN, 0}};
        pthread_mutex_lock(&writer_lock);
        for (int i = 0; i < SKY_INPUT_READERS; i++)
            fds[i + 2] = (struct pollfd){subscribers[i].control, POLLIN, 0};
        pthread_mutex_unlock(&writer_lock);
        int ready = poll(fds, SKY_INPUT_READERS + 2, -1);
        if (ready < 0) {
            if (errno == EINTR) continue;
            fprintf(stderr, "SkyInput: broker poll failed: %s\n", strerror(errno));
            break;
        }
        if (fds[1].revents) break;
        pthread_mutex_lock(&writer_lock);
        for (int i = 0; i < SKY_INPUT_READERS; i++) {
            if (fds[i + 2].revents) {
                raw_close(subscribers[i].control);
                raw_close(subscribers[i].notify);
                subscribers[i].control = subscribers[i].notify = -1;
            }
        }
        pthread_mutex_unlock(&writer_lock);
        if (!(fds[0].revents & POLLIN)) continue;
        int client = accept4(listener, NULL, NULL, SOCK_CLOEXEC);
        if (client < 0) continue;
        struct timeval limit = {0, 200000};
        setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, &limit, sizeof(limit));
        setsockopt(client, SOL_SOCKET, SO_SNDTIMEO, &limit, sizeof(limit));
        struct ucred peer;
        socklen_t len = sizeof(peer);
        uint32_t request[2];
        ssize_t got = recv(client, request, sizeof(request), MSG_TRUNC);
        if (getsockopt(client, SOL_SOCKET, SO_PEERCRED, &peer, &len) != 0 || peer.uid != getuid()
                || got != sizeof(request) || request[0] != SKY_INPUT_VERSION || request[1] >= SKY_INPUT_SLOTS) {
            fprintf(stderr, "SkyInput: rejected invalid subscriber handshake\n");
            raw_close(client);
            continue;
        }
        pthread_mutex_lock(&writer_lock);
        int index;
        for (index = 0; index < SKY_INPUT_READERS && subscribers[index].control >= 0; index++) {}
        int notify = index < SKY_INPUT_READERS ? eventfd(1, EFD_NONBLOCK | EFD_CLOEXEC) : -1;
        if (notify >= 0) {
            uint32_t version = SKY_INPUT_VERSION;
            struct iovec io = {&version, sizeof(version)};
            union { struct cmsghdr align; char data[CMSG_SPACE(2 * sizeof(int))]; } ancillary;
            memset(&ancillary, 0, sizeof(ancillary));
            struct msghdr message = {.msg_iov = &io, .msg_iovlen = 1,
                                     .msg_control = ancillary.data, .msg_controllen = sizeof(ancillary.data)};
            struct cmsghdr *cmsg = CMSG_FIRSTHDR(&message);
            cmsg->cmsg_level = SOL_SOCKET;
            cmsg->cmsg_type = SCM_RIGHTS;
            cmsg->cmsg_len = CMSG_LEN(2 * sizeof(int));
            int handles[] = {ring_fds[request[1]], notify};
            memcpy(CMSG_DATA(cmsg), handles, sizeof(handles));
            if (sendmsg(client, &message, MSG_NOSIGNAL) == sizeof(version)) {
                subscribers[index].control = client;
                subscribers[index].notify = notify;
                subscribers[index].slot = (int)request[1];
                fprintf(stderr, "SkyInput: protocol=2 slot=%u reader=%d ready\n", request[1], peer.pid);
                client = -1;
                notify = -1;
            }
        } else fprintf(stderr, "SkyInput: subscriber limit or eventfd failure\n");
        raw_close(notify);
        raw_close(client);
        pthread_mutex_unlock(&writer_lock);
    }
    return NULL;
}

int sky_input_start(const char *dir) {
    if (listener >= 0) { errno = EALREADY; return -1; }
    for (int i = 0; i < SKY_INPUT_SLOTS; i++) { ring_fds[i] = -1; rings[i] = NULL; }
    for (int i = 0; i < SKY_INPUT_READERS; i++) subscribers[i].control = subscribers[i].notify = -1;
    if (snprintf(endpoint, sizeof(endpoint), "%s/.input-v2", dir) >= (int)sizeof(endpoint)) {
        errno = ENAMETOOLONG; return -1;
    }
    int error = 0;
    const char *stage = "ring allocation";
    for (int i = 0; i < SKY_INPUT_SLOTS; i++) {
        if (snprintf(backing[i], sizeof(backing[i]), "%s/.input-v2-slot%d", dir, i) >= (int)sizeof(backing[i])) {
            error = ENAMETOOLONG; goto fail;
        }
        if (unlink(backing[i]) != 0 && errno != ENOENT) { error = errno; goto fail; }
        int file = open(backing[i], O_RDWR | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
        if (file < 0) { error = errno; goto fail; }
        if (ftruncate(file, sizeof(struct sky_input_ring)) != 0) { error = errno; raw_close(file); goto fail; }
        rings[i] = mmap(NULL, sizeof(struct sky_input_ring), PROT_READ | PROT_WRITE, MAP_SHARED, file, 0);
        raw_close(file);
        if (rings[i] == MAP_FAILED) { rings[i] = NULL; error = errno; goto fail; }
        ring_fds[i] = open(backing[i], O_RDONLY | O_CLOEXEC);
        if (ring_fds[i] < 0) { error = errno; goto fail; }
        *rings[i] = (struct sky_input_ring){.magic = SKY_INPUT_MAGIC, .version = SKY_INPUT_VERSION,
            .bytes = sizeof(struct sky_input_ring), .capacity = SKY_INPUT_CAPACITY, .generation = 1, .connected = 1};
    }
    listener = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    stop_fd = eventfd(0, EFD_CLOEXEC);
    stage = "notification descriptors";
    if (listener < 0 || stop_fd < 0) { error = errno; goto fail; }
    struct sockaddr_un address = {.sun_family = AF_UNIX};
    memcpy(address.sun_path, endpoint, strlen(endpoint) + 1);
    if (unlink(endpoint) != 0 && errno != ENOENT) { error = errno; goto fail; }
    stage = "socket bind";
    if (bind(listener, (struct sockaddr *)&address, sizeof(address)) < 0) { error = errno; goto fail; }
    stage = "socket permissions";
    if (chmod(endpoint, 0600) < 0) { error = errno; goto fail; }
    stage = "socket listen";
    if (listen(listener, 8) < 0) { error = errno; goto fail; }
    stage = "broker thread";
    error = pthread_create(&broker, NULL, serve, NULL);
    if (error) goto fail;
    fprintf(stderr, "SkyInput: protocol=2 fixedBytes=%zu slots=%d\n", sizeof(struct sky_input_ring) * SKY_INPUT_SLOTS, SKY_INPUT_SLOTS);
    return 0;
fail:
    fprintf(stderr, "SkyInput: startup failed stage=%s path=%s: %s\n", stage, endpoint, strerror(error));
    raw_close(listener); listener = -1;
    raw_close(stop_fd); stop_fd = -1;
    for (int i = 0; i < SKY_INPUT_SLOTS; i++) {
        if (rings[i]) munmap(rings[i], sizeof(struct sky_input_ring));
        rings[i] = NULL;
        raw_close(ring_fds[i]); ring_fds[i] = -1;
        if (backing[i][0]) unlink(backing[i]);
    }
    unlink(endpoint);
    errno = error;
    return -1;
}

int sky_input_activate(int slot) {
    if (slot < 0 || slot >= SKY_INPUT_SLOTS || listener < 0) { errno = ENODEV; return -1; }
    pthread_mutex_lock(&writer_lock);
    struct sky_input_ring *r = rings[slot];
    if (!sky_load(&r->connected)) {
        sky_store(&r->revision, sky_load(&r->revision) + 1);
        sky_store(&r->generation, sky_load(&r->generation) + 1);
        for (int i = 0; i < 10; i++) sky_store(&r->buttons[i], 0);
        for (int i = 0; i < 8; i++) sky_store(&r->axes[i], 0);
        sky_store(&r->head, 0);
        sky_store(&r->connected, 1);
        sky_store(&r->revision, sky_load(&r->revision) + 1);
        wake_slot(slot);
    }
    pthread_mutex_unlock(&writer_lock);
    return 0;
}

int sky_input_publish(int slot, const void *events, size_t bytes) {
    if (slot < 0 || slot >= SKY_INPUT_SLOTS || listener < 0 || bytes % 24 || bytes / 24 > 29) { errno = EINVAL; return -1; }
    pthread_mutex_lock(&writer_lock);
    struct sky_input_ring *r = rings[slot];
    if (!sky_load(&r->connected)) { pthread_mutex_unlock(&writer_lock); errno = ENODEV; return -1; }
    sky_store(&r->revision, sky_load(&r->revision) + 1);
    uint32_t head = sky_load(&r->head);
    const unsigned char *p = events;
    for (size_t offset = 0; offset < bytes; offset += 24) {
        uint32_t words[6];
        memcpy(words, p + offset, 24);
        uint32_t type = words[4] & 0xffff, code = words[4] >> 16;
        struct sky_input_event *event = &r->events[head++ % SKY_INPUT_CAPACITY];
        sky_store(&event->sec, words[0]);
        sky_store(&event->usec, words[2]);
        sky_store(&event->type_code, words[4]);
        sky_store(&event->value, words[5]);
        if (type == EV_KEY)
            for (int i = 0; i < 10; i++) if ((int)code == buttons[i]) sky_store(&r->buttons[i], words[5] != 0);
        if (type == EV_ABS)
            for (int i = 0; i < 8; i++) if ((int)code == axes[i]) sky_store(&r->axes[i], words[5]);
    }
    sky_store(&r->head, head);
    sky_store(&r->revision, sky_load(&r->revision) + 1);
    wake_slot(slot);
    pthread_mutex_unlock(&writer_lock);
    return (int)bytes;
}

void sky_input_disconnect(int slot) {
    if (slot < 0 || slot >= SKY_INPUT_SLOTS || listener < 0) return;
    pthread_mutex_lock(&writer_lock);
    struct sky_input_ring *r = rings[slot];
    sky_store(&r->revision, sky_load(&r->revision) + 1);
    for (int i = 0; i < 10; i++) sky_store(&r->buttons[i], 0);
    for (int i = 0; i < 8; i++) sky_store(&r->axes[i], 0);
    sky_store(&r->connected, 0);
    sky_store(&r->revision, sky_load(&r->revision) + 1);
    wake_slot(slot);
    pthread_mutex_unlock(&writer_lock);
}

void sky_input_stop(void) {
    if (listener < 0) return;
    for (int slot = 0; slot < SKY_INPUT_SLOTS; slot++) sky_input_disconnect(slot);
    uint64_t one = 1;
    syscall(SYS_write, stop_fd, &one, sizeof(one));
    pthread_join(broker, NULL);
    for (int i = 0; i < SKY_INPUT_READERS; i++) {
        raw_close(subscribers[i].control);
        raw_close(subscribers[i].notify);
    }
    for (int i = 0; i < SKY_INPUT_SLOTS; i++) {
        munmap(rings[i], sizeof(struct sky_input_ring));
        rings[i] = NULL;
        raw_close(ring_fds[i]);
        unlink(backing[i]);
    }
    raw_close(listener); listener = -1;
    raw_close(stop_fd); stop_fd = -1;
    unlink(endpoint);
}
