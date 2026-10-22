#ifndef XCONNECTOR_SEND_PACKET_H
#define XCONNECTOR_SEND_PACKET_H

#include <errno.h>
#include <sys/poll.h>
#include <sys/socket.h>
#include <string.h>

/* A loading game can stop reading X11 for minutes. Only the output worker waits;
 * its bounded queue protects memory, and shutdown() cancels this wait on close.
 * Keep partial writes in order and attach an SCM_RIGHTS descriptor only once. */
static int xconnector_send_packet(int fd, const char *bytes, int length, int ancillaryFd) {
    int offset = 0;
    while (offset < length) {
        struct pollfd pfd = {.fd = fd, .events = POLLOUT};
        int ready = poll(&pfd, 1, -1);
        if (ready < 0 && errno == EINTR) continue;
        if (ready <= 0 || (pfd.revents & (POLLERR | POLLHUP | POLLNVAL))) return 0;
        struct iovec iov = {.iov_base = (void *)(bytes + offset), .iov_len = length - offset};
        char control[CMSG_SPACE(sizeof(int))];
        struct msghdr msg = {.msg_iov = &iov, .msg_iovlen = 1};
        if (ancillaryFd >= 0) {
            memset(control, 0, sizeof(control));
            msg.msg_control = control;
            msg.msg_controllen = sizeof(control);
            struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
            cmsg->cmsg_level = SOL_SOCKET;
            cmsg->cmsg_type = SCM_RIGHTS;
            cmsg->cmsg_len = CMSG_LEN(sizeof(int));
            memcpy(CMSG_DATA(cmsg), &ancillaryFd, sizeof(int));
        }
        ssize_t sent = sendmsg(fd, &msg, MSG_DONTWAIT | MSG_NOSIGNAL);
        if (sent < 0 && (errno == EAGAIN || errno == EINTR)) continue;
        if (sent <= 0) return 0;
        ancillaryFd = -1;
        offset += sent;
    }
    return 1;
}

#endif
