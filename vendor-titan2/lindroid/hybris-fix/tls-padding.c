/*
 * libtls-padding.so for the Lindroid container on Titan 2 (LD_PRELOAD for sddm/kwin/plasma).
 *
 * 1. As in the UBports tls-padding: a TLS reserve at the start of the block where bionic counts slots as its own.
 * 2. libhybris does not hook aligned_alloc/reallocarray, so memory from them is allocated by
 *    bionic (scudo) but freed by glibc free() (free is hooked) -> "free(): invalid pointer".
 *    This crashed kwin_wayland and all GL clients on Mali: libc++ (aligned new) and
 *    libGLES_mali (the mali-compiler thread). We install a libhybris hook callback that
 *    returns these functions from glibc.
 * Built without a sysroot (scripts/build-lindroid-hybris-fix.sh), hence our own prototypes.
 */
typedef unsigned long size_t;

__thread void *tls_padding[16];

extern void *aligned_alloc(size_t alignment, size_t size);
extern void *reallocarray(void *ptr, size_t nmemb, size_t size);
extern void *dlopen(const char *file, int mode);
extern void *dlsym(void *handle, const char *name);
extern char *getenv(const char *name);
extern int strcmp(const char *a, const char *b);

#define RTLD_NOW 0x2
#define RTLD_GLOBAL 0x100

static void *titan2_hook(const char *sym, const char *requester)
{
    (void)requester;
    if (!strcmp(sym, "aligned_alloc"))
        return (void *)aligned_alloc;
    if (!strcmp(sym, "reallocarray"))
        return (void *)reallocarray;
    return 0;
}

__attribute__((constructor)) static void titan2_hybris_fix(void)
{
    /* only for graphics stack processes: libhybris without constructors, loading is cheap */
    if (!getenv("HYBRIS_LD_LIBRARY_PATH") && !getenv("EGL_PLATFORM"))
        return;
    void *h = dlopen("libhybris-common.so.1", RTLD_NOW | RTLD_GLOBAL);
    if (!h)
        return;
    void (*set_cb)(void *(*)(const char *, const char *)) =
        (void (*)(void *(*)(const char *, const char *)))dlsym(h, "hybris_set_hook_callback");
    if (set_cb)
        set_cb(titan2_hook);
}
