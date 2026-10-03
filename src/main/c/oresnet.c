#define _POSIX_C_SOURCE 200112L

#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <netinet/tcp.h>

#ifndef MSG_NOSIGNAL
#define MSG_NOSIGNAL 0
#endif

static int as_fd(jlong value) {
    if (value < 0 || value > INT32_MAX) {
        errno = EBADF;
        return -1;
    }
    return (int)value;
}

static void throw_with_message(JNIEnv *env, const char *message) {
    jclass cls = (*env)->FindClass(env, "java/io/IOException");
    if (cls != NULL) {
        (*env)->ThrowNew(env, cls, message);
    }
}

static void throw_errno(JNIEnv *env, const char *operation) {
    char message[512];
    int error = errno;
    snprintf(message, sizeof(message), "%s: %s", operation, strerror(error));
    throw_with_message(env, message);
}

static int validate_port(JNIEnv *env, jint port) {
    if (port < 0 || port > 65535) {
        throw_with_message(env, "port must be between 0 and 65535");
        return 0;
    }
    return 1;
}

static int connect_one(int fd, const struct sockaddr *address, socklen_t length, jint timeout_ms) {
    if (timeout_ms <= 0) {
        while (connect(fd, address, length) < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        return 0;
    }

    int original_flags = fcntl(fd, F_GETFL, 0);
    if (original_flags < 0) return -1;
    if (fcntl(fd, F_SETFL, original_flags | O_NONBLOCK) < 0) return -1;

    int result = connect(fd, address, length);
    if (result == 0) {
        (void)fcntl(fd, F_SETFL, original_flags);
        return 0;
    }
    if (errno != EINPROGRESS) {
        int saved = errno;
        (void)fcntl(fd, F_SETFL, original_flags);
        errno = saved;
        return -1;
    }

    struct pollfd poll_fd;
    memset(&poll_fd, 0, sizeof(poll_fd));
    poll_fd.fd = fd;
    poll_fd.events = POLLOUT;

    do {
        result = poll(&poll_fd, 1, timeout_ms);
    } while (result < 0 && errno == EINTR);

    if (result == 0) {
        (void)fcntl(fd, F_SETFL, original_flags);
        errno = ETIMEDOUT;
        return -1;
    }
    if (result < 0) {
        int saved = errno;
        (void)fcntl(fd, F_SETFL, original_flags);
        errno = saved;
        return -1;
    }

    int socket_error = 0;
    socklen_t error_length = sizeof(socket_error);
    if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &socket_error, &error_length) < 0) {
        int saved = errno;
        (void)fcntl(fd, F_SETFL, original_flags);
        errno = saved;
        return -1;
    }

    (void)fcntl(fd, F_SETFL, original_flags);
    if (socket_error != 0) {
        errno = socket_error;
        return -1;
    }
    return 0;
}

JNIEXPORT jlong JNICALL
Java_dev_oreslang_net_NativeSocketBridge_connect(
        JNIEnv *env, jclass cls, jstring host, jint port, jint timeout_ms) {
    (void)cls;
    if (host == NULL) {
        throw_with_message(env, "host cannot be null");
        return -1;
    }
    if (!validate_port(env, port)) return -1;

    const char *host_chars = (*env)->GetStringUTFChars(env, host, NULL);
    if (host_chars == NULL) return -1;

    char service[16];
    snprintf(service, sizeof(service), "%d", (int)port);

    struct addrinfo hints;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_protocol = IPPROTO_TCP;

    struct addrinfo *addresses = NULL;
    int gai = getaddrinfo(host_chars, service, &hints, &addresses);
    (*env)->ReleaseStringUTFChars(env, host, host_chars);

    if (gai != 0) {
        char message[512];
        snprintf(message, sizeof(message), "getaddrinfo: %s", gai_strerror(gai));
        throw_with_message(env, message);
        return -1;
    }

    int fd = -1;
    int last_error = ECONNREFUSED;
    for (struct addrinfo *it = addresses; it != NULL; it = it->ai_next) {
        fd = socket(it->ai_family, it->ai_socktype, it->ai_protocol);
        if (fd < 0) {
            last_error = errno;
            continue;
        }
        if (connect_one(fd, it->ai_addr, (socklen_t)it->ai_addrlen, timeout_ms) == 0) {
            freeaddrinfo(addresses);
            return (jlong)fd;
        }
        last_error = errno;
        close(fd);
        fd = -1;
    }

    freeaddrinfo(addresses);
    errno = last_error;
    throw_errno(env, "connect");
    return -1;
}

