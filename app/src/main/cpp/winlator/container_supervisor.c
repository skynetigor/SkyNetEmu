#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static volatile sig_atomic_t stopping;

static void stop_requested(int signal_number) {
    (void)signal_number;
    stopping = 1;
}

static long long milliseconds(void) {
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now)) {
        perror("ContainerSupervisor clock");
        exit(125);
    }
    return (long long)now.tv_sec * 1000 + now.tv_nsec / 1000000;
}

struct entry {
    pid_t pid, parent;
    unsigned long long birth;
    int owned;
};

static int read_entry(pid_t pid, struct entry *entry) {
    char path[64], buffer[4096];
    struct stat metadata;
    snprintf(path, sizeof(path), "/proc/%d", pid);
    if (stat(path, &metadata) || metadata.st_uid != getuid()) return 0;
    snprintf(path, sizeof(path), "/proc/%d/stat", pid);
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    ssize_t length = read(fd, buffer, sizeof(buffer) - 1);
    close(fd);
    if (length <= 0) return 0;
    buffer[length] = '\0';
    char *end = strrchr(buffer, ')');
    if (!end || end[1] != ' ') return 0;
    char *save, *field = strtok_r(end + 2, " ", &save);
    entry->pid = pid;
    entry->owned = 0;
    for (int index = 0; field; index++, field = strtok_r(NULL, " ", &save)) {
        if (index == 1) entry->parent = (pid_t)strtol(field, NULL, 10);
        if (index == 19) {
            entry->birth = strtoull(field, NULL, 10);
            return 1;
        }
    }
    return 0;
}

static void signal_entry(const struct entry *entry, int signal_number) {
    struct entry current;
    int fd = -1;
#ifdef SYS_pidfd_open
    fd = (int)syscall(SYS_pidfd_open, entry->pid, 0);
#endif
    /* Bind a pidfd before revalidating identity; old kernels use the same birth check. */
    if (!read_entry(entry->pid, &current) || current.birth != entry->birth) {
        if (fd >= 0) close(fd);
        return;
    }
    int result;
#ifdef SYS_pidfd_send_signal
    if (fd >= 0) result = (int)syscall(SYS_pidfd_send_signal, fd, signal_number, NULL, 0);
    else
#endif
        result = kill(entry->pid, signal_number);
    if (fd >= 0) close(fd);
    if (result && errno != ESRCH)
        fprintf(stderr, "ContainerSupervisor: signal %d to pid %d failed: %s\n",
                signal_number, entry->pid, strerror(errno));
}

static void signal_descendants(int signal_number) {
    DIR *proc = opendir("/proc");
    if (!proc) {
        perror("ContainerSupervisor /proc");
        return;
    }
    struct entry *entries = NULL;
    size_t count = 0, capacity = 0;
    struct dirent *dir;
    while ((dir = readdir(proc))) {
        char *end;
        long pid = strtol(dir->d_name, &end, 10);
        if (*end || pid <= 0) continue;
        struct entry entry;
        if (!read_entry((pid_t)pid, &entry)) continue;
        if (count == capacity) {
            size_t next = capacity ? capacity * 2 : 64;
            struct entry *grown = realloc(entries, next * sizeof(*entries));
            if (!grown) {
                perror("ContainerSupervisor process list");
                free(entries);
                closedir(proc);
                return;
            }
            entries = grown;
            capacity = next;
        }
        entries[count++] = entry;
    }
    closedir(proc);
    int changed;
    do {
        changed = 0;
        for (size_t i = 0; i < count; i++) {
            if (entries[i].owned || entries[i].pid == getpid()) continue;
            if (entries[i].parent == getpid()) {
                entries[i].owned = 1;
                changed = 1;
            } else {
                for (size_t j = 0; j < count; j++) {
                    if (entries[j].owned && entries[i].parent == entries[j].pid) {
                        entries[i].owned = 1;
                        changed = 1;
                        break;
                    }
                }
            }
        }
    } while (changed);
    for (size_t i = 0; i < count; i++)
        if (entries[i].owned) {
            signal_entry(&entries[i], signal_number);
            if (signal_number == SIGTERM) signal_entry(&entries[i], SIGCONT);
        }
    free(entries);
}

static void restore_loader_environment(void) {
    const char *names[] = {"LD_PRELOAD", "LD_LIBRARY_PATH"};
    const char *saved[] = {"SKYNET_SUPERVISOR_PRELOAD", "SKYNET_SUPERVISOR_LIBRARY_PATH"};
    for (int i = 0; i < 2; i++) {
        const char *value = getenv(saved[i]);
        if (value && setenv(names[i], value, 1)) {
            perror("ContainerSupervisor environment");
            _exit(125);
        }
        unsetenv(saved[i]);
    }
}

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "ContainerSupervisor: expected status token and program\n");
        return 125;
    }
    pid_t owner = getppid();
    if (owner <= 1 || prctl(PR_SET_CHILD_SUBREAPER, 1)) {
        perror("ContainerSupervisor subreaper");
        return 125;
    }
    struct sigaction action = {0};
    action.sa_handler = stop_requested;
    sigemptyset(&action.sa_mask);
    if (sigaction(SIGTERM, &action, NULL) || sigaction(SIGINT, &action, NULL)
            || sigaction(SIGHUP, &action, NULL)) {
        perror("ContainerSupervisor signals");
        return 125;
    }
    /* The Android owner may disappear with its output pipe before cleanup finishes. */
    signal(SIGPIPE, SIG_IGN);
    pid_t root = fork();
    if (root < 0) {
        perror("ContainerSupervisor fork");
        return 125;
    }
    if (!root) {
        signal(SIGTERM, SIG_DFL);
        signal(SIGINT, SIG_DFL);
        signal(SIGHUP, SIG_DFL);
        signal(SIGPIPE, SIG_DFL);
        restore_loader_environment();
        execvp(argv[2], argv + 2);
        perror("ContainerSupervisor exec");
        _exit(127);
    }
    long long deadline = 0;
    int warned = 0;
    for (;;) {
        int status;
        pid_t reaped;
        while ((reaped = waitpid(-1, &status, WNOHANG)) > 0) {
            if (reaped == root) {
                int code = WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
                printf("\n%s:%d\n", argv[1], code);
                fflush(stdout);
            }
        }
        if (reaped < 0 && errno == ECHILD) return 0;
        if (reaped < 0 && errno != EINTR) {
            perror("ContainerSupervisor waitpid");
            stopping = 1;
        }
        if (getppid() != owner) stopping = 1;
        if (stopping) {
            long long now = milliseconds();
            if (!deadline) deadline = now + 1500;
            signal_descendants(now < deadline ? SIGTERM : SIGKILL);
            if (!warned && now >= deadline + 3500) {
                fprintf(stderr, "ContainerSupervisor: descendants remain after shutdown deadline\n");
                warned = 1;
            }
        }
        struct timespec pause = {0, 20000000};
        nanosleep(&pause, NULL);
    }
}
