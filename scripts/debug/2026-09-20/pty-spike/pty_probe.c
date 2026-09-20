#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

/* Feasibility probe only. The shell is in a private Android service's UID/domain. */
static char last_output[1024];
static long long monotonic_millis(void) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (long long)now.tv_sec * 1000 + now.tv_nsec / 1000000;
}
static int exchange(int fd, const char *input, const char *expected) {
    size_t size = strlen(input), sent = 0, used = 0;
    char output[32768] = {0};
    while (sent < size) {
        ssize_t n = write(fd, input + sent, size - sent);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return 0;
        sent += (size_t)n;
    }
    const long long deadline = monotonic_millis() + 10000;
    while (monotonic_millis() < deadline) {
        struct pollfd ready = {.fd = fd, .events = POLLIN};
        int result = poll(&ready, 1, 100);
        if (result < 0 && errno == EINTR) continue;
        if (result < 0) return 0;
        if (result == 0) continue;
        ssize_t n = read(fd, output + used, sizeof(output) - used - 1);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return 0;
        used += (size_t)n;
        output[used] = 0;
        snprintf(last_output, sizeof(last_output), "%s", output);
        /* Wait for shell readiness, not merely a foreground child's output. */
        char *marker = strstr(output, expected);
        if (marker && strstr(marker + strlen(expected), "probe> ")) return 1;
        if (used == sizeof(output) - 1) return 0;
    }
    return 0;
}

JNIEXPORT jstring JNICALL
Java_com_helix_spike_termlib_PtyProbeService_runProbe(JNIEnv *env, jclass cls) {
    (void)cls;
    const char *failure = "open PTY";
    char diagnostic[1200];
    int master = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    pid_t child = -1;
    int status = 0;
    if (master < 0) goto done;
    if (grantpt(master) || unlockpt(master)) goto done;
    char slave[256];
    if (ptsname_r(master, slave, sizeof(slave))) goto done;
    struct winsize size = {.ws_row = 24, .ws_col = 80};
    if (ioctl(master, TIOCSWINSZ, &size)) goto done;
    child = fork();
    if (child == 0) {
        /* No JVM, allocation, or logging after fork in the multithreaded host. */
        sigset_t unblocked;
        sigemptyset(&unblocked);
        if (sigprocmask(SIG_SETMASK, &unblocked, NULL)) _exit(125);
        struct sigaction action = {.sa_handler = SIG_DFL};
        sigemptyset(&action.sa_mask);
        const int reset[] = {SIGINT, SIGQUIT, SIGTERM, SIGHUP, SIGCHLD, SIGPIPE,
                             SIGTSTP, SIGTTIN, SIGTTOU};
        for (unsigned i = 0; i < sizeof(reset) / sizeof(reset[0]); i++)
            if (sigaction(reset[i], &action, NULL)) _exit(126);
        if (setsid() < 0) _exit(121);
        int fd = open(slave, O_RDWR);
        if (fd < 0 || ioctl(fd, TIOCSCTTY, 0) < 0) _exit(122);
        for (int target = 0; target <= 2; target++) if (dup2(fd, target) < 0) _exit(123);
        if (fd > 2) close(fd);
        close(master);
        char *const argv[] = {"/system/bin/sh", "-i", NULL};
        char *const vars[] = {"PATH=/system/bin", "TERM=xterm-256color", "PS1=probe> ", NULL};
        execve(argv[0], argv, vars);
        _exit(124);
    }
    failure = "fork";
    if (child < 0) goto done;
    /* Split markers prevent echoed command text from masquerading as shell output. */
    failure = "TTY and persistent state";
    if (!exchange(master, "PS1='probe> '; set +o emacs; set +o vi; test -t 0 && test -t 1 && export PROBE=ready; cd /; printf 'TTY_%s\\n' \"$PROBE\"\n", "TTY_ready\r\n")) goto done;
    failure = "UTF-8 and cwd/env persistence";
    if (!exchange(master, "printf 'STATE_%s_%s_中文\\n' \"$PROBE\" \"$PWD\"\n", "STATE_ready_/_中文\r\n")) goto done;
    failure = "initial dimensions";
    if (!exchange(master, "stty size\n", "24 80\r\n")) goto done;
    size.ws_row = 37; size.ws_col = 101;
    failure = "resize";
    if (ioctl(master, TIOCSWINSZ, &size) || !exchange(master, "stty size\n", "37 101\r\n")) goto done;
    failure = "foreground command ownership";
    const char command[] = "sleep 30\n";
    if (write(master, command, sizeof(command) - 1) != sizeof(command) - 1) goto done;
    pid_t foreground = -1;
    for (int i = 0; i < 100; i++) {
        foreground = tcgetpgrp(master);
        if (foreground > 0 && foreground != child) break;
        usleep(10000);
    }
    if (foreground <= 0 || foreground == child) goto done;
    failure = "Ctrl-C returns shell prompt";
    if (!exchange(master, "\003", "")) goto done;
    failure = "Ctrl-C status and shell survival";
    if (!exchange(master, "printf 'INT_%s_%s\\n' \"$?\" \"$PROBE\"\n", "INT_130_ready\r\n")) goto done;
    failure = "foreground process reaped";
    if (kill(foreground, 0) == 0 || errno != ESRCH) goto done;
    failure = "EOF exit";
    if (write(master, "\004", 1) != 1) goto done;
    for (int i = 0; i < 100; i++) {
        pid_t result = waitpid(child, &status, WNOHANG);
        if (result == child) {
            child = -1;
            if (WIFEXITED(status) && WEXITSTATUS(status) == 0) failure = NULL;
            else { snprintf(diagnostic, sizeof(diagnostic), "EOF wait status=%d", status); failure = diagnostic; }
            goto done;
        }
        if (result < 0 && errno != EINTR) goto done;
        usleep(10000);
    }
    failure = "EOF timeout after 1s";
done:
    if (child > 0) {
        kill(-child, SIGKILL);
        kill(child, SIGKILL);
        while (waitpid(child, &status, 0) < 0 && errno == EINTR) {}
    }
    if (master >= 0) close(master);
    if (failure && failure != diagnostic) {
        snprintf(diagnostic, sizeof(diagnostic), "%s; output=%s", failure, last_output);
        failure = diagnostic;
    }
    return (*env)->NewStringUTF(env, failure ? failure : "OK: tty, UTF-8, cwd/env, resize, Ctrl-C, EOF");
}
