#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Install plektra-linux into the Lindroid container rootfs.
#   install.sh SRC ROOT
# SRC : directory with the plektra-linux files (in the firmware /system_ext/usr/share/titan2/plektra-linux)
# ROOT: rootfs root: "/" inside the container or $LXC_ROOTFS from the lxc template (Android sh/toybox).
# POSIX sh + cp/mkdir/ln/chmod/grep only: works both on Android and on Debian.
set -e
SRC=${1:?SRC}
ROOT=${2:-/}
case "$ROOT" in */) ;; *) ROOT="$ROOT/" ;; esac
LIB="${ROOT}usr/local/lib/plektra-linux"
mkdir -p "$LIB/mappings" "${ROOT}etc/xkb/symbols" "${ROOT}etc/xkb/rules" \
         "${ROOT}etc/plektra-linux" "${ROOT}etc/systemd/system/multi-user.target.wants"
for f in plektra-linux.py plektra-linux-im.py plektra_render.py plektra_dbus.py plektra_android.py \
         plektra_config.py \
         plektra_touchpad.py kwin-focus.js config.example.json README.md README.ru.md COPYING; do
  if [ -f "$SRC/$f" ]; then
    cp "$SRC/$f" "$LIB/$f"
    chmod 0644 "$LIB/$f"
  fi
done
chmod 0755 "$LIB/plektra-linux.py" "$LIB/plektra-linux-im.py"
for f in "$SRC"/mappings/*.json; do
  cp "$f" "$LIB/mappings/" && chmod 0644 "$LIB/mappings/${f##*/}"
done
mkdir -p "$LIB/dict"
for f in "$SRC"/dict/*.tsv; do
  cp "$f" "$LIB/dict/" && chmod 0644 "$LIB/dict/${f##*/}"
done
# start the bar (input method) with one command, for the kwin session and the sddm login screen
mkdir -p "${ROOT}usr/local/bin"
printf '#!/bin/sh\nexec /usr/bin/python3 -I -B /usr/local/lib/plektra-linux/plektra-linux-im.py "$@"\n' \
  > "${ROOT}usr/local/bin/plektra-linux-im"
chmod 0755 "${ROOT}usr/local/bin/plektra-linux-im"
# login screen: the sddm kwin starts with --inputmethod maliit-keyboard, replace it with Plektra
pw="${ROOT}etc/sddm.conf.d/plasma-wayland.conf"
if [ -f "$pw" ] && grep -q "^CompositorCommand=.*--inputmethod" "$pw"; then
  { echo "# plektra-linux: the Plektra bar on the login screen (otherwise as in plasma-wayland.conf)"
    echo "[Wayland]"
    grep "^CompositorCommand=" "$pw" | tail -1 | \
      sed 's#--inputmethod [^ ]*#--inputmethod /usr/local/bin/plektra-linux-im#'
  } > "${ROOT}etc/sddm.conf.d/91-plektra-linux.conf"
  chmod 0644 "${ROOT}etc/sddm.conf.d/91-plektra-linux.conf"
fi
# the bar at the bottom of the screen as on Android: kwin shows the bottom panel right on field focus
# only with KWIN_IM_SHOW_ALWAYS=1 (session: environment.d, login screen: GreeterEnvironment)
mkdir -p "${ROOT}etc/environment.d"
printf 'PLEKTRA_IM_PANEL=bottom\nKWIN_IM_SHOW_ALWAYS=1\n' > "${ROOT}etc/environment.d/92-plektra-linux-im.conf"
chmod 0644 "${ROOT}etc/environment.d/92-plektra-linux-im.conf"
gconf="${ROOT}etc/sddm.conf.d/91-plektra-linux.conf"
if [ -f "$pw" ] && grep -q "^GreeterEnvironment=" "$pw" && [ -f "$gconf" ]; then
  genv=$(grep "^GreeterEnvironment=" "$pw" | tail -1 | sed 's#,*KWIN_IM_SHOW_ALWAYS=[^,]*##; s#,*PLEKTRA_IM_PANEL=[^,]*##')
  printf '[General]\n%s,KWIN_IM_SHOW_ALWAYS=1,PLEKTRA_IM_PANEL=bottom\n' "$genv" >> "$gconf"
fi
# xkb: the character slots and the rule that includes them over any layout
cp "$SRC/xkb/symbols/titan2" "${ROOT}etc/xkb/symbols/titan2"
chmod 0644 "${ROOT}etc/xkb/symbols/titan2"
rules="${ROOT}etc/xkb/rules/evdev"
if [ ! -f "$rules" ] || grep -q plektra-linux "$rules"; then
  cp "$SRC/xkb/rules/evdev" "$rules" && chmod 0644 "$rules"
