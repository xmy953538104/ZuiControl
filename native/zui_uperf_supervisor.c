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
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#ifndef ZUI_UPERF_PATH
#define ZUI_UPERF_PATH "/system/bin/uperf"
#endif

#define STARTUP_TIMEOUT_MS 20000L
#define STARTUP_CADENCE_MS 100L
#define READ_BUFFER_SIZE 4096U
#define MAX_LOG_LINE_SIZE 65536U

enum scan_result {
    SCAN_PENDING = 0,
    SCAN_READY,
    SCAN_FAILED,
    SCAN_ERROR,
};

struct log_reader {
    int fd;
    dev_t device;
    ino_t inode;
    off_t offset;
    char *line;
    size_t line_length;
    size_t line_capacity;
    bool line_overflow;
};

static void log_error(const char *format, ...) {
    va_list args;

    va_start(args, format);
    fputs("zui_uperf_supervisor: ", stderr);
    vfprintf(stderr, format, args);
    fputc('\n', stderr);
    va_end(args);
}

static int write_all(int fd, const void *buffer, size_t length) {
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
        return -1;
    }
    return 0;
}

static void write_best_effort(int fd, const void *buffer, size_t length) {
    (void)write_all(fd, buffer, length);
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

static long startup_timeout_ms(void) {
#ifdef ZUI_SUPERVISOR_TESTING
    const char *value = getenv("ZUI_UPERF_TEST_TIMEOUT_MS");
    char *end = NULL;
    long parsed;

    if (value == NULL || value[0] == '\0') {
        return STARTUP_TIMEOUT_MS;
    }
    errno = 0;
    parsed = strtol(value, &end, 10);
    if (errno == 0 && end != value && *end == '\0' && parsed > 0 &&
            parsed <= STARTUP_TIMEOUT_MS) {
        return parsed;
    }
#endif
    return STARTUP_TIMEOUT_MS;
}

static long startup_cadence_ms(void) {
#ifdef ZUI_SUPERVISOR_TESTING
    const char *value = getenv("ZUI_UPERF_TEST_CADENCE_MS");
    char *end = NULL;
    long parsed;

    if (value == NULL || value[0] == '\0') {
        return STARTUP_CADENCE_MS;
    }
    errno = 0;
    parsed = strtol(value, &end, 10);
    if (errno == 0 && end != value && *end == '\0' && parsed > 0 &&
            parsed <= STARTUP_CADENCE_MS) {
        return parsed;
    }
#endif
    return STARTUP_CADENCE_MS;
}

static int monotonic_millis(long long *result) {
    struct timespec now;

    if (clock_gettime(CLOCK_MONOTONIC, &now) < 0) {
        return -1;
    }
    *result = (long long)now.tv_sec * 1000LL + now.tv_nsec / 1000000LL;
    return 0;
}

static int sleep_until_millis(long long deadline) {
    struct timespec when;
    int result;

    when.tv_sec = (time_t)(deadline / 1000LL);
    when.tv_nsec = (long)((deadline % 1000LL) * 1000000LL);
    do {
        result = clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &when, NULL);
    } while (result == EINTR);
    if (result != 0) {
        errno = result;
        return -1;
    }
    return 0;
}

static void reset_line(struct log_reader *reader) {
    reader->line_length = 0;
    reader->line_overflow = false;
}

static void close_log(struct log_reader *reader) {
    if (reader->fd >= 0) {
        (void)close(reader->fd);
        reader->fd = -1;
    }
    free(reader->line);
    reader->line = NULL;
    reader->line_capacity = 0;
    reset_line(reader);
}

static int append_line_byte(struct log_reader *reader, char value) {
    size_t required;
    size_t capacity;
    char *resized;

    if (reader->line_overflow) {
        return 0;
    }
    required = reader->line_length + 2U;
    if (required <= reader->line_capacity) {
        reader->line[reader->line_length++] = value;
        return 0;
    }
    capacity = reader->line_capacity == 0 ? 256U : reader->line_capacity * 2U;
    if (capacity > MAX_LOG_LINE_SIZE) {
        capacity = MAX_LOG_LINE_SIZE;
    }
    if (required > capacity) {
        reader->line_overflow = true;
        return 0;
    }
    resized = realloc(reader->line, capacity);
    if (resized == NULL) {
        return -1;
    }
    reader->line = resized;
    reader->line_capacity = capacity;
    reader->line[reader->line_length++] = value;
    return 0;
}

static bool ends_with(const char *text, const char *suffix) {
    size_t text_length = strlen(text);
    size_t suffix_length = strlen(suffix);

    return text_length >= suffix_length &&
            memcmp(text + text_length - suffix_length, suffix, suffix_length) == 0;
}

static enum scan_result finish_line(struct log_reader *reader) {
    enum scan_result result = SCAN_PENDING;

