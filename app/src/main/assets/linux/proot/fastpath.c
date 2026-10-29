#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#define STUB_ADDRESS 0xffff10000UL
#define MAX_MAPS 128
#define SLOW (-100000L)
typedef long (*syscall_fn)(long, long, long, long, long, long, long);
static syscall_fn native;
static int enabled, diagnostics;
static _Atomic unsigned long hits, fallbacks;
struct mapping { char *guest, *host; size_t length; };
static struct mapping mappings[MAX_MAPS];
static size_t map_count;
static void *(*next_dlsym)(void *, const char *);

static void *original(const char *name) {
    if (!next_dlsym) next_dlsym = dlvsym(RTLD_NEXT, "dlsym", "GLIBC_2.17");
    void *symbol = next_dlsym(RTLD_NEXT, name);
    if (!symbol) {
        fprintf(stderr, "skyfastpath: required libc symbol missing: %s\n", name);
        abort();
    }
    return symbol;
}

static long raw(long nr, long a, long b, long c, long d) {
    return native(nr, a, b, c, d, 0, 0);
}

static int beneath(const char *path, const char *prefix, size_t length) {
    return !strncmp(path, prefix, length)
        && (length == 1 || path[length] == '/' || path[length] == 0);
}

__attribute__((constructor))
static void initialize(void) {
    next_dlsym = dlvsym(RTLD_NEXT, "dlsym", "GLIBC_2.17");
    const char *table = getenv("SKY_FASTPATH_MAP");
    if (!table) return;
    diagnostics = getenv("SKY_FASTPATH_STATS") != NULL;
    long capability = syscall(SYS_prctl, 0x53594b46, 0, 0, 0, 0);
    if (capability != 0x534b5901) {
        fprintf(stderr, "skyfastpath: PRoot capability mismatch; using normal PRoot\n");
        return;
    }
    char *copy = strdup(table);
    if (!copy) {
        fprintf(stderr, "skyfastpath: cannot allocate bind table; using normal PRoot\n");
        return;
    }
    char *save;
    for (char *line = strtok_r(copy, "\n", &save); line; line = strtok_r(NULL, "\n", &save)) {
        char *tab = strchr(line, '\t');
        if (!tab || strchr(tab + 1, '\t') || map_count == MAX_MAPS) goto invalid;
        *tab++ = 0;
        size_t length = strlen(line);
        if (!length || line[0] != '/' || tab[0] != '/' || strlen(tab) >= PATH_MAX
                || length >= PATH_MAX || (length > 1 && line[length - 1] == '/')) goto invalid;
        mappings[map_count++] = (struct mapping){line, tab, length};
    }
    if (!map_count) goto invalid;
    long page = sysconf(_SC_PAGESIZE);
    if (page < 4096 || page > 65536) goto invalid;
    void *stub = mmap((void *)STUB_ADDRESS, page, PROT_READ | PROT_WRITE,
            MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE, -1, 0);
    if (stub != (void *)STUB_ADDRESS) {
        if (stub != MAP_FAILED) munmap(stub, page);
        fprintf(stderr, "skyfastpath: trampoline unavailable; using normal PRoot\n");
        free(copy);
        return;
    }
    const uint32_t code[] = {0xaa0003e8, 0xaa0103e0, 0xaa0203e1, 0xaa0303e2,
        0xaa0403e3, 0xaa0503e4, 0xaa0603e5, 0xd4000001, 0xd65f03c0};
    memcpy(stub, code, sizeof(code));
    __builtin___clear_cache(stub, (char *)stub + sizeof(code));
    if (mprotect(stub, page, PROT_READ | PROT_EXEC)) {
        munmap(stub, page);
        fprintf(stderr, "skyfastpath: executable trampoline denied; using normal PRoot\n");
        free(copy);
        return;
    }
    native = (syscall_fn)stub;
    enabled = 1;
    fprintf(stderr, "skyfastpath: v1 active, %zu bind mappings, read-only regular files\n", map_count);
    return;
invalid:
    free(copy);
    map_count = 0;
    fprintf(stderr, "skyfastpath: invalid bind configuration; using normal PRoot\n");
}

/* Resolve absolute paths through pinned directory descriptors. Symlinks,
 * relative paths and ambiguous components always go back through PRoot. */
