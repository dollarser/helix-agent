#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#define CHUNK_BYTES 8192
#define MAX_VECTOR 128
#define MAX_STRING 4096
#define MAX_VECTOR_BYTES 65536

static void fail(JNIEnv *env, const char *type, const char *message) {
    jclass cls = (*env)->FindClass(env, type);
    if (cls) (*env)->ThrowNew(env, cls, message);
}
static void io_error(JNIEnv *env, int error) {
    fail(env, "java/io/IOException", strerror(error));
}
static void free_vector(char **values) {
    if (!values) return;
    for (int i = 0; values[i]; i++) free(values[i]);
    free(values);
}
/* All JVM interaction and allocation finish BEFORE fork. UTF-8, not JNI modified UTF-8. */
static char **copy_vector(JNIEnv *env, jobjectArray source, int minimum) {
    if (!source) { fail(env, "java/lang/IllegalArgumentException", "Missing PTY vector"); return NULL; }
    jsize count = (*env)->GetArrayLength(env, source);
    if (count < minimum || count > MAX_VECTOR) {
        fail(env, "java/lang/IllegalArgumentException", "Invalid PTY vector size"); return NULL;
    }
    char **values = calloc((size_t)count + 1, sizeof(char *));
    if (!values) { io_error(env, ENOMEM); return NULL; }
    int total = 0;
    for (int i = 0; i < count; i++) {
        jbyteArray bytes = (*env)->GetObjectArrayElement(env, source, i);
        if (!bytes) { fail(env, "java/lang/IllegalArgumentException", "Missing PTY argument"); goto failed; }
        jsize size = (*env)->GetArrayLength(env, bytes);
        if (size > MAX_STRING || size > MAX_VECTOR_BYTES - total) {
            (*env)->DeleteLocalRef(env, bytes);
            fail(env, "java/lang/IllegalArgumentException", "Oversized PTY arguments"); goto failed;
        }
        total += size;
        values[i] = calloc((size_t)size + 1, 1);
        if (!values[i]) { (*env)->DeleteLocalRef(env, bytes); io_error(env, ENOMEM); goto failed; }
        (*env)->GetByteArrayRegion(env, bytes, 0, size, (jbyte *)values[i]);
        (*env)->DeleteLocalRef(env, bytes);
        if ((*env)->ExceptionCheck(env)) goto failed;
        if (memchr(values[i], 0, (size_t)size)) {
            fail(env, "java/lang/IllegalArgumentException", "NUL in PTY argument"); goto failed;
        }
    }
    return values;
failed:
    free_vector(values);
    return NULL;
}

static void child_exec(int master, int slave, long max_fd, char **argv, char **envp) {
    /* Async-signal-safe operations only: no JVM, logging framework, malloc or stdio. */
    close(master);
    sigset_t empty;
    sigemptyset(&empty);
    sigprocmask(SIG_SETMASK, &empty, NULL);
    struct sigaction reset = {0};
    reset.sa_handler = SIG_DFL;
    sigemptyset(&reset.sa_mask);
    for (int signal = 1; signal < NSIG; signal++) {
        if (signal != SIGKILL && signal != SIGSTOP) sigaction(signal, &reset, NULL);
    }
    if (setsid() < 0 || ioctl(slave, TIOCSCTTY, 0) < 0 ||
        dup2(slave, STDIN_FILENO) < 0 || dup2(slave, STDOUT_FILENO) < 0 || dup2(slave, STDERR_FILENO) < 0) {
        _exit(126);
    }
    /* The slave was opened before fork: no transient master EOF before child setup. */
    for (int fd = 3; fd < max_fd; fd++) close(fd);
    execve(argv[0], argv, envp);
    static const char message[] = "Helix PTY exec failed\r\n";
    (void)write(STDERR_FILENO, message, sizeof(message) - 1);
    _exit(127);
}

JNIEXPORT jintArray JNICALL
Java_com_helix_runtime_proot_app_ProotPtyNative_spawn(
    JNIEnv *env, jobject self, jobjectArray args, jobjectArray environment, jint rows, jint columns) {
    (void)self;
    if (rows < 1 || rows > 512 || columns < 1 || columns > 512) {
        fail(env, "java/lang/IllegalArgumentException", "Invalid PTY dimensions"); return NULL;
    }
    char **argv = copy_vector(env, args, 1);
    if (!argv) return NULL;
    char **envp = copy_vector(env, environment, 0);
    if (!envp) { free_vector(argv); return NULL; }
    jintArray result = (*env)->NewIntArray(env, 2);
    if (!result) { free_vector(argv); free_vector(envp); return NULL; }
    int master = -1, slave = -1;
    long max_fd = sysconf(_SC_OPEN_MAX);
    if (max_fd < 3 || max_fd > 1048576) { errno = EMFILE; goto failed; }
    master = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC | O_NONBLOCK);
    if (master < 0 || grantpt(master) < 0 || unlockpt(master) < 0) goto failed;
    char path[128];
    int name_error = ptsname_r(master, path, sizeof(path));
    if (name_error) { errno = name_error; goto failed; }
    slave = open(path, O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (slave < 0) goto failed;
    struct winsize size = {.ws_row = (unsigned short)rows, .ws_col = (unsigned short)columns};
    if (ioctl(master, TIOCSWINSZ, &size) < 0) goto failed;
    pid_t child = fork();
    if (child < 0) goto failed;
    if (child == 0) child_exec(master, slave, max_fd, argv, envp);
    close(slave);
    free_vector(argv);
    free_vector(envp);
    jint pair[] = {(jint)child, master};
    (*env)->SetIntArrayRegion(env, result, 0, 2, pair);
    return result;
failed: {
    int error = errno;
    if (slave >= 0) close(slave);
    if (master >= 0) close(master);
    free_vector(argv);
    free_vector(envp);
    io_error(env, error);
    return NULL;
}
}

