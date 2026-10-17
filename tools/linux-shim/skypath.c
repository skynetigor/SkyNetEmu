/*
 * skypath: an LD_PRELOAD library for the Winlator skyNET Linux runtime (glibc aarch64) that does, inside the process, what proot
 * did from outside: the guest's view of the file system, and the start of glibc programs.
 *
 * Why. proot stops the process at every system call that names a path (ptrace) and translates the path in its own process: about
 * 100 us for a stat or an open on the phone, against about 0.5-2 us for the call itself. A game that opens thousands of files while
 * it loads pays that for each of them. Here the same translation is made by the libc functions of the process, in nanoseconds.
 *
 * Paths. The guest sees the runtime as "/" (the runtime's folder is the guest root), plus a few places of the phone bound over it
 * (proot's -b): /dev, /proc, /sys, the app's folders, shared storage, the container's home as /root, ... The table is given in
 * $SKY_PATHMAP as "guest=host|guest=host|...": the longest guest prefix that matches decides; a path that no entry matches goes
 * under $SKY_ROOT. A path that is not absolute is left alone (the working directory is a real folder).
 *
 * Programs. The kernel starts a glibc program through the interpreter its ELF header names, "/lib/ld-linux-aarch64.so.1", which does
 * not exist on Android; a script's "#!/bin/sh" names a program that does not exist either. exec and spawn here translate the path
 * and start such a program as "<loader> --library-path <$SKY_LIBPATH> --argv0 <name> <program> <arguments>", and a script as its
 * interpreter with the script as argument. /proc/self/exe then names the loader, so $SKY_SELF carries the program's own path
 * (readlink of /proc/self/exe answers it; the child gets its own).
 *
 * Only the libc functions that name a path are interposed; a program that makes the system call itself is not seen.
 * Switched on by $SKY_ROOT; without it every function goes straight to libc.
 *
 * MIT licence, written for this project.
 */
#define _GNU_SOURCE
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <ucontext.h>
#include <link.h>
#include <elf.h>
#include <fcntl.h>
#include <limits.h>
#include <spawn.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/statvfs.h>
#include <sys/syscall.h>
#include <sys/time.h>
#include <sys/un.h>
#include <sys/inotify.h>
#include <sys/mman.h>
#include <sys/wait.h>
#include <unistd.h>
#include <utime.h>

#define MAXENT 64
#define BUF PATH_MAX

struct entry { char *guest; size_t glen; char *host; };
static struct entry ents[MAXENT];
static int nents;
static int on;
static char root[BUF];
static size_t rootlen;
static char loader[BUF];
static char libpath[BUF * 2];
static char self_path[BUF];

static int by_length(const void *a, const void *b) {
    const struct entry *x = a, *y = b;
    return (x->glen < y->glen) - (x->glen > y->glen);
}

/* An app's seccomp filter kills a process that calls a system call it does not know (pkey_alloc, rseq, ...) with SIGSYS, where a PC kernel would answer ENOSYS;
   proot answers for the process. The handler does the same: the call has not run, the program goes on after it with -ENOSYS in x0. */
static void sigsys_handler(int sig, siginfo_t *info, void *ctx) {
    (void)sig;
    ucontext_t *uc = ctx;
    long nr = info ? info->si_syscall : -1;
    uc->uc_mcontext.regs[0] = (unsigned long long)(nr == 99 ? 0 : -(long)ENOSYS);
}

__attribute__((constructor)) static void sp_init(void) {
    const char *r = getenv("SKY_ROOT");
    if (!r || !*r) return;
    struct sigaction sa;
    memset(&sa, 0, sizeof sa);
    sa.sa_sigaction = sigsys_handler;
    sa.sa_flags = SA_SIGINFO | SA_NODEFER;
    sigaction(SIGSYS, &sa, NULL);
    snprintf(root, sizeof root, "%s", r);
    rootlen = strlen(root);
    while (rootlen > 1 && root[rootlen - 1] == '/') root[--rootlen] = 0;
    const char *l = getenv("SKY_LOADER");
    if (l && *l) snprintf(loader, sizeof loader, "%s", l);
    else snprintf(loader, sizeof loader, "%s/usr/lib/ld-linux-aarch64.so.1", root);
    const char *lp = getenv("SKY_LIBPATH");
    if (lp && *lp) snprintf(libpath, sizeof libpath, "%s", lp);
    else snprintf(libpath, sizeof libpath, "%s/usr/lib", root);
    const char *s = getenv("SKY_SELF");
    if (s && *s) snprintf(self_path, sizeof self_path, "%s", s);

    const char *map = getenv("SKY_PATHMAP");
    if (map) {
        char *copy = strdup(map);
        for (char *tok = strtok(copy, "|"); tok && nents < MAXENT; tok = strtok(NULL, "|")) {
            char *eq = strchr(tok, '=');
            if (!eq || tok[0] != '/') continue;
            *eq = 0;
            size_t gl = strlen(tok);
            while (gl > 1 && tok[gl - 1] == '/') tok[--gl] = 0;
            ents[nents].guest = strdup(tok);
            ents[nents].glen = gl;
            ents[nents].host = strdup(eq + 1);
            nents++;
        }
        free(copy);
        qsort(ents, nents, sizeof ents[0], by_length);
    }
    on = 1;
}

