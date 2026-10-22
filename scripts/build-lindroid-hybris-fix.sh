#!/usr/bin/env bash
# Builds vendor-titan2/lindroid/hybris-fix/libtls-padding.so (aarch64, glibc) for the Lindroid container.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
D=vendor-titan2/lindroid/hybris-fix
docker run --rm -u "$(id -u):$(id -g)" -v "$ROOT:/work" -w /work titan2-kbuild \
  /work/toolchains/clang-r487747c/bin/clang --target=aarch64-linux-gnu -O2 -fPIC -shared -nostdlib \
  -fuse-ld=lld -Wl,-soname,libtls-padding.so -Wl,--hash-style=gnu -o $D/libtls-padding.so $D/tls-padding.c
ls -l "$ROOT/$D/libtls-padding.so"
