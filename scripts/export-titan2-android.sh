#!/usr/bin/env bash
# Public tree github.com/Serlk/titan2-android from this repository: only the list below
# (no recon/, backup/, secrets/, stock files) + publish/titan2-android/ on top.
# Patch authors are rewritten to the GitHub noreply address. Commit locally only, push by hand.
#   scripts/export-titan2-android.sh [directory]   (default ../titan2-android)
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
DST=$(realpath -m "${1:-$ROOT/../titan2-android}")
AUTHOR='serlk <22211586+Serlk@users.noreply.github.com>'
PATHS=(
  android-manifests/lindroid-a16.xml
  configs/lindroid-ksu-bbr-titan2.config
  docker/Dockerfile docker/aosp/Dockerfile
  docs/INSTALL.ru.md docs/INSTALL.md
  patches/titan2-a16 patches/lindroid-a16 patches/kernel patches/mtkcamera
  vendor-titan2 vendor-extra
  scripts/android-sync-crdroid16.sh scripts/android-build-crdroid16.sh scripts/apply-titan2-patches.sh
  scripts/prepare-vendor-titan2.sh scripts/fetch-plektra.sh scripts/patch-mtkcamera.sh
  scripts/flash-crdroid16.sh scripts/kernel-prepare.sh scripts/build-kernel.sh
  scripts/build-lindroid-hybris-vulkan.sh scripts/build-lindroid-hybris-fix.sh
  scripts/install-plektra-linux.sh scripts/check-plektra-linux.sh
  scripts/export-titan2-android.sh scripts/make-modem-vendor-dlkm.sh
  modem
)
cd "$ROOT"
mkdir -p "$DST"
[ -d "$DST/.git" ] || git -C "$DST" init -q -b main
# the tree is replaced as a whole: what is removed here disappears there too. .git and the build
# work directories are kept (in .gitignore; android/secrets may be links to this tree)
find "$DST" -mindepth 1 -maxdepth 1 ! -name .git ! -name android ! -name secrets ! -name src \
  ! -name out ! -name toolchains ! -name downloads ! -name tools -exec rm -rf {} +
# tracked and new files, without ignored ones (.apk, camera blobs, generated Android.bp)
git ls-files -z -co --exclude-standard -- "${PATHS[@]}" | while IFS= read -r -d '' f; do
  [ -e "$f" ] || continue
  mkdir -p "$DST/$(dirname "$f")"; cp -p "$f" "$DST/$f"
done
cp -rp publish/titan2-android/. "$DST/"
# free modem stack: sources of the version tested on the phone (src/titan2-modem 2dcb1fc) with
# the comments translated to English (branch publish-en; code verified identical, strict LINE0)
MODEM_COMMIT=8260d1f
mkdir -p "$DST/modem/src"
git -C src/titan2-modem archive "$MODEM_COMMIT" | tar -x -C "$DST/modem/src"
echo "$MODEM_COMMIT" > "$DST/modem/src/VERSION"
find "$DST" -name __pycache__ -prune -exec rm -rf {} +
# From: in the patches (git am takes the author from it) — the public address instead of the personal one
{ grep -rlZE '^From: (serlk|titan2) <' "$DST/patches" || true; } | xargs -0 -r sed -i -E "s/^From: (serlk|titan2) <[^>]*>/From: $AUTHOR/"
# audit everything that was exported. The build work directories are skipped only at the top
# level: grep --exclude-dir would also skip modem/src, Titan2Parts/src, docker/tools, ...
SKIP=(.git android secrets src out toolchains downloads tools)
exported() {
  local prune=() d
  for d in "${SKIP[@]}"; do prune+=(-path "$DST/$d" -o); done
  find "$DST" \( "${prune[@]}" -false \) -prune -o -type f ! -path "$DST/scripts/export-titan2-android.sh" -print0
}
# check: no personal data allowed
hits=$(exported | xargs -0 -r grep -IlE 'gmail\.com|serlk1123e|TITAN2000[0-9A-Z]{4,}|/home/serlk' || true)
[ -z "$hits" ] || { echo "$hits"; echo "STOP: personal data in the files above"; exit 1; }
# check: no private trees and no binaries/keys; the only .so are our two built from source
hits=$( { exported | tr '\0' '\n' | grep -E "^$DST/(recon|backup|secrets|firmware|scratchpad)/" || true
          exported | tr '\0' '\n' | grep -E '\.(apk|img|pk8|jks|keystore|so|so\.[0-9.]+|ko|jar|dex|odex|vdex|oat|zip)$' |
            grep -vxF -e "$DST/vendor-titan2/lindroid/gpu/libvulkan-hybris.so" \
                      -e "$DST/vendor-titan2/lindroid/hybris-fix/libtls-padding.so" || true; } )
[ -z "$hits" ] || { echo "$hits"; echo "STOP: binaries/keys/private files above"; exit 1; }
# warning only: references to recon/ are dead links in the public tree
refs=$(exported | xargs -0 -r grep -IlE '(^|[^a-z])recon/' || true)
[ -z "$refs" ] || { echo "$refs"; echo "WARNING: recon/ references in the files above"; }
echo "export: $DST ($(git -C "$DST" status --porcelain | wc -l) changes)"