/* The real path for a guest path: {p} itself when nothing changes, else {buf}. */
static const char *mp(const char *p, char *buf) {
    if (!on || !p || p[0] != '/') return p;
    for (int i = 0; i < nents; i++) {
        size_t gl = ents[i].glen;
        if (strncmp(p, ents[i].guest, gl) == 0 && (p[gl] == 0 || p[gl] == '/' || gl == 1)) {
            if (ents[i].host[0] == 0) return p;
            snprintf(buf, BUF, "%s%s", ents[i].host, p + (gl == 1 ? 0 : gl));
            return buf;
        }
    }
    snprintf(buf, BUF, "%s%s", root, p);
    return buf;
}

#define MAP(var, p) char var##_b[BUF]; const char *var = mp(p, var##_b)

/* The phone's /sys refuses with "permission denied" what a PC's kernel would answer "no such file" (/sys/class/power_supply/BAT0 on a phone without that
   battery): programs treat the two differently. */
static int sysnone(const char *guest, int rc) {
    if (rc < 0 && errno == EACCES && guest && strncmp(guest, "/sys/", 5) == 0) errno = ENOENT;
    return rc;
}

/* ---- files and folders --------------------------------------------------------------------------------------------- */

#define NEXT(name) static __typeof__(&name) real; if (!real) real = dlsym(RTLD_NEXT, #name)

int open(const char *path, int flags, ...) {
    static int (*real)(const char *, int, ...);
    if (!real) real = dlsym(RTLD_NEXT, "open");
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) { va_list ap; va_start(ap, flags); mode = va_arg(ap, mode_t); va_end(ap); }
    MAP(m, path);
    return sysnone(path, real(m, flags, mode));
}
int open64(const char *path, int flags, ...) {
    static int (*real)(const char *, int, ...);
    if (!real) real = dlsym(RTLD_NEXT, "open64");
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) { va_list ap; va_start(ap, flags); mode = va_arg(ap, mode_t); va_end(ap); }
    MAP(m, path);
    return sysnone(path, real(m, flags, mode));
}
int openat(int fd, const char *path, int flags, ...) {
    static int (*real)(int, const char *, int, ...);
    if (!real) real = dlsym(RTLD_NEXT, "openat");
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) { va_list ap; va_start(ap, flags); mode = va_arg(ap, mode_t); va_end(ap); }
    MAP(m, path);
    return sysnone(path, real(fd, m, flags, mode));
}
int openat64(int fd, const char *path, int flags, ...) {
    static int (*real)(int, const char *, int, ...);
    if (!real) real = dlsym(RTLD_NEXT, "openat64");
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) { va_list ap; va_start(ap, flags); mode = va_arg(ap, mode_t); va_end(ap); }
    MAP(m, path);
    return sysnone(path, real(fd, m, flags, mode));
}
int creat(const char *path, mode_t mode) { MAP(m, path); return open(m, O_CREAT | O_WRONLY | O_TRUNC, mode); }
int creat64(const char *path, mode_t mode) { MAP(m, path); return open64(m, O_CREAT | O_WRONLY | O_TRUNC, mode); }
/* the _FORTIFY_SOURCE forms the runtime's libraries call */
int __open_2(const char *path, int flags) { return open(path, flags); }
int __open64_2(const char *path, int flags) { return open64(path, flags); }
int __openat_2(int fd, const char *path, int flags) { return openat(fd, path, flags); }
int __openat64_2(int fd, const char *path, int flags) { return openat64(fd, path, flags); }

FILE *fopen(const char *path, const char *mode) { NEXT(fopen); MAP(m, path); return real(m, mode); }
FILE *fopen64(const char *path, const char *mode) { NEXT(fopen64); MAP(m, path); return real(m, mode); }

/* glibc's shm_open/unlink use hidden file calls, bypassing the open/unlink hooks. Box64 uses these
   objects to synthesize /proc/cpuinfo; falling back to Android's ARM data breaks x86 CPU detection. */
static int shm_path(const char *name, char *path, size_t size) {
    while (*name == '/') name++;
    size_t len = strlen(name);
    if (len > NAME_MAX) { errno = ENAMETOOLONG; return -1; }
    if (!len || strchr(name, '/') || !strcmp(name, ".") || !strcmp(name, "..")) {
        errno = EINVAL;
        return -1;
    }
    snprintf(path, size, "/dev/shm/%s", name);
    return 0;
}

int shm_open(const char *name, int flags, mode_t mode) {
    if (!on) { NEXT(shm_open); return real(name, flags, mode); }
    char path[NAME_MAX + 10];
    if (shm_path(name, path, sizeof path) < 0) return -1;
    NEXT(open64);
    MAP(m, path);
    int fd = real(m, flags | O_NOFOLLOW | O_CLOEXEC, mode);
    if (fd < 0 && errno == EISDIR) errno = EINVAL;
    return fd;
}

int shm_unlink(const char *name) {
    if (!on) { NEXT(shm_unlink); return real(name); }
    char path[NAME_MAX + 10];
    if (shm_path(name, path, sizeof path) < 0) return -1;
    NEXT(unlink);
    MAP(m, path);
    return real(m);
}
FILE *freopen(const char *path, const char *mode, FILE *s) { NEXT(freopen); MAP(m, path); return real(m, mode, s); }
FILE *freopen64(const char *path, const char *mode, FILE *s) { NEXT(freopen64); MAP(m, path); return real(m, mode, s); }

