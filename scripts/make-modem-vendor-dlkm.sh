#!/usr/bin/env bash
# vendor_dlkm image for the Titan 2 with the free modem stack (8 CCCI modules from modem/,
# GPL-2.0) on top of YOUR OWN stock vendor_dlkm V01.00.14. The other ~460 modules are stock
# and closed, so the finished image is not distributed: everyone builds it themselves.
#   scripts/make-modem-vendor-dlkm.sh <stock vendor_dlkm.img | super.img> <directory with .ko> [out.img]
# Stock vendor_dlkm: from super.img of the official firmware (the script extracts vendor_dlkm_a itself)
# or dumped from the phone: adb shell su -c 'dd if=/dev/block/by-name/vendor_dlkm_a' > vendor_dlkm_a.img
# Modules: from the release (titan2-modem-ccci-*.zip) or built by scripts/build-ccci.sh.
# Needs fsck.erofs and mkfs.erofs with --file-contexts support (erofs-utils built with
# libselinux, or from the Android tree: out/host/linux-x86/bin), lpunpack/simg2img for super.img.
# Flash in fastbootd:  fastboot flash vendor_dlkm_a <out.img>
# Rollback: the same stock vendor_dlkm.img.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
IN=${1:?stock vendor_dlkm.img or super.img}
KO=${2:?directory with .ko modules}
OUT=${3:-$PWD/vendor_dlkm-titan2-modem.img}
MODS="ccci_util_lib ccci_md_all ccci_ccif ccci_dpmaif ccci_fsm_scp ccci_auxadc ccmni mddp"
FC=$ROOT/modem/vendor_dlkm_file_contexts

MKFS=${MKFS_EROFS:-mkfs.erofs}
for t in $(ls -d "$ROOT"/android/*/out/host/linux-x86/bin 2>/dev/null); do
	[ -n "${MKFS_EROFS:-}" ] || [ ! -x "$t/mkfs.erofs" ] || MKFS=$t/mkfs.erofs
done
"$MKFS" --help 2>&1 | grep -q -- --file-contexts ||
	{ echo "$MKFS does not support --file-contexts (needs erofs-utils with libselinux); set MKFS_EROFS=" >&2; exit 1; }

W=$(mktemp -d)
trap 'rm -rf "$W"' EXIT
img=$IN
if [ "$(head -c 4 "$IN" | od -An -tx1 | tr -d ' ')" = 3aff26ed ]; then   # sparse
	simg2img "$IN" "$W/raw.img"; img=$W/raw.img
fi
if lpdump "$img" >/dev/null 2>&1; then                                   # super.img
	lpunpack -p vendor_dlkm_a "$img" "$W/"; img=$W/vendor_dlkm_a.img
fi
mkdir "$W/root"
fsck.erofs --extract="$W/root" --no-preserve-owner --preserve-perms "$img" >/dev/null
[ -f "$W/root/lib/modules/ccci_md_all.ko" ] || { echo "not a Titan 2 vendor_dlkm" >&2; exit 1; }

for m in $MODS; do
	[ -f "$KO/$m.ko" ] || { echo "missing $KO/$m.ko" >&2; exit 1; }
	install -m 0644 "$KO/$m.ko" "$W/root/lib/modules/$m.ko"
done

rm -f "$OUT"
# 1230768000 = 2009-01-01 00:00 UTC, same as the stock files
"$MKFS" -zlz4hc --all-root -T 1230768000 --mount-point=/vendor_dlkm \
	--file-contexts="$FC" "$OUT" "$W/root" >/dev/null
fsck.erofs "$OUT" >/dev/null
ls -l "$OUT"
