/*
 * Anduino trap supervisor — runs a natively executed toolchain program (and everything it
 * starts) and answers the system calls that Android's app seccomp filter traps with
 * ENOSYS, instead of letting the kernel kill the process with SIGSYS.
 *
 * Android only allows the system calls bionic uses. glibc 2.39 makes some others during
 * startup (before any preloaded code can install a signal handler) and when starting
 * threads and processes, and they arrive as SIGSYS ("Bad system call"). proot survives
 * them because it ptraces its programs and fixes up the trapped call; this does the same
 * and nothing else: no system call is intercepted, the tracee only stops for signals and
 * for fork/clone/exec, so programs keep native speed.
 *
 * Usage: libanduino-trap.so <program> [args...]   (exit status = the program's)
 * If ANDUINO_TRAP_LOG names a file, each trapped system call number is appended to it
 * once per run, so the app can report which calls Android blocked.
 *
 * Freestanding (no libc): glibc's own startup is what Android may kill.
 */
typedef unsigned long u64;

#if defined(__aarch64__)
enum { NR_openat = 56, NR_close = 57, NR_write = 64, NR_exit_group = 94, NR_ptrace = 117, NR_kill = 129,
       NR_getpid = 172, NR_clone = 220, NR_execve = 221, NR_wait4 = 260 };
