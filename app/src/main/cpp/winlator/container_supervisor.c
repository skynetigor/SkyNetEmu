#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/ptrace.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#ifndef PTRACE_SEIZE
#define PTRACE_SEIZE 0x4206
#endif
#ifndef PTRACE_O_EXITKILL
#define PTRACE_O_EXITKILL 0x00100000
#endif
#ifndef PTRACE_O_TRACEFORK
#define PTRACE_O_TRACEFORK 0x00000002
#endif
#ifndef PTRACE_O_TRACEVFORK
#define PTRACE_O_TRACEVFORK 0x00000004
#endif
#ifndef PTRACE_O_TRACECLONE
#define PTRACE_O_TRACECLONE 0x00000008
#endif
#ifndef PTRACE_EVENT_FORK
#define PTRACE_EVENT_FORK 1
#endif
#ifndef PTRACE_EVENT_VFORK
#define PTRACE_EVENT_VFORK 2
#endif
#ifndef PTRACE_EVENT_CLONE
#define PTRACE_EVENT_CLONE 3
#endif
#ifndef PTRACE_EVENT_STOP
#define PTRACE_EVENT_STOP 128
#endif

static volatile sig_atomic_t stopping;
static volatile sig_atomic_t suspended;

static void stop_requested(int signal_number) {
    (void)signal_number;
    stopping = 1;
}

static void suspension_requested(int signal_number) {
    suspended = signal_number == SIGUSR1;
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

static void signal_tasks(pid_t pid, int signal_number);
static void release_task(pid_t pid);

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
    /* A zombie leader still has a thread group. Group-directed SIGKILL is not
     * always delivered to the siblings that kept running after the leader exited. */
    if (signal_number == SIGKILL || signal_number == SIGSTOP)
        signal_tasks(entry->pid, signal_number);
}

static void signal_tasks(pid_t pid, int signal_number) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/task", pid);
    DIR *tasks = opendir(path);
    if (!tasks) {
        syscall(SYS_tgkill, pid, pid, signal_number);
        return;
    }
    struct dirent *task;
    while ((task = readdir(tasks))) {
        char *end = NULL;
        long tid = strtol(task->d_name, &end, 10);
        if (!end || *end || tid <= 0) continue;
        if (syscall(SYS_tgkill, pid, (pid_t)tid, signal_number) && errno != ESRCH)
            fprintf(stderr, "ContainerSupervisor: signal %d to tid %ld failed: %s\n",
                    signal_number, tid, strerror(errno));
    }
    closedir(tasks);
}

static void signal_descendants(int signal_number, int release_ptrace) {
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
            if (!release_ptrace || signal_number) {
                signal_entry(&entries[i], signal_number);
                if (signal_number == SIGTERM) signal_entry(&entries[i], SIGCONT);
            }
            if (release_ptrace) release_task(entries[i].pid);
        }
    free(entries);
}

static int command_uses_proot(int argc, char **argv) {
    for (int i = 2; i < argc; i++) {
        const char *base = strrchr(argv[i], '/');
        base = base ? base + 1 : argv[i];
        if (!strcmp(base, "proot")) return 1;
    }
    return 0;
}

/* Proot is already the tracer of its guests. Seizing that tree would steal
 * its children. Its own PTRACE_O_EXITKILL covers them once this direct child
 * dies; PR_SET_PDEATHSIG makes that happen when the supervisor is SIGKILLed.
 * Every other guest is seized here so a zombie leader's live threads are
 * tracees and die with this process. */
static void resume_tracee(pid_t pid, int status) {
    int event = status >> 16;
    int sig = WSTOPSIG(status);
    if (event == PTRACE_EVENT_CLONE || event == PTRACE_EVENT_FORK || event == PTRACE_EVENT_VFORK
            || event == PTRACE_EVENT_STOP) {
        if (event != PTRACE_EVENT_STOP || (!suspended && !stopping))
            ptrace(PTRACE_CONT, pid, NULL, 0);
        return;
    }
    if ((sig == SIGSTOP || sig == SIGTSTP) && !suspended && !stopping) {
        ptrace(PTRACE_CONT, pid, NULL, 0);
        return;
    }
    if (sig == SIGCONT && suspended) {
        ptrace(PTRACE_CONT, pid, NULL, 0);
        return;
    }
    ptrace(PTRACE_CONT, pid, NULL, (void *)(long)sig);
}

/* A seized task sitting in ptrace-stop does not observe a later SIGCONT until
 * its tracer restarts it. kill(SIGCONT) alone therefore cannot resume pause. */