#define PATH1(ret, name, decl, call) \
ret name decl { NEXT(name); MAP(m, path); return real call; }
#define PATH1S(name, decl, call) \
int name decl { NEXT(name); MAP(m, path); return sysnone(path, real call); }
PATH1S(stat, (const char *path, struct stat *st), (m, st))
PATH1S(lstat, (const char *path, struct stat *st), (m, st))
PATH1S(stat64, (const char *path, struct stat64 *st), (m, st))
PATH1S(lstat64, (const char *path, struct stat64 *st), (m, st))
PATH1S(access, (const char *path, int mode), (m, mode))
PATH1S(eaccess, (const char *path, int mode), (m, mode))
PATH1S(euidaccess, (const char *path, int mode), (m, mode))
PATH1(int, chdir, (const char *path), (m))
PATH1(DIR *, opendir, (const char *path), (m))
PATH1(int, mkdir, (const char *path, mode_t mode), (m, mode))
PATH1(int, rmdir, (const char *path), (m))
PATH1(int, unlink, (const char *path), (m))
PATH1(int, remove, (const char *path), (m))
PATH1(int, chmod, (const char *path, mode_t mode), (m, mode))
PATH1(int, chown, (const char *path, uid_t u, gid_t g), (m, u, g))
PATH1(int, lchown, (const char *path, uid_t u, gid_t g), (m, u, g))
PATH1(int, utime, (const char *path, const struct utimbuf *t), (m, t))
PATH1(int, utimes, (const char *path, const struct timeval t[2]), (m, t))
PATH1(int, lutimes, (const char *path, const struct timeval t[2]), (m, t))
PATH1(int, truncate, (const char *path, off_t len), (m, len))
PATH1(int, truncate64, (const char *path, off64_t len), (m, len))
PATH1(int, mknod, (const char *path, mode_t mode, dev_t dev), (m, mode, dev))
PATH1(int, mkfifo, (const char *path, mode_t mode), (m, mode))
PATH1(int, statfs, (const char *path, struct statfs *st), (m, st))
PATH1(int, statfs64, (const char *path, struct statfs64 *st), (m, st))
PATH1(int, statvfs, (const char *path, struct statvfs *st), (m, st))
PATH1(int, statvfs64, (const char *path, struct statvfs64 *st), (m, st))
PATH1(int, scandir, (const char *path, struct dirent ***l, int (*f)(const struct dirent *), int (*c)(const struct dirent **, const struct dirent **)), (m, l, f, c))
PATH1(int, scandir64, (const char *path, struct dirent64 ***l, int (*f)(const struct dirent64 *), int (*c)(const struct dirent64 **, const struct dirent64 **)), (m, l, f, c))

int fstatat(int fd, const char *path, struct stat *st, int flags) { NEXT(fstatat); MAP(m, path); return sysnone(path, real(fd, m, st, flags)); }
int fstatat64(int fd, const char *path, struct stat64 *st, int flags) { NEXT(fstatat64); MAP(m, path); return sysnone(path, real(fd, m, st, flags)); }
int statx(int fd, const char *path, int flags, unsigned mask, struct statx *st) { NEXT(statx); MAP(m, path); return sysnone(path, real(fd, m, flags, mask, st)); }
int faccessat(int fd, const char *path, int mode, int flags) { NEXT(faccessat); MAP(m, path); return sysnone(path, real(fd, m, mode, flags)); }
int mkdirat(int fd, const char *path, mode_t mode) { NEXT(mkdirat); MAP(m, path); return real(fd, m, mode); }
int unlinkat(int fd, const char *path, int flags) { NEXT(unlinkat); MAP(m, path); return real(fd, m, flags); }
int fchmodat(int fd, const char *path, mode_t mode, int flags) { NEXT(fchmodat); MAP(m, path); return real(fd, m, mode, flags); }
int fchownat(int fd, const char *path, uid_t u, gid_t g, int flags) { NEXT(fchownat); MAP(m, path); return real(fd, m, u, g, flags); }
int utimensat(int fd, const char *path, const struct timespec t[2], int flags) { NEXT(utimensat); MAP(m, path); return real(fd, m, t, flags); }
int mknodat(int fd, const char *path, mode_t mode, dev_t dev) { NEXT(mknodat); MAP(m, path); return real(fd, m, mode, dev); }
int mkfifoat(int fd, const char *path, mode_t mode) { NEXT(mkfifoat); MAP(m, path); return real(fd, m, mode); }

int rename(const char *a, const char *b) { NEXT(rename); MAP(x, a); MAP(y, b); return real(x, y); }
int renameat(int fa, const char *a, int fb, const char *b) { NEXT(renameat); MAP(x, a); MAP(y, b); return real(fa, x, fb, y); }
int renameat2(int fa, const char *a, int fb, const char *b, unsigned flags) { NEXT(renameat2); MAP(x, a); MAP(y, b); return real(fa, x, fb, y, flags); }
int link(const char *a, const char *b) { NEXT(link); MAP(x, a); MAP(y, b); return real(x, y); }
int linkat(int fa, const char *a, int fb, const char *b, int flags) { NEXT(linkat); MAP(x, a); MAP(y, b); return real(fa, x, fb, y, flags); }
int symlink(const char *target, const char *linkpath) { NEXT(symlink); MAP(y, linkpath); return real(target, y); }
int symlinkat(const char *target, int fd, const char *linkpath) { NEXT(symlinkat); MAP(y, linkpath); return real(target, fd, y); }

