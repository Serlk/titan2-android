#!/usr/bin/env bash
# Install plektra-linux (the Titan 2 keyboard "like in Plektra") into an already created and running
# Lindroid container on the phone over adb (root). New containers get it from the firmware themselves
# (lxc template, patches/lindroid-a16/vendor_lindroid/0006).
#   scripts/install-plektra-linux.sh            — install/update and restart the service
#   scripts/install-plektra-linux.sh --status   — only the service state and journal
# Files go as a tar stream over stdin into a process inside the container (nsenter): writing to the rootfs
# from the Android side (/data/lindroid/...) gives "Stale file handle".
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
ADB=${ADB:-$ROOT/tools/platform-tools/adb}
SRC=$ROOT/vendor-titan2/lindroid/plektra-linux
ENVX='/usr/bin/env -i PATH=/usr/sbin:/usr/bin:/sbin:/bin'

"$ADB" get-state >/dev/null
PID=$("$ADB" shell "su -c 'pidof systemd-journald'" | tr -d '\r' | awk '{print $1}')
[ -n "$PID" ] || { echo "the Lindroid container is not running (no systemd-journald)" >&2; exit 1; }
in_ct() {  # in_ct 'command for sh inside the container' — no single quotes inside
  "$ADB" shell "su -c 'nsenter -t $PID -m -p -- $ENVX sh -c \"$1\"'"
}

if [ "${1:-}" != "--status" ]; then
  TMP=$(mktemp -d)
  trap 'rm -rf "$TMP"' EXIT
  tar -C "$SRC" --exclude=tests --exclude=__pycache__ -czf "$TMP/plk.tgz" .
  "$ADB" push "$TMP/plk.tgz" /data/local/tmp/plektra-linux.tgz >/dev/null
  # cat runs in the Android namespace, tar and install.sh inside the container
  "$ADB" shell "su -c 'cat /data/local/tmp/plektra-linux.tgz | nsenter -t $PID -m -p -- $ENVX sh -c \"rm -rf /run/plk && mkdir -p /run/plk && tar xzf - -C /run/plk && sh /run/plk/install.sh /run/plk /; rc=\\\$?; rm -rf /run/plk; exit \\\$rc\"; rm -f /data/local/tmp/plektra-linux.tgz'"
  # Containers created before vendor_lindroid 0011 still coldplug every device on start
  # (udevadm trigger --type=all --action=add): Android ueventd then resets vendor /dev nodes
  # (camera flash, fingerprint) to root 0600. Add the same drop-in the template writes now.
  cat > "$TMP/10-lindroid.conf" <<'UNIT'
[Service]
ExecStart=
ExecStart=-udevadm trigger --type=subsystems --action=add
ExecStart=-udevadm trigger --type=devices --action=add --subsystem-match=input --subsystem-match=drm --subsystem-match=sound --subsystem-match=usb --subsystem-match=hidraw
UNIT
  "$ADB" push "$TMP/10-lindroid.conf" /data/local/tmp/lindroid-udev-trigger.conf >/dev/null
  "$ADB" shell "su -c 'cat /data/local/tmp/lindroid-udev-trigger.conf | nsenter -t $PID -m -p -- $ENVX sh -c \"d=/etc/systemd/system/systemd-udev-trigger.service.d; [ -f \\\$d/10-lindroid.conf ] || { mkdir -p \\\$d && cat > \\\$d/10-lindroid.conf && echo coldplug drop-in installed; }\"; rm -f /data/local/tmp/lindroid-udev-trigger.conf'"
fi
in_ct "systemctl --no-pager status plektra-linux.service | head -n 12; journalctl --no-pager -b -n 20 -t plektra-linux"
