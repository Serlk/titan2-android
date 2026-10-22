# Modem: what is still closed and can be opened

The kernel CCCI stack (8 modules, see [README.md](README.md)) is open. This list is what we can
still open or replace with our own code. Things that cannot be opened at all are listed at the end.

## Kernel

- [ ] **Fix the MediaTek bugs found during the refactor** (`src/notes/bugs-found.md`) and publish
      the stack version with the fixes (the published one is `2dcb1fc`, without them).
- [ ] **Drop the debug-only closed dependencies**: `aee_aed`, `mrdump`, `irq-dbg`,
      `ufs-mediatek-dbg`. The stack only uses them for crash reports and debug dumps.
- [x] **Build the platform dependencies from source** (GPL MediaTek tree `kernel_device_modules-6.1`):
      `emi`, `emi-mpu`, `smpu`, `device-apc-common`, `mtk-dvfsrc`, `mtk_slbc`, `mtk_pbm`, `mtk_mdpm`,
      `spmi-mtk-pmif`, `scp`, `mtk_tinysys_ipi`, `rps_perf` — all 12 build and are function- and
      data-identical to stock, exports and CRCs match (`titan2-mtk-deps`).
- [ ] **Test them on the phone**: first the `vendor_dlkm` ones (`scp`, `rps_perf`, `mtk_tinysys_ipi`,
      `mtk_pbm`, `mtk_mdpm`), then the first-stage ones from `vendor_boot` (needs a repacked
      `vendor_boot`; `spmi-mtk-pmif` is the PMIC bus — keep the stock image to roll back).
- [x] **Full dependency closure** (`titan2-mtk-deps/notes/closure.md`): 43 closed stock modules
      beyond the CCCI stack. 36 have GPL sources and build identical to stock (incl. `aee_aed`,
      `mrdump`, `irq-dbg`, `mtk-scpsys`, `mtk-mbox`, `mtk_rpmsg_mbox`, `clk-common`; `aee_aed` needs
      the SID check from the Motorola MT6878 tree), 2 are kernel DRM helpers, 5 have no source.
- The `mediatek-drm` hooks stay: `ccci_md_all` uses `mtk_disp_mipi_ccci_callback` and
  `mtk_disp_osc_ccci_callback` to move the display MIPI/OSC frequencies away from the active RF
  band (desense protection). This edge pulls in 30 of the 43 modules, so the display side must
  be opened too:
- [ ] **`mediatek-drm`**: builds from source, 12 functions still differ from stock — match them.
- [ ] **Clean-room rewrite of the Agold modules without source** (`titan2-agold`): one side writes
      behavioral specs from the binaries (`spec/`), a separate side writes new GPL drivers only from
      the specs. Modules: `panel-FLS_AC213G992_FLIP` (rear panel), `panel-A61QWERTY46` (main panel),
      `agold_board_info`, `agold_hw_compatible`, `agold_pm_info`, `fauge` (SH366100 fuel gauge).
      Unihertz/Agold have not published the Titan 2 kernel sources.
- [ ] **Mainline port via the WWAN subsystem** (modeled on `t7xx`): passive CCIF, modem power-on
      following the stock sequence, AT and MIPC ports (`WWAN_PORT_AT`, `WWAN_PORT_MIPC` already exist
      upstream). Work in progress, not tested on the phone yet.

## Userspace

- [ ] **NVRAM FS server** (`ccci-fsd`): an open replacement for the FS server inside the closed
      `mdinit`. The modem reads and writes its NVRAM only through it. Needed for mainline; must never
      write to the NVRAM partitions until verified against traffic captured on stock.
- [ ] **Modem bring-up without `mdinit`**: the rest of `mdinit` (start/stop, the rpcd operations;
      see the userspace ABI notes).
- [ ] **Open RIL path**: on Linux, AT commands via ModemManager; MIPC (`/dev/ttyCMIPC*`,
      the protocol `libmipc.so` speaks) to be documented and implemented.
- [ ] **VoLTE control**: the IMS stack runs inside the modem and is driven with AT/MIPC commands
      (`EIMS`, `EIMSPDN`, `ECFGSET`), so enabling IMS and VoLTE can be done in open code. Today the
      GSI has no MediaTek IMS service and calls fall back to CSFB.
- [ ] **Call voice path**: speech goes through `/dev/ccci_aud` and the closed audio HAL. The hardest
      item; it needs the speech control protocol reverse-engineered.

## Cannot be opened

- Modem firmware (`md1rom`, `md1dsp`), including the in-modem IMS stack: runs on the modem CPU,
  signed, loaded by LK.
- Boot chain (preloader, LK, TEE): signed. LK starts the modem on every boot and sets up the
  memory protection for it.
- Closed stock userspace (`libmtk-ril`, `mdinit`, the MediaTek IMS service, the audio HAL): no
  sources; they can only be replaced (items above), not opened.
- NVRAM contents (calibration, IMEI): per-device data, not code; never published.
