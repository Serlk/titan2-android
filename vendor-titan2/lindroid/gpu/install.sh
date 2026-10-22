#!/bin/sh
# GPU in the Lindroid container on Titan 2: Vulkan on the Android driver (Mali-G615, Vulkan 1.3) via
# libvulkan.so.1 from libhybris, and OpenGL up to 3.1 through Zink on top of it (zink-run).
#   install.sh SRC ROOT
# SRC : directory with the files (in the firmware /system_ext/usr/share/titan2/lindroid-gpu)
# ROOT: rootfs root: "/" inside the container or $LXC_ROOTFS from the lxc template (Android sh/toybox).
# The Debian Khronos loader is left alone: our libvulkan.so.1 goes to a separate directory which
# ld.so.conf.d puts before the system one, so apt upgrade will not overwrite it.
set -e
SRC=${1:?SRC}
ROOT=${2:-/}
case "$ROOT" in */) ;; *) ROOT="$ROOT/" ;; esac
LIB="${ROOT}usr/lib/aarch64-linux-gnu/hybris-vulkan"
mkdir -p "$LIB" "${ROOT}etc/ld.so.conf.d" "${ROOT}usr/local/bin"
cp "$SRC/libvulkan-hybris.so" "$LIB/libvulkan.so.1"
chmod 0644 "$LIB/libvulkan.so.1"
echo "/usr/lib/aarch64-linux-gnu/hybris-vulkan" > "${ROOT}etc/ld.so.conf.d/00-hybris-vulkan.conf"
cp "$SRC/zink-run" "${ROOT}usr/local/bin/zink-run"
chmod 0755 "${ROOT}usr/local/bin/zink-run"
if [ "$ROOT" = / ]; then
  ldconfig
else
  chroot "$ROOT" /sbin/ldconfig
fi
