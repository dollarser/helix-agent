#ifndef HELIX_FD_HYGIENE_H
#define HELIX_FD_HYGIENE_H
#include <dirent.h>
#include <limits.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <unistd.h>
/* Dedicated launcher only: standard streams must not be sockets or devices;
 * discard every other inherited descriptor before untrusted code runs.
 * Not a substitute for preventing later FD receipt or privileged delegation.
 */
static int close_inherited_descriptors(void) {
    for (int fd = 0; fd <= 2; fd++) {
        struct stat st;
        if (fstat(fd, &st)) return -1;
        if (!S_ISREG(st.st_mode) && !S_ISFIFO(st.st_mode)) { errno = EPERM; return -1; }
    }
    DIR *dir = opendir("/proc/self/fd");
    if (!dir) return -1;
    int own = dirfd(dir);
    for (;;) {
        errno = 0;
        struct dirent *entry = readdir(dir);
        if (!entry) {
            int saved = errno;
            closedir(dir);
            errno = saved;
            return saved ? -1 : 0;
        }
        char *end;
        long value = strtol(entry->d_name, &end, 10);
        if (*end || value < 3 || value > INT_MAX || value == own) continue;
        if (close((int)value) && errno != EBADF) {
            int saved = errno; closedir(dir); errno = saved; return -1;
        }
    }
}
#endif