JNIEXPORT jlong JNICALL
Java_dev_oreslang_net_NativeSocketBridge_listen(
        JNIEnv *env, jclass cls, jstring host, jint port, jint backlog, jboolean reuse_address) {
    (void)cls;
    if (!validate_port(env, port)) return -1;
    if (backlog <= 0) backlog = 50;

    const char *host_chars = NULL;
    const char *host_query = NULL;
    if (host != NULL) {
        host_chars = (*env)->GetStringUTFChars(env, host, NULL);
        if (host_chars == NULL) return -1;
        host_query = host_chars[0] == '\0' ? NULL : host_chars;
    }

    char service[16];
    snprintf(service, sizeof(service), "%d", (int)port);

    struct addrinfo hints;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_protocol = IPPROTO_TCP;
    hints.ai_flags = AI_PASSIVE;

    struct addrinfo *addresses = NULL;
    int gai = getaddrinfo(host_query, service, &hints, &addresses);

    if (host_chars != NULL) {
        (*env)->ReleaseStringUTFChars(env, host, host_chars);
    }

    if (gai != 0) {
        char message[512];
        snprintf(message, sizeof(message), "getaddrinfo: %s", gai_strerror(gai));
        throw_with_message(env, message);
        return -1;
    }

    int fd = -1;
    int last_error = EADDRNOTAVAIL;
    for (struct addrinfo *it = addresses; it != NULL; it = it->ai_next) {
        fd = socket(it->ai_family, it->ai_socktype, it->ai_protocol);
        if (fd < 0) {
            last_error = errno;
            continue;
        }

        int one = reuse_address ? 1 : 0;
        if (setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one)) < 0) {
            last_error = errno;
            close(fd);
            fd = -1;
            continue;
        }

        if (bind(fd, it->ai_addr, (socklen_t)it->ai_addrlen) == 0
                && listen(fd, backlog) == 0) {
            freeaddrinfo(addresses);
            return (jlong)fd;
        }

        last_error = errno;
        close(fd);
        fd = -1;
    }

    freeaddrinfo(addresses);
    errno = last_error;
    throw_errno(env, "bind/listen");
    return -1;
}

JNIEXPORT jlong JNICALL
Java_dev_oreslang_net_NativeSocketBridge_accept(JNIEnv *env, jclass cls, jlong raw_fd) {
    (void)cls;
    int fd = as_fd(raw_fd);
    if (fd < 0) {
        throw_errno(env, "accept");
        return -1;
    }

    int accepted;
    do {
        accepted = accept(fd, NULL, NULL);
    } while (accepted < 0 && errno == EINTR);

    if (accepted < 0) {
        throw_errno(env, "accept");
        return -1;
    }
    return (jlong)accepted;
}

static int validate_array_range(JNIEnv *env, jbyteArray bytes, jint offset, jint length) {
    if (bytes == NULL) {
        throw_with_message(env, "byte array cannot be null");
        return 0;
    }
    jsize size = (*env)->GetArrayLength(env, bytes);
    if (offset < 0 || length < 0 || offset > size || length > size - offset) {
        throw_with_message(env, "byte array range is out of bounds");
        return 0;
    }
    return 1;
}

JNIEXPORT jint JNICALL
Java_dev_oreslang_net_NativeSocketBridge_read(
        JNIEnv *env, jclass cls, jlong raw_fd, jbyteArray bytes, jint offset, jint length) {
    (void)cls;
    int fd = as_fd(raw_fd);
    if (fd < 0) {
        throw_errno(env, "recv");
        return -1;
    }
    if (!validate_array_range(env, bytes, offset, length)) return -1;
    if (length == 0) return 0;

    jbyte *buffer = (*env)->GetByteArrayElements(env, bytes, NULL);
    if (buffer == NULL) return -1;

    ssize_t count;
    do {
        count = recv(fd, buffer + offset, (size_t)length, 0);
    } while (count < 0 && errno == EINTR);

    if (count < 0) {
        int saved = errno;
        (*env)->ReleaseByteArrayElements(env, bytes, buffer, JNI_ABORT);
        errno = saved;
        throw_errno(env, "recv");
        return -1;
    }

    (*env)->ReleaseByteArrayElements(env, bytes, buffer, 0);
    return count == 0 ? -1 : (jint)count;
}

