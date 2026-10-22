#!/usr/bin/env bash
# External files for vendor-titan2 (not committed to git):
#  - Plektra (GPL-3.0, scripts/fetch-plektra.sh) — always;
#  - proprietary ones, only from YOUR OWN stock firmware and only if it is unpacked (STOCK):
#    the stock MTK camera (APK after scripts/patch-mtkcamera.sh — Agui stubs, JNI/isphal libs,
#    privapp allowlist) and Google Phone (goes only into the GApps build).
#   scripts/prepare-vendor-titan2.sh [STOCK]
# STOCK — directory with the unpacked logical partitions of stock V01.00.10 (system_a/, system_ext_a/,
# product_a/; default out/a15-super/x). No directory — the build goes without the MTK camera and Google Phone.
# During the build the camera APK is re-signed with the platform key (signature permissions are granted normally).
set -euo pipefail
cd "$(dirname "$0")/.."
X=${1:-out/a15-super/x}; C=vendor-titan2/MtkCamera; D=vendor-titan2/GoogleDialer
scripts/fetch-plektra.sh
if [ ! -d "$X/system_ext_a" ]; then
  echo "no unpacked stock ($X): the MTK camera and Google Phone will not be in the image"
  rm -f $C/Android.bp $D/Android.bp
  exit 0
fi
H=$(ls -d android/*/out/host/linux-x86 2>/dev/null | head -1 || true)
[ -x "$H/bin/aapt2" ] || { echo "no Android host tools (android/*/out/host/linux-x86/bin/aapt2): build them first" >&2; exit 1; }
[ -f out/MtkCamera-gsi.apk ] || STOCK="$X" scripts/patch-mtkcamera.sh
mkdir -p $C/lib64
cp out/MtkCamera-gsi.apk $C/MtkCamera.apk
cp $X/system_a/system/lib64/libjni_custom_isphal_impl{,_aidl}.so \
   $X/system_ext_a/lib64/vendor.mediatek.hardware.camera.isphal{-V1-ndk,@1.0,@1.1}.so $C/lib64/
# a priv-app without an allowlist crashes system_server with ro.control_privapp_permissions=enforce
{ echo '<?xml version="1.0" encoding="utf-8"?>'
  echo '<permissions>'
  echo '    <privapp-permissions package="com.mediatek.camera">'
  LD_LIBRARY_PATH=$H/lib64 $H/bin/aapt2 dump permissions $C/MtkCamera.apk \
    | grep -oE "uses-permission: name='[^']+'" | sed "s/.*name='\(.*\)'/        <permission name=\"\1\"\/>/" \
    | grep -v DYNAMIC_RECEIVER
  echo '    </privapp-permissions>'
  echo '</permissions>'; } > $C/privapp-permissions-com.mediatek.camera.xml
cp $C/Android.bp.in $C/Android.bp
# Google Phone (Google-signed) and its privapp allowlist
cp $X/product_a/priv-app/GoogleDialer/GoogleDialer.apk $D/
cp $X/product_a/etc/permissions/privapp-permissions-google-comms-suite.xml $D/
cp $D/Android.bp.in $D/Android.bp
ls -l $C $C/lib64 $D
