#!/usr/bin/env bash
# Builds the native fast-path helpers into app/src/main/jniLibs/<abi>/:
#   libanduino-shim.so  exec shim preloaded into natively run toolchain programs
#   libanduino-run.so   static relay client that runs inside proot
#   libanduino-trap.so  freestanding ptrace supervisor that answers system calls
#                       Android's seccomp filter traps with ENOSYS
# Both are glibc programs (Ubuntu 24.04 glibc 2.39, the same version as
# scripts/fetch-glibc.sh ships). Requires gcc and gcc-aarch64-linux-gnu.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/native"
CFLAGS=(-O2 -Wall -Wextra -Wno-unused-parameter -fno-stack-protector -Wl,-z,max-page-size=16384)

build() { # <abi> <cc>
  local out="$ROOT/app/src/main/jniLibs/$1" cc="$2"
  mkdir -p "$out"
  "$cc" "${CFLAGS[@]}" -shared -fPIC -o "$out/libanduino-shim.so" "$SRC/exec_shim.c" -ldl
  "$cc" "${CFLAGS[@]}" -static -o "$out/libanduino-run.so" "$SRC/run_client.c"
  "$cc" -O2 -Wall -Wextra -static -nostdlib -ffreestanding -fno-builtin -fno-stack-protector \
    -fno-pie -no-pie -Wl,-z,max-page-size=16384 -Wl,--build-id=none -o "$out/libanduino-trap.so" "$SRC/trap.c"
  echo "built $1"
}

build arm64-v8a aarch64-linux-gnu-gcc
build x86_64 gcc
