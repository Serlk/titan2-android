#!/usr/bin/env bash
# Flash the crDroid 16 GSI on the Titan 2 over the stock Android partitions (vendor etc. are already there).
#   VBMETA=<stock vbmeta.img> scripts/flash-crdroid16.sh <system.img> [boot.img]   phone in fastboot (lk)
# Flashed: [boot_a], vbmeta_a with verification disabled, product_a deleted, system_a.
# With the titan2-boot dual-boot menu in boot_a, passing boot.img replaces the menu.
# Data is wiped PROPERLY (order verified on the phone): metadata is formatted in fastbootd
# (`format:f2fs`, lk does not know its size), then userdata with `-w` in lk (lk formats it; `-w` in
# fastbootd only erases and leaves metadata empty -> the /data encryption key lives in tmpfs ->
# bootloop from the second boot).
# Only boot_a, vbmeta_a, system_a, product_a, userdata and metadata are written:
# preloader/lk/tee/nvram/seccfg/protect are not touched.
set -euo pipefail
cd "$(dirname "$0")/.."
F=${FASTBOOT:-tools/platform-tools/fastboot}
[ -x "$F" ] || F=fastboot
SYSTEM=${1:?system.img}
BOOT=${2:-}
VBMETA=${VBMETA:-backup/20261017-V01.00.14/vbmeta_a.img}
[ -f "$SYSTEM" ] || { echo "missing $SYSTEM"; exit 1; }
[ -f "$VBMETA" ] || { echo "missing $VBMETA: point VBMETA= at the stock vbmeta.img V01.00.14"; exit 1; }
[ -z "$BOOT" ] || [ -f "$BOOT" ] || { echo "missing $BOOT"; exit 1; }

mode() { "$F" getvar is-userspace 2>&1 | sed -n 's/^is-userspace: //p'; }
wait_mode() {  # yes = fastbootd, no = lk
  for _ in $(seq 1 60); do [ "$(mode)" = "$1" ] && return 0; sleep 2; done
  echo "timed out waiting for is-userspace=$1"; exit 1
}

"$F" devices | grep -q fastboot || { echo "Phone is not in fastboot"; exit 1; }
[ "$(mode)" = no ] || { echo "needs lk fastboot (bootloader), not fastbootd"; exit 1; }
# `flash system` in fastbootd writes the current slot: it must be a, like boot_a/vbmeta_a
slot=$("$F" getvar current-slot 2>&1 | sed -n 's/^current-slot: //p')
case "$slot" in a|_a) ;; *) echo "current slot is '$slot', expected a: run 'fastboot set_active a' first"; exit 1 ;; esac
echo "Will flash ${BOOT:+boot_a, }vbmeta_a, system_a; delete product_a; wipe ALL data."
read -rp "Continue? Type yes: " ans; [ "$ans" = yes ] || exit 1

[ -z "$BOOT" ] || "$F" flash boot_a "$BOOT"
"$F" --disable-verity --disable-verification flash vbmeta_a "$VBMETA"
"$F" reboot fastboot
wait_mode yes
"$F" delete-logical-partition product_a 2>/dev/null || true
"$F" flash system "$SYSTEM"
"$F" format:f2fs metadata
"$F" reboot bootloader
wait_mode no
# -w only in lk (it formats userdata there; in fastbootd it would erase metadata again)
[ "$(mode)" = no ] || { echo "not in lk: refusing -w"; exit 1; }
"$F" -w
"$F" reboot
echo "Done. The first boot takes a few minutes. Check: reboot once more and make sure it boots."
