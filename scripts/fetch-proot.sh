#!/usr/bin/env bash
# Repackages Termux's proot (and its two shared-library dependencies) as
# "lib*.so" files under app/src/main/jniLibs/<abi>/.
#
# Why: apps targeting Android 10+ may not exec() files from their writable data
# directory (W^X). Files shipped in the APK's native library directory CAN be
# executed, so the proot binary and its loader are shipped there. proot then
# runs every Linux (glibc) program of the Ubuntu rootfs through its loader,
# which only mmap()s them - that is allowed.
#
# Requirements: curl, ar (binutils), tar with xz support, patchelf
#   (pip install patchelf), python3.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MIRROR="https://packages.termux.dev/apt/termux-main"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

declare -A ABI_FOR=([aarch64]=arm64-v8a [x86_64]=x86_64)

for arch in aarch64 x86_64; do
  abi="${ABI_FOR[$arch]}"
  out="$ROOT/app/src/main/jniLibs/$abi"
  mkdir -p "$out" "$WORK/$arch"
  curl -fsSL "$MIRROR/dists/stable/main/binary-$arch/Packages" -o "$WORK/$arch/Packages"

  for pkg in proot libtalloc libandroid-shmem; do
    file="$(python3 - "$WORK/$arch/Packages" "$pkg" <<'EOF'
import sys
path, want = sys.argv[1], sys.argv[2]
for block in open(path).read().split("\n\n"):
    fields = dict(l.split(": ", 1) for l in block.splitlines() if ": " in l)
    if fields.get("Package") == want:
        print(fields["Filename"]); break
EOF
)"
    echo "[$arch] $pkg -> $file"
    mkdir -p "$WORK/$arch/$pkg"
    curl -fsSL "$MIRROR/$file" -o "$WORK/$arch/$pkg.deb"
    (cd "$WORK/$arch/$pkg" && ar x "../$pkg.deb" && tar xf data.tar.*)
  done

  prefix="data/data/com.termux/files/usr"
  cp "$WORK/$arch/proot/$prefix/bin/proot" "$out/libproot.so"
  cp "$WORK/$arch/proot/$prefix/libexec/proot/loader" "$out/libproot-loader.so"
  cp "$(ls "$WORK/$arch/libtalloc/$prefix/lib/"libtalloc.so.2.* | head -1)" "$out/libtalloc.so"
  cp "$WORK/$arch/libandroid-shmem/$prefix/lib/libandroid-shmem.so" "$out/libandroid-shmem.so"

  # Android only extracts files named lib*.so, so the versioned soname has to go.
  patchelf --replace-needed libtalloc.so.2 libtalloc.so "$out/libproot.so"
  patchelf --set-soname libtalloc.so "$out/libtalloc.so"
  for f in libproot.so libtalloc.so libandroid-shmem.so; do
    patchelf --remove-rpath "$out/$f"
  done
  chmod 755 "$out"/*.so
done

echo "Done. Check 16 KB page alignment (Align column should be 0x4000 or larger):"
for f in "$ROOT"/app/src/main/jniLibs/*/*.so; do
  printf '%s: ' "${f#$ROOT/}"
  readelf -lW "$f" | awk '/LOAD/ {print $NF; exit}'
done
