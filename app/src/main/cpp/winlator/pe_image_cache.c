/* MIT. Native Wine PE-section backing cache. Never changes a game file.
 * Wine falls back to anonymous pread for sector-aligned PE offsets that mmap
 * cannot accept. Give those exact read-only image sections a shared, page-aligned
 * backing file instead. MAP_PRIVATE retains per-process relocation/import COW.
 * Opt-in preload scope is native ARM64 Wine only; all failures retain Wine's
 * original mmap failure/pread path. See tools/pe-image-cache-test/README.md.
 */
#define _GNU_SOURCE
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>
#ifdef __ANDROID__
#include <android/log.h>
#endif
#ifdef __APPLE__
#define MTIME(st) ((st).st_mtimespec)
#define CTIME(st) ((st).st_ctimespec)
#else
#define MTIME(st) ((st).st_mtim)
#define CTIME(st) ((st).st_ctim)
#endif
#define EXPORT __attribute__((visibility("default")))
#define CACHE_CAP (1024ULL * 1024 * 1024)
#define MAX_SECTION (256ULL * 1024 * 1024)
#define MAX_ENTRIES 4096
static int enabled;
static int stats_enabled;
static size_t page_size;
static char cache_path[PATH_MAX];
static __thread int in_hook;
static _Atomic uint64_t hits, created, mapped_bytes, failures;
static _Atomic uint64_t mmap_calls, cache_attempts, section_matches, large_probes;
#ifndef SKY_PE_CACHE_TEST
static void *(*next_mmap)(void *,size_t,int,int,int,off_t);
#endif
/* No uninitialized padding in the serialized key. Contains no names/paths. */
typedef struct { uint64_t magic, dev, ino, size, msec, mnsec, csec, cnsec, offset, length; } Key;
#define KEY_MAGIC 0x534b595045000001ULL

static uint16_t u16(const unsigned char *p) { return p[0] | ((uint16_t)p[1] << 8); }
static uint32_t u32(const unsigned char *p) { return u16(p) | ((uint32_t)u16(p+2) << 16); }
static int read_at(int fd, void *data, size_t size, off_t off) {
    char *p=data;
    while (size) {
        ssize_t n=pread(fd,p,size,off);
        if (n<0 && errno==EINTR) continue;
        if (n<=0) return 0;
        p+=n; off+=n; size-=n;
    }
    return 1;
}
static Key source_key(const struct stat *s, off_t offset, size_t length) {
    return (Key){KEY_MAGIC,s->st_dev,s->st_ino,s->st_size,MTIME(*s).tv_sec,MTIME(*s).tv_nsec,
                 CTIME(*s).tv_sec,CTIME(*s).tv_nsec,(uint64_t)offset,length};
}
static uint64_t round_page(uint64_t n) { return (n+page_size-1)&~(uint64_t)(page_size-1); }
/* Android filesystems can give several writes identical coarse timestamps.
 * Never create/use backing while a file could still change within that tick.
 * Recent/future timestamps use Wine's original read path, with no launch delay. */
static int source_quiet(const Key *key) {
    struct timespec now;
    if (clock_gettime(CLOCK_REALTIME,&now) || now.tv_sec<0) return 0;
    uint64_t secs[2]={key->msec,key->csec}, nanos[2]={key->mnsec,key->cnsec};
    for (unsigned i=0;i<2;i++) {
        if (secs[i]>(uint64_t)now.tv_sec || (uint64_t)now.tv_sec-secs[i]<3) return 0;
        if ((uint64_t)now.tv_sec-secs[i]==3 && nanos[i]>(uint64_t)now.tv_nsec) return 0;
    }
    return 1;
}

/* Accept complete PE headers or Wine's sector-rounded read-only raw section
 * range, never arbitrary file mappings or writable/shared sections. */
