#!/usr/bin/env bash
# Stock MTK camera (com.mediatek.camera, V01.00.10) -> APK that works on a GSI.
#   scripts/patch-mtkcamera.sh   -> out/MtkCamera-gsi.apk (AOSP testkey signature), then make-magisk-mtkcamera.sh
# Code changes (smali):
#  - getSystemService(AServiceManager.class) -> AServiceManager.stub(): the Agui service (stock framework.jar) is missing on a GSI,
#    and without a null check the camera crashed; stubs in patches/mtkcamera/smali ("SuperLight" torch = unsupported);
#  - Settings.System.getIntForUser(USER_CURRENT) -> getInt: INTERACT_ACROSS_USERS_FULL (signature) cannot be granted.
set -euo pipefail
cd "$(dirname "$0")/.."
# Android tree with the host tools built (zipalign, apksigner, aapt2)
L=${ANDROID_TREE:-$(dirname $(dirname $(dirname $(ls -d android/*/out/host/linux-x86 | head -1))))}
J=$L/prebuilts/jdk/jdk21/linux-x86/bin/java; H=$L/out/host/linux-x86
SRC=${STOCK:-out/a15-super/x}/system_ext_a/app/ACamera2/ACamera2.apk
W=out/mtkcamera-work; rm -rf $W; mkdir -p $W
unzip -q -o $SRC classes.dex -d $W
$J -jar tools/smali/baksmali-2.5.2.jar d $W/classes.dex -o $W/smali
python3 -I - $W/smali <<'PY'
import re,sys,glob
pat=re.compile(r'(const-class ([vp]\d+), Lcom/agui/server/AServiceManager;\n(?:(?:\s*\.line \d+\n)|\s*\n)*?\s*)'
               r'invoke-virtual \{([vp]\d+), \2\}, (L[^;]+;)->getSystemService\(Ljava/lang/Class;\)Ljava/lang/Object;')
gsu='Landroid/provider/Settings$System;->getIntForUser(Landroid/content/ContentResolver;Ljava/lang/String;II)I'
n1=n2=0
for f in glob.glob(sys.argv[1]+'/**/*.smali',recursive=True):
    s=open(f).read(); o=s
    s,n=pat.subn(lambda m: m.group(1)+'invoke-static {}, Lcom/agui/server/AServiceManager;->stub()Lcom/agui/server/AServiceManager;', s); n1+=n
    n2+=s.count(gsu); s=s.replace(gsu, gsu.replace('Landroid/provider/Settings$System;','Lcom/agui/server/GsiCompat;'))
    if s!=o: open(f,'w').write(s)
print('getSystemService(AServiceManager):',n1,' getIntForUser:',n2)
assert n1==5 and n2==4, 'wrong APK version: check the calls by hand'
PY
mkdir -p $W/smali/com/agui/server; cp patches/mtkcamera/smali/*.smali $W/smali/com/agui/server/
mkdir -p $W/dex; $J -jar tools/smali/smali-2.5.2.jar a $W/smali --api 28 -o $W/dex/classes.dex
cp $SRC $W/u.apk; (cd $W && zip -q -d u.apk 'META-INF/*' && cd dex && zip -q -0 ../u.apk classes.dex)
export LD_LIBRARY_PATH=$H/lib64 PATH=$(dirname $J):$PATH
$H/bin/zipalign -f -p 4 $W/u.apk $W/a.apk
$H/bin/apksigner sign --key $L/build/make/target/product/security/testkey.pk8 \
  --cert $L/build/make/target/product/security/testkey.x509.pem --out out/MtkCamera-gsi.apk $W/a.apk
echo "done: out/MtkCamera-gsi.apk"
