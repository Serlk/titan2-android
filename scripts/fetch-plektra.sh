#!/usr/bin/env bash
# Download Plektra (nightly, full variant) for vendor-titan2/Plektra/Plektra.apk and verify its sha256.
# New version: change TAG and SHA (from the release SHA256SUMS), run, rebuild.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
TAG=v26.10-nightly.11.184948Z
SHA=7ca4d8c1c240e4771c15d40fa3570f213b2377d7c0cac26daf14b5693ac59a2c
NAME=Plektra-${TAG#v}-full.apk
DL=$ROOT/downloads/plektra/$TAG
mkdir -p "$DL"
[ -f "$DL/$NAME" ] || curl -fL -o "$DL/$NAME" "https://github.com/pkb-rocks/plektra/releases/download/nightly/$TAG/$NAME"
echo "$SHA  $DL/$NAME" | sha256sum -c -
cp "$DL/$NAME" "$ROOT/vendor-titan2/Plektra/Plektra.apk"