/* /proc/self/exe is the loader's file when a program is started through it: answer the program's own path. */
static int is_self_exe(const char *p) { return p && self_path[0] && strcmp(p, "/proc/self/exe") == 0; }

static ssize_t answer_self(char *buf, size_t size) {
    size_t n = strlen(self_path);
    if (n > size) n = size;
    memcpy(buf, self_path, n);
    return (ssize_t)n;
}

ssize_t readlink(const char *path, char *buf, size_t size) {
    NEXT(readlink);
    if (on && is_self_exe(path)) return answer_self(buf, size);
    MAP(m, path);
    return real(m, buf, size);
}
ssize_t readlinkat(int fd, const char *path, char *buf, size_t size) {
    NEXT(readlinkat);
    if (on && is_self_exe(path)) return answer_self(buf, size);
    MAP(m, path);
    return real(fd, m, buf, size);
}
ssize_t __readlink_chk(const char *path, char *buf, size_t size, size_t buflen) { (void)buflen; return readlink(path, buf, size); }
ssize_t __readlinkat_chk(int fd, const char *path, char *buf, size_t size, size_t buflen) { (void)buflen; return readlinkat(fd, path, buf, size); }

char *realpath(const char *path, char *resolved) {
    NEXT(realpath);
    if (on && is_self_exe(path)) {
        if (!resolved) return strdup(self_path);
        snprintf(resolved, PATH_MAX, "%s", self_path);
        return resolved;
    }
    MAP(m, path);
    return real(m, resolved);
}
char *__realpath_chk(const char *path, char *resolved, size_t len) { (void)len; return realpath(path, resolved); }
char *canonicalize_file_name(const char *path) { return realpath(path, NULL); }

void *dlopen(const char *file, int mode) { NEXT(dlopen); MAP(m, file); return real(m, mode); }
int inotify_add_watch(int fd, const char *path, uint32_t mask) { NEXT(inotify_add_watch); MAP(m, path); return real(fd, m, mask); }

/* ---- temporary names: glibc makes the folder or file inside itself, past the hooks above; the template is filled in place, so the mapped copy's tail goes back
   to the caller's. {suffix} is the number of characters after the Xs. */

static void tmpl_back(char *tmpl, const char *m, size_t suffix) {
    size_t tl = strlen(tmpl), ml = strlen(m);
    if (tl >= 6 + suffix && ml >= 6 + suffix) memcpy(tmpl + tl - 6 - suffix, m + ml - 6 - suffix, 6);
}
char *mkdtemp(char *tmpl) {
    static char *(*real)(char *);
    if (!real) real = dlsym(RTLD_NEXT, "mkdtemp");
    char b[BUF];
    const char *m = mp(tmpl, b);
    if (m == tmpl) return real(tmpl);
    char *r = real((char *)m);
    if (r) tmpl_back(tmpl, m, 0);
    return r ? tmpl : NULL;
}
int mkstemp(char *tmpl) {
    static int (*real)(char *);
    if (!real) real = dlsym(RTLD_NEXT, "mkstemp");
    char b[BUF]; const char *m = mp(tmpl, b);
    if (m == tmpl) return real(tmpl);
    int fd = real((char *)m);
    if (fd >= 0) tmpl_back(tmpl, m, 0);
    return fd;
}
int mkstemp64(char *tmpl) {
    static int (*real)(char *);
    if (!real) real = dlsym(RTLD_NEXT, "mkstemp64");
    char b[BUF]; const char *m = mp(tmpl, b);
    if (m == tmpl) return real(tmpl);
    int fd = real((char *)m);
    if (fd >= 0) tmpl_back(tmpl, m, 0);
    return fd;
}
int mkostemp(char *tmpl, int flags) {
    static int (*real)(char *, int);
    if (!real) real = dlsym(RTLD_NEXT, "mkostemp");
    char b[BUF]; const char *m = mp(tmpl, b);
    if (m == tmpl) return real(tmpl, flags);
    int fd = real((char *)m, flags);
    if (fd >= 0) tmpl_back(tmpl, m, 0);
    return fd;
}
int mkostemp64(char *tmpl, int flags) {
    static int (*real)(char *, int);
    if (!real) real = dlsym(RTLD_NEXT, "mkostemp64");
    char b[BUF]; const char *m = mp(tmpl, b);
    if (m == tmpl) return real(tmpl, flags);
    int fd = real((char *)m, flags);
    if (fd >= 0) tmpl_back(tmpl, m, 0);
    return fd;
}
int mkstemps(char *tmpl, int suffix) {
    static int (*real)(char *, int);
    if (!real) real = dlsym(RTLD_NEXT, "mkstemps");
    char b[BUF]; const char *m = mp(tmpl, b);
    if (m == tmpl) return real(tmpl, suffix);
    int fd = real((char *)m, suffix);
    if (fd >= 0) tmpl_back(tmpl, m, (size_t)suffix);
    return fd;
}
int mkstemps64(char *tmpl, int suffix) {
    static int (*real)(char *, int);
    if (!real) real = dlsym(RTLD_NEXT, "mkstemps64");
    char b[BUF]; const char *m = mp(tmpl, b);
    if (m == tmpl) return real(tmpl, suffix);
    int fd = real((char *)m, suffix);
    if (fd >= 0) tmpl_back(tmpl, m, (size_t)suffix);
    return fd;
}

