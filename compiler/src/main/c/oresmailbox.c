#define _GNU_SOURCE
#include "oresmailbox.h"
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <stdint.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>
#ifdef __linux__
#include <sched.h>
#endif

#define WIRE_MAGIC UINT32_C(0x4f524553)
enum { DATA = 1, OFFER = 2, COMMIT = 3 };
/* Same-host ABI only; reserved field and explicit size prevent padding disclosure. */
struct header { uint32_t magic, kind; uint64_t id; uint32_t size, reserved; };
struct packet { struct header h; unsigned char payload[ORES_MAILBOX_MAX_PAYLOAD]; };

static int64_t millis(void) {
    struct timespec t;
    if (clock_gettime(CLOCK_MONOTONIC, &t) < 0) return -1;
    return (int64_t)t.tv_sec * 1000 + t.tv_nsec / 1000000;
}
static int64_t deadline(int timeout_ms) {
    if (timeout_ms <= 0) { errno = EINVAL; return -1; }
    int64_t now = millis();
    return now < 0 ? -1 : now + timeout_ms;
}
static int ready(int fd, short events, int64_t end) {
    for (;;) {
        int64_t now = millis();
        if (now < 0) return -1;
        int64_t remaining = end - now;
        if (remaining <= 0) { errno = ETIMEDOUT; return -1; }
        struct pollfd p = { .fd = fd, .events = events };
        int n = poll(&p, 1, remaining > INT_MAX ? INT_MAX : (int)remaining);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) { if (n == 0) errno = ETIMEDOUT; return -1; }
        if (p.revents & events) return 0;
        errno = (p.revents & POLLNVAL) ? EBADF : EPIPE;
        return -1;
    }
}
static int cloexec(int fd) {
    int flags = fcntl(fd, F_GETFD);
    return flags < 0 ? -1 : fcntl(fd, F_SETFD, flags | FD_CLOEXEC);
}
int ores_mailbox_pair(int fds[2]) {
    if (!fds) { errno = EINVAL; return -1; }
    fds[0] = fds[1] = -1;
    int pair[2];
#ifdef __linux__
    if (socketpair(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC, 0, pair) < 0) return -1;
#else
    if (socketpair(AF_UNIX, SOCK_DGRAM, 0, pair) < 0) return -1;
#endif
    if (cloexec(pair[0]) < 0 || cloexec(pair[1]) < 0) {
        int error = errno; close(pair[0]); close(pair[1]); errno = error; return -1;
    }
    fds[0] = pair[0]; fds[1] = pair[1];
    return 0;
}
static int send_packet(int fd, uint32_t kind, uint64_t id, const void *data,
                       size_t size, int passed_fd, int64_t end) {
    struct header h = { .magic = WIRE_MAGIC, .kind = kind, .id = id,
                        .size = (uint32_t)size, .reserved = 0 };
    struct iovec iov[2] = { { &h, sizeof h }, { (void *)data, size } };
    union { struct cmsghdr align; unsigned char bytes[CMSG_SPACE(sizeof(int))]; } control = {0};
    struct msghdr msg = {0};
    msg.msg_iov = iov; msg.msg_iovlen = size ? 2 : 1;
    if (passed_fd >= 0) {
        msg.msg_control = control.bytes; msg.msg_controllen = sizeof control.bytes;
        struct cmsghdr *c = CMSG_FIRSTHDR(&msg);
        c->cmsg_level = SOL_SOCKET; c->cmsg_type = SCM_RIGHTS;
        c->cmsg_len = CMSG_LEN(sizeof(int));
        memcpy(CMSG_DATA(c), &passed_fd, sizeof passed_fd);
    }
    for (;;) {
        if (ready(fd, POLLOUT, end) < 0) return -1;
        int flags = MSG_DONTWAIT;
#ifdef MSG_NOSIGNAL
        flags |= MSG_NOSIGNAL;
#endif
        ssize_t n = sendmsg(fd, &msg, flags);
        if (n < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)) continue;
        if (n < 0) return -1;
        if ((size_t)n != sizeof h + size) { errno = EIO; return -1; }
        return 0;
    }
}
/* Receive all ancillary descriptors so malformed packets cannot leak rights. */
static int receive_packet(int fd, struct packet *p, int *received_fd, int64_t end) {
    union { struct cmsghdr align; unsigned char bytes[CMSG_SPACE(16 * sizeof(int))]; } control;
    for (;;) {
        if (ready(fd, POLLIN, end) < 0) return -1;
        memset(&control, 0, sizeof control);
        struct iovec iov = { p, sizeof *p };
        struct msghdr msg = {0};
        msg.msg_iov = &iov; msg.msg_iovlen = 1;
        msg.msg_control = control.bytes; msg.msg_controllen = sizeof control.bytes;
        int flags = MSG_DONTWAIT;
#ifdef MSG_CMSG_CLOEXEC
        flags |= MSG_CMSG_CLOEXEC;
#endif
        ssize_t n = recvmsg(fd, &msg, flags);
        if (n < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)) continue;
        if (n < 0) return -1;
        int first = -1, count = 0, invalid = 0;
        for (struct cmsghdr *c = CMSG_FIRSTHDR(&msg); c; c = CMSG_NXTHDR(&msg, c)) {
            if (c->cmsg_level != SOL_SOCKET || c->cmsg_type != SCM_RIGHTS || c->cmsg_len < CMSG_LEN(0)) {
                invalid = 1; continue;
            }
            size_t bytes = c->cmsg_len - CMSG_LEN(0);
            if (bytes % sizeof(int)) invalid = 1;
            for (size_t j = 0; j + sizeof(int) <= bytes; j += sizeof(int)) {
                int right; memcpy(&right, (unsigned char *)CMSG_DATA(c) + j, sizeof right);
                if (++count == 1) first = right; else close(right);
            }
        }
        if (msg.msg_flags & (MSG_TRUNC | MSG_CTRUNC)) invalid = 1;
        if (n < (ssize_t)sizeof p->h || p->h.magic != WIRE_MAGIC || p->h.reserved != 0 ||
            p->h.size > ORES_MAILBOX_MAX_PAYLOAD || (size_t)n != sizeof p->h + p->h.size || count > 1)
            invalid = 1;
        if (first >= 0 && cloexec(first) < 0) invalid = 1;
        if (invalid) { if (first >= 0) close(first); errno = EPROTO; return -1; }
        *received_fd = first;
        return 0;
    }
}
int ores_mailbox_send(int fd, const void *data, size_t size, int timeout_ms) {
    if (size > ORES_MAILBOX_MAX_PAYLOAD || (size && !data)) { errno = EINVAL; return -1; }
    int64_t end = deadline(timeout_ms);
    return end < 0 ? -1 : send_packet(fd, DATA, 0, data, size, -1, end);
}
int ores_mailbox_receive(int fd, void *data, size_t capacity, size_t *size, int timeout_ms) {
    if (!size || (capacity && !data)) { errno = EINVAL; return -1; }
    *size = 0;
    int64_t end = deadline(timeout_ms);
    if (end < 0) return -1;
    struct packet p; int right = -1;
    if (receive_packet(fd, &p, &right, end) < 0) return -1;
    if (right >= 0) close(right);
    if (p.h.kind != DATA || p.h.id != 0 || right >= 0) { errno = EPROTO; return -1; }
    if (p.h.size > capacity) { errno = EMSGSIZE; return -1; }
    if (p.h.size) memcpy(data, p.payload, p.h.size);
    *size = p.h.size;
    return 0;
}
int ores_mailbox_move_fd(int fd, int *owned, uint64_t id, enum ores_worker_trust trust, int timeout_ms) {
    if (trust != ORES_WORKER_TRUSTED) { errno = EPERM; return -1; }
    if (!owned || *owned < 0 || *owned == fd || id == 0) { errno = EINVAL; return -1; }
    int64_t end = deadline(timeout_ms);
    if (end < 0) return -1;
    if (send_packet(fd, OFFER, id, NULL, 0, *owned, end) < 0) return -1;
    int moved = *owned; *owned = -1;
    /* Do not retry close: the numeric descriptor may have been reused. */
    if (close(moved) < 0) return -1;
    return send_packet(fd, COMMIT, id, NULL, 0, -1, end);
}
int ores_mailbox_receive_fd(int fd, int *owned, uint64_t *id, enum ores_worker_trust trust, int timeout_ms) {
    if (trust != ORES_WORKER_TRUSTED) { errno = EPERM; return -1; }
    if (!owned || !id) { errno = EINVAL; return -1; }
    *owned = -1; *id = 0;
    int64_t end = deadline(timeout_ms);
    if (end < 0) return -1;
    struct packet offer, commit; int right = -1, unexpected = -1;
    if (receive_packet(fd, &offer, &right, end) < 0) return -1;
    if (offer.h.kind != OFFER || offer.h.id == 0 || offer.h.size != 0 || right < 0) {
        if (right >= 0) close(right);
        errno = EPROTO; return -1;
    }
    if (receive_packet(fd, &commit, &unexpected, end) < 0) {
        int error = errno; close(right); errno = error; return -1;
    }
    if (unexpected >= 0) close(unexpected);
    if (commit.h.kind != COMMIT || commit.h.id != offer.h.id || commit.h.size != 0 || unexpected >= 0) {
        close(right); errno = EPROTO; return -1;
    }
    *owned = right; *id = offer.h.id;
    return 0;
}
int ores_carrier_bind_cpu(unsigned int cpu) {
#ifdef __linux__
    if (cpu >= CPU_SETSIZE) { errno = EINVAL; return -1; }
    cpu_set_t set; CPU_ZERO(&set); CPU_SET(cpu, &set);
    return sched_setaffinity(0, sizeof set, &set);
#else
    (void)cpu; errno = ENOTSUP; return -1;
#endif
}
