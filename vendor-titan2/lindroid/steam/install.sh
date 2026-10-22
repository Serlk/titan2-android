#!/bin/sh
# Steam (FEX) in the Lindroid container on Titan 2, optional: installs titan2-steam-setup.sh, a service
# (builds FEX-Emu, downloads the x86-64 RootFS and the Steam client: hours and several GB, see the script)
# and the "Install Steam" menu entry. The service itself is not enabled: it is started by the menu entry or
#   sudo titan2-steam-install
#   install.sh SRC ROOT
# SRC : directory with the files (in the firmware /system_ext/usr/share/titan2/lindroid-steam)
# ROOT: rootfs root: "/" inside the container or $LXC_ROOTFS from the lxc template (Android sh/toybox).
set -e
SRC=${1:?SRC}
ROOT=${2:-/}
case "$ROOT" in */) ;; *) ROOT="$ROOT/" ;; esac
D="${ROOT}usr/share/titan2/steam"
mkdir -p "$D" "${ROOT}etc/systemd/system" "${ROOT}usr/local/bin" "${ROOT}usr/share/applications"
cp "$SRC/titan2-steam-setup.sh" "$D/titan2-steam-setup.sh"
chmod 0755 "$D/titan2-steam-setup.sh"
cat > "${ROOT}etc/systemd/system/titan2-steam-setup.service" <<'EOF'
[Unit]
Description=Titan 2: Steam through FEX-Emu (one-time setup)
Wants=network-online.target
After=network-online.target
ConditionPathExists=!/var/lib/titan2/steam-setup.done

[Service]
Type=oneshot
ExecStart=/bin/sh /usr/share/titan2/steam/titan2-steam-setup.sh lindroid
TimeoutStartSec=infinity
Nice=10
IOSchedulingClass=idle
# no network yet / download failed: try again later, the script resumes where it stopped
Restart=on-failure
RestartSec=10min

[Install]
WantedBy=multi-user.target
EOF
# enabled earlier (previous versions): disable it, installation is on request only
rm -f "${ROOT}etc/systemd/system/multi-user.target.wants/titan2-steam-setup.service"
cat > "${ROOT}usr/local/bin/titan2-steam-install" <<'EOF'
#!/bin/sh
# Steam installation (FEX-Emu): enables the service (it resumes by itself after a failure or reboot)
# and shows the log. When done, Steam appears in the menu.
[ "$(id -u)" = 0 ] || exec sudo "$0" "$@"
systemctl enable --now --no-block titan2-steam-setup.service
echo "Steam is being installed in the background (FEX-Emu is built from source, this takes long). You can close this window."
exec tail -n 20 -F /var/lib/titan2/steam-setup.log
EOF
chmod 0755 "${ROOT}usr/local/bin/titan2-steam-install"
cat > "${ROOT}usr/share/applications/titan2-steam-install.desktop" <<'EOF'
[Desktop Entry]
Name=Install Steam
Name[ru]=Установить Steam
Comment=Steam through FEX-Emu: several GB download, builds for hours
Comment[ru]=Steam через FEX-Emu: несколько ГБ, сборка займёт часы
Exec=konsole -e titan2-steam-install
Icon=system-software-install
Terminal=false
Type=Application
Categories=Game;System;
EOF
