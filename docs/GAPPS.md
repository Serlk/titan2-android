Русская версия: [GAPPS.ru.md](GAPPS.ru.md)

# Google services (GApps)

The prebuilt image from the releases is built **without GApps**: we have no right to distribute Google apps.
There are two ways.

## Option 1: build the image with GApps (recommended)

The MindTheGapps build has been tested on the Titan 2 and is the author's daily driver.

1. Prepare the build following [BUILD.md](BUILD.md), steps 1–4. `scripts/android-sync-crdroid16.sh`
   downloads MindTheGapps by itself — a third-party project
   ([gitlab.com/MindTheGapps/vendor_gapps](https://gitlab.com/MindTheGapps/vendor_gapps), branch
   `baklava`, pulled in by the TrebleDroid manifest) — into `vendor/gapps`. This repository contains
   no Google apps: only two small patches to the MindTheGapps build files
   (`patches/titan2-a16/vendor_gapps`). By downloading and building GApps, you are responsible
   for complying with Google's terms.
2. Build the `bgN4` target:

   ```bash
   scripts/android-build-crdroid16.sh bgN4
   ```

3. Flash the resulting `system.img`. If a build without GApps was installed before, data must be
   **wiped** (full installation, section 5 of [INSTALL.md](INSTALL.md)): adding Google services
   to an already set-up system without a reset usually ends with Play Services crashing.

Google Phone from the stock firmware is included in such a build if step 4 of BUILD.md was done.

### Play Protect certification

An image from your own build is not certified. If the Play Store says "Device is not
certified", register the Android ID at
[google.com/android/uncertified](https://www.google.com/android/uncertified) and wait
a few hours.

## Option 2: without Google services

The image without GApps is fully functional. Apps can be installed from F-Droid or as APKs. Apps that
require Google Play Services may not work without them — then use option 1.

Third-party ways to "add" GApps to the prebuilt GSI (KernelSU/Magisk modules, repacking
`system.img`) have not been tested on the Titan 2, and we do not support them.
