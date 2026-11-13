/*
 * Anduino exec shim — preloaded (ld.so --preload) into toolchain programs that run
 * natively on Android, outside proot.
 *
 * Android forbids exec() of files in app storage, where the toolchains live, but it
 * allows exec() of the glibc dynamic loader shipped in the APK, and that loader may map
 * programs from app storage. So when gcc runs cc1plus, or Espressif's launcher runs
 * the real compiler, this shim turns
 *     execve("/data/.../cc1plus", argv, envp)
 * into
 *     execve(LDSO, [LDSO, --library-path, LIBS, --preload, SHIM, --argv0, argv[0],
 *                   "/data/.../cc1plus", argv[1..]], envp + ANDUINO_SELF_EXE)
 * It does the same for posix_spawn(), and answers readlink("/proc/self/exe") with the
 * real program instead of the loader, which programs use to find their own files.
 *
 * Everything here may run in a vfork() child, so no malloc: only stack buffers.
 * Configuration comes from the environment: ANDUINO_LDSO, ANDUINO_LIBPATH,
 * ANDUINO_SHIM, ANDUINO_ROOT (rootfs, for guest paths such as /bin/sh) and
 * ANDUINO_SELF_EXE (set per process by whoever started it).
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <spawn.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

extern char **environ;

typedef int (*spawn_fn)(pid_t *, const char *, const posix_spawn_file_actions_t *,
                        const posix_spawnattr_t *, char *const[], char *const[]);
typedef ssize_t (*readlink_fn)(const char *, char *, size_t);
typedef ssize_t (*readlinkat_fn)(int, const char *, char *, size_t);

static spawn_fn real_posix_spawn;
static readlink_fn real_readlink;
static readlinkat_fn real_readlinkat;

__attribute__((constructor)) static void shim_init(void) {
    real_posix_spawn = (spawn_fn)dlsym(RTLD_NEXT, "posix_spawn");
    real_readlink = (readlink_fn)dlsym(RTLD_NEXT, "readlink");
    real_readlinkat = (readlinkat_fn)dlsym(RTLD_NEXT, "readlinkat");
}

static const char *env_in(char *const envp[], const char *name) {
    size_t n = strlen(name);
    if (!envp) return NULL;
    for (char *const *e = envp; *e; e++)
        if (strncmp(*e, name, n) == 0 && (*e)[n] == '=') return *e + n + 1;
    return NULL;
}

static const char *cfg(const char *name) { return env_in(environ, name); }

/* Guest-style absolute paths (/bin/sh, /usr/bin/python3) live under ANDUINO_ROOT. */
static int map_path(const char *path, char out[PATH_MAX]) {
    if (strlen(path) >= PATH_MAX) return -1;
    if (path[0] == '/' && access(path, F_OK) != 0) {
        const char *root = cfg("ANDUINO_ROOT");
        if (root && snprintf(out, PATH_MAX, "%s%s", root, path) < PATH_MAX && access(out, F_OK) == 0)
            return 0;
    }
    strcpy(out, path);
    return 0;
}

/* execvp-style lookup of a bare command name in PATH. */
static int search_path(const char *file, char *const envp[], char out[PATH_MAX]) {
    if (strchr(file, '/')) return map_path(file, out);
    const char *path = env_in(envp, "PATH");
    if (!path) path = cfg("PATH");
    if (!path) path = "/usr/local/bin:/usr/bin:/bin";
    char cand[PATH_MAX];
    while (*path) {
        const char *colon = strchr(path, ':');
        size_t len = colon ? (size_t)(colon - path) : strlen(path);
        if (len > 0 && len + strlen(file) + 2 < PATH_MAX) {
            memcpy(cand, path, len);
            cand[len] = '/';
            strcpy(cand + len + 1, file);
            char mapped[PATH_MAX];
            if (map_path(cand, mapped) == 0 && access(mapped, X_OK) == 0) {
                strcpy(out, mapped);
                return 0;
            }
        }
        if (!colon) break;
        path = colon + 1;
    }
    errno = ENOENT;
    return -1;
}

enum kind { K_OTHER, K_DYNAMIC_ELF, K_SCRIPT };