JNIEXPORT jint JNICALL
Java_dev_oreslang_net_NativeSocketBridge_write(
        JNIEnv *env, jclass cls, jlong raw_fd, jbyteArray bytes, jint offset, jint length) {
    (void)cls;
    int fd = as_fd(raw_fd);
    if (fd < 0) {
        throw_errno(env, "send");
        return -1;
    }
    if (!validate_array_range(env, bytes, offset, length)) return -1;
    if (length == 0) return 0;

    jbyte *buffer = (*env)->GetByteArrayElements(env, bytes, NULL);
    if (buffer == NULL) return -1;

    ssize_t count;
    do {
        count = send(fd, buffer + offset, (size_t)length, MSG_NOSIGNAL);
    } while (count < 0 && errno == EINTR);

    if (count < 0) {
        int saved = errno;
        (*env)->ReleaseByteArrayElements(env, bytes, buffer, JNI_ABORT);
        errno = saved;
        throw_errno(env, "send");
        return -1;
    }

    (*env)->ReleaseByteArrayElements(env, bytes, buffer, JNI_ABORT);
    return (jint)count;
}

JNIEXPORT jint JNICALL
Java_dev_oreslang_net_NativeSocketBridge_available(JNIEnv *env, jclass cls, jlong raw_fd) {
    (void)cls;
    int fd = as_fd(raw_fd);
    int count = 0;
    if (fd < 0 || ioctl(fd, FIONREAD, &count) < 0) {
        throw_errno(env, "ioctl(FIONREAD)");
        return -1;
    }
    return (jint)count;
}

JNIEXPORT void JNICALL
Java_dev_oreslang_net_NativeSocketBridge_shutdownInput(JNIEnv *env, jclass cls, jlong raw_fd) {
    (void)cls;
    int fd = as_fd(raw_fd);
    if (fd < 0 || shutdown(fd, SHUT_RD) < 0) {
        if (errno != ENOTCONN) throw_errno(env, "shutdown(input)");
    }
}

JNIEXPORT void JNICALL
Java_dev_oreslang_net_NativeSocketBridge_shutdownOutput(JNIEnv *env, jclass cls, jlong raw_fd) {
    (void)cls;
    int fd = as_fd(raw_fd);
    if (fd < 0 || shutdown(fd, SHUT_WR) < 0) {
        if (errno != ENOTCONN) throw_errno(env, "shutdown(output)");
    }
}

JNIEXPORT void JNICALL
Java_dev_oreslang_net_NativeSocketBridge_close(JNIEnv *env, jclass cls, jlong raw_fd) {
    (void)cls;
    int fd = as_fd(raw_fd);
    if (fd < 0) return;
    while (close(fd) < 0) {
        if (errno == EINTR) continue;
        throw_errno(env, "close");
        return;
    }
}

static int socket_name(JNIEnv *env, int fd, int peer, char *host, size_t host_len, int *port) {
    struct sockaddr_storage address;
    socklen_t address_len = sizeof(address);
    int result = peer
            ? getpeername(fd, (struct sockaddr *)&address, &address_len)
            : getsockname(fd, (struct sockaddr *)&address, &address_len);
    if (result < 0) {
        throw_errno(env, peer ? "getpeername" : "getsockname");
        return 0;
    }

    char service[32];
    int gai = getnameinfo(
            (struct sockaddr *)&address, address_len,
            host, (socklen_t)host_len,
            service, sizeof(service),
            NI_NUMERICHOST | NI_NUMERICSERV);
    if (gai != 0) {
        char message[512];
        snprintf(message, sizeof(message), "getnameinfo: %s", gai_strerror(gai));
        throw_with_message(env, message);
        return 0;
    }
    *port = atoi(service);
    return 1;
}

JNIEXPORT jstring JNICALL
Java_dev_oreslang_net_NativeSocketBridge_remoteAddress(JNIEnv *env, jclass cls, jlong raw_fd) {
    (void)cls;
    int fd = as_fd(raw_fd);
    char host[NI_MAXHOST];
    int port;
    if (fd < 0 || !socket_name(env, fd, 1, host, sizeof(host), &port)) return NULL;
    return (*env)->NewStringUTF(env, host);
}

JNIEXPORT jstring JNICALL
Java_dev_oreslang_net_NativeSocketBridge_localAddress(JNIEnv *env, jclass cls, jlong raw_fd) {
    (void)cls;
    int fd = as_fd(raw_fd);
    char host[NI_MAXHOST];
    int port;
    if (fd < 0 || !socket_name(env, fd, 0, host, sizeof(host), &port)) return NULL;
    return (*env)->NewStringUTF(env, host);
}

