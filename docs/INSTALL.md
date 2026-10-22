Русская версия: [INSTALL.ru.md](INSTALL.ru.md)

# Installing crDroid 16 on the Unihertz Titan 2

crDroid 16 (Android 16, LineageOS 23.2) as a GSI with Titan 2 hardware support:
- the keyboard and touchpad work as on the factory firmware;
- keyboard backlight, IR blaster, FM radio;
- our own kernel with KernelSU and TCP BBR;
- Lindroid — Linux (KDE Plasma) in a container.

The prebuilt image is built **without Google services** and without the stock MTK camera (both are proprietary).
How to get GApps — see [GAPPS.md](GAPPS.md); the camera — see [BUILD.md](BUILD.md).

> **Warning.** Installation wipes all data on the phone and requires an unlocked bootloader. Everything you do is at your own risk. Read the whole guide before you start.

---

## 1. What you need

| What | Why |
|---|---|
| A Titan 2 (MT6878) on stock firmware **V01.00.14 (Android 16)** | crDroid uses the stock vendor partitions. With the Android 15 vendor (V01.00.10 and older) the system will not boot, so first update to V01.00.14 the normal way. |
| Unlocked bootloader | without it the phone will not accept unsigned images |
| A PC with Linux, Windows or macOS and recent `platform-tools` (adb/fastboot) | flashing |
| A good USB cable, battery at 50% or more | a connection drop in the middle of flashing is the main source of trouble |
| Release files | `crdroid-16.0-titan2-<date>.img.xz` (system), `boot-titan2-<date>.img` (kernel), `SHA256SUMS` — from the Releases page |
| Stock files of your V01.00.14 firmware | `vbmeta.img`, `init_boot.img` — for rolling back and for step 4 |

Unpack the system image: `xz -d crdroid-16.0-titan2-<date>.img.xz`. Verify the checksums: `sha256sum -c SHA256SUMS`.

---

## 2. Never do this

- **Do not flash or erase** `preloader`, `lk`, `seccfg`, `tee`, `nvram`, `nvdata`, `protect*`, `md*`. Without them the phone becomes a brick, and the IMEI and calibrations are lost for good.
- **Do not lock the bootloader** (`fastboot flashing lock`) while the phone runs anything but signed stock firmware. Otherwise the phone will not boot.
- **Do not run `fastboot -w` in fastbootd mode** (the mode where `fastboot getvar is-userspace` answers `yes`). In this mode `-w` erases the `metadata` partition but does not format it. The first boot goes fine, but from the **second** boot on you get an endless bootloop: the data encryption key is lost. The correct order is in step 5.
- Do not interrupt flashing or unplug the cable until fastboot prints `Finished`.

---

## 3. Unlocking the bootloader (once; wipes data)

1. "Settings → About phone" — tap "Build number" 7 times to enable developer options.
2. "Settings → System → Developer options" — enable "OEM unlocking" and "USB debugging".
3. `adb reboot bootloader`
4. `fastboot flashing unlock`, then confirm on the phone with a volume key. Data will be wiped.
5. From now on an "Orange state" warning appears at every boot. This is normal.

---

## 4. Backup (strongly recommended)

Save the stock images so you can go back. At minimum, what we are going to overwrite: `boot`, `vbmeta`, `init_boot`. The easiest way is to take them from the official V01.00.14 firmware package of the same version as on the phone. The version number is in "About phone".

Find the active slot: `fastboot getvar current-slot` (usually `a`). Slot `a` is used everywhere below. If yours is `b`, replace `_a` with `_b`.

---

## 5. Flashing

> **Dual boot (titan2-boot).** If `boot_a` holds the Android/Linux boot menu, flashing a
> `boot.img` into `boot_a` replaces the menu: Android then boots directly and nothing else breaks.
> To get the menu back, rebuild it with that `boot.img`. Later the Android kernel will move to
> `boot_b` and `boot_a` will hold only the menu; these instructions will change then.

The phone must be in the **bootloader** (lk): `adb reboot bootloader`. Check the mode: `fastboot getvar is-userspace` must return `no`.

```bash
# 5.1 Kernel (Lindroid + KernelSU + BBR). init_boot stays stock.
fastboot flash boot_a boot-titan2-<date>.img

# 5.2 vbmeta from the STOCK V01.00.14 firmware, with verification disabled
fastboot --disable-verity --disable-verification flash vbmeta_a vbmeta.img

# 5.3 Switch to fastbootd (userspace fastboot) — logical partitions are visible only there
fastboot reboot fastboot
fastboot getvar is-userspace        # must be: yes

# 5.4 Free up space in super and write the system
fastboot delete-logical-partition product_a
fastboot flash system crdroid-16.0-titan2-<date>.img

# 5.5 PROPERLY format metadata (here, in fastbootd)
fastboot format:f2fs metadata

# 5.6 Go back to the bootloader and wipe data THERE
fastboot reboot bootloader
fastboot getvar is-userspace        # must be: no
fastboot -w

fastboot reboot
```

With the sources, a single script does the same with mode checks: `VBMETA=vbmeta.img scripts/flash-crdroid16.sh <system.img> [boot.img]`. The phone must be in the bootloader before you run it.

