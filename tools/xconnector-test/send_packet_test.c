#include <assert.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

#ifndef SEND_PACKET_HEADER
#define SEND_PACKET_HEADER "../../app/src/main/cpp/winlator/xconnector_send_packet.h"
#endif
#include SEND_PACKET_HEADER

enum { PAYLOAD_SIZE = 1024 * 1024 };
static char payload[PAYLOAD_SIZE];
struct sender { int fd, ancillary, result; atomic_int done; };

static void *send_payload(void *arg) {
    struct sender *s = arg;
    s->result = xconnector_send_packet(s->fd, payload, sizeof(payload), s->ancillary);
    atomic_store(&s->done, 1);
    return NULL;
}

static void socket_pair(int fds[2]) {
    assert(socketpair(AF_UNIX, SOCK_STREAM, 0, fds) == 0);
    int size = 4096;
    assert(setsockopt(fds[0], SOL_SOCKET, SO_SNDBUF, &size, sizeof(size)) == 0);
}

static void ignore_signal(int sig) { (void)sig; }

static void delayed_reader(void) {
    int fds[2]; socket_pair(fds);
    int pass_fd = open("/dev/null", O_RDONLY);
    assert(pass_fd >= 0);
    struct sender s = {.fd=fds[0], .ancillary=pass_fd};
    pthread_t worker;
    assert(pthread_create(&worker, NULL, send_payload, &s) == 0);
    usleep(100000);
    assert(pthread_kill(worker, SIGUSR1) == 0); // Interrupted poll must keep waiting.
    sleep(3); // A loading client pauses longer than the old two-second deadline.
    assert(!atomic_load(&s.done));
    size_t total = 0;
    int descriptors = 0;
    while (total < sizeof(payload)) {
        char bytes[8192], control[CMSG_SPACE(sizeof(int) * 4)];
        struct iovec iov = {.iov_base=bytes, .iov_len=sizeof(bytes)};
        struct msghdr msg = {.msg_iov=&iov, .msg_iovlen=1,
                             .msg_control=control, .msg_controllen=sizeof(control)};
        ssize_t n = recvmsg(fds[1], &msg, 0);
        assert(n > 0 && !(msg.msg_flags & MSG_CTRUNC));
        assert(total + n <= sizeof(payload));
        assert(memcmp(bytes, payload + total, n) == 0);
        total += n;
        for (struct cmsghdr *c = CMSG_FIRSTHDR(&msg); c; c = CMSG_NXTHDR(&msg, c)) {
            assert(c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_RIGHTS);
            int count = (c->cmsg_len - CMSG_LEN(0)) / sizeof(int);
            int *received = (int *)CMSG_DATA(c);
            for (int i=0; i<count; i++) {
                assert(fcntl(received[i], F_GETFD) >= 0);
                close(received[i]); descriptors++;
            }
        }
    }
    assert(pthread_join(worker, NULL) == 0 && s.result == 1);
    assert(descriptors == 1);
    // A subsequent packet must start after the complete first packet.
    assert(xconnector_send_packet(fds[0], "next", 4, -1) == 1);
    char next[4]; assert(recv(fds[1], next, sizeof(next), MSG_WAITALL) == 4);
    assert(memcmp(next, "next", 4) == 0);
    close(pass_fd); close(fds[0]); close(fds[1]);
    puts("PASS delayed reader, interrupted wait, partial writes, FD once, packet order");
}

static void stopped_reader(int disconnect) {
    int fds[2]; socket_pair(fds);
    struct sender s = {.fd=fds[0], .ancillary=-1};
    pthread_t worker;
    assert(pthread_create(&worker, NULL, send_payload, &s) == 0);
    usleep(100000);
    assert(!atomic_load(&s.done));
    if (disconnect) close(fds[1]);
    else assert(shutdown(fds[0], SHUT_RDWR) == 0);
    assert(pthread_join(worker, NULL) == 0 && s.result == 0);
    close(fds[0]); if (!disconnect) close(fds[1]);
    puts(disconnect ? "PASS peer disconnect" : "PASS shutdown cancels blocked writer");
}

int main(void) {
    alarm(15); // Cancellation failures must fail the test, never hang indefinitely.
    struct sigaction action = {.sa_handler=ignore_signal};
    sigemptyset(&action.sa_mask);
    assert(sigaction(SIGUSR1, &action, NULL) == 0);
    for (size_t i=0; i<sizeof(payload); i++) payload[i] = (char)(i * 31u + i / 251u);
    delayed_reader(); stopped_reader(0); stopped_reader(1);
    return 0;
}