/* ---- dlsym: a program that looks libc's functions up in libc's own handle (Box64 does, for the functions it wraps) skips the preload. Here such a
   lookup gets this library's version of the function, when it has one. ----------------------------------------------------------------- */

static void *(*real_dlsym)(void *, const char *);
static void *(*real_dlvsym)(void *, const char *, const char *);
static void *self_handle;

/* libc's own dlsym and dlvsym, found in its symbol table (asking dlsym for them would come back here). */
struct libc_find { const char *name; void *addr; };
static int libc_cb(struct dl_phdr_info *info, size_t size, void *data) {
    (void)size;
    struct libc_find *f = data;
    if (!info->dlpi_name || !strstr(info->dlpi_name, "libc.so")) return 0;
    const ElfW(Dyn) *dyn = NULL;
    for (int i = 0; i < info->dlpi_phnum; i++)
        if (info->dlpi_phdr[i].p_type == PT_DYNAMIC) dyn = (const ElfW(Dyn) *)(info->dlpi_addr + info->dlpi_phdr[i].p_vaddr);
    if (!dyn) return 0;
    const ElfW(Sym) *symtab = NULL; const char *strtab = NULL;
    for (; dyn->d_tag != DT_NULL; dyn++) {
        if (dyn->d_tag == DT_SYMTAB) symtab = (const ElfW(Sym) *)dyn->d_un.d_ptr;
        else if (dyn->d_tag == DT_STRTAB) strtab = (const char *)dyn->d_un.d_ptr;
    }
    if (!symtab || !strtab || (const char *)symtab >= strtab) return 0;
    size_t n = (size_t)(strtab - (const char *)symtab) / sizeof *symtab;
    for (size_t i = 1; i < n; i++) {
        if (symtab[i].st_shndx != SHN_UNDEF && symtab[i].st_value && ELF64_ST_TYPE(symtab[i].st_info) == STT_FUNC && strcmp(strtab + symtab[i].st_name, f->name) == 0) {
            f->addr = (void *)(info->dlpi_addr + symtab[i].st_value);
            return 1;
        }
    }
    return 0;
}
static void *libc_symbol(const char *name) {
    struct libc_find f = { name, NULL };
    dl_iterate_phdr(libc_cb, &f);
    return f.addr;
}
static void dl_real(void) {
    if (!real_dlsym) real_dlsym = libc_symbol("dlsym");
    if (!real_dlvsym) real_dlvsym = libc_symbol("dlvsym");
}

static void *mine(void *handle, void *found, const char *name) {
    if (!on || !found || handle == RTLD_NEXT || handle == self_handle) return found;
    Dl_info info;
    if (!dladdr(found, &info) || !info.dli_fname || !strstr(info.dli_fname, "libc.so")) return found;
    if (!self_handle) {
        Dl_info me;
        if (dladdr((void *)mine, &me) && me.dli_fname) self_handle = dlopen(me.dli_fname, RTLD_NOW | RTLD_NOLOAD);
        if (!self_handle) return found;
    }
    void *own = real_dlsym(self_handle, name);
    return own ? own : found;
}

void *dlsym(void *handle, const char *name) {
    dl_real();
    /* RTLD_NEXT means "after the library that asks": a tail call keeps the caller's return address. */
    if (handle == RTLD_NEXT) __attribute__((musttail)) return real_dlsym(handle, name);
    void *found = real_dlsym(handle, name);
    return mine(handle, found, name);
}

void *dlvsym(void *handle, const char *name, const char *version) {
    dl_real();
    if (handle == RTLD_NEXT) __attribute__((musttail)) return real_dlvsym(handle, name, version);
    void *found = real_dlvsym(handle, name, version);
    return mine(handle, found, name);
}

/* ---- raw system calls: syscall(SYS_x, ...) (what Box64 does for the guest's own "syscall" instruction) ------------------ */

long syscall(long nr, ...) {
    static long (*real)(long, ...);
    if (!real) real = dlsym(RTLD_NEXT, "syscall");
    va_list ap;
    va_start(ap, nr);
    unsigned long a[6];
    for (int i = 0; i < 6; i++) a[i] = va_arg(ap, unsigned long);
    va_end(ap);
    char b0[BUF], b1[BUF];
    int p0 = -1, p1 = -1;
    switch (nr) {
#ifdef SYS_openat
    case SYS_openat: case 437 /* openat2 */: case SYS_mkdirat: case SYS_unlinkat: case SYS_faccessat: case 439 /* faccessat2 */:
    case SYS_fchmodat: case SYS_fchownat: case SYS_readlinkat: case SYS_newfstatat: case SYS_utimensat: case SYS_statx:
    case SYS_mknodat:
        p0 = 1; break;
    case SYS_symlinkat: p0 = 2; break;
    case SYS_linkat: case SYS_renameat: case 276 /* renameat2 */: p0 = 1; p1 = 3; break;
    case SYS_chdir: case SYS_statfs: case SYS_truncate: case SYS_chroot: p0 = 0; break;
#endif
    default: break;
    }
    if (p0 >= 0 && a[p0]) a[p0] = (unsigned long)mp((const char *)a[p0], b0);
    if (p1 >= 0 && a[p1]) a[p1] = (unsigned long)mp((const char *)a[p1], b1);
    return real(nr, a[0], a[1], a[2], a[3], a[4], a[5]);
}

