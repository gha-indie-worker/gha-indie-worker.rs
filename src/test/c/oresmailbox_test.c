#define _GNU_SOURCE
#include "oresmailbox.h"
#include <arpa/inet.h>
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <signal.h>
#include <spawn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <unistd.h>
#ifdef __linux__
#include <sched.h>
#endif
extern char **environ;

static int worker(void) {
    alarm(15);
    unsigned private_requests = 0;
    for (;;) {
        int connection = -1; uint64_t id = 0;
        if (ores_mailbox_receive_fd(3, &connection, &id, ORES_WORKER_TRUSTED, 3000) < 0) return 3;
        assert(fcntl(connection, F_GETFD) & FD_CLOEXEC);
        char request[1024]; size_t used = 0;
        while (used < sizeof request - 1) {
            ssize_t n = read(connection, request + used, sizeof request - 1 - used);
            if (n < 0 && errno == EINTR) continue;
            assert(n > 0); used += (size_t)n; request[used] = 0;
            if (strstr(request, "\r\n\r\n")) break;
        }
        assert(strstr(request, "GET /health HTTP/1.1\r\n"));
        char body[128], response[512];
        int length = snprintf(body, sizeof body, "worker=%ld request=%u transfer=%llu\n",
                              (long)getpid(), ++private_requests, (unsigned long long)id);
        int size = snprintf(response, sizeof response,
                            "HTTP/1.1 200 OK\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s", length, body);
        for (int sent = 0; sent < size;) {
            ssize_t n = write(connection, response + sent, (size_t)(size - sent));
            if (n < 0 && errno == EINTR) continue;
            assert(n > 0); sent += (int)n;
        }
        close(connection);
        assert(ores_mailbox_send(3, &private_requests, sizeof private_requests, 1000) == 0);
        if (private_requests == 10) return 0;
    }
}
static void write_all(int fd, const char *data, size_t size) {
    while (size) {
        ssize_t n = write(fd, data, size);
        if (n < 0 && errno == EINTR) continue;
        assert(n > 0); data += n; size -= (size_t)n;
    }
}
static void http_handoff(const char *executable) {
    int mb[2]; assert(ores_mailbox_pair(mb) == 0);
    /* Keep the spawn source clear of descriptor 3; dup2 intentionally grants only the mailbox. */
    int source = fcntl(mb[1], F_DUPFD_CLOEXEC, 10); assert(source >= 10);
    posix_spawn_file_actions_t actions;
    assert(posix_spawn_file_actions_init(&actions) == 0);
    assert(posix_spawn_file_actions_adddup2(&actions, source, 3) == 0);
    char *args[] = { (char *)executable, "--http-worker", NULL };
    pid_t pid; assert(posix_spawn(&pid, executable, &actions, NULL, args, environ) == 0);
    posix_spawn_file_actions_destroy(&actions); close(source); close(mb[1]);
    assert(pid != getpid());
    unsigned parent_private_requests = 0;
    int listener = socket(AF_INET, SOCK_STREAM, 0); assert(listener >= 0);
    struct sockaddr_in address = {0}; address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    assert(bind(listener, (struct sockaddr *)&address, sizeof address) == 0);
    assert(listen(listener, 2) == 0);
    socklen_t address_size = sizeof address;
    assert(getsockname(listener, (struct sockaddr *)&address, &address_size) == 0);
    for (unsigned i = 1; i <= 10; ++i) {
        int client = socket(AF_INET, SOCK_STREAM, 0); assert(client >= 0);
        assert(connect(client, (struct sockaddr *)&address, sizeof address) == 0);
        int owned = accept(listener, NULL, NULL); assert(owned >= 0);
        const char *request = "GET /health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
        write_all(client, request, strlen(request));
        int former = owned;
        assert(ores_mailbox_move_fd(mb[0], &owned, i, ORES_WORKER_TRUSTED, 1000) == 0);
        assert(owned == -1);
        assert(fcntl(former, F_GETFD) == -1 && errno == EBADF);
        char response[1024]; size_t used = 0;
        for (;;) {
            ssize_t n = read(client, response + used, sizeof response - 1 - used);
            if (n < 0 && errno == EINTR) continue;
            assert(n >= 0); if (!n) break; used += (size_t)n; assert(used < sizeof response - 1);
        }
        response[used] = 0;
        assert(strstr(response, "HTTP/1.1 200 OK\r\n"));
        char expected[128]; snprintf(expected, sizeof expected, "worker=%ld request=%u transfer=%u\n", (long)pid, i, i);
        assert(strstr(response, expected));
        unsigned observed; size_t bytes;
        assert(ores_mailbox_receive(mb[0], &observed, sizeof observed, &bytes, 1000) == 0);
        assert(bytes == sizeof observed && observed == i);
        assert(parent_private_requests == 0);
        close(client);
    }
    close(listener); close(mb[0]);
    int status; assert(waitpid(pid, &status, 0) == pid);
    assert(WIFEXITED(status) && WEXITSTATUS(status) == 0);
    puts("PASS: 10 HTTP sockets moved to an exec worker with its own address space");
}
static int open_count(void) {
    int n = 0;
    for (int i = 0; i < 256; ++i) if (fcntl(i, F_GETFD) >= 0) n++;
    return n;
}
static void incomplete_offer(int mailbox, int right, unsigned count) {
    struct { uint32_t magic, kind; uint64_t id; uint32_t size, reserved; } header =
            { 0x4f524553, 2, 99, 0, 0 };
    struct iovec iov = { &header, sizeof header };
    union { struct cmsghdr align; unsigned char bytes[CMSG_SPACE(2 * sizeof(int))]; } control = {0};
    struct msghdr msg = {0}; msg.msg_iov = &iov; msg.msg_iovlen = 1;
    msg.msg_control = control.bytes; msg.msg_controllen = CMSG_SPACE(count * sizeof(int));
    struct cmsghdr *c = CMSG_FIRSTHDR(&msg); c->cmsg_level = SOL_SOCKET; c->cmsg_type = SCM_RIGHTS;
    c->cmsg_len = CMSG_LEN(count * sizeof(int));
    for (unsigned i = 0; i < count; ++i) memcpy((unsigned char *)CMSG_DATA(c) + i * sizeof(int), &right, sizeof right);
    assert(sendmsg(mailbox, &msg, 0) == sizeof header);
}
static void failures(void) {
    int mb[2], pipefd[2]; assert(ores_mailbox_pair(mb) == 0); assert(pipe(pipefd) == 0);
    int original = pipefd[0];
    assert(ores_mailbox_move_fd(mb[0], &pipefd[0], 1, ORES_WORKER_UNTRUSTED, 100) == -1 && errno == EPERM);
    assert(pipefd[0] == original && fcntl(original, F_GETFD) >= 0);
    assert(ores_mailbox_move_fd(mb[0], &pipefd[0], 0, ORES_WORKER_TRUSTED, 100) == -1 && errno == EINVAL);
    int baseline = open_count();
    incomplete_offer(mb[0], original, 1);
    int received = -1; uint64_t id;
    assert(ores_mailbox_receive_fd(mb[1], &received, &id, ORES_WORKER_TRUSTED, 30) == -1 && errno == ETIMEDOUT);
    assert(received == -1 && id == 0 && open_count() == baseline);
    incomplete_offer(mb[0], original, 2);
    assert(ores_mailbox_receive_fd(mb[1], &received, &id, ORES_WORKER_TRUSTED, 100) == -1 && errno == EPROTO);
    assert(open_count() == baseline);
    assert(ores_mailbox_send(mb[0], "bad commit", 10, 100) == 0);
    assert(ores_mailbox_receive_fd(mb[1], &received, &id, ORES_WORKER_TRUSTED, 100) == -1 && errno == EPROTO);
    char small[1]; size_t bytes;
    assert(ores_mailbox_send(mb[0], "large", 5, 100) == 0);
    assert(ores_mailbox_receive(mb[1], small, sizeof small, &bytes, 100) == -1 && errno == EMSGSIZE);
    assert(ores_mailbox_send(mb[0], small, ORES_MAILBOX_MAX_PAYLOAD + 1, 100) == -1 && errno == EINVAL);
    close(mb[1]);
    assert(ores_mailbox_move_fd(mb[0], &pipefd[0], 1, ORES_WORKER_TRUSTED, 100) == -1);
    assert(pipefd[0] == original && fcntl(original, F_GETFD) >= 0);
    close(pipefd[0]); close(pipefd[1]); close(mb[0]);
    puts("PASS: untrusted denial, malformed rights, missing commit, bounds and peer closure");
}
static void affinity(void) {
#ifdef __linux__
    cpu_set_t initial; assert(sched_getaffinity(0, sizeof initial, &initial) == 0);
    int cpu = 0; while (cpu < CPU_SETSIZE && !CPU_ISSET(cpu, &initial)) cpu++;
    assert(cpu < CPU_SETSIZE && ores_carrier_bind_cpu((unsigned)cpu) == 0);
    cpu_set_t actual; assert(sched_getaffinity(0, sizeof actual, &actual) == 0);
    assert(CPU_COUNT(&actual) == 1 && CPU_ISSET(cpu, &actual));
    assert(sched_setaffinity(0, sizeof initial, &initial) == 0);
    puts("PASS: Linux hard affinity mask verified and restored");
#else
    assert(ores_carrier_bind_cpu(0) == -1 && errno == ENOTSUP);
    puts("PASS: hard affinity explicitly unsupported on this platform");
#endif
}
int main(int argc, char **argv) {
    if (argc == 2 && !strcmp(argv[1], "--http-worker")) return worker();
    alarm(30);
    failures(); affinity(); http_handoff(argv[0]);
    return 0;
}
