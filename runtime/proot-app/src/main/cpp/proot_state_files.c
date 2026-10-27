#define _GNU_SOURCE
#include <jni.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

/* Every descendant is opened relative to a held directory, never through a guest symlink. */
static int remove_tree(int parent, const char *name, unsigned depth, unsigned *budget) {
    struct stat before, opened;
    if (depth > 128 || *budget == 0) return -E2BIG;
    --*budget;
    if (fstatat(parent, name, &before, AT_SYMLINK_NOFOLLOW) != 0) return errno == ENOENT ? 0 : -errno;
    if (!S_ISDIR(before.st_mode)) return unlinkat(parent, name, 0) == 0 ? 0 : -errno;
    int fd = openat(parent, name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) return -errno;
    if (fstat(fd, &opened) != 0 || opened.st_dev != before.st_dev || opened.st_ino != before.st_ino) {
        close(fd); return -ESTALE;
    }
    DIR *directory = fdopendir(fd);
    if (!directory) { int e = errno; close(fd); return -e; }
    int result = 0;
    struct dirent *entry;
    errno = 0;
    while ((entry = readdir(directory)) != NULL) {
        if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, "..")) continue;
        result = remove_tree(fd, entry->d_name, depth + 1, budget);
        if (result != 0) break;
        errno = 0;
    }
    if (!result && errno) result = -errno;
    closedir(directory);
    if (!result && unlinkat(parent, name, AT_REMOVEDIR) != 0 && errno != ENOENT) result = -errno;
    return result;
}

static int restore(int parent, const char *isolated, const char *name) {
    /* linkat does not replace a concurrently created source and does not follow a symlink. */
    if (linkat(parent, isolated, parent, name, 0) != 0) return -errno;
    return unlinkat(parent, isolated, 0) == 0 ? 0 : -errno;
}

static int remove_metadata(int parent, const char *name, const char *isolated, const unsigned char *expected, size_t length) {
    struct stat saved, source;
    if (fstatat(parent, isolated, &saved, AT_SYMLINK_NOFOLLOW) == 0) {
        if (fstatat(parent, name, &source, AT_SYMLINK_NOFOLLOW) == 0) {
            if (saved.st_dev != source.st_dev || saved.st_ino != source.st_ino) return -EEXIST;
            if (unlinkat(parent, isolated, 0) != 0) return -errno;
            if (renameat(parent, name, parent, isolated) != 0) return -errno;
        } else if (errno != ENOENT) return -errno;
    } else {
        if (errno != ENOENT) return -errno;
        if (renameat(parent, name, parent, isolated) != 0) return errno == ENOENT ? 0 : -errno;
    }
    int fd = openat(parent, isolated, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    struct stat status;
    int matched = fd >= 0 && fstat(fd, &status) == 0 && S_ISREG(status.st_mode) && (size_t)status.st_size == length;
    unsigned char buffer[32768];
    size_t count = 0;
    while (matched && count < length) {
        ssize_t got = read(fd, buffer + count, length - count);
        if (got < 0 && errno == EINTR) continue;
        if (got <= 0) { matched = 0; break; }
        count += (size_t)got;
    }
    if (fd >= 0) close(fd);
    if (!matched || memcmp(buffer, expected, length)) {
        int result = restore(parent, isolated, name);
        return result ? result : 1;
    }
    return unlinkat(parent, isolated, 0) == 0 ? 0 : -errno;
}

static int remove_relative(const char *root, const char *relative, const char *isolated,
                           const unsigned char *expected, size_t length) {
    if (!relative[0] || relative[0] == '/' || strlen(relative) > 4096) return -EINVAL;
    int parent = open(root, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (parent < 0) return errno == ENOENT ? 0 : -errno;
    char *copy = strdup(relative);
    if (!copy) { close(parent); return -ENOMEM; }
    char *part = copy;
    int result = 0;
    for (;;) {
        char *slash = strchr(part, '/');
        if (slash) *slash = '\0';
        if (!part[0] || !strcmp(part, ".") || !strcmp(part, "..")) { result = -EINVAL; break; }
        if (!slash) {
            unsigned budget = 10000;
            result = expected ? remove_metadata(parent, part, isolated, expected, length)
                              : remove_tree(parent, part, 0, &budget);
            break;
        }
        int next = openat(parent, part, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        if (next < 0) { result = errno == ENOENT ? 0 : -errno; break; }
        close(parent); parent = next; part = slash + 1;
    }
    free(copy); close(parent); return result;
}

JNIEXPORT jint JNICALL Java_com_helix_runtime_proot_core_PtyStateNative_removeNative(
    JNIEnv *env, jobject self, jstring root, jstring relative, jbyteArray expected, jstring isolated) {
    (void)self;
    const char *r = (*env)->GetStringUTFChars(env, root, NULL);
    if (!r) return -ENOMEM;
    const char *p = (*env)->GetStringUTFChars(env, relative, NULL);
    if (!p) { (*env)->ReleaseStringUTFChars(env, root, r); return -ENOMEM; }
    const char *q = (*env)->GetStringUTFChars(env, isolated, NULL);
    if (!q) { (*env)->ReleaseStringUTFChars(env, relative, p); (*env)->ReleaseStringUTFChars(env, root, r); return -ENOMEM; }
    jbyte *bytes = expected ? (*env)->GetByteArrayElements(env, expected, NULL) : NULL;
    jsize size = expected ? (*env)->GetArrayLength(env, expected) : 0;
    int result = -EINVAL;
    if (size <= 32768 && (!expected || bytes) && !strchr(q, '/') && strcmp(q, ".") && strcmp(q, "..")) {
        result = remove_relative(r, p, q, (unsigned char *)bytes, (size_t)size);
    }
    if (bytes) (*env)->ReleaseByteArrayElements(env, expected, bytes, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, isolated, q);
    (*env)->ReleaseStringUTFChars(env, relative, p);
    (*env)->ReleaseStringUTFChars(env, root, r);
    return result;
}
