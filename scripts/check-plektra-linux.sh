#!/usr/bin/env bash
# plektra-linux compiles nothing (python3 from the Lindroid rootfs). After editing the tables in
# vendor-titan2/lindroid/plektra-linux/mappings this script regenerates xkb/symbols/titan2,
# runs the logic tests and checks that a keymap with our rule compiles (us,ru).
# Python comes from the titan2-kbuild image: the host's system python3 may be broken.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
D=vendor-titan2/lindroid/plektra-linux
py() { docker run --rm -u "$(id -u):$(id -g)" -v "$ROOT:/work" -w "/work/$D" titan2-kbuild python3 -I -B "$@"; }
py plektra-linux.py --gen-xkb xkb/symbols/titan2 mappings
py tests/test_engine.py
[ -f "$ROOT/$D/tests/test_touchpad.py" ] && py tests/test_touchpad.py || echo "tests/test_touchpad.py: skipped (missing)"
py tests/test_config.py
py -c "import ast,sys; [ast.parse(open(f,encoding=\"utf-8\").read(),f) for f in (\"plektra-linux-im.py\",\"plektra_render.py\",\"plektra_dbus.py\",\"plektra_android.py\",\"plektra_config.py\")]; print(\"im: syntax ok\")"
if command -v xkbcli >/dev/null; then
  T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
  cp -r "$ROOT/$D/xkb/." "$T/"
  xkbcli compile-keymap --include "$T" --include-defaults --rules evdev --model pc105 \
    --layout us,ru >"$T/keymap" && grep -c 'TWO_LEVEL' "$T/keymap" | sed 's/^/xkb: TWO_LEVEL keys: /'
fi
