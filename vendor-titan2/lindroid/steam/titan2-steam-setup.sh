#!/bin/sh
# Steam (x86-64) in the Lindroid container on Titan 2: FEX-Emu from source + x86-64 RootFS + the Steam
# client. GPU for games: Vulkan Mali via hybris (template.sh installs libvulkan.so from hybris),
# OpenGL: Zink on top of it (up to GL 3.1, the ARM driver gives no more).
# On request only: the "Install Steam" menu entry / sudo titan2-steam-install enables
# titan2-steam-setup.service (needs network). Manually: sudo sh /usr/share/titan2/steam/titan2-steam-setup.sh [user]
# Log: /var/lib/titan2/steam-setup.log. A rerun after a failure continues from the same point.
set -eu
USER_NAME=${1:-lindroid}
STATE=/var/lib/titan2
DONE=$STATE/steam-setup.done
SRC=/usr/src/fex
[ -e "$DONE" ] && exit 0
mkdir -p "$STATE"
[ -t 1 ] || exec >>"$STATE/steam-setup.log" 2>&1
echo "== $(date) titan2-steam-setup for $USER_NAME"
HOME_DIR=$(getent passwd "$USER_NAME" | cut -d: -f6)
[ -n "$HOME_DIR" ] || { echo "no user $USER_NAME"; exit 1; }

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends git ca-certificates curl xz-utils zstd \
    cmake ninja-build clang clang-tools lld llvm pkg-config python3 python3-setuptools \
    squashfs-tools erofs-utils libepoxy-dev libsdl2-dev libssl-dev nasm

# FEX-Emu: the latest release (FEX-YYMM tags)
# FEX-2610+: the emulator is `FEX` (formerly FEXInterpreter)
if ! command -v FEX >/dev/null && ! command -v FEXInterpreter >/dev/null; then
    TAG=$(git ls-remote --tags --refs https://github.com/FEX-Emu/FEX.git 'FEX-*' \
          | sed 's,.*/,,' | sort -V | tail -1)
    echo "FEX $TAG"
    if [ ! -d "$SRC/.git" ]; then
        git clone --depth 1 --branch "$TAG" https://github.com/FEX-Emu/FEX.git "$SRC"
    fi
    # submodules without test binaries (hundreds of MB, not needed for the build)
    git -C "$SRC" submodule update --init --depth 1 $(git -C "$SRC" config -f .gitmodules \
        --get-regexp '\.path$' | awk '{print $2}' | grep -v -i test)
    cmake -S "$SRC" -B "$SRC/build" -G Ninja -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++ -DUSE_LINKER=lld \
        -DENABLE_LTO=True -DBUILD_TESTING=False -DCMAKE_CXX_SCAN_FOR_MODULES=OFF -DENABLE_ASSERTIONS=False \
        -DBUILD_FEXCONFIG=False
    nice ninja -C "$SRC/build" -j"$(nproc)"
    ninja -C "$SRC/build" install
    rm -rf "$SRC/build"
fi

# x86-64 RootFS for FEX (Ubuntu, unpacked: there is no FUSE in the container)
if [ ! -d "$HOME_DIR/.fex-emu/RootFS" ] || [ -z "$(ls -A "$HOME_DIR/.fex-emu/RootFS" 2>/dev/null)" ]; then
    # rootfs.fex-emu.gg is sometimes unreachable on some carriers (CDN blocking): then the
    # Ubuntu_24_04.sqsh/.ero image, downloaded elsewhere, goes into /var/lib/titan2/fex-rootfs/
    img=$(ls "$STATE"/fex-rootfs/*.sqsh "$STATE"/fex-rootfs/*.ero 2>/dev/null | head -1)
    if [ -n "$img" ]; then
        name=$(basename "${img%.*}")
        dst="$HOME_DIR/.fex-emu/RootFS/$name"
        rm -rf "$dst"
        mkdir -p "$HOME_DIR/.fex-emu/RootFS"
        case "$img" in
            *.sqsh) unsquashfs -q -d "$dst" "$img" ;;
            *.ero) mkdir -p "$dst" && fsck.erofs --extract="$dst" "$img" ;;
        esac
        printf '{"Config":{"RootFS":"%s"}}\n' "$name" > "$HOME_DIR/.fex-emu/Config.json"
        chown -R "$USER_NAME": "$HOME_DIR/.fex-emu"
    else
        runuser -u "$USER_NAME" -- FEXRootFSFetcher -y -x --distro-name=ubuntu --distro-version=24.04
    fi
fi

# Steam client: the official Valve package unpacked into /opt/steam-launcher (dpkg is not used:
# the package is amd64). The bootstrap downloads the client into ~/.local/share/Steam on first run.
if [ ! -x /opt/steam-launcher/usr/lib/steam/bin_steam.sh ]; then
    tmp=$(mktemp -d)
    curl -fL -o "$tmp/steam.deb" https://repo.steampowered.com/steam/archive/stable/steam_latest.deb
    rm -rf /opt/steam-launcher
    dpkg-deb -x "$tmp/steam.deb" /opt/steam-launcher
    rm -rf "$tmp"
fi

install -Dm755 /dev/stdin /usr/local/bin/steam-fex <<'EOF'
#!/bin/sh
# Steam via FEX. The client UI (Chromium) without GPU compositing: its GL goes to llvmpipe.
# arguments (steam:// URLs from the browser) are single-quoted for the -c string: unquoted, a
# URL with ; or $( ) would run as a command
q=
for a in "$@"; do q="$q '$(printf '%s' "$a" | sed "s/'/'\\\\''/g")'"; done
exec FEXBash -c "/opt/steam-launcher/usr/lib/steam/bin_steam.sh -cef-disable-gpu-compositing$q"
EOF
install -Dm644 /dev/stdin /usr/share/applications/steam-fex.desktop <<'EOF'
[Desktop Entry]
Name=Steam
Comment=Steam (x86-64 via FEX-Emu)
Exec=/usr/local/bin/steam-fex %U
Icon=/opt/steam-launcher/usr/share/icons/hicolor/256x256/apps/steam.png
Terminal=false
Type=Application
Categories=Network;FileTransfer;Game;
MimeType=x-scheme-handler/steam;x-scheme-handler/steamlink;
EOF

# the "Install Steam" entry is no longer needed: Steam itself is in the menu now
rm -f /usr/share/applications/titan2-steam-install.desktop
systemctl disable titan2-steam-setup.service 2>/dev/null || true
touch "$DONE"
echo "== $(date) done"