static int section_key(int fd, off_t offset, size_t length, Key *key) {
    struct stat s;
    unsigned char dos[64], nt[24], opt[64], sec[40];
    if (fstat(fd,&s) || !S_ISREG(s.st_mode) || s.st_size<64 ||
        !read_at(fd,dos,sizeof(dos),0) || u16(dos)!=0x5a4d) return 0;
    uint64_t pe=u32(dos+60);
    if (pe<64 || pe>1024*1024 || pe+24> (uint64_t)s.st_size ||
        !read_at(fd,nt,sizeof(nt),pe) || u32(nt)!=0x4550 || u16(nt+4)!=0xaa64) return 0;
    unsigned count=u16(nt+6), optional=u16(nt+20);
    if (!count || count>96 || optional<sizeof(opt) ||
        pe+24+optional+(uint64_t)count*40>(uint64_t)s.st_size ||
        !read_at(fd,opt,sizeof(opt),pe+24) || u16(opt)!=0x20b || u32(opt+32)<4096) return 0;
    uint64_t headers=u32(opt+60);
    if (!offset && length==headers && headers<=(uint64_t)s.st_size &&
        pe+24+optional+(uint64_t)count*40<=headers) {
        *key=source_key(&s,0,length);
        return 1;
    }
    for (unsigned i=0;i<count;i++) {
        if (!read_at(fd,sec,sizeof(sec),pe+24+optional+i*40)) return 0;
        uint64_t virt=u32(sec+8), va=u32(sec+12), raw=u32(sec+16), pos=u32(sec+20);
        uint32_t flags=u32(sec+36);
        uint64_t start=pos&~511ULL, bytes=(raw+(pos&511)+511)&~511ULL;
        uint64_t virtual_bytes=round_page(virt ? virt : raw);
        if (bytes>virtual_bytes) bytes=virtual_bytes;
        if (start!=(uint64_t)offset || bytes!=length || !raw || pos<u32(opt+60) ||
            pos+raw>(uint64_t)s.st_size || start+bytes>(uint64_t)s.st_size ||
            va%page_size || va+virtual_bytes>round_page(u32(opt+56)) ||
            !(flags&0x40000000) || (flags&(0x80000000|0x10000000)) || !(flags&0x60)) continue;
        *key=source_key(&s,offset,length);
        return 1;
    }
    return 0;
}
static void key_name(const Key *key,char *out,size_t capacity) {
    snprintf(out,capacity,"%llx-%llx-%llx-%llx-%llx-%llx-%llx-%llx-%llx.img",
             (unsigned long long)key->dev,(unsigned long long)key->ino,(unsigned long long)key->size,
             (unsigned long long)key->msec,(unsigned long long)key->mnsec,
             (unsigned long long)key->csec,(unsigned long long)key->cnsec,
             (unsigned long long)key->offset,(unsigned long long)key->length);
}
static int private_file(int fd) {
    struct stat s;
    return !fstat(fd,&s) && S_ISREG(s.st_mode) && s.st_uid==geteuid() && s.st_nlink==1 && !(s.st_mode&077);
}
static int cached_fd(int dir,const char *name,const Key *key) {
    int fd=openat(dir,name,O_RDONLY|O_CLOEXEC|O_NOFOLLOW);
    struct stat s;Key stored;
    if (fd<0) return -1;
    if (!private_file(fd) || fstat(fd,&s) || (uint64_t)s.st_size!=page_size+round_page(key->length) ||
        !read_at(fd,&stored,sizeof(stored),0) || memcmp(&stored,key,sizeof(stored))) {
        close(fd);return -1;
    }
    return fd;
}
static int suffix(const char *s,const char *end) {
    size_t a=strlen(s),b=strlen(end);return a>=b && !strcmp(s+a-b,end);
}
/* Called only under the interprocess lock. Unlinking mapped backing is safe:
 * live mappings retain their inode. Bound scans and disk use, including stale
 * temporary files left by a killed writer. Fail open to Wine on any uncertainty. */