/* Reads the start of the file: dynamic ELF (has PT_INTERP), "#!" script, or other. */
static enum kind classify(const char *path, char interp[PATH_MAX], char interp_arg[256]) {
    unsigned char buf[4096];
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return K_OTHER;
    ssize_t n = read(fd, buf, sizeof buf);
    close(fd);
    if (n >= 2 && buf[0] == '#' && buf[1] == '!') {
        ssize_t i = 2, end;
        while (i < n && (buf[i] == ' ' || buf[i] == '\t')) i++;
        end = i;
        while (end < n && buf[end] != ' ' && buf[end] != '\t' && buf[end] != '\n') end++;
        if (end - i <= 0 || end - i >= PATH_MAX) return K_OTHER;
        memcpy(interp, buf + i, end - i);
        interp[end - i] = 0;
        interp_arg[0] = 0;
        i = end;
        while (i < n && (buf[i] == ' ' || buf[i] == '\t')) i++;
        end = i;
        while (end < n && buf[end] != '\n') end++;
        while (end > i && (buf[end - 1] == ' ' || buf[end - 1] == '\t' || buf[end - 1] == '\r')) end--;
        if (end > i && end - i < 256) {
            memcpy(interp_arg, buf + i, end - i);
            interp_arg[end - i] = 0;
        }
        return K_SCRIPT;
    }
    if (n < (ssize_t)sizeof(Elf64_Ehdr) || memcmp(buf, ELFMAG, SELFMAG) != 0 || buf[EI_CLASS] != ELFCLASS64)
        return K_OTHER;
    Elf64_Ehdr *eh = (Elf64_Ehdr *)buf;
    if (eh->e_phoff + (Elf64_Off)eh->e_phnum * sizeof(Elf64_Phdr) > (Elf64_Off)n) return K_OTHER;
    for (int i = 0; i < eh->e_phnum; i++) {
        Elf64_Phdr *ph = (Elf64_Phdr *)(buf + eh->e_phoff + i * sizeof(Elf64_Phdr));
        if (ph->p_type == PT_INTERP) return K_DYNAMIC_ELF;
    }
    return K_OTHER;
}

static int count(char *const v[]) {
    int n = 0;
    if (v) while (v[n]) n++;
    return n;
}

/*
 * Builds the loader command line for running `path` with `argv`. Returns the number
 * of entries written to out_argv (NULL-terminated), or -1 to leave the call alone.
 * `scratch` holds the strings that need storage.
 */
#define MAX_PREFIX 16
static int rewrite(const char *path, char *const argv[], char **out_argv, int out_cap,
                   char interp[PATH_MAX], char interp_arg[256], char resolved_interp[PATH_MAX]) {
    const char *ldso = cfg("ANDUINO_LDSO"), *libs = cfg("ANDUINO_LIBPATH"), *shim = cfg("ANDUINO_SHIM");
    if (!ldso || !libs || !shim) return -1;
    int argc = count(argv);
    const char *program = path;
    int k = 0;
    switch (classify(path, interp, interp_arg)) {
    case K_DYNAMIC_ELF:
        break;
    case K_SCRIPT: {
        /* "#!/usr/bin/env bash" etc.: run the interpreter, itself a dynamic ELF. */
        char unused_interp[PATH_MAX], unused_arg[256];
        if (map_path(interp, resolved_interp) != 0) return -1;
        if (classify(resolved_interp, unused_interp, unused_arg) != K_DYNAMIC_ELF) return -1;
        program = resolved_interp;
        break;
    }
    default:
        return -1;
    }
    if (argc + MAX_PREFIX + 3 > out_cap) return -1;
    out_argv[k++] = (char *)ldso;
    out_argv[k++] = "--library-path";
    out_argv[k++] = (char *)libs;
    out_argv[k++] = "--preload";
    out_argv[k++] = (char *)shim;
    out_argv[k++] = "--argv0";
    if (program == path) {
        out_argv[k++] = argc > 0 ? argv[0] : (char *)path;
        out_argv[k++] = (char *)path;
        for (int i = 1; i < argc; i++) out_argv[k++] = argv[i];
    } else {
        out_argv[k++] = (char *)program;
        out_argv[k++] = (char *)program;
        if (interp_arg[0]) out_argv[k++] = interp_arg;
        out_argv[k++] = (char *)path;
        for (int i = 1; i < argc; i++) out_argv[k++] = argv[i];
    }
    out_argv[k] = NULL;
    return k;
}

/* envp + ANDUINO_SELF_EXE=<program>, + our ANDUINO_* settings if the caller dropped them. */
static const char *KEEP[] = {"ANDUINO_LDSO", "ANDUINO_LIBPATH", "ANDUINO_SHIM", "ANDUINO_ROOT", "GLIBC_TUNABLES", NULL};

static int build_env(char *const envp[], const char *self, char **out, int cap, char self_buf[PATH_MAX + 32]) {
    int k = 0;
    if (envp)
        for (char *const *e = envp; *e && k < cap - 8; e++)
            if (strncmp(*e, "ANDUINO_SELF_EXE=", 17) != 0) out[k++] = *e;
    for (int i = 0; KEEP[i]; i++) {
        if (!env_in(envp, KEEP[i])) {
            /* point at our own environ entry, it outlives the exec */
            size_t n = strlen(KEEP[i]);
            for (char **e = environ; e && *e; e++)
                if (strncmp(*e, KEEP[i], n) == 0 && (*e)[n] == '=' && k < cap - 2) { out[k++] = *e; break; }
        }
    }
    snprintf(self_buf, PATH_MAX + 32, "ANDUINO_SELF_EXE=%s", self);
    out[k++] = self_buf;
    out[k] = NULL;
    return k;
}

