# titan2-android

crDroid 16 (Android 16 QPR2, LineageOS 23.2) GSI for the **Unihertz Titan 2** (MT6878 /
Dimensity 7300) with a device layer for the hardware keyboard, touchpad and
keyboard backlight, a GKI 6.1 kernel with KernelSU, and Lindroid (Debian KDE in an LXC
container with Mali GPU through libhybris).

This repository holds patches and build scripts on top of the crDroid / TrebleDroid sources,
not a fork of every AOSP project. Prebuilt images are on the Releases page. The mainline Linux
port lives separately in [Serlk/titan2-linux](https://github.com/Serlk/titan2-linux).

Documentation: [installation](docs/INSTALL.md), [building](docs/BUILD.md), [GApps](docs/GAPPS.md).
Русская документация: [установка](docs/INSTALL.ru.md), [сборка](docs/BUILD.ru.md),
[GApps](docs/GAPPS.ru.md).

---

## What it is

A crDroid 16 GSI on top of the stock Titan 2 vendor partitions (Global V01.00.14, Android 16),
plus a hardware support layer:

| Area | What is done |
|---|---|
| **Settings → Titan 2** (`vendor-titan2/Titan2Parts`) | keyboard (Sym/Fn/Alt/Shift, letter shortcuts, presets), side keys (short/long/double press), touchpad (per-app scroll and cursor helpers), keyboard backlight, charge limit and battery health, 5 GHz hotspot, TCP BBR, per-app firewall, USB while locked, CPU profile |
| Display | the square 1440×1440 screen at low density uses phone layouts (`smallestScreenWidthDp` is capped at 599 dp; launcher/SystemUI/Settings follow it) |
| App sandbox | an isolated app cannot see the list of installed apps, VPNs and their interfaces, and, as chosen, contacts/files/location |
| Kernel (`patches/kernel`) | NothingOSS GKI 6.1 (MT6878) + KernelSU, EVDI (Lindroid virtual display), SysV IPC with the KMI preserved, Mali in a PID namespace, TCP BBR; stock modules load unchanged |
| Modem (`modem/`) | free in-kernel MediaTek CCCI modem stack: open, cleaned up for MT6878 (−13,500 lines), tested with calls, SMS and mobile data; optional install into `vendor_dlkm` — [modem/README.md](modem/README.md) |
| Lindroid (`patches/lindroid-a16`, `vendor-titan2/lindroid`) | Debian KDE in a container: Mali GPU through libhybris, Vulkan 1.3 + OpenGL through Zink, Plektra-style keyboard, clipboard shared with Android, USB passthrough (RTL-SDR, Flipper), Steam through FEX-Emu (optional: "Install Steam" menu entry) |
| Other | Plektra (IME for the hardware keyboard), FM radio (open MTK FMRadio), call recording, IR blaster |

## What does not work / limitations

- **VoLTE**: the GSI has no MTK ImsService. Calls, SMS and mobile data work.
- **Camera**: the stock MTK camera is proprietary and is not in the image. Without it you get the
  crDroid camera (Aperture) with limited features. If you build the image yourself, you can
  include the stock camera from your own firmware (see [BUILD.md](docs/BUILD.md)).
- **GApps** are not in the prebuilt image — see [GAPPS.md](docs/GAPPS.md).
- **Rear screen** does not work yet: on the GSI it cannot be enabled without freezing the main
  screen (being investigated).
- The panel runs at 60 Hz; the display engine (MiraVision) is disabled.

## Repository layout

| Directory | Contents |
|---|---|
| `patches/titan2-a16/<project>/` | Titan 2 layer patches for tree projects (`frameworks_base` = `frameworks/base`) |
| `patches/lindroid-a16/` | Lindroid port to Android 16 / GSI |
| `patches/kernel/` | kernel patches + `BASE_COMMIT`, `EVDI_COMMIT`, `clang.url` |
| `patches/mtkcamera/` | stubs for the stock MTK camera (our code; the APK itself comes from your firmware) |
| `vendor-titan2/` | device layer, mounted into the tree as `vendor/titan2` |
| `modem/` | free modem stack (sources, description, installation) |
| `android-manifests/` | Lindroid local manifest |
| `scripts/` | sync, patch application, build, flashing |
| `docker/` | build images: `aosp/` for Android, the top-level `Dockerfile` for the kernel |
| `configs/` | kernel config fragment |

## Licenses

- Our code and scripts — **GPL-2.0-or-later** (`LICENSE`).
- Patches to AOSP / LineageOS / crDroid files — under the license of the original file (usually Apache-2.0).
- Kernel patches — GPL-2.0, like the kernel itself. KernelSU — GPL-2.0/GPL-3.0 (upstream).
- Plektra (GPL-3.0) is downloaded at build time and is not stored in the repository.
- `vendor-titan2/lindroid/plektra-linux` — under the license in its own `COPYING`.
- `libvulkan-hybris.so` is built from libhybris (Apache-2.0) by `scripts/build-lindroid-hybris-vulkan.sh`.
- There are no proprietary Unihertz/MediaTek/Google files in the repository or the releases.

## Disclaimer

Flashing requires an unlocked bootloader and wipes your data. Everything is at your own risk.
Read [INSTALL.md](docs/INSTALL.md) in full before installing.
