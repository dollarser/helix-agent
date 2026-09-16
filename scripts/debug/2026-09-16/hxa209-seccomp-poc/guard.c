/* HXA-209 candidate only: install before exec, inherited by descendants.
 * See poc-filter.h for deliberately unproven bypass surfaces.
 */
#define _GNU_SOURCE
#include "poc-filter.h"
#include "fd-hygiene.h"
#include <stdio.h>
#include <sys/prctl.h>
#include <unistd.h>
int main(int argc, char **argv) {
    if (argc < 2) { fprintf(stderr, "usage: guard CMD [ARGS...]\n"); return 2; }
    if (close_inherited_descriptors()) { perror("unsafe inherited descriptors"); return 1; }
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0)) { perror("no_new_privs"); return 1; }
    struct sock_fprog p = { .len = POC_FILTER_LEN, .filter = network_filter };
    if (syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &p)) { perror("seccomp"); return 1; }
    int u = socket(AF_UNIX, SOCK_STREAM, 0);
    if (u < 0) { perror("unix selftest"); return 1; }
    close(u);
    int n = socket(AF_INET, SOCK_DGRAM, 0);
    if (n >= 0 || errno != EPERM) {
        if (n >= 0) close(n);
        fprintf(stderr, "inet selftest failed; refusing exec\n"); return 1;
    }
    execvp(argv[1], argv + 1);
    perror("execvp"); return 1;
}
