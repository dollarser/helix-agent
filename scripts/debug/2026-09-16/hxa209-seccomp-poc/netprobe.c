/*
 * HXA-209 Phase A PoC: netprobe (STATIC; must run inside the alpine guest).
 *
 * Minimal inet probe. Expected outcomes:
 *   baseline (unrestricted guest):
 *     udp4  connect -> OK
 *     tcp4  connect -> ECONNREFUSED (111) when nothing listens: the socket
 *           path works end to end, the port is just closed
 *     inet6 socket  -> OK (fd)
 *     unix  socket  -> OK
 *   under the seccomp guard:
 *     udp4  socket  -> EPERM (1)   (blocked at socket())
 *     tcp4  socket  -> EPERM (1)
 *     inet6 socket  -> EPERM (1)
 *     unix  socket  -> OK          (AF_UNIX must keep working)
 */
#define _GNU_SOURCE
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <unistd.h>

static void probe_udp4(int port) {
    int fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (fd < 0) {
        printf("udp4  socket: errno=%d (%s)\n", errno, strerror(errno));
        return;
    }
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons((unsigned short)port);
    addr.sin_addr.s_addr = 0x0100007f; /* 127.0.0.1 network order */
    if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) != 0) {
        printf("udp4  connect: errno=%d (%s)\n", errno, strerror(errno));
    } else {
        printf("udp4  connect: OK\n");
    }
    close(fd);
}

static void probe_tcp4(int port) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) {
        printf("tcp4  socket: errno=%d (%s)\n", errno, strerror(errno));
        return;
    }
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons((unsigned short)port);
    addr.sin_addr.s_addr = 0x0100007f;
    if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) != 0) {
        printf("tcp4  connect: errno=%d (%s)\n", errno, strerror(errno));
    } else {
        printf("tcp4  connect: OK\n");
    }
    close(fd);
}

static void probe_inet6_socket(void) {
    int fd = socket(AF_INET6, SOCK_STREAM, 0);
    if (fd < 0) {
        printf("inet6 socket: errno=%d (%s)\n", errno, strerror(errno));
    } else {
        printf("inet6 socket: OK\n");
        close(fd);
    }
}

static void probe_unix_socket(void) {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        printf("unix  socket: errno=%d (%s)\n", errno, strerror(errno));
    } else {
        printf("unix  socket: OK\n");
        close(fd);
    }
}

int main(int argc, char **argv) {
    int port = 8977;
    if (argc > 1) port = atoi(argv[1]);
    probe_udp4(port);
    probe_tcp4(port);
    probe_inet6_socket();
    probe_unix_socket();
    return 0;
}