#define RET_REG 0 /* x0 in struct user_pt_regs */
#define NREGS 34
static long sys6(long n, long a, long b, long c, long d, long e, long f) {
    register long x8 __asm__("x8") = n;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x3 __asm__("x3") = d;
    register long x4 __asm__("x4") = e;
    register long x5 __asm__("x5") = f;
    __asm__ volatile("svc 0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5) : "memory", "cc");
    return x0;
}
__asm__(".text\n.global _start\n_start:\n mov x0, sp\n bl start_c\n brk #0\n");
#elif defined(__x86_64__)
enum { NR_openat = 257, NR_close = 3, NR_write = 1, NR_exit_group = 231, NR_ptrace = 101, NR_kill = 62,
       NR_getpid = 39, NR_clone = 56, NR_execve = 59, NR_wait4 = 61 };
#define RET_REG 10 /* rax in struct user_regs_struct */
#define NREGS 27
static long sys6(long n, long a, long b, long c, long d, long e, long f) {
    register long r10 __asm__("r10") = d;
    register long r8 __asm__("r8") = e;
    register long r9 __asm__("r9") = f;
    long ret;
    __asm__ volatile("syscall" : "=a"(ret) : "a"(n), "D"(a), "S"(b), "d"(c), "r"(r10), "r"(r8), "r"(r9)
                     : "rcx", "r11", "memory");
    return ret;
}
__asm__(".text\n.global _start\n_start:\n xor %rbp, %rbp\n mov %rsp, %rdi\n and $-16, %rsp\n call start_c\n hlt\n");
#else
#error "unsupported architecture"
#endif

#define sys(n, a, b, c) sys6(n, (long)(a), (long)(b), (long)(c), 0, 0, 0)

enum { SIGSTOP = 19, SIGSYS = 31, SIGCHLD = 17, ENOSYS = 38, EINTR = 4, SYS_SECCOMP = 1 };
enum { PTRACE_TRACEME = 0, PTRACE_CONT = 7, PTRACE_DETACH = 17, PTRACE_SETOPTIONS = 0x4200, PTRACE_GETSIGINFO = 0x4202,
       PTRACE_GETREGSET = 0x4204, PTRACE_SETREGSET = 0x4205, NT_PRSTATUS = 1 };
#define TRACE_OPTIONS (0x2 | 0x4 | 0x8 | 0x10 | 0x100000) /* FORK VFORK CLONE EXEC EXITKILL */
#define WALL 0x40000000

struct iovec { void *base; u64 len; };

/* gcc may emit calls to these even in freestanding code. */
void *memset(void *d, int c, u64 n) { unsigned char *p = d; while (n--) *p++ = (unsigned char)c; return d; }
void *memcpy(void *d, const void *s, u64 n) { unsigned char *p = d; const unsigned char *q = s; while (n--) *p++ = *q++; return d; }

static u64 len(const char *s) { u64 n = 0; while (s[n]) n++; return n; }

static void say(const char *s) { sys(NR_write, 2, s, len(s)); }

static const char *getenv_in(char **envp, const char *name) {
    u64 n = len(name);
    for (; *envp; envp++) {
        u64 i = 0;
        while (i < n && (*envp)[i] == name[i]) i++;
        if (i == n && (*envp)[n] == '=') return *envp + n + 1;
    }
    return 0;
}

static int log_fd = -1;
static int seen[64], nseen;

static void note(int nr) {
    for (int i = 0; i < nseen; i++) if (seen[i] == nr) return;
    if (nseen < 64) seen[nseen++] = nr;
    if (log_fd < 0) return;
    char buf[16], *p = buf + sizeof buf;
    *--p = '\n';
    unsigned v = (unsigned)nr;
    do *--p = (char)('0' + v % 10); while (v /= 10);
    sys(NR_write, log_fd, p, buf + sizeof buf - p);
}

/* The trapped call was not executed; make it return -ENOSYS and carry on. */
static void answer_enosys(int pid) {
    u64 regs[NREGS];
    struct iovec io = {regs, sizeof regs};
    if (sys6(NR_ptrace, PTRACE_GETREGSET, pid, NT_PRSTATUS, (long)&io, 0, 0) < 0) return;
    regs[RET_REG] = (u64)-ENOSYS;
    sys6(NR_ptrace, PTRACE_SETREGSET, pid, NT_PRSTATUS, (long)&io, 0, 0);
}

static void cont(int pid, int sig) { sys6(NR_ptrace, PTRACE_CONT, pid, 0, sig, 0, 0); }

void start_c(long *sp) {
    int argc = (int)sp[0];
    char **argv = (char **)(sp + 1);
    char **envp = argv + argc + 1;
    if (argc < 2) {
        say("usage: anduino-trap <program> [args...]\n");
        sys(NR_exit_group, 2, 0, 0);
    }
    const char *log_path = getenv_in(envp, "ANDUINO_TRAP_LOG");
    if (log_path) log_fd = (int)sys6(NR_openat, -100, (long)log_path, 01 | 0100 | 02000 | 02000000, 0600, 0, 0);

    long child = sys6(NR_clone, SIGCHLD, 0, 0, 0, 0, 0);
    if (child == 0) {
        if (log_fd >= 0) sys(NR_close, log_fd, 0, 0);
        /* If tracing isn't allowed, just run the program (as if this wrapper weren't here). */
        if (sys6(NR_ptrace, PTRACE_TRACEME, 0, 0, 0, 0, 0) == 0) sys(NR_kill, sys(NR_getpid, 0, 0, 0), SIGSTOP, 0);
        sys(NR_execve, argv[1], argv + 1, envp);
        say("anduino-trap: cannot run ");
        say(argv[1]);
        say("\n");
        sys(NR_exit_group, 127, 0, 0);
    }
    if (child < 0) {
        say("anduino-trap: fork failed\n");
        sys(NR_exit_group, 126, 0, 0);
    }

    int status = 0;
    if (sys6(NR_wait4, child, (long)&status, WALL, 0, 0, 0) != child) sys(NR_exit_group, 126, 0, 0);
    if ((status & 0xff) != 0x7f) { /* not traced: it simply ran to completion */
        int low = status & 0x7f;
        sys(NR_exit_group, low == 0 ? (status >> 8) & 0xff : 128 + low, 0, 0);
    }
    if (sys6(NR_ptrace, PTRACE_SETOPTIONS, child, 0, TRACE_OPTIONS, 0, 0) != 0) {
        /* Without the options exec would deliver SIGTRAP; let it run untraced instead. */
        sys6(NR_ptrace, PTRACE_DETACH, child, 0, 0, 0, 0);
        while (sys6(NR_wait4, child, (long)&status, WALL, 0, 0, 0) == -EINTR) {}
        int low = status & 0x7f;
        sys(NR_exit_group, low == 0 ? (status >> 8) & 0xff : 128 + low, 0, 0);
    }
    cont((int)child, 0);

    for (;;) {
        long pid = sys6(NR_wait4, -1, (long)&status, WALL, 0, 0, 0);
        if (pid == -EINTR) continue;
        if (pid < 0) sys(NR_exit_group, 126, 0, 0); /* no tracees left without the main one exiting */
        int low = status & 0x7f;
        if (low != 0x7f) { /* exited or killed */
            if (pid == child) {
                int code = low == 0 ? (status >> 8) & 0xff : 128 + low;
                sys(NR_exit_group, code, 0, 0); /* EXITKILL ends any leftover tracee */
            }
            continue;
        }
        int sig = (status >> 8) & 0xff;
        if (status >> 16) { /* fork/vfork/clone/exec event */
            cont((int)pid, 0);
            continue;
        }
        if (sig == SIGSTOP) { /* every new tracee starts with one */
            cont((int)pid, 0);
            continue;
        }
        if (sig == SIGSYS) {
            int info[32]; /* siginfo_t: si_signo, si_errno, si_code, ..., si_syscall at byte 24 */
            if (sys6(NR_ptrace, PTRACE_GETSIGINFO, pid, 0, (long)info, 0, 0) == 0 && info[2] == SYS_SECCOMP) {
                note(info[6]);
                answer_enosys((int)pid);
                cont((int)pid, 0);
                continue;
            }
        }
        cont((int)pid, sig);
    }
}
