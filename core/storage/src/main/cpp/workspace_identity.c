#include <jni.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <stdio.h>
#include <sys/ioctl.h>
#include <linux/fs.h>

/* Read-only identity query. Never use mtime/ctime as a substitute for birth time. */
JNIEXPORT jstring JNICALL
Java_com_helix_core_storage_WorkspaceNativeIdentity_read(JNIEnv *env, jobject self, jstring path) {
    (void)self;
    const char *name = (*env)->GetStringUTFChars(env, path, NULL);
    if (name == NULL) return NULL;
    /* API29 app seccomp disallows statx. ext4/f2fs inode generation is an immutable
       allocation identity available through an existing read-only directory fd. */
    int fd = open(name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    struct stat legacy = {0};
    int generation = 0;
    if (fd >= 0 && fstat(fd, &legacy) == 0 && ioctl(fd, FS_IOC_GETVERSION, &generation) == 0) {
        close(fd);
        (*env)->ReleaseStringUTFChars(env, path, name);
        char identity[160];
        snprintf(identity, sizeof(identity), "inode-generation:%llu:%llu:%u",
                 (unsigned long long)legacy.st_dev, (unsigned long long)legacy.st_ino,
                 (unsigned)generation);
        return (*env)->NewStringUTF(env, identity);
    }
    if (fd >= 0) close(fd);
    (*env)->ReleaseStringUTFChars(env, path, name);
    jclass error = (*env)->FindClass(env, "java/io/IOException");
    if (error != NULL) (*env)->ThrowNew(env, error, "Directory allocation identity is unavailable");
    return NULL;
}
