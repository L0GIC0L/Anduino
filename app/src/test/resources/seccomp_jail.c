#define _GNU_SOURCE
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <stdio.h>
#include <sys/prctl.h>
#include <unistd.h>
/* Traps set_robust_list (273), clone3 (435) and faccessat2 (439) with SIGSYS, as Android's app
 * seccomp policy does with calls bionic doesn't use, then execs argv[1]. x86_64 numbers. */
int main(int argc, char **argv) {
    struct sock_filter f[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 273, 3, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 435, 2, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 439, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
    };
    struct sock_fprog prog = {sizeof f / sizeof f[0], f};
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) || prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog)) {
        perror("seccomp");
        return 125;
    }
    execv(argv[1], argv + 1);
    perror("execv");
    return 126;
}
