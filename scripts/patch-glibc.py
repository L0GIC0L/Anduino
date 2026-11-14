#!/usr/bin/env python3
"""Makes glibc's clone3() wrapper answer ENOSYS without calling the kernel.

Android's app seccomp policy traps clone3 with SIGSYS. glibc 2.39 calls clone3 for
every pthread_create() and posix_spawn(), with all signals blocked, so the trap cannot
be handled and the process is killed ("Bad system call"). When clone3 answers ENOSYS,
glibc falls back to the classic clone() call, which Android allows. Termux's glibc port
disables clone3 for the same reason.

The patch replaces the syscall instruction in __clone3 (sysdeps/unix/sysv/linux/
<arch>/clone3.S); the code right after it already handles a negative result as an
error (errno = ENOSYS, return -1). Usage: patch-glibc.py <libc.so.6> <abi>. Running it
again on a patched file changes nothing.
"""
import sys

ENOSYS = 38

PATCHES = {
    # mov x8, #435 ; svc #0   ->   mov x8, #435 ; movn x0, #37  (x0 = -38)
    "arm64-v8a": (bytes.fromhex("683680d2010000d4"), bytes.fromhex("683680d2a0048092")),
    # mov eax, 435 ; syscall  ->   mov rax, -38
    "x86_64": (bytes.fromhex("b8b30100000f05"), bytes.fromhex("48c7c0daffffff")),
}


def main() -> None:
    path, abi = sys.argv[1], sys.argv[2]
    original, patched = PATCHES[abi]
    data = open(path, "rb").read()
    if data.count(patched) == 1 and data.count(original) == 0:
        print(f"{path}: clone3 already disabled")
        return
    if data.count(original) != 1:
        sys.exit(f"{path}: expected exactly one clone3 call site, found {data.count(original)}")
    open(path, "wb").write(data.replace(original, patched))
    print(f"{path}: clone3 disabled (returns -{ENOSYS})")


if __name__ == "__main__":
    main()