JNIEXPORT jint JNICALL
Java_dev_oreslang_net_NativeSocketBridge_remotePort(JNIEnv *env, jclass cls, jlong raw_fd) {
    (void)cls;
    int fd = as_fd(raw_fd);
    char host[NI_MAXHOST];
    int port;
    if (fd < 0 || !socket_name(env, fd, 1, host, sizeof(host), &port)) return -1;
    return (jint)port;
}

JNIEXPORT jint JNICALL
Java_dev_oreslang_net_NativeSocketBridge_localPort(JNIEnv *env, jclass cls, jlong raw_fd) {
    (void)cls;
    int fd = as_fd(raw_fd);
    char host[NI_MAXHOST];
    int port;
    if (fd < 0 || !socket_name(env, fd, 0, host, sizeof(host), &port)) return -1;
    return (jint)port;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_oreslang_net_NativeSocketBridge_resolveAll(
        JNIEnv *env, jclass cls, jstring host) {
    (void)cls;
    if (host == NULL) {
        throw_with_message(env, "host cannot be null");
        return NULL;
    }

    const char *host_chars = (*env)->GetStringUTFChars(env, host, NULL);
    if (host_chars == NULL) return NULL;

    struct addrinfo hints;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;

    struct addrinfo *addresses = NULL;
    int gai = getaddrinfo(host_chars, NULL, &hints, &addresses);
    (*env)->ReleaseStringUTFChars(env, host, host_chars);
    if (gai != 0) {
        char message[512];
        snprintf(message, sizeof(message), "getaddrinfo: %s", gai_strerror(gai));
        throw_with_message(env, message);
        return NULL;
    }

    int count = 0;
    for (struct addrinfo *it = addresses; it != NULL; it = it->ai_next) count++;

    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    if (string_class == NULL) {
        freeaddrinfo(addresses);
        return NULL;
    }

    jobjectArray result = (*env)->NewObjectArray(env, count, string_class, NULL);
    if (result == NULL) {
        freeaddrinfo(addresses);
        return NULL;
    }

    int index = 0;
    for (struct addrinfo *it = addresses; it != NULL; it = it->ai_next) {
        char numeric[NI_MAXHOST];
        int info = getnameinfo(
                it->ai_addr, (socklen_t)it->ai_addrlen,
                numeric, sizeof(numeric),
                NULL, 0, NI_NUMERICHOST);
        if (info != 0) {
            freeaddrinfo(addresses);
            char message[512];
            snprintf(message, sizeof(message), "getnameinfo: %s", gai_strerror(info));
            throw_with_message(env, message);
            return NULL;
        }
        jstring value = (*env)->NewStringUTF(env, numeric);
        (*env)->SetObjectArrayElement(env, result, index++, value);
        (*env)->DeleteLocalRef(env, value);
    }

    freeaddrinfo(addresses);
    return result;
}

static void set_bool_option(JNIEnv *env, int fd, int level, int option, jboolean enabled, const char *name) {
    int value = enabled ? 1 : 0;
    if (setsockopt(fd, level, option, &value, sizeof(value)) < 0) throw_errno(env, name);
}

static jboolean get_bool_option(JNIEnv *env, int fd, int level, int option, const char *name) {
    int value = 0;
    socklen_t length = sizeof(value);
    if (getsockopt(fd, level, option, &value, &length) < 0) {
        throw_errno(env, name);
        return JNI_FALSE;
    }
    return value ? JNI_TRUE : JNI_FALSE;
}

static void set_int_option(JNIEnv *env, int fd, int level, int option, jint value, const char *name) {
    int native_value = value;
    if (setsockopt(fd, level, option, &native_value, sizeof(native_value)) < 0) throw_errno(env, name);
}

static jint get_int_option(JNIEnv *env, int fd, int level, int option, const char *name) {
    int value = 0;
    socklen_t length = sizeof(value);
    if (getsockopt(fd, level, option, &value, &length) < 0) {
        throw_errno(env, name);
        return -1;
    }
    return (jint)value;
}

JNIEXPORT void JNICALL
Java_dev_oreslang_net_NativeSocketBridge_setTcpNoDelay(JNIEnv *env, jclass cls, jlong raw_fd, jboolean enabled) {
    (void)cls;
    set_bool_option(env, as_fd(raw_fd), IPPROTO_TCP, TCP_NODELAY, enabled, "setsockopt(TCP_NODELAY)");
}

JNIEXPORT jboolean JNICALL
Java_dev_oreslang_net_NativeSocketBridge_getTcpNoDelay(JNIEnv *env, jclass cls, jlong raw_fd) {
    (void)cls;
    return get_bool_option(env, as_fd(raw_fd), IPPROTO_TCP, TCP_NODELAY, "getsockopt(TCP_NODELAY)");
}

JNIEXPORT void JNICALL
Java_dev_oreslang_net_NativeSocketBridge_setKeepAlive(JNIEnv *env, jclass cls, jlong raw_fd, jboolean enabled) {
    (void)cls;
    set_bool_option(env, as_fd(raw_fd), SOL_SOCKET, SO_KEEPALIVE, enabled, "setsockopt(SO_KEEPALIVE)");
}

JNIEXPORT jboolean JNICALL
Java_dev_oreslang_net_NativeSocketBridge_getKeepAlive(JNIEnv *env, jclass cls, jlong raw_fd) {
    (void)cls;
    return get_bool_option(env, as_fd(raw_fd), SOL_SOCKET, SO_KEEPALIVE, "getsockopt(SO_KEEPALIVE)");
}

JNIEXPORT void JNICALL
Java_dev_oreslang_net_NativeSocketBridge_setReuseAddress(JNIEnv *env, jclass cls, jlong raw_fd, jboolean enabled) {
    (void)cls;
    set_bool_option(env, as_fd(raw_fd), SOL_SOCKET, SO_REUSEADDR, enabled, "setsockopt(SO_REUSEADDR)");
}

JNIEXPORT jboolean JNICALL
Java_dev_oreslang_net_NativeSocketBridge_getReuseAddress(JNIEnv *env, jclass cls, jlong raw_fd) {
    (void)cls;
    return get_bool_option(env, as_fd(raw_fd), SOL_SOCKET, SO_REUSEADDR, "getsockopt(SO_REUSEADDR)");
}

JNIEXPORT void JNICALL
Java_dev_oreslang_net_NativeSocketBridge_setReceiveBufferSize(JNIEnv *env, jclass cls, jlong raw_fd, jint bytes) {
    (void)cls;
    set_int_option(env, as_fd(raw_fd), SOL_SOCKET, SO_RCVBUF, bytes, "setsockopt(SO_RCVBUF)");
}

JNIEXPORT jint JNICALL
Java_dev_oreslang_net_NativeSocketBridge_getReceiveBufferSize(JNIEnv *env, jclass cls, jlong raw_fd) {
    (void)cls;
    return get_int_option(env, as_fd(raw_fd), SOL_SOCKET, SO_RCVBUF, "getsockopt(SO_RCVBUF)");
}

JNIEXPORT void JNICALL
Java_dev_oreslang_net_NativeSocketBridge_setSendBufferSize(JNIEnv *env, jclass cls, jlong raw_fd, jint bytes) {
    (void)cls;
    set_int_option(env, as_fd(raw_fd), SOL_SOCKET, SO_SNDBUF, bytes, "setsockopt(SO_SNDBUF)");
}

JNIEXPORT jint JNICALL
Java_dev_oreslang_net_NativeSocketBridge_getSendBufferSize(JNIEnv *env, jclass cls, jlong raw_fd) {
    (void)cls;
    return get_int_option(env, as_fd(raw_fd), SOL_SOCKET, SO_SNDBUF, "getsockopt(SO_SNDBUF)");
}

JNIEXPORT void JNICALL
Java_dev_oreslang_net_NativeSocketBridge_setSoTimeout(JNIEnv *env, jclass cls, jlong raw_fd, jint timeout_ms) {
    (void)cls;
    if (timeout_ms < 0) {
        throw_with_message(env, "SO_TIMEOUT cannot be negative");
        return;
    }
    struct timeval value;
    value.tv_sec = timeout_ms / 1000;
    value.tv_usec = (timeout_ms % 1000) * 1000;
    int fd = as_fd(raw_fd);
    if (setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &value, sizeof(value)) < 0) {
        throw_errno(env, "setsockopt(SO_RCVTIMEO)");
    }
}

JNIEXPORT jint JNICALL
Java_dev_oreslang_net_NativeSocketBridge_getSoTimeout(JNIEnv *env, jclass cls, jlong raw_fd) {
    (void)cls;
    struct timeval value;
    socklen_t length = sizeof(value);
    int fd = as_fd(raw_fd);
    if (getsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &value, &length) < 0) {
        throw_errno(env, "getsockopt(SO_RCVTIMEO)");
        return -1;
    }
    long millis = value.tv_sec * 1000L + value.tv_usec / 1000L;
    return (jint)millis;
}