static int make_room(int dir,uint64_t required) {
    for (unsigned pass=0;pass<=MAX_ENTRIES;pass++) {
        DIR *stream=fdopendir(openat(dir,".",O_RDONLY|O_DIRECTORY|O_CLOEXEC));
        if (!stream) return 0;
        uint64_t total=0;unsigned count=0;char oldest[NAME_MAX+1]={0};struct timespec stamp={0};
        struct dirent *entry;
        while ((entry=readdir(stream))) {
            if (suffix(entry->d_name,".tmp")) { unlinkat(dir,entry->d_name,0);continue; }
            if (!suffix(entry->d_name,".img")) continue;
            struct stat s;
            if (++count>MAX_ENTRIES || fstatat(dir,entry->d_name,&s,AT_SYMLINK_NOFOLLOW) ||
                !S_ISREG(s.st_mode) || s.st_uid!=geteuid() || s.st_size<0) {
                closedir(stream);return 0;
            }
            total+=(uint64_t)s.st_size;
            struct timespec t=MTIME(s);
            if (!oldest[0] || t.tv_sec<stamp.tv_sec || (t.tv_sec==stamp.tv_sec && t.tv_nsec<stamp.tv_nsec)) {
                snprintf(oldest,sizeof(oldest),"%s",entry->d_name);stamp=t;
            }
        }
        closedir(stream);
        if (total+required<=CACHE_CAP) return 1;
        if (!oldest[0] || unlinkat(dir,oldest,0)) return 0;
    }
    return 0;
}
static int backing_fd(int source,const Key *key) {
    int dir=-1,lock=-1,out=-1,result=-1;char name[NAME_MAX+1],temp[80]={0};
    unsigned char *buffer=NULL;
    struct stat st;
    dir=open(cache_path,O_RDONLY|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW);
    if (dir<0 || fstat(dir,&st) || st.st_uid!=geteuid() || (st.st_mode&077)) goto done;
    key_name(key,name,sizeof(name));
    result=cached_fd(dir,name,key);
    if (result>=0) { atomic_fetch_add(&hits,1);goto done; }
    lock=openat(dir,".lock",O_CREAT|O_RDWR|O_CLOEXEC|O_NOFOLLOW,0600);
    if (lock<0 || !private_file(lock) || flock(lock,LOCK_EX)) goto done;
    /* Another CEF process may have populated the same entry while we waited. */
    result=cached_fd(dir,name,key);
    if (result>=0) { atomic_fetch_add(&hits,1);goto done; }
    uint64_t bytes=page_size+round_page(key->length);
    if (!make_room(dir,bytes)) goto done;
    static _Atomic unsigned serial;
    snprintf(temp,sizeof(temp),"%ld-%u.tmp",(long)getpid(),atomic_fetch_add(&serial,1));
    out=openat(dir,temp,O_CREAT|O_EXCL|O_RDWR|O_CLOEXEC|O_NOFOLLOW,0600);
    if (out<0 || ftruncate(out,bytes) || pwrite(out,key,sizeof(*key),0)!=(ssize_t)sizeof(*key)) goto done;
    buffer=malloc(65536);
    if (!buffer) goto done;
    for (uint64_t offset=0;offset<key->length;) {
        size_t n=key->length-offset;if (n>65536) n=65536;
        if (!read_at(source,buffer,n,key->offset+offset)) goto done;
        size_t sent=0;
        while (sent<n) {
            ssize_t written=pwrite(out,buffer+sent,n-sent,page_size+offset+sent);
            if (written<0 && errno==EINTR) continue;
            if (written<=0) goto done;
            sent+=written;
        }
        offset+=n;
    }
    /* No incomplete/stale cache is published if the source changes during copy. */
    if (fstat(source,&st)) goto done;
    Key after=source_key(&st,key->offset,key->length);
    if (memcmp(key,&after,sizeof(after)) || fsync(out) || fchmod(out,0400) || renameat(dir,temp,dir,name)) goto done;
    temp[0]=0;
    close(out);out=-1;
    result=cached_fd(dir,name,key);
    if (result>=0) atomic_fetch_add(&created,1);
done:
    free(buffer);
    if (out>=0) close(out);
    if (temp[0] && dir>=0) unlinkat(dir,temp,0);
    if (lock>=0) close(lock);
    if (dir>=0) close(dir);
    return result;
}
static void *system_map(void *addr,size_t length,int prot,int flags,int fd,off_t off) {
#ifdef SKY_PE_CACHE_TEST
    return mmap(addr,length,prot,flags,fd,off);
#else
    if (next_mmap) return next_mmap(addr,length,prot,flags,fd,off);
    /* Only the resolver/bootstrap path can reach this before the constructor. */
    return (void *)syscall(SYS_mmap,addr,length,prot,flags,fd,off);
#endif
}
static int candidate(void *addr,size_t length,int prot,int flags,int fd,off_t offset) {
    return enabled && fd>=0 && offset>=0 &&
        (offset ? offset%page_size : (prot&PROT_EXEC)) && length && length<=MAX_SECTION &&
        addr && !((uintptr_t)addr%page_size) && flags==(MAP_PRIVATE|MAP_FIXED) &&
        (prot&PROT_READ) && !(prot&~(PROT_READ|PROT_WRITE|PROT_EXEC));
}
static void *cache_map(void *addr,size_t length,int prot,int flags,int fd,off_t offset) {
    if (!candidate(addr,length,prot,flags,fd,offset)) return MAP_FAILED;
    Key key;
    if (!section_key(fd,offset,length,&key)) return MAP_FAILED;
    atomic_fetch_add(&section_matches,1);
    if (!source_quiet(&key)) return MAP_FAILED;
    int cached=backing_fd(fd,&key);
    if (cached<0) { atomic_fetch_add(&failures,1);return MAP_FAILED; }
    void *result=system_map(addr,length,prot,flags,cached,page_size);
    if (result!=MAP_FAILED) {
        uint64_t previous=atomic_fetch_add(&mapped_bytes,length);
#ifdef __ANDROID__
        if (!previous) __android_log_print(ANDROID_LOG_INFO,"WinePeImageCache",
                "Shared PE backing active first_map_kb=%zu",length/1024);
#else
        (void)previous;
#endif
        futimens(cached,NULL); /* LRU hint; backing bytes remain immutable. */
    } else atomic_fetch_add(&failures,1);
    close(cached);
    return result;
}
static void initialize(void) {
    const char *on=getenv("SKY_PE_IMAGE_CACHE"),*path=getenv("SKY_PE_IMAGE_CACHE_DIR");
    stats_enabled=getenv("SKY_PE_IMAGE_CACHE_STATS")!=NULL;
    page_size=(size_t)sysconf(_SC_PAGESIZE);
    if (!on || strcmp(on,"1") || !path || path[0]!='/' || strlen(path)>=sizeof(cache_path)) return;
#ifndef SKY_PE_CACHE_TEST
    /* Wine 9's image mapper assumes 4 KiB pages. Do not widen its mappings on
     * 16 KiB Android kernels: leave that runtime's existing behavior intact. */
    if (page_size!=4096) return;
#endif
    snprintf(cache_path,sizeof(cache_path),"%s",path);
    enabled=1;
}
#ifndef SKY_PE_CACHE_TEST
__attribute__((constructor)) static void start_cache(void) {
    next_mmap=dlsym(RTLD_NEXT,"mmap");
    initialize();
}
EXPORT void *mmap(void *addr,size_t length,int prot,int flags,int fd,off_t offset) {
    int saved=errno;
    int is_candidate;
    if (enabled) atomic_fetch_add(&mmap_calls,1);
    is_candidate=candidate(addr,length,prot,flags,fd,offset);
    if (is_candidate && !offset) {
        /* A failed executable header on Android's noexec shared storage makes
         * Wine skip mmap for every section. Private header backing avoids that. */
        void *original=system_map(addr,length,prot,flags,fd,offset);
        if (original!=MAP_FAILED) { errno=saved;return original; }
        if (errno!=EACCES && errno!=EPERM && errno!=ENODEV && errno!=ENOEXEC) return MAP_FAILED;
        saved=errno;
    }
    // Non-target mappings must not initialize emulated TLS, even for diagnostics.
    if (is_candidate && !in_hook) {
        in_hook=1;
        atomic_fetch_add(&cache_attempts,1);
        if (stats_enabled && length>=16*1024*1024 && atomic_fetch_add(&large_probes,1)<16) {
            struct stat source;
            unsigned char dos[64],nt[8];
            unsigned machine=0;
            if (!fstat(fd,&source) && read_at(fd,dos,sizeof(dos),0) && u16(dos)==0x5a4d) {
                uint64_t pe=u32(dos+60);
                if (pe<=1024*1024 && read_at(fd,nt,sizeof(nt),pe) && u32(nt)==0x4550) machine=u16(nt+4);
            }
            Key key;
            int section=section_key(fd,offset,length,&key);
            int quiet=section && source_quiet(&key);
#ifdef __ANDROID__
            __android_log_print(ANDROID_LOG_INFO,"WinePeImageCache",
                "mmap_probe offset=%lld length=%zu prot=%x flags=%x addr_aligned=%d machine=%x file_size=%lld candidate=%d section=%d quiet=%d",
                (long long)offset,length,prot,flags,addr && !((uintptr_t)addr%page_size),machine,
                !fstat(fd,&source)?(long long)source.st_size:-1,is_candidate,section,quiet);
#endif
        }
        void *result=cache_map(addr,length,prot,flags,fd,offset);
        in_hook=0;
        if (result!=MAP_FAILED) { errno=saved;return result; }
    }
    errno=saved;
    return system_map(addr,length,prot,flags,fd,offset);
}
EXPORT void *mmap64(void *addr,size_t length,int prot,int flags,int fd,off64_t offset) {
    return mmap(addr,length,prot,flags,fd,(off_t)offset);
}
__attribute__((destructor)) static void cache_stats(void) {
#ifdef __ANDROID__
    if (enabled && stats_enabled)
        __android_log_print(ANDROID_LOG_INFO,"WinePeImageCache",
            "mmap_calls=%llu probes=%llu candidates=%llu sections=%llu hits=%llu created=%llu mapped_kb=%llu failures=%llu",
            (unsigned long long)atomic_load(&mmap_calls),(unsigned long long)atomic_load(&large_probes),
            (unsigned long long)atomic_load(&cache_attempts),(unsigned long long)atomic_load(&section_matches),
            (unsigned long long)atomic_load(&hits),(unsigned long long)atomic_load(&created),
            (unsigned long long)(atomic_load(&mapped_bytes)/1024),(unsigned long long)atomic_load(&failures));
#endif
}
#endif