    if (!reader->line_overflow && reader->line_length > 0) {
        if (reader->line_length > 0 && reader->line[reader->line_length - 1U] == '\r') {
            reader->line_length--;
        }
        reader->line[reader->line_length] = '\0';
        if (strstr(reader->line, "I Failed to start") != NULL ||
                strstr(reader->line, "Failed to start uperf") != NULL) {
            result = SCAN_FAILED;
        } else if (ends_with(reader->line, "I Uperf is running")) {
            result = SCAN_READY;
        }
    }
    reset_line(reader);
    return result;
}

static enum scan_result consume_log(struct log_reader *reader) {
    unsigned char buffer[READ_BUFFER_SIZE];

    for (;;) {
        ssize_t count = read(reader->fd, buffer, sizeof(buffer));
        size_t index;

        if (count > 0) {
            reader->offset += count;
            for (index = 0; index < (size_t)count; index++) {
                enum scan_result result;

                if (buffer[index] != '\n') {
                    if (append_line_byte(reader, (char)buffer[index]) < 0) {
                        return SCAN_ERROR;
                    }
                    continue;
                }
                result = finish_line(reader);
                if (result != SCAN_PENDING) {
                    return result;
                }
            }
            continue;
        }
        if (count == 0 || (count < 0 && (errno == EAGAIN || errno == EWOULDBLOCK))) {
            return SCAN_PENDING;
        }
        if (count < 0 && errno == EINTR) {
            continue;
        }
        return SCAN_ERROR;
    }
}

static enum scan_result scan_log_path(struct log_reader *reader, const char *path) {
    struct stat candidate_stat;
    struct stat current_stat;
    int candidate;

    candidate = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC | O_NOFOLLOW);
    if (candidate < 0) {
        if (errno != ENOENT) {
            return SCAN_ERROR;
        }
        if (reader->fd >= 0) {
            (void)close(reader->fd);
            reader->fd = -1;
            reader->offset = 0;
            reset_line(reader);
        }
        return SCAN_PENDING;
    }
    if (fstat(candidate, &candidate_stat) < 0 || !S_ISREG(candidate_stat.st_mode)) {
        (void)close(candidate);
        errno = EINVAL;
        return SCAN_ERROR;
    }
    if (reader->fd < 0 || reader->device != candidate_stat.st_dev ||
            reader->inode != candidate_stat.st_ino) {
        if (reader->fd >= 0) {
            (void)close(reader->fd);
        }
        reader->fd = candidate;
        reader->device = candidate_stat.st_dev;
        reader->inode = candidate_stat.st_ino;
        reader->offset = 0;
        reset_line(reader);
    } else {
        (void)close(candidate);
    }
    if (fstat(reader->fd, &current_stat) < 0) {
        return SCAN_ERROR;
    }
    if (current_stat.st_size < reader->offset) {
        if (lseek(reader->fd, 0, SEEK_SET) < 0) {
            return SCAN_ERROR;
        }
        reader->offset = 0;
        reset_line(reader);
    }
    return consume_log(reader);
}

static int reap_startup_children(bool *tree_alive) {
    for (;;) {
        int status;
        pid_t reaped = waitpid(-1, &status, WNOHANG);

        if (reaped > 0) {
            continue;
        }
        if (reaped == 0) {
            *tree_alive = true;
            return 0;
        }
        if (errno == EINTR) {
            continue;
        }
        if (errno == ECHILD) {
            *tree_alive = false;
            return 0;
        }
        return -1;
    }
}

static int wait_for_startup(const char *log_path) {
    struct log_reader reader = {.fd = -1};
    long long start;
    long long deadline;
    long long next_check;
    long timeout = startup_timeout_ms();
    long cadence = startup_cadence_ms();
    int result = -1;

    if (monotonic_millis(&start) < 0) {
        log_error("clock_gettime failed: %s", strerror(errno));
        return -1;
    }
    deadline = start + timeout;
    next_check = start;
    for (;;) {
        enum scan_result scan;
        bool tree_alive;
        long long now;

        if (monotonic_millis(&now) < 0) {
            log_error("clock_gettime failed: %s", strerror(errno));
            break;
        }
        if (now >= deadline) {
            log_error("Uperf readiness timed out after %ld ms", timeout);
            break;
        }
        if (reap_startup_children(&tree_alive) < 0) {
            log_error("startup waitpid failed: %s", strerror(errno));
            break;
        }
        if (!tree_alive) {
            log_error("supervised Uperf descendant tree exited before readiness");
            break;
        }
        errno = 0;
        scan = scan_log_path(&reader, log_path);
        if (scan == SCAN_READY) {
            result = 0;
            break;
        }
        if (scan == SCAN_FAILED) {
            log_error("Uperf reported startup failure");
            break;
        }
        if (scan == SCAN_ERROR) {
            log_error("startup log check failed for %s: %s", log_path, strerror(errno));
            break;
        }
        next_check += cadence;
        if (next_check <= now) {
            next_check = now + cadence;
        }
        if (next_check > deadline) {
            next_check = deadline;
        }
        if (sleep_until_millis(next_check) < 0) {
            log_error("startup sleep failed: %s", strerror(errno));
            break;
        }
    }
    close_log(&reader);
    return result;
}

