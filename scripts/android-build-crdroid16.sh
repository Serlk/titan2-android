#!/usr/bin/env bash
# Build the crDroid 16.0 GSI (LineageOS 23.2, Android 16 QPR2) on the TrebleDroid patches (MisterZtr lineage-23.2)
# + the Titan 2 layer in Docker. The tree is prepared by scripts/android-sync-crdroid16.sh.
# Signing: own keys from secrets/android-certs (mounted as vendor/lineage-priv/keys) if present;
# otherwise the AOSP test keys.
#   scripts/android-build-crdroid16.sh [bvN4|bgN4|bvNE|bgNE]   (default bvN4 = no GApps, ext4;
#   bgN4 = with MindTheGapps)
# Result: android/home/build-output/crdroid-16.0-trebledroid-<target>-<date>.img
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
GSI_TARGET=${1:-bvN4}
[ -f "$ROOT/vendor-titan2/Plektra/Plektra.apk" ] || "$ROOT/scripts/prepare-vendor-titan2.sh"
KEYS=()
[ -d "$ROOT/secrets/android-certs" ] && KEYS=(-v "$ROOT/secrets/android-certs:/src/vendor/lineage-priv/keys:ro")
# TrebleApp (MisterZtr 23.2 builds it from treble_app, not prebuilt): gradle, Java 17.
# uid without a passwd entry -> Java takes user.home="?", so set it explicitly.
# build.sh signs with the repository test key, but Android.mk (LOCAL_CERTIFICATE := platform)
# re-signs with our platform key.
[ -f "$ROOT/android/crdroid-16/treble_app/TrebleApp.apk" ] || docker run --rm -u "$(id -u):$(id -g)" \
  -e HOME=/home/builder -e GRADLE_USER_HOME=/home/builder/.gradle \
  -e JAVA_TOOL_OPTIONS=-Duser.home=/home/builder \
  -v "$ROOT/android/home:/home/builder" \
  -v "$ROOT/android/crdroid-16/treble_app:/app" -w /app titan2-aosp ./build.sh
# tmux kill-session does not stop the container: a previous run would hold out/.lock
docker rm -f titan2-crdroid16-build >/dev/null 2>&1 || true
docker run --rm --name titan2-crdroid16-build -u "$(id -u):$(id -g)" -e HOME=/home/builder -e CCACHE_DIR=/home/builder/.ccache -e GSI_TARGET="$GSI_TARGET" \
  -v "$ROOT/android/home:/home/builder" -v "$ROOT/android/crdroid-16:/src" \
  "${KEYS[@]}" \
  -v "$ROOT/vendor-titan2:/src/vendor/titan2:ro" \
  -v "$ROOT/vendor-extra:/src/vendor/extra:ro" -w /src titan2-aosp bash -c '
export USE_CCACHE=1 CCACHE_COMPRESS=1; ccache -M 80G >/dev/null
# crDroid envsetup.sh returns non-zero for no reason: set -e only after it
source build/envsetup.sh >/dev/null
breakfast lineage_arm64_$GSI_TARGET-bp4a-userdebug || exit 1
set -eo pipefail
make -j6 systemimage
mkdir -p ~/build-output
cp $OUT/system.img ~/build-output/crdroid-16.0-trebledroid-$GSI_TARGET-$(date -u +%Y%m%d).img
'
ls -l "$ROOT/android/home/build-output/"
