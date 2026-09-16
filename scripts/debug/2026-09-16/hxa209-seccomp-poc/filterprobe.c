/* Independent child per filter. Exit codes stay within 0..255.
 * Nonzero parent exit means a failed check, not a completed transcript.
 */
#define _GNU_SOURCE
#include "poc-filter.h"
#include <stdio.h>
#include <sys/prctl.h>
#include <sys/wait.h>
#include <unistd.h>
static struct sock_filter allow_k[] = { BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW) };
static struct sock_filter allow_a[] = {
    BPF_STMT(BPF_LD | BPF_W | BPF_IMM, SECCOMP_RET_ALLOW),
    BPF_STMT(BPF_RET | BPF_A, 0),
};
static struct sock_filter invalid[] = { BPF_STMT(BPF_LD | BPF_W | BPF_ABS, 0) };
static int check_socket(int family, int blocked) {
    int fd = socket(family, SOCK_DGRAM, 0);
    int saved = errno;
    if (fd >= 0) close(fd);
    return blocked ? (fd == -1 && saved == EPERM) : fd >= 0;
}
static int run(const char *name, struct sock_filter *filter, unsigned short len, int block, int reject) {
    fflush(NULL);
    pid_t pid = fork();
    if (pid < 0) { perror("fork"); return 1; }
    if (!pid) {
        if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0)) _exit(10);
        struct sock_fprog p = { .len = len, .filter = filter };
        int rc = syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &p);
        if (reject) _exit(rc == -1 && errno == EINVAL ? 0 : 11);
        if (rc) { perror("install"); _exit(12); }
        if (!check_socket(AF_UNIX, 0)) _exit(13);
        if (!check_socket(AF_INET, block)) _exit(14);
        if (!check_socket(AF_INET6, block)) _exit(15);
        if (block) {
            int fd = socket(AF_UNIX, SOCK_STREAM, 0);
            struct sockaddr addr = { .sa_family = AF_UNIX };
            int cr = connect(fd, &addr, sizeof(addr));
            int saved = errno;
            close(fd);
            if (cr != -1 || saved != EPERM) _exit(16);
        }
        _exit(0);
    }
    int status;
    while (waitpid(pid, &status, 0) < 0) { if (errno != EINTR) return 1; }
    int ok = WIFEXITED(status) && WEXITSTATUS(status) == 0;
    printf("%s: %s (wait status=%d)\n", name, ok ? "PASS" : "FAIL", status);
    return !ok;
}
int main(void) {
    int failures = 0;
    failures += run("RET K allow", allow_k, 1, 0, 0);
    failures += run("RET A allow", allow_a, 2, 0, 0);
    failures += run("network filter", network_filter, POC_FILTER_LEN, 1, 0);
    failures += run("invalid control EINVAL", invalid, 1, 0, 1);
    return failures ? 1 : 0;
}