static void release_task(pid_t pid) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/task", pid);
    DIR *tasks = opendir(path);
    if (!tasks) {
        ptrace(PTRACE_CONT, pid, NULL, (void *)(long)SIGCONT);
        return;
    }
    struct dirent *task;
    while ((task = readdir(tasks))) {
        char *end = NULL;
        long tid = strtol(task->d_name, &end, 10);
        if (!end || *end || tid <= 0) continue;
        ptrace(PTRACE_CONT, (pid_t)tid, NULL, (void *)(long)SIGCONT);
    }
    closedir(tasks);
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
    action.sa_handler = suspension_requested;
    if (sigaction(SIGUSR1, &action, NULL) || sigaction(SIGUSR2, &action, NULL)) {
        perror("ContainerSupervisor suspension signals");
        return 125;
    }
    /* ART worker threads can launch us with the control signals blocked. */
    sigset_t controls, inherited;
    sigemptyset(&controls);
    sigaddset(&controls, SIGTERM);
    sigaddset(&controls, SIGINT);
    sigaddset(&controls, SIGHUP);
    sigaddset(&controls, SIGUSR1);
    sigaddset(&controls, SIGUSR2);
    if (sigprocmask(SIG_UNBLOCK, &controls, &inherited)) {
        perror("ContainerSupervisor control signal mask");
        return 125;
    }
    /* The Android owner may disappear with its output pipe before cleanup finishes. */
    signal(SIGPIPE, SIG_IGN);
    int uses_proot = command_uses_proot(argc, argv);
    int wake[2];
    if (pipe2(wake, O_CLOEXEC)) {
        perror("ContainerSupervisor jail pipe");
        return 125;
    }
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
        signal(SIGUSR1, SIG_DFL);
        signal(SIGUSR2, SIG_DFL);
        if (sigprocmask(SIG_SETMASK, &inherited, NULL)) {
            perror("ContainerSupervisor guest signal mask");
            _exit(125);
        }
        pid_t supervisor = getppid();
        if (prctl(PR_SET_PDEATHSIG, SIGKILL) || getppid() != supervisor) _exit(125);
        if (!uses_proot) {
            close(wake[1]);
            char byte;
            while (read(wake[0], &byte, 1) < 0 && errno == EINTR) ;
            close(wake[0]);
        } else {
            close(wake[0]);
            close(wake[1]);
        }
        restore_loader_environment();
        execvp(argv[2], argv + 2);
        perror("ContainerSupervisor exec");
        _exit(127);
    }
    close(wake[0]);
    int jailed = 0;
    if (!uses_proot) {
        long options = PTRACE_O_EXITKILL | PTRACE_O_TRACECLONE | PTRACE_O_TRACEFORK | PTRACE_O_TRACEVFORK;
        if (ptrace(PTRACE_SEIZE, root, NULL, (void *)options) == 0) jailed = 1;
        else fprintf(stderr, "ContainerSupervisor: ptrace jail unavailable: %s\n", strerror(errno));
    }
    close(wake[1]);
    if (jailed) fprintf(stderr, "ContainerSupervisor: ptrace jail attached to %d\n", root);
    printf("\n%s:ready\n", argv[1]);
    fflush(stdout);
    long long deadline = 0;
    int warned = 0;
    int was_suspended = 0;
    long long next_suspend = 0;
    for (;;) {
        int status;
        pid_t reaped;
        while ((reaped = waitpid(-1, &status, WNOHANG)) > 0) {
            if (WIFSTOPPED(status)) {
                if (jailed) resume_tracee(reaped, status);
                continue;
            }
            if (reaped == root && (WIFEXITED(status) || WIFSIGNALED(status))) {
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
        if (!stopping) {
            if (suspended) {
                long long now = milliseconds();
                if (!was_suspended || now >= next_suspend) {
                    signal_descendants(SIGSTOP, 0);
                    next_suspend = now + 200;
                }
                was_suspended = 1;
            } else if (was_suspended) {
                signal_descendants(SIGCONT, jailed);
                was_suspended = 0;
            }
        }
        if (stopping) {
            long long now = milliseconds();
            if (!deadline) deadline = now + 1500;
            signal_descendants(now < deadline ? SIGTERM : SIGKILL, 0);
            if (!warned && now >= deadline + 3500) {
                fprintf(stderr, "ContainerSupervisor: descendants remain after shutdown deadline\n");
                warned = 1;
            }
        }
        struct timespec pause = {0, 20000000};
        nanosleep(&pause, NULL);
    }
}
