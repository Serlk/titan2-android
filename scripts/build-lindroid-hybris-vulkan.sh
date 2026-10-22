#!/usr/bin/env bash
# Builds vendor-titan2/lindroid/gpu/libvulkan-hybris.so (aarch64, glibc, Debian trixie), a libvulkan.so.1
# from libhybris: loads the Android Vulkan loader and vulkan.mali.so, windows via the vulkanplatform_* plugins
# of the container's libhybris package. Vulkan 309 headers = same as libhybris in the Lindroid rootfs (trixie).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
H=$ROOT/android/crdroid-16/external/libhybris/hybris
docker run --rm -v "$H:/h:ro" -v "$ROOT/vendor-titan2/lindroid/gpu:/out" debian:trixie bash -euc '
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq >/dev/null
apt-get install -y -qq gcc-aarch64-linux-gnu libvulkan-dev patchelf >/dev/null
mkdir -p /tmp/cfg && echo "#define WANT_WAYLAND 1" > /tmp/cfg/config.h
cd /h/vulkan
# libhybris-common.so.1 exists only in the rootfs: symbols stay unresolved, NEEDED via patchelf
aarch64-linux-gnu-gcc -O2 -fPIC -shared -I/tmp/cfg \
  -DPKGLIBDIR="\"/usr/lib/aarch64-linux-gnu/libhybris/\"" \
  -I/h/include -I/h/common -I/h/platforms/common -I/h/vulkan \
  vulkan.c ws.c -o /tmp/libvulkan-hybris.so -Wl,-soname,libvulkan.so.1 -ldl
patchelf --add-needed libhybris-common.so.1 /tmp/libvulkan-hybris.so
install -m644 -o '"$(id -u)"' -g '"$(id -g)"' /tmp/libvulkan-hybris.so /out/libvulkan-hybris.so'
ls -l "$ROOT/vendor-titan2/lindroid/gpu/libvulkan-hybris.so"
