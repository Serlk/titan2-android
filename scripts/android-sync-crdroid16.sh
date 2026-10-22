#!/usr/bin/env bash
# crDroid 16.0 (= LineageOS 23.2, Android 16 QPR2) as a GSI on the TrebleDroid patches (MisterZtr lineage-23.2)
# + our Titan 2 layer. Tree android/crdroid-16, Docker, shared ccache.
#   scripts/android-sync-crdroid16.sh      (running again = update + reapply the patches)
# Build: scripts/android-build-crdroid16.sh
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
mkdir -p "$ROOT/android/crdroid-16"
docker run --rm -u "$(id -u):$(id -g)" -e HOME=/home/builder \
  -v "$ROOT/android/home:/home/builder" -v "$ROOT/android/crdroid-16:/src" \
  -v "$ROOT/android-manifests:/manifests:ro" -w /src titan2-aosp bash -euo pipefail -c '
git config --global user.name serlk; git config --global user.email 22211586+Serlk@users.noreply.github.com
git config --global color.ui false; git lfs install >/dev/null
[ -d .repo ] || repo init -u https://github.com/crdroidandroid/android.git -b 16.0 --git-lfs --depth=1
rm -rf .repo/local_manifests
git clone -q --depth=1 -b lineage-23.2 https://github.com/MisterZtr/treble_manifest.git .repo/local_manifests
cp /manifests/lindroid-a16.xml .repo/local_manifests/
repo sync -c -j8 --force-sync --no-tags --no-clone-bundle --optimized-fetch --prune --fail-fast
# The MisterZtr patches were written for plain Lineage: some apply to the crDroid forks with fuzz, some do not.
# The script does not stop on failed ones but prints "FAILED APPLYING" — go through them in the log.
bash LineageOS_gsi/patches/apply-patches.sh .
echo SYNC_DONE
'
# Our Titan 2 layer (cameras, rear screen) — after the TrebleDroid patches
"$ROOT/scripts/apply-titan2-patches.sh" crdroid-16 titan2-a16
# Lindroid: port of the LMODroid input patch to A16 + vendor_lindroid glue for a GSI
"$ROOT/scripts/apply-titan2-patches.sh" crdroid-16 lindroid-a16
