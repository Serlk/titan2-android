#!/usr/bin/env bash
# Kernel sources for scripts/build-kernel.sh: NothingOSS MT6878 6.1 at the commit in patches/kernel/BASE_COMMIT
# + our patches (branch titan2-halium) and AOSP clang from patches/kernel/clang.url. Build Docker image:
#   docker build -t titan2-kbuild docker/
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
K=$ROOT/src/kernel-6.1
BASE=$(cat "$ROOT/patches/kernel/BASE_COMMIT")
if [ ! -d "$K/.git" ]; then
  git init -q "$K"
  git -C "$K" remote add origin https://github.com/NothingOSS/android_kernel_6.1_nothing_mt6878
fi
git -C "$K" cat-file -e "$BASE^{commit}" 2>/dev/null || git -C "$K" fetch --depth 1 origin "$BASE"
git -C "$K" checkout -q -B titan2-halium "$BASE"
git -C "$K" -c user.name=titan2 -c user.email=titan2@localhost -c apply.whitespace=nowarn am -q "$ROOT"/patches/kernel/*.patch
C=$ROOT/toolchains/clang-r487747c
if [ ! -x "$C/bin/clang" ]; then
  mkdir -p "$C"
  curl -fL "$(cat "$ROOT/patches/kernel/clang.url")" | tar -xz -C "$C"
fi
echo "done: $K ($(git -C "$K" log --oneline -1))"