static int publish_ready_marker(const char *path) {
    struct timespec uptime;
    char *temporary;
    char value[64];
    size_t path_length = strlen(path);
    int value_length;
    int fd = -1;
    int saved_errno;
    int result = -1;

    temporary = malloc(path_length + sizeof(".tmp"));
    if (temporary == NULL) {
        return -1;
    }
    memcpy(temporary, path, path_length);
    memcpy(temporary + path_length, ".tmp", sizeof(".tmp"));
    if (clock_gettime(CLOCK_BOOTTIME, &uptime) < 0) {
        goto done;
    }
    value_length = snprintf(value, sizeof(value), "%lld\n", (long long)uptime.tv_sec);
    if (value_length <= 0 || (size_t)value_length >= sizeof(value)) {
        errno = EOVERFLOW;
        goto done;
    }
    if (unlink(temporary) < 0 && errno != ENOENT) {
        goto done;
    }
    fd = open(temporary, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (fd < 0 || fchmod(fd, 0600) < 0 ||
            write_all(fd, value, (size_t)value_length) < 0 || fsync(fd) < 0) {
        goto done;
    }
    if (close(fd) < 0) {
        fd = -1;
        goto done;
    }
    fd = -1;
    if (rename(temporary, path) < 0) {
        goto done;
    }
    result = 0;

done:
    saved_errno = errno;
    if (fd >= 0) {
        (void)close(fd);
    }
    if (result != 0) {
        (void)unlink(temporary);
    }
    free(temporary);
    errno = saved_errno;
    return result;
}

static int wait_for_tree(void) {
    for (;;) {
        int status;
        pid_t reaped = waitpid(-1, &status, 0);

        if (reaped > 0 || (reaped < 0 && errno == EINTR)) {
            continue;
        }
        if (reaped < 0 && errno == ECHILD) {
            log_error("supervised Uperf descendant tree is gone");
            return 1;
        }
        log_error("waitpid failed: %s", strerror(errno));
        return 70;
    }
}

int main(int argc, char **argv) {
    const char *binary;
    const char *config;
    const char *log_path;
    const char *ready_marker;
    struct sigaction action = {0};
    int exec_status[2];
    pid_t child;
    int exec_errno = 0;
    ssize_t exec_read;

    if (argc != 4) {
        log_error("usage: %s <config> <regular-log> <ready-marker>", argv[0]);
        return 64;
    }
    config = argv[1];
    log_path = argv[2];
    ready_marker = argv[3];
    binary = uperf_path();

    action.sa_handler = handle_usr1;
    sigemptyset(&action.sa_mask);
    if (sigaction(SIGUSR1, &action, NULL) < 0) {
        log_error("sigaction(SIGUSR1) failed: %s", strerror(errno));
        return 70;
    }
    if (prctl(PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0) < 0) {
        log_error("PR_SET_CHILD_SUBREAPER failed: %s", strerror(errno));
        return 70;
    }
    if ((unlink(log_path) < 0 && errno != ENOENT) ||
            (unlink(ready_marker) < 0 && errno != ENOENT)) {
        log_error("stale runtime cleanup failed: %s", strerror(errno));
        return 70;
    }
    if (pipe(exec_status) < 0 || set_cloexec(exec_status[0]) < 0 ||
            set_cloexec(exec_status[1]) < 0) {
        log_error("exec status pipe failed: %s", strerror(errno));
        return 70;
    }

    child = fork();
    if (child < 0) {
        log_error("fork failed: %s", strerror(errno));
        return 70;
    }
    if (child == 0) {
        int saved_errno;

        (void)close(exec_status[0]);
        execl(binary, binary, config, "-o", log_path, (char *)NULL);
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
        return 70;
    }
    if (exec_read == (ssize_t)sizeof(exec_errno)) {
        log_error("ZUI_UPERF_SUPERVISOR_EXEC_FAILED detail=%d: exec %s failed: %s",
                exec_errno, binary, strerror(exec_errno));
        while (waitpid(-1, NULL, 0) < 0 && errno == EINTR) {
        }
        return 127;
    }
    if (exec_read != 0) {
        log_error("exec status protocol failed");
        return 70;
    }

    if (wait_for_startup(log_path) < 0) {
        return 1;
    }
    if (publish_ready_marker(ready_marker) < 0) {
        log_error("ready marker publish failed for %s: %s", ready_marker, strerror(errno));
        return 1;
    }
    return wait_for_tree();
}
