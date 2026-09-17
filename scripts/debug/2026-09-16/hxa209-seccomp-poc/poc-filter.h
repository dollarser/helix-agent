/* Diagnostic candidate, not a complete network isolation boundary.
 * Use NDK Linux UAPI directly; never redeclare its layouts or opcodes.
 */
#ifndef HELIX_POC_FILTER_H
#define HELIX_POC_FILTER_H
#include <errno.h>
#include <stddef.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <sys/socket.h>
#include <sys/syscall.h>
_Static_assert(sizeof(struct sock_filter) == 8, "sock_filter ABI");
_Static_assert(offsetof(struct sock_filter, k) == 4, "k offset");
_Static_assert(sizeof(struct seccomp_data) == 64, "seccomp_data ABI");
_Static_assert(BPF_W == 0 && BPF_A == 0x10, "BPF encoding");
#if defined(__aarch64__)
#define POC_ARCH AUDIT_ARCH_AARCH64
_Static_assert(SYS_seccomp == 277 && SYS_socket == 198 && SYS_connect == 203, "arm64 syscall ABI");
#elif defined(__x86_64__)
#define POC_ARCH AUDIT_ARCH_X86_64
_Static_assert(SYS_seccomp == 317 && SYS_socket == 41 && SYS_connect == 42, "x86_64 syscall ABI");
#else
#error Unsupported PoC ABI
#endif
/* Reject other ABIs, then block new INET sockets and all connect calls.
 * AF_UNIX creation works; AF_UNIX connect deliberately does not.
 * This does NOT cover inherited/received socket FDs, sendto/sendmsg,
 * io_uring, Binder/local proxies, or same-UID process interference.
 */
static struct sock_filter network_filter[] = {
    BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, POC_ARCH, 1, 0),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
    BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_socket, 0, 3),
    BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0])),
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AF_INET, 2, 0),
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AF_INET6, 1, 2),
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_connect, 0, 1),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
};
#define POC_FILTER_LEN (sizeof(network_filter) / sizeof(network_filter[0]))
#endif