static long regular(const char *path, int flags, struct stat *metadata) {
    if (!enabled || !path || path[0] != '/' || strnlen(path, PATH_MAX) == PATH_MAX) return SLOW;
    /* Bind selection must not differ from PRoot after path normalization. */
    for (const char *part = path + 1; *part;) {
        const char *end = strchr(part, '/');
        size_t length = end ? (size_t)(end - part) : strlen(part);
        if (!length || (length == 1 && part[0] == '.')
                || (length == 2 && part[0] == '.' && part[1] == '.')) return SLOW;
        if (!end) break;
        part = end + 1;
    }
    if (beneath(path, "/proc", 5) || beneath(path, "/sys", 4) || beneath(path, "/dev", 4)) return SLOW;
    struct mapping *selected = NULL;
    for (size_t i = 0; i < map_count; i++)
        if (beneath(path, mappings[i].guest, mappings[i].length)
                && (!selected || mappings[i].length >= selected->length)) selected = &mappings[i];
    if (!selected) return SLOW;
    const char *relative = path + selected->length;
    if (selected->length > 1) {
        if (*relative != '/') return SLOW;
        relative++;
    }
    if (!*relative) return SLOW;
    char components[PATH_MAX];
    strcpy(components, relative);
    long directory = raw(SYS_openat, AT_FDCWD, (long)selected->host, O_PATH | O_DIRECTORY | O_CLOEXEC, 0);
    if (directory < 0) return SLOW;
    char *component = components;
    long result = SLOW;
    while (*component) {
        char *slash = strchr(component, '/');
        if (slash) *slash = 0;
        if (!*component || !strcmp(component, ".") || !strcmp(component, "..")) break;
        if (slash) {
            long next = raw(SYS_openat, directory, (long)component, O_PATH | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC, 0);
            if (next < 0) break;
            raw(SYS_close, directory, 0, 0, 0);
            directory = next;
            component = slash + 1;
            continue;
        }
        int requested = metadata ? O_PATH | O_NOFOLLOW | O_CLOEXEC : flags | O_NOFOLLOW | O_NONBLOCK;
        long file = raw(SYS_openat, directory, (long)component, requested, 0);
        if (file < 0) break;
        struct stat status;
        if (raw(SYS_fstat, file, (long)&status, 0, 0) < 0 || !S_ISREG(status.st_mode)) {
            raw(SYS_close, file, 0, 0, 0);
            break;
        }
        if (metadata) {
            *metadata = status;
            raw(SYS_close, file, 0, 0, 0);
            result = 0;
        } else {
            if (!(flags & O_NONBLOCK) && raw(SYS_fcntl, file, F_SETFL, flags, 0) < 0) {
                raw(SYS_close, file, 0, 0, 0);
                break;
            }
            result = file;
        }
        unsigned long count = atomic_fetch_add_explicit(&hits, 1, memory_order_relaxed) + 1;
        if (diagnostics && (count == 1 || count % 8192 == 0)) {
            char line[160];
            int length = snprintf(line, sizeof(line), "skyfastpath: pid=%d accelerated=%lu fallback=%lu\n",
                getpid(), count, atomic_load_explicit(&fallbacks, memory_order_relaxed));
            raw(SYS_write, STDERR_FILENO, (long)line, length, 0);
        }
        break;
    }
    raw(SYS_close, directory, 0, 0, 0);
    return result;
}

static int open_impl(int dirfd, const char *path, int flags, mode_t mode) {
    int saved = errno;
    int permitted = O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK | O_LARGEFILE | O_NOCTTY;
    if (!(flags & ~permitted)) {
        long result = regular(path, flags, NULL);
        if (result != SLOW) { errno = saved; return (int)result; }
    }
    atomic_fetch_add_explicit(&fallbacks, 1, memory_order_relaxed);
    int (*slow_open)(int, const char *, int, ...) = original("openat");
    errno = saved;
    return slow_open(dirfd, path, flags, mode);
}

static mode_t open_mode(int flags, va_list args) {
    return (flags & O_CREAT) || (flags & O_TMPFILE) == O_TMPFILE ? va_arg(args, unsigned int) : 0;
}

int open(const char *path, int flags, ...) {
    va_list args;
    va_start(args, flags);
    mode_t mode = open_mode(flags, args);
    va_end(args);
    return open_impl(AT_FDCWD, path, flags, mode);
}

int open64(const char *path, int flags, ...) {
    va_list args;
    va_start(args, flags);
    mode_t mode = open_mode(flags, args);
    va_end(args);
    return open_impl(AT_FDCWD, path, flags, mode);
}

int openat(int dirfd, const char *path, int flags, ...) {
    va_list args;
    va_start(args, flags);
    mode_t mode = open_mode(flags, args);
    va_end(args);
    return open_impl(dirfd, path, flags, mode);
}

int openat64(int dirfd, const char *path, int flags, ...) {
    va_list args;
    va_start(args, flags);
    mode_t mode = open_mode(flags, args);
    va_end(args);
    return open_impl(dirfd, path, flags, mode);
}

int fstatat(int dirfd, const char *path, struct stat *status, int flags) {
    int saved = errno;
    if (flags == 0 || flags == AT_SYMLINK_NOFOLLOW) {
        struct stat local;
        long result = regular(path, 0, &local);
        if (result != SLOW) { *status = local; errno = saved; return 0; }
    }
    atomic_fetch_add_explicit(&fallbacks, 1, memory_order_relaxed);
    int (*slow_stat)(int, const char *, struct stat *, int) = original("fstatat");
    errno = saved;
    return slow_stat(dirfd, path, status, flags);
}

int stat(const char *path, struct stat *status) { return fstatat(AT_FDCWD, path, status, 0); }
int lstat(const char *path, struct stat *status) { return fstatat(AT_FDCWD, path, status, AT_SYMLINK_NOFOLLOW); }

/* Box64 asks its explicit libc handle for these functions, not RTLD_DEFAULT. */
void *dlsym(void *handle, const char *name) {
    if (!next_dlsym) next_dlsym = dlvsym(RTLD_NEXT, "dlsym", "GLIBC_2.17");
    if (handle == RTLD_NEXT) __attribute__((musttail)) return next_dlsym(handle, name);
    void *found = next_dlsym(handle, name);
    Dl_info owner;
    if (!enabled || !found || !dladdr(found, &owner) || !owner.dli_fname
            || !strstr(owner.dli_fname, "/libc.so")) return found;
#define HOOK(symbol) if (!strcmp(name, #symbol)) return (void *)symbol
    HOOK(open); HOOK(open64); HOOK(openat); HOOK(openat64);
    HOOK(stat); HOOK(lstat); HOOK(fstatat);
#undef HOOK
    return found;
}

void sky_fastpath_counts(unsigned long *fast, unsigned long *slow) {
    *fast = atomic_load_explicit(&hits, memory_order_relaxed);
    *slow = atomic_load_explicit(&fallbacks, memory_order_relaxed);
}

__attribute__((destructor))
static void report(void) {
    if (diagnostics) fprintf(stderr, "skyfastpath: pid=%d fast=%lu fallback=%lu\n", getpid(),
        atomic_load_explicit(&hits, memory_order_relaxed), atomic_load_explicit(&fallbacks, memory_order_relaxed));
}
