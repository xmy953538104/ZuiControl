#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#ifndef ZUI_UPERF_PATH
#define ZUI_UPERF_PATH "/system/bin/uperf"
#endif

static void log_error(const char *format, ...) {
    va_list args;

    va_start(args, format);
    fputs("zui_uperf_supervisor: ", stderr);
    vfprintf(stderr, format, args);
    fputc('\n', stderr);
    va_end(args);
}

static void write_best_effort(int fd, const void *buffer, size_t length) {
    const unsigned char *cursor = buffer;

    while (length > 0) {
        ssize_t written = write(fd, cursor, length);

        if (written > 0) {
            cursor += written;
            length -= (size_t)written;
            continue;
        }
        if (written < 0 && errno == EINTR) {
            continue;
        }
        return;
    }
}

static void notify_startup_fifo(const char *path, const char *event, int detail) {
    char line[160];
    int length = snprintf(line, sizeof(line), "%s detail=%d\n", event, detail);
    int fd;

    if (length <= 0 || (size_t)length >= sizeof(line)) {
        return;
    }
    fd = open(path, O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) {
        return;
    }
    write_best_effort(fd, line, (size_t)length);
    (void)close(fd);
}

static int set_cloexec(int fd) {
    int flags = fcntl(fd, F_GETFD);

    if (flags < 0) {
        return -1;
    }
    return fcntl(fd, F_SETFD, flags | FD_CLOEXEC);
}

static void handle_usr1(int signal_number) {
    (void)signal_number;
}

static const char *uperf_path(void) {
#ifdef ZUI_SUPERVISOR_TESTING
    const char *override = getenv("ZUI_UPERF_TEST_BINARY");

    if (override != NULL && override[0] != '\0') {
        return override;
    }
#endif
    return ZUI_UPERF_PATH;
}

int main(int argc, char **argv) {
    const char *binary;
    const char *config;
    const char *log_pipe;
    struct sigaction action = {0};
    int exec_status[2];
    pid_t child;
    int exec_errno = 0;
    ssize_t exec_read;

    if (argc != 3) {
        log_error("usage: %s <config> <log-pipe>", argv[0]);
        return 64;
    }
    config = argv[1];
    log_pipe = argv[2];
    binary = uperf_path();

    action.sa_handler = handle_usr1;
    sigemptyset(&action.sa_mask);
    if (sigaction(SIGUSR1, &action, NULL) < 0) {
        log_error("sigaction(SIGUSR1) failed: %s", strerror(errno));
        return 70;
    }
    if (prctl(PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0) < 0) {
        int saved_errno = errno;
        notify_startup_fifo(log_pipe, "ZUI_UPERF_SUPERVISOR_SUBREAPER_FAILED", saved_errno);
        log_error("PR_SET_CHILD_SUBREAPER failed: %s", strerror(saved_errno));
        return 70;
    }
    if (pipe(exec_status) < 0 || set_cloexec(exec_status[0]) < 0 ||
            set_cloexec(exec_status[1]) < 0) {
        int saved_errno = errno;
        notify_startup_fifo(log_pipe, "ZUI_UPERF_SUPERVISOR_PIPE_FAILED", saved_errno);
        log_error("exec status pipe failed: %s", strerror(saved_errno));
        return 70;
    }

    child = fork();
    if (child < 0) {
        int saved_errno = errno;
        notify_startup_fifo(log_pipe, "ZUI_UPERF_SUPERVISOR_FORK_FAILED", saved_errno);
        log_error("fork failed: %s", strerror(saved_errno));
        return 70;
    }
    if (child == 0) {
        int saved_errno;

        (void)close(exec_status[0]);
        execl(binary, binary, config, "-o", log_pipe, (char *)NULL);
        saved_errno = errno;
        write_best_effort(exec_status[1], &saved_errno, sizeof(saved_errno));
        _exit(127);
    }

    (void)close(exec_status[1]);
    do {
        exec_read = read(exec_status[0], &exec_errno, sizeof(exec_errno));
    } while (exec_read < 0 && errno == EINTR);
    (void)close(exec_status[0]);
    if (exec_read < 0) {
        log_error("exec status read failed: %s", strerror(errno));
    } else if (exec_read == (ssize_t)sizeof(exec_errno)) {
        notify_startup_fifo(log_pipe, "ZUI_UPERF_SUPERVISOR_EXEC_FAILED", exec_errno);
        log_error("exec %s failed: %s", binary, strerror(exec_errno));
    }

    for (;;) {
        int status;
        pid_t reaped = waitpid(-1, &status, 0);

        if (reaped > 0) {
            continue;
        }
        if (reaped < 0 && errno == EINTR) {
            continue;
        }
        if (reaped < 0 && errno == ECHILD) {
            break;
        }
        log_error("waitpid failed: %s", strerror(errno));
        notify_startup_fifo(log_pipe, "ZUI_UPERF_SUPERVISOR_WAIT_FAILED", errno);
        return 70;
    }

    if (exec_read == (ssize_t)sizeof(exec_errno)) {
        return 127;
    }
    notify_startup_fifo(log_pipe, "ZUI_UPERF_SUPERVISOR_TREE_GONE", 0);
    log_error("supervised Uperf descendant tree is gone");
    return 1;
}
