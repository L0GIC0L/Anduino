#!/usr/bin/env bash
# Packages Ubuntu 24.04's glibc dynamic loader and core libraries as
# app/src/main/jniLibs/<abi>/lib*.so for the native ("fast") compile path.
#
# Android only lets an app exec() files from its APK's native library dir, so the
# glibc loader ships there; it then loads toolchain programs from app storage
# (mmap, which Android allows), the glibc equivalent of termux-exec's
# "system linker exec". libc and friends come from the same package as the loader
# so they always match it. At run time the app symlinks them under their sonames.
#
# Requirements: curl, ar, tar (xz/zstd), python3.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

fetch() { # <abi> <debian arch> <mirror> <lib dir> <loader name>
  local abi="$1" arch="$2" mirror="$3" libdir="$4" loader="$5"
  local out="$ROOT/app/src/main/jniLibs/$abi"
  mkdir -p "$out" "$WORK/$arch"
  curl -fsSL "$mirror/dists/noble-updates/main/binary-$arch/Packages.gz" | gunzip > "$WORK/$arch/Packages"
  local file
  file="$(python3 - "$WORK/$arch/Packages" <<'EOF'
import sys
for block in open(sys.argv[1]).read().split("\n\n"):
    f = dict(l.split(": ", 1) for l in block.splitlines() if ": " in l)
    if f.get("Package") == "libc6":
        print(f["Filename"]); break
EOF
)"
  echo "[$abi] $file"
  curl -fsSL "$mirror/$file" -o "$WORK/$arch/libc6.deb"
  (cd "$WORK/$arch" && ar x libc6.deb && mkdir -p data && tar -xf data.tar.* -C data)
  local src="$WORK/$arch/data/$libdir"
  cp -L "$src/$loader" "$out/libanduino-ld.so"
  for pair in c:libc.so.6 m:libm.so.6 pthread:libpthread.so.0 dl:libdl.so.2 rt:librt.so.1 \
              util:libutil.so.1 resolv:libresolv.so.2; do
    cp -L "$src/${pair#*:}" "$out/libglibc-${pair%%:*}.so"
  done
  chmod 755 "$out"/libanduino-ld.so "$out"/libglibc-*.so
}

fetch arm64-v8a arm64 http://ports.ubuntu.com/ubuntu-ports usr/lib/aarch64-linux-gnu ld-linux-aarch64.so.1
fetch x86_64 amd64 http://archive.ubuntu.com/ubuntu usr/lib/x86_64-linux-gnu ld-linux-x86-64.so.2
ls -la "$ROOT"/app/src/main/jniLibs/*/libanduino-ld.so "$ROOT"/app/src/main/jniLibs/*/libglibc-*.so