else
  echo "plektra-linux: $rules already exists and is not ours, leaving it; add the lines from $SRC/xkb/rules/evdev to it" >&2
fi
# the settings app (a plasmoid in a plasmawindowed window) + a menu shortcut + an entry in "System Settings"
PD="${ROOT}usr/share/plasma/plasmoids/org.plektra.settings"
rm -rf "$PD"
mkdir -p "$PD/contents/ui" "${ROOT}usr/share/plasma/systemsettings/externalmodules" \
         "${ROOT}usr/share/applications"
cp "$SRC/settings/metadata.json" "$PD/metadata.json"
for f in "$SRC"/settings/contents/ui/*; do cp "$f" "$PD/contents/ui/"; done
chmod -R a+rX "$PD"
cp "$SRC/org.plektra.settings.desktop" "${ROOT}usr/share/applications/org.plektra.settings.desktop"
cp "$SRC/org.plektra.settings.desktop" \
   "${ROOT}usr/share/plasma/systemsettings/externalmodules/org.plektra.settings.desktop"
chmod 0644 "${ROOT}usr/share/applications/org.plektra.settings.desktop" \
   "${ROOT}usr/share/plasma/systemsettings/externalmodules/org.plektra.settings.desktop"
# the Plektra bar = the kwin input method instead of maliit (visible only when a text field is focused)
IMD=/usr/share/applications/org.plektra.linux.im.desktop
mkdir -p "${ROOT}usr/share/applications" "${ROOT}etc/xdg"
cp "$SRC/org.plektra.linux.im.desktop" "${ROOT}$IMD" && chmod 0644 "${ROOT}$IMD"
kwinrc="${ROOT}etc/xdg/kwinrc"
if ! grep -q "^InputMethod" "$kwinrc" 2>/dev/null; then
  printf '\n[Wayland]\nInputMethod=%s\n' "$IMD" >> "$kwinrc"
fi
# for users who have maliit selected, switch to Plektra
for f in "${ROOT}"home/*/.config/kwinrc "${ROOT}"root/.config/kwinrc; do
  if [ -f "$f" ] && grep -q "^InputMethod.*maliit" "$f"; then
    sed -i -E "s#^InputMethod(\[\\\$e\])?=.*maliit.*\$#InputMethod=$IMD#" "$f"
  fi
done
cp "$SRC/plektra-linux.service" "${ROOT}etc/systemd/system/plektra-linux.service"
chmod 0644 "${ROOT}etc/systemd/system/plektra-linux.service"
ln -sf ../plektra-linux.service "${ROOT}etc/systemd/system/multi-user.target.wants/plektra-linux.service"
[ -x "${ROOT}usr/bin/python3" ] || echo "plektra-linux: the rootfs has no /usr/bin/python3 (apt install python3)" >&2
# inside a running container, start right away
if [ "$ROOT" = "/" ] && [ -d /run/systemd/system ] && command -v systemctl >/dev/null 2>&1; then
  systemctl daemon-reload
  systemctl restart plektra-linux.service
  # in open Plasma sessions kwin restarts the input method at once (KConfigWatcher). KConfig
  # does not write a value equal to the default from /etc/xdg/kwinrc (and sends no notification), so
  # the system kwinrc is moved away while writing, and the user value is erased first.
  mv /etc/xdg/kwinrc /etc/xdg/kwinrc.plektra
  for f in /home/*/.config/kwinrc /root/.config/kwinrc; do
    if [ -f "$f" ]; then sed -i -E '/^InputMethod(\[.*\])?(=.*)?$/d' "$f"; fi
  done
  for b in /run/user/*/bus; do
    [ -S "$b" ] || continue
    u=${b#/run/user/}; u=${u%/bus}
    [ "$u" -ge 1000 ] 2>/dev/null || continue
    setpriv --reuid="$u" --regid="$(id -g "$u")" --clear-groups \
      env DBUS_SESSION_BUS_ADDRESS="unix:path=$b" XDG_RUNTIME_DIR="/run/user/$u" \
      HOME="$(getent passwd "$u" | cut -d: -f6)" \
      kwriteconfig6 --notify --file kwinrc --group Wayland --key InputMethod "$IMD" || true
  done
  mv /etc/xdg/kwinrc.plektra /etc/xdg/kwinrc
  # a running bar of the old version: SIGKILL = a "crash", kwin restarts it with the new one
  for p in $(ps -eo pid=,args= | awk '/plektra-linux-im\.py/ && !/awk/ {print $1}'); do
    kill -KILL "$p" 2>/dev/null || true
  done
  echo "plektra-linux: service restarted; kwin will reload the layout and start the Plektra bar"
fi
