#!/usr/bin/env bash
# Build the GKI kernel from the Nothing sources (MT6878) in Docker and pack it into boot.img (kernel only,
# the ramdisk is in the stock init_boot). Sources and clang are prepared by scripts/kernel-prepare.sh.
# Result: out/boot-<tag>.img
#   scripts/build-kernel.sh [tag] [extra config fragment]
#   scripts/build-kernel.sh lindroid-ksu-bbr configs/lindroid-ksu-bbr-titan2.config   — the release kernel
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
TAG=${1:-gki}
FRAG=${2:-}
mkdir -p "$ROOT/out"

docker run --rm -u "$(id -u):$(id -g)" -v "$ROOT:/work" -e TAG="$TAG" -e FRAG="$FRAG" \
  -e JOBS="${JOBS:-6}" titan2-kbuild bash -euo pipefail -c '
export PATH=/work/toolchains/clang-r487747c/bin:$PATH
K=/work/src/kernel-6.1
O=/work/out/kbuild-$TAG
M="make -C $K O=$O ARCH=arm64 LLVM=1 LLVM_IAS=1 LOCALVERSION=-android14-11 -j${JOBS:-6}"
mkdir -p $O
$M gki_defconfig
if [ -n "$FRAG" ]; then
  $K/scripts/kconfig/merge_config.sh -m -O $O $O/.config /work/$FRAG
  $M olddefconfig
fi
$M Image modules_prepare
lz4 -l -12 -f $O/arch/arm64/boot/Image $O/Image.lz4

# mkbootimg/avbtool: tools/ or from the Android tree (system/tools/mkbootimg, external/avb)
A=$(ls -d /work/android/*/system/tools/mkbootimg 2>/dev/null | head -1 || true)
MKBOOTIMG=/work/tools/mkbootimg/mkbootimg.py; [ -f $MKBOOTIMG ] || MKBOOTIMG=$A/mkbootimg.py
AVBTOOL=/work/tools/avbtool.py; [ -f $AVBTOOL ] || AVBTOOL=${A%/system/tools/mkbootimg}/external/avb/avbtool.py
python3 $MKBOOTIMG --header_version 4 --kernel $O/Image.lz4 \
  --output /work/out/boot-$TAG.img
python3 $AVBTOOL add_hash_footer --image /work/out/boot-$TAG.img \
  --partition_name boot --partition_size 67108864 \
  --prop com.android.build.boot.os_version:14 \
  --prop com.android.build.boot.security_patch:2024-04-05
strings $O/arch/arm64/boot/Image | grep "Linux version" | head -1 || true
'
ls -l "$ROOT/out/boot-$TAG.img"