`FAILED (remote: 'Partition not found')` at the `delete-logical-partition` step can be ignored: it means `product_a` is already gone.

---

## 6. First boot and checks

- The first boot takes 3–5 minutes. The Unihertz logo and the unlocked-bootloader notice are normal.
- After the setup wizard, **reboot the phone once more** and make sure it boots again. This checks that metadata and encryption are fine.
- Optionally, in "Settings → System → Performance" you can enable verbose boot: kernel logs are shown instead of the animation. Handy for diagnostics.

If a bootloop starts on the second boot, step 5.5–5.6 was almost certainly skipped or done in the wrong order. Repeat steps 5.3–5.6. Data will be wiped, but the system stays.

---

## 7. What to set up after installation

All device settings are in one place: **Settings → Titan 2**.

| Section | What |
|---|---|
| **Keyboard** | Sym/Fn/Alt/Shift and other keys, letter shortcuts, presets, wake on Alt, setup wizard |
| **Side keys** | short/long/double press: flashlight, camera, notification shade, sound, an app |
| **Keyboard touchpad** | scroll and cursor helper (per-app modes), double-tap to wake |
| **Keyboard backlight** | duration, brightness, auto by the light sensor, backlight on slide-out |
| **Network** | 5 GHz hotspot, TCP BBR/cubic, per-app firewall |
| **Battery** | charge limit, battery health |
| **System** | CPU profile, I/O scheduler, log and verbose boot |
| **Security** | disable USB data transfer while the phone is locked |

Also:
- **KernelSU Manager**: install the APK from the KernelSU GitHub; the kernel already includes KernelSU.
- **Sandbox** (the "Sandbox" app): an app sandbox — the chosen apps do not see the list of installed
  apps, VPNs, contacts, files or location.
- The default keyboard is Plektra (IME for the hardware keyboard).

---

## 8. Lindroid (Linux on the phone)

1. Open the **Lindroid** app and create a container. The app downloads a rootfs of about 1.5–2 GB; Wi-Fi is needed.
2. Everything needed on the Titan 2 is installed automatically when the container is created:
   - Mali graphics through libhybris;
   - a virtual display;
   - a keyboard with the Plektra layout (Sym, Alt, Fn);
   - USB passthrough.
3. The first KDE Plasma start takes up to a minute. The login and password are set when the container is created.
4. **Steam** (x86-64 through FEX-Emu) is not installed by itself: the KDE menu has an "Install Steam" entry
   (or run `sudo titan2-steam-install` in a terminal). It needs several GB and several hours: FEX-Emu
   is built from source. The installation runs in the background and resumes after a reboot; when
   it is done, Steam appears in the menu.
5. USB devices for Linux, such as an RTL-SDR V4 or a Flipper Zero: connect them through an OTG adapter, then run `sudo sh /system_ext/usr/share/titan2/titan2-radio-setup` inside the container.
6. If the container was created on an older firmware version, some fixes can be delivered manually from the PC. The scripts for that are in `scripts/` in the sources, for example `install-plektra-linux.sh`. The easiest way is to recreate the container.

---

## 9. Updating to a new build (keeping data)

If the `vendor` partition has not changed and only system and/or the kernel change:

```bash
adb reboot bootloader
fastboot flash boot_a boot-titan2-<new date>.img     # if the kernel changed
fastboot reboot fastboot
fastboot flash system crdroid-16.0-titan2-<new date>.img
fastboot reboot
```

**No** `-w` and **no** `format metadata`: your data is kept.

---

## 10. Going back to stock

- **Kernel only:** `fastboot flash boot_a <stock boot.img>`.
- **Fully:** flash the official V01.00.14 package with the standard Unihertz tool (SP Flash Tool, *Download* mode, **not** *Format All*). Or flash the `super`, `vbmeta*`, `boot`, `init_boot`, `vendor_boot`, `dtbo` images from the package through fastboot, then run `fastboot -w` in the bootloader.
- You may lock the bootloader only after a full return to signed stock.

---

## 11. Known limitations

- Double-tap on the touchpad to wake is not supported by the hardware on the revision with the Synaptics controller. On stock this option is hidden on that revision too.
- "Quick input" (picking words with a swipe up) works on stock only with the Kika keyboard and has not been ported.
- The panel is 60 Hz; a 90 Hz mode is not possible.
- The display engine (MiraVision) is disabled: on the GSI it caused a white screen.

---

## 12. If something went wrong

| Symptom | What to do |
|---|---|
| Bootloop from the second boot | repeat steps 5.3–5.6 (metadata in fastbootd, then `-w` in the bootloader) |
| Bootloop from the first boot | check that the V01.00.14 (Android 16) vendor partitions are installed; reflash vbmeta with the `--disable-*` flags |
| `fastboot` does not see the phone | another cable or port; on Windows — the Android Bootloader Interface driver |
| Cannot get into fastboot | with the phone off, hold volume down and power |
| White screen after changing the display mode | `adb shell settings put secure display_engine_mode 0` |
| Lindroid: black screen | update the firmware and recreate the container; the log inside the container: `journalctl -b` |
