Русская версия: [README.ru.md](README.ru.md)

# Free Titan 2 modem stack (MediaTek CCCI)

The in-kernel driver for the integrated MT6878 modem (Dimensity 7300, gen 6299 modem): everything
Android uses to talk to the modem — starting and restarting the modem, the `/dev/ccci_*`,
`ttyC*`, `ttyCMIPC*` ports (AT commands, MIPC for the RIL, call audio, IMS), the `ccmni*`
network interfaces for mobile data, and data offload to the modem during tethering.

On stock these are 8 modules in the `vendor_dlkm` partition, built by Unihertz from MediaTek sources
with their changes. Here is the same stack in open form, cleaned up for a single platform.

## What was done

**Base:** the CCCI code from MediaTek's open `kernel_device_modules-6.1` tree (the
NothingOSS branch for MT6878, GPL-2.0), configured for the Titan 2 (`configs/ccci-mt6878.config`).
Every change is our own edit of that public code or code taken from other public MediaTek trees.

**Cleanup (12 steps; `src/` is the state after them):**
- only MT6878 and the gen 6299 modem are kept: code for other platforms and generations is removed;
- DPMAIF (the fast data channel) — only v3, no v1/v2;
- CLDMA (the old interface, unused on MT6878) and its module are removed;
- conditional compilation is resolved for the Titan 2 configuration; macros that are never
  defined and functions that nobody calls are removed;
- our own `Kbuild` instead of building inside the MediaTek tree.

Result: ~13,500 fewer lines (111 files touched), and the stack is readable.

**How it was verified.** Every step was built and compared with the stock modules function by function:
the mechanical steps give 0 differences in machine code. The finished modules were tested on the phone:
calls, SMS and mobile data work.

**What is not there yet:**
- this is only the kernel side. The modem firmware itself and the RIL service in Android (`libmtk-ril`, `mdinit`)
  are closed and will stay that way;
- no VoLTE: the GSI has no MediaTek IMS service (the IMS stack lives inside the modem; the Android
  side needs a closed service). This is unrelated to the driver;
- MediaTek sloppiness found during the analysis (`src/notes/bugs-found.md`) is fixed
  in later versions and is not in this one.

**Next:** fixing the bugs found, decoupling from the remaining closed MediaTek modules and,
as the goal, moving to mainline (the WWAN subsystem). The full list of what can still be opened is in
[TODO.md](TODO.md).

## Installation (optional, Linux only)

The modules replace the stock ones in the `vendor_dlkm` partition. We do not publish a ready partition image:
besides our 8 modules it contains ~460 closed ones. The image is built from your stock one:

1. Stock `vendor_dlkm` V01.00.14 — from the `super.img` of the official firmware or dumped
   from the phone (root): `adb shell su -c 'dd if=/dev/block/by-name/vendor_dlkm_a' > vendor_dlkm_a.img`.
   **Keep it** — that is your rollback.
2. Modules: `titan2-modem-ccci-<version>.zip` from the release (unpack into a directory).
3. Build the image (needs `erofs-utils` with SELinux support, `lpunpack`, `simg2img`):

   ```bash
   scripts/make-modem-vendor-dlkm.sh vendor_dlkm_a.img titan2-modem-ccci/ vendor_dlkm-modem.img
   ```

4. Flash it in fastbootd (where `fastboot getvar is-userspace` → `yes`):

   ```bash
   adb reboot fastboot
   fastboot flash vendor_dlkm_a vendor_dlkm-modem.img
   fastboot reboot
   ```

Check after booting: a call, an SMS, mobile data. To roll back, flash the stock
`vendor_dlkm_a.img` the same way.

## Sources

`src/` holds the module sources (`src/VERSION`: the tested `2dcb1fc` with comments translated to English and the transmit length
check in `ccmni` restored to the public MediaTek code). They are built out of the kernel tree against the headers of our kernel
(`patches/kernel`) and the `Module.symvers` of the stock modules: the stack calls exports of other
closed MediaTek modules, so the build needs their CRCs from your firmware.

License — GPL-2.0, like the MediaTek sources (MediaTek copyrights are kept).