static int do_execve(const char *path, char *const argv[], char *const envp[]) {
    char mapped[PATH_MAX], interp[PATH_MAX], interp_arg[256], resolved[PATH_MAX];
    if (map_path(path, mapped) != 0) return (int)syscall(SYS_execve, path, argv, envp);
    int argc = count(argv), envc = count(envp);
    char *new_argv[argc + MAX_PREFIX + 3];
    char *new_envp[envc + 16];
    char self_buf[PATH_MAX + 32];
    if (rewrite(mapped, argv, new_argv, argc + MAX_PREFIX + 3, interp, interp_arg, resolved) < 0)
        return (int)syscall(SYS_execve, mapped, argv, envp);
    build_env(envp, mapped, new_envp, envc + 16, self_buf);
    return (int)syscall(SYS_execve, new_argv[0], new_argv, new_envp);
}

int execve(const char *path, char *const argv[], char *const envp[]) { return do_execve(path, argv, envp); }

int execv(const char *path, char *const argv[]) { return do_execve(path, argv, environ); }

int execvpe(const char *file, char *const argv[], char *const envp[]) {
    char resolved[PATH_MAX];
    if (search_path(file, envp, resolved) != 0) return -1;
    return do_execve(resolved, argv, envp);
}

int execvp(const char *file, char *const argv[]) { return execvpe(file, argv, environ); }

#define COLLECT_ARGS(first, args, extra)                                    \
    va_list ap;                                                             \
    int n = 1;                                                              \
    va_start(ap, first);                                                    \
    while (va_arg(ap, char *)) n++;                                         \
    va_end(ap);                                                             \
    char *args[n + 1];                                                      \
    args[0] = (char *)(first);                                              \
    va_start(ap, first);                                                    \
    for (int i = 1; i <= n; i++) args[i] = va_arg(ap, char *);              \
    extra;                                                                  \
    va_end(ap);

int execl(const char *path, const char *arg, ...) {
    COLLECT_ARGS(arg, args, (void)0)
    return do_execve(path, args, environ);
}

int execlp(const char *file, const char *arg, ...) {
    COLLECT_ARGS(arg, args, (void)0)
    return execvpe(file, args, environ);
}

int execle(const char *path, const char *arg, ...) {
    char **envp;
    COLLECT_ARGS(arg, args, envp = va_arg(ap, char **))
    return do_execve(path, args, envp);
}

static int do_spawn(pid_t *pid, const char *path, const posix_spawn_file_actions_t *fa,
                    const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
    if (!real_posix_spawn) shim_init();
    char mapped[PATH_MAX], interp[PATH_MAX], interp_arg[256], resolved[PATH_MAX];
    if (map_path(path, mapped) != 0) return real_posix_spawn(pid, path, fa, attr, argv, envp);
    int argc = count(argv), envc = count(envp);
    char *new_argv[argc + MAX_PREFIX + 3];
    char *new_envp[envc + 16];
    char self_buf[PATH_MAX + 32];
    if (rewrite(mapped, argv, new_argv, argc + MAX_PREFIX + 3, interp, interp_arg, resolved) < 0)
        return real_posix_spawn(pid, mapped, fa, attr, argv, envp);
    build_env(envp, mapped, new_envp, envc + 16, self_buf);
    return real_posix_spawn(pid, new_argv[0], fa, attr, new_argv, new_envp);
}

int posix_spawn(pid_t *pid, const char *path, const posix_spawn_file_actions_t *fa,
                const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
    return do_spawn(pid, path, fa, attr, argv, envp);
}

int posix_spawnp(pid_t *pid, const char *file, const posix_spawn_file_actions_t *fa,
                 const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
    char resolved[PATH_MAX];
    if (search_path(file, envp, resolved) != 0) return ENOENT;
    return do_spawn(pid, resolved, fa, attr, argv, envp);
}

static int is_self_exe(const char *p) {
    if (strcmp(p, "/proc/self/exe") == 0) return 1;
    char own[64];
    snprintf(own, sizeof own, "/proc/%d/exe", (int)getpid());
    return strcmp(p, own) == 0;
}

static ssize_t fake_self(char *buf, size_t size) {
    const char *self = cfg("ANDUINO_SELF_EXE");
    if (!self) return -2;
    size_t n = strlen(self);
    if (n > size) n = size; /* readlink truncates silently */
    memcpy(buf, self, n);
    return (ssize_t)n;
}

ssize_t readlink(const char *path, char *buf, size_t size) {
    if (!real_readlink) shim_init();
    if (is_self_exe(path)) {
        ssize_t r = fake_self(buf, size);
        if (r != -2) return r;
    }
    return real_readlink(path, buf, size);
}

ssize_t readlinkat(int dirfd, const char *path, char *buf, size_t size) {
    if (!real_readlinkat) shim_init();
    if (is_self_exe(path)) {
        ssize_t r = fake_self(buf, size);
        if (r != -2) return r;
    }
    return real_readlinkat(dirfd, path, buf, size);
}