/* ---- sockets: a path in sockaddr_un ------------------------------------------------------------------------------------- */

/* A socket path under the phone's long app folder does not fit the 107 bytes of sun_path (the guest's short /root/... did): the socket is named
   through its folder, as /proc/self/fd/<folder>/<name>. *dirfd is that folder, to be closed after the call. */
static int map_un(const struct sockaddr *addr, socklen_t len, struct sockaddr_un *out, socklen_t *olen, int *dirfd) {
    *dirfd = -1;
    if (!on || !addr || addr->sa_family != AF_UNIX || len <= sizeof(sa_family_t) || len > sizeof *out) return 0;
    const struct sockaddr_un *un = (const struct sockaddr_un *)addr;
    if (un->sun_path[0] != '/') return 0; /* abstract or relative */
    char tmp[BUF];
    size_t n = len - offsetof(struct sockaddr_un, sun_path);
    if (n >= sizeof tmp) n = sizeof tmp - 1;
    memcpy(tmp, un->sun_path, n);
    tmp[n] = 0;
    char b[BUF];
    const char *m = mp(tmp, b);
    if (m == tmp) return 0;
    memset(out, 0, sizeof *out);
    out->sun_family = AF_UNIX;
    if (strlen(m) >= sizeof out->sun_path) {
        char dir[BUF];
        snprintf(dir, sizeof dir, "%s", m);
        char *slash = strrchr(dir, '/');
        if (!slash || slash == dir) return 0;
        *slash = 0;
        int d = open(dir, O_PATH | O_DIRECTORY | O_CLOEXEC);
        if (d < 0) return 0;
        char short_path[sizeof out->sun_path];
        if (snprintf(short_path, sizeof short_path, "/proc/self/fd/%d/%s", d, slash + 1) >= (int)sizeof short_path) { close(d); return 0; }
        strcpy(out->sun_path, short_path);
        *dirfd = d;
        *olen = offsetof(struct sockaddr_un, sun_path) + strlen(short_path) + 1;
        return 1;
    }
    strcpy(out->sun_path, m);
    *olen = offsetof(struct sockaddr_un, sun_path) + strlen(m) + 1;
    return 1;
}

int connect(int fd, const struct sockaddr *addr, socklen_t len) {
    NEXT(connect);
    struct sockaddr_un un; socklen_t ul; int dirfd;
    if (map_un(addr, len, &un, &ul, &dirfd)) {
        int r = real(fd, (struct sockaddr *)&un, ul);
        int e = errno;
        if (dirfd >= 0) close(dirfd);
        errno = e;
        return r;
    }
    return real(fd, addr, len);
}
int bind(int fd, const struct sockaddr *addr, socklen_t len) {
    NEXT(bind);
    struct sockaddr_un un; socklen_t ul; int dirfd;
    if (map_un(addr, len, &un, &ul, &dirfd)) {
        int r = real(fd, (struct sockaddr *)&un, ul);
        int e = errno;
        if (dirfd >= 0) close(dirfd);
        errno = e;
        return r;
    }
    return real(fd, addr, len);
}

/* ---- starting programs --------------------------------------------------------------------------------------------------- */

typedef int (*execve_fn)(const char *, char *const[], char *const[]);

#define MAXARGS 4096