JNIEXPORT jint JNICALL
Java_com_helix_runtime_proot_app_ProotPtyNative_read(JNIEnv *env, jobject self, jint fd, jbyteArray target) {
    (void)self;
    jsize capacity = (*env)->GetArrayLength(env, target);
    if (capacity < 1 || capacity > CHUNK_BYTES) {
        fail(env, "java/lang/IllegalArgumentException", "Invalid PTY read size"); return 0;
    }
    struct pollfd ready = {.fd = fd, .events = POLLIN};
    int result = poll(&ready, 1, 20);
    if (result == 0 || (result < 0 && errno == EINTR)) return 0;
    if (result < 0) { io_error(env, errno); return 0; }
    if (ready.revents & POLLNVAL) { io_error(env, EBADF); return 0; }
    char bytes[CHUNK_BYTES];
    ssize_t size = read(fd, bytes, (size_t)capacity);
    if (size < 0 && (errno == EINTR || errno == EAGAIN)) return 0;
    if (size == 0 || (size < 0 && errno == EIO)) return -1;
    if (size < 0) { io_error(env, errno); return 0; }
    (*env)->SetByteArrayRegion(env, target, 0, (jsize)size, (jbyte *)bytes);
    return (jint)size;
}

JNIEXPORT jint JNICALL
Java_com_helix_runtime_proot_app_ProotPtyNative_write(
    JNIEnv *env, jobject self, jint fd, jbyteArray source, jint offset, jint length) {
    (void)self;
    jsize size = (*env)->GetArrayLength(env, source);
    if (offset < 0 || length < 1 || length > CHUNK_BYTES || offset > size - length) {
        fail(env, "java/lang/IllegalArgumentException", "Invalid PTY write range"); return 0;
    }
    char bytes[CHUNK_BYTES];
    (*env)->GetByteArrayRegion(env, source, offset, length, (jbyte *)bytes);
    if ((*env)->ExceptionCheck(env)) return 0;
    ssize_t written = write(fd, bytes, (size_t)length);
    if (written < 0 && (errno == EINTR || errno == EAGAIN)) return 0;
    if (written < 0) { io_error(env, errno); return 0; }
    return (jint)written;
}

JNIEXPORT void JNICALL
Java_com_helix_runtime_proot_app_ProotPtyNative_resize(
    JNIEnv *env, jobject self, jint fd, jint rows, jint columns) {
    (void)self;
    if (rows < 1 || rows > 512 || columns < 1 || columns > 512) {
        fail(env, "java/lang/IllegalArgumentException", "Invalid PTY dimensions"); return;
    }
    struct winsize size = {.ws_row = (unsigned short)rows, .ws_col = (unsigned short)columns};
    if (ioctl(fd, TIOCSWINSZ, &size) < 0) io_error(env, errno);
}

JNIEXPORT jint JNICALL
Java_com_helix_runtime_proot_app_ProotPtyNative_waitExit(JNIEnv *env, jobject self, jint pid) {
    (void)self;
    if (pid <= 1) { fail(env, "java/lang/IllegalArgumentException", "Invalid PTY pid"); return -1; }
    siginfo_t info = {0};
    int result = waitid(P_PID, (id_t)pid, &info, WEXITED | WNOHANG | WNOWAIT);
    if ((result < 0 && errno == EINTR) || (result == 0 && info.si_pid == 0)) return -1;
    if (result < 0) { io_error(env, errno); return -1; }
    return info.si_code == CLD_EXITED ? info.si_status : 256 + info.si_status;
}

JNIEXPORT void JNICALL
Java_com_helix_runtime_proot_app_ProotPtyNative_killInitialGroup(JNIEnv *env, jobject self, jint pid) {
    (void)self;
    if (pid <= 1) { fail(env, "java/lang/IllegalArgumentException", "Invalid PTY pid"); return; }
    /* Caller owns an UNREAPED child. This is NOT proof that all interactive jobs stopped. */
    if (kill(-pid, SIGKILL) < 0 && errno != ESRCH) { io_error(env, errno); return; }
    if (kill(pid, SIGKILL) < 0 && errno != ESRCH) io_error(env, errno);
}

JNIEXPORT void JNICALL
Java_com_helix_runtime_proot_app_ProotPtyNative_closeMaster(JNIEnv *env, jobject self, jint fd) {
    (void)env;
    (void)self;
    /* Never retry close(EINTR): the descriptor may already have been released. */
    close(fd);
}

JNIEXPORT jint JNICALL
Java_com_helix_runtime_proot_app_ProotPtyNative_foregroundGroup(JNIEnv *env, jobject self, jint fd) {
    (void)self;
    pid_t group = tcgetpgrp(fd);
    if (group < 0) { io_error(env, errno); return -1; }
    return group;
}

JNIEXPORT jint JNICALL
Java_com_helix_runtime_proot_app_ProotPtyNative_reap(JNIEnv *env, jobject self, jint pid) {
    (void)self;
    if (pid <= 1) { fail(env, "java/lang/IllegalArgumentException", "Invalid PTY pid"); return -1; }
    int status;
    pid_t result = waitpid(pid, &status, WNOHANG);
    if (result == 0 || (result < 0 && errno == EINTR)) return -1;
    if (result < 0) { io_error(env, errno); return -1; }
    return WIFEXITED(status) ? WEXITSTATUS(status) : 256 + WTERMSIG(status);
}