static int is_glibc_elf(const char *file, char *interp, size_t size) {
    int fd = ((int (*)(const char *, int, ...))dlsym(RTLD_NEXT, "open"))(file, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1; /* errno from the open */
    unsigned char h[4096];
    ssize_t n = read(fd, h, sizeof h);
    close(fd);
    if (n >= 4 && h[0] == 0x7f && h[1] == 'E' && h[2] == 'L' && h[3] == 'F') {
        /* the interpreter named in the first bytes ("/lib/ld-linux-aarch64.so.1"); a program that has none (static) starts as it is */
        for (ssize_t i = 0; i + 8 < n; i++) {
            if (h[i] == '/' && memcmp(h + i, "/lib/ld-linux-", 14) == 0) return 1;
        }
        return 0;
    }
    if (n >= 2 && h[0] == '#' && h[1] == '!') {
        ssize_t i = 2;
        while (i < n && (h[i] == ' ' || h[i] == '\t')) i++;
        ssize_t j = i;
        while (j < n && h[j] != '\n' && h[j] != 0) j++;
        size_t len = (size_t)(j - i);
        if (len >= size) len = size - 1;
        memcpy(interp, h + i, len);
        interp[len] = 0;
        return 2;
    }
    return 0;
}

/* The loader's library path for a child: its own $LD_LIBRARY_PATH (guest paths, translated), then the runtime's folder. */
static void child_libpath(char *const envp[], char *out, size_t size) {
    out[0] = 0;
    const char *ld = NULL;
    for (int i = 0; envp && envp[i]; i++) if (strncmp(envp[i], "LD_LIBRARY_PATH=", 16) == 0) ld = envp[i] + 16;
    size_t used = 0;
    while (ld && *ld) {
        const char *end = strchr(ld, ':');
        size_t len = end ? (size_t)(end - ld) : strlen(ld);
        if (len > 0 && len < BUF) {
            char one[BUF], b[BUF];
            memcpy(one, ld, len);
            one[len] = 0;
            const char *m = mp(one, b);
            int n = snprintf(out + used, size - used, "%s%s", used ? ":" : "", m);
            if (n > 0 && used + (size_t)n < size) used += (size_t)n;
        }
        if (!end) break;
        ld = end + 1;
    }
    snprintf(out + used, size - used, "%s%s", used ? ":" : "", libpath);
}

/* The environment of the child: $SKY_SELF names the program. */
static char **env_with_self(char *const envp[], const char *self) {
    int n = 0;
    while (envp && envp[n]) n++;
    char **out = malloc((size_t)(n + 2) * sizeof *out);
    int k = 0;
    for (int i = 0; i < n; i++) if (strncmp(envp[i], "SKY_SELF=", 9) != 0) out[k++] = envp[i];
    char *entry = malloc(strlen(self) + 10);
    sprintf(entry, "SKY_SELF=%s", self);
    out[k++] = entry;
    out[k] = NULL;
    return out;
}

/*
 * What to run for {file} with {argv}: fills {path} and {args} (NULL terminated) and {envp_out} (the environment to give).
 * 0 on success (the program can be started), -1 with errno otherwise.
 */
static int prepare(const char *file, char *const argv[], char *const envp[], char *path, char **args, char ***envp_out, int depth) {
    char b[BUF];
    const char *m = mp(file, b);
    char interp[BUF];
    int kind = is_glibc_elf(m, interp, sizeof interp);
    if (kind < 0) return -1;
    int argc = 0;
    while (argv && argv[argc]) argc++;
    if (argc > MAXARGS - 8) { errno = E2BIG; return -1; }
    if (kind == 1) {
        int k = 0;
        snprintf(path, BUF, "%s", loader);
        args[k++] = path;
        args[k++] = "--library-path";
        char *lp = malloc(BUF * 4);
        child_libpath(envp, lp, BUF * 4);
        args[k++] = lp;
        args[k++] = "--argv0";
        args[k++] = (char *)(argc > 0 ? argv[0] : file);
        args[k++] = strdup(m);
        for (int i = 1; i < argc; i++) args[k++] = argv[i];
        args[k] = NULL;
        *envp_out = env_with_self(envp, m);
        return 0;
    }
    if (kind == 2 && depth < 4) {
        /* the script runs under its interpreter: "interp [argument] script arguments..." */
        char *arg = NULL;
        char *space = strpbrk(interp, " \t");
        if (space) { *space = 0; arg = space + 1; while (*arg == ' ' || *arg == '\t') arg++; if (!*arg) arg = NULL; }
        char *nargv[MAXARGS];
        int k = 0;
        nargv[k++] = strdup(interp);
        if (arg) nargv[k++] = strdup(arg);
        nargv[k++] = strdup(m);
        for (int i = 1; i < argc; i++) nargv[k++] = argv[i];
        nargv[k] = NULL;
        return prepare(interp, nargv, envp, path, args, envp_out, depth + 1);
    }
    snprintf(path, BUF, "%s", m);
    int k = 0;
    for (; k < argc; k++) args[k] = argv[k];
    args[k] = NULL;
    *envp_out = (char **)envp;
    return 0;
}

static int sp_execve(execve_fn next, const char *file, char *const argv[], char *const envp[]) {
    if (!on) return next(file, argv, envp);
    if (is_self_exe(file)) file = self_path;
    char path[BUF];
    char *args[MAXARGS];
    char **env = NULL;
    if (prepare(file, argv, envp, path, args, &env, 0) < 0) return -1;
    return next(path, args, env);
}

int execve(const char *file, char *const argv[], char *const envp[]) {
    static execve_fn next;
    if (!next) next = dlsym(RTLD_NEXT, "execve");
    return sp_execve(next, file, argv, envp);
}

extern char **environ;

int execv(const char *file, char *const argv[]) { return execve(file, argv, environ); }

/* A bare name is looked for in the guest's $PATH. */
static const char *search(const char *file, char *buffer, size_t size) {
    if (strchr(file, '/')) return file;
    const char *path = getenv("PATH");
    if (!path) path = "/usr/local/bin:/usr/bin:/bin";
    while (*path) {
        const char *end = strchr(path, ':');
        size_t len = end ? (size_t)(end - path) : strlen(path);
        if (len == 0) { snprintf(buffer, size, "%s", file); }
        else snprintf(buffer, size, "%.*s/%s", (int)len, path, file);
        char b[BUF];
        const char *m = mp(buffer, b);
        if (access(m, X_OK) == 0) {
            struct stat st;
            if (stat(m, &st) == 0 && S_ISREG(st.st_mode)) return buffer;
        }
        if (!end) break;
        path = end + 1;
    }
    errno = ENOENT;
    return NULL;
}

int execvpe(const char *file, char *const argv[], char *const envp[]) {
    char buf[BUF];
    const char *found = search(file, buf, sizeof buf);
    if (!found) return -1;
    return execve(found, argv, envp);
}
int execvp(const char *file, char *const argv[]) { return execvpe(file, argv, environ); }

int execl(const char *path, const char *arg, ...) {
    char *argv[MAXARGS];
    int n = 0;
    va_list ap;
    va_start(ap, arg);
    argv[n++] = (char *)arg;
    while (n < MAXARGS - 1 && (argv[n] = va_arg(ap, char *))) n++;
    va_end(ap);
    argv[n] = NULL;
    return execve(path, argv, environ);
}
int execlp(const char *file, const char *arg, ...) {
    char *argv[MAXARGS];
    int n = 0;
    va_list ap;
    va_start(ap, arg);
    argv[n++] = (char *)arg;
    while (n < MAXARGS - 1 && (argv[n] = va_arg(ap, char *))) n++;
    va_end(ap);
    argv[n] = NULL;
    return execvp(file, argv);
}
int execle(const char *path, const char *arg, ...) {
    char *argv[MAXARGS];
    int n = 0;
    va_list ap;
    va_start(ap, arg);
    argv[n++] = (char *)arg;
    while (n < MAXARGS - 1 && (argv[n] = va_arg(ap, char *))) n++;
    char *const *envp = va_arg(ap, char *const *);
    va_end(ap);
    argv[n] = NULL;
    return execve(path, argv, envp);
}

typedef int (*spawn_fn)(pid_t *, const char *, const posix_spawn_file_actions_t *, const posix_spawnattr_t *, char *const[], char *const[]);

static int spawn_wrapped(spawn_fn next, int searching, pid_t *pid, const char *file, const posix_spawn_file_actions_t *actions,
                         const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
    if (!on) return next(pid, file, actions, attr, argv, envp);
    char buf[BUF];
    if (searching) {
        const char *found = search(file, buf, sizeof buf);
        if (!found) return ENOENT;
        file = found;
    }
    if (is_self_exe(file)) file = self_path;
    char path[BUF];
    char *args[MAXARGS];
    char **env = NULL;
    if (prepare(file, argv, envp, path, args, &env, 0) < 0) return errno;
    /* the program is a real path now: posix_spawn, not posix_spawnp */
    static spawn_fn plain;
    if (!plain) plain = dlsym(RTLD_NEXT, "posix_spawn");
    return plain(pid, path, actions, attr, args, env);
}

int posix_spawn(pid_t *pid, const char *path, const posix_spawn_file_actions_t *actions, const posix_spawnattr_t *attr,
                char *const argv[], char *const envp[]) {
    static spawn_fn next;
    if (!next) next = dlsym(RTLD_NEXT, "posix_spawn");
    return spawn_wrapped(next, 0, pid, path, actions, attr, argv, envp);
}
int posix_spawnp(pid_t *pid, const char *file, const posix_spawn_file_actions_t *actions, const posix_spawnattr_t *attr,
                 char *const argv[], char *const envp[]) {
    static spawn_fn next;
    if (!next) next = dlsym(RTLD_NEXT, "posix_spawnp");
    return spawn_wrapped(next, 1, pid, file, actions, attr, argv, envp);
}

/* system() and popen() start "/bin/sh" inside libc, where nothing can be interposed: do it here. */
int system(const char *command) {
    NEXT(system);
    if (!on) return real(command);
    if (!command) return 1;
    pid_t pid;
    char *argv[] = {"sh", "-c", (char *)command, NULL};
    int err = posix_spawn(&pid, "/bin/sh", NULL, NULL, argv, environ);
    if (err) return -1;
    int status;
    while (waitpid(pid, &status, 0) < 0 && errno == EINTR) {}
    return status;
}

/* popen() starts "/bin/sh" inside libc too (the phone's own sh, which cannot load the runtime's libraries): start it here. */
#define MAXPOPEN 1024
static pid_t popen_pid[MAXPOPEN];

FILE *popen(const char *command, const char *type) {
    NEXT(popen);
    if (!on) return real(command, type);
    int reading = type && type[0] == 'r';
    int p[2];
    if (pipe2(p, O_CLOEXEC) < 0) return NULL;
    pid_t pid = fork();
    if (pid < 0) { close(p[0]); close(p[1]); return NULL; }
    if (pid == 0) {
        if (reading) { dup2(p[1], 1); } else { dup2(p[0], 0); }
        close(p[0]);
        close(p[1]);
        char *argv[] = {"sh", "-c", (char *)command, NULL};
        execve("/bin/sh", argv, environ);
        _exit(127);
    }
    int keep = reading ? p[0] : p[1];
    close(reading ? p[1] : p[0]);
    FILE *f = fdopen(keep, reading ? "r" : "w");
    if (!f) { close(keep); return NULL; }
    if (keep < MAXPOPEN) popen_pid[keep] = pid;
    return f;
}

int pclose(FILE *f) {
    NEXT(pclose);
    if (!on) return real(f);
    int fd = fileno(f);
    pid_t pid = (fd >= 0 && fd < MAXPOPEN) ? popen_pid[fd] : 0;
    if (!pid) return real(f);
    popen_pid[fd] = 0;
    fclose(f);
    int status;
    while (waitpid(pid, &status, 0) < 0 && errno == EINTR) {}
    return status;
}
