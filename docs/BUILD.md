Русская версия: [BUILD.ru.md](BUILD.ru.md)

# Building from source

Everything is built in Docker: the host only needs `git`, `docker`, `curl` and `bash`.

| What | How much |
|---|---|
| Disk space | ~250 GB for the Android tree and out, plus 20–80 GB for ccache |
| Memory | 32 GB at `-j6` (with less memory, lower `-j` in `scripts/android-build-crdroid16.sh`) |
| First build | several hours; later builds 30–60 minutes with ccache |

## 1. Docker images

```bash
docker build -t titan2-aosp docker/aosp     # Android
docker build -t titan2-kbuild docker         # kernel and small Lindroid binaries
```

## 2. Android sources

```bash
scripts/android-sync-crdroid16.sh
```

The script puts crDroid 16.0 into `android/crdroid-16`, adds the TrebleDroid patches (MisterZtr,
`lineage-23.2`) and the Lindroid manifest, then applies our patches (`patches/titan2-a16`,
`patches/lindroid-a16`). Running it again updates the tree; patches that are already applied are skipped.

Some TrebleDroid patches were written for plain LineageOS and do not all apply to the crDroid
forks — those print `FAILED APPLYING`. At the time of publishing, the known failures do not affect the build.

## 3. Signing keys (optional)

Without keys the image is signed with the AOSP test keys. This works for personal use,
but anyone can then sign an APK with the "system" key. To use your own keys, follow the
[LineageOS guide](https://wiki.lineageos.org/signing_builds) (`make_key`) and put them into
`secrets/android-certs/` (`releasekey`, `platform`, `shared`, `media`, `networkstack`, ...).
The `secrets/` directory is in `.gitignore`.

## 4. Stock MTK camera and Google Phone (optional)

These apps are proprietary, so they are not in the repository or the releases. You can take them from **your own**
stock V01.00.10 firmware:

1. Unpack the `super.img` of the firmware package (`simg2img`, then `lpunpack`) and extract the files of the
   `system_a`, `system_ext_a`, `product_a` logical partitions into directories with the same names
   (`7z x`, `debugfs -R rdump` or `erofsfuse`, depending on the file system).
2. `scripts/prepare-vendor-titan2.sh <directory with system_a/ system_ext_a/ product_a/>`

The script patches the camera (`scripts/patch-mtkcamera.sh`: Agui stubs from `patches/mtkcamera`) and
puts it into `vendor-titan2/MtkCamera`. The camera has only been tested with V01.00.10: with another version
the script stops with an error about the number of calls found. Google Phone is included only in the build
with GApps (`bgN4`).

Without this step `prepare-vendor-titan2.sh` downloads only Plektra, and the image is built without them.

## 5. Building the image

```bash
scripts/android-build-crdroid16.sh          # bvN4: no GApps
scripts/android-build-crdroid16.sh bgN4     # with MindTheGapps (see GAPPS.md)
```

The build is long — running it in `tmux` is convenient. Result:
`android/home/build-output/crdroid-16.0-trebledroid-<target>-<date>.img` (this is the `system.img`).

## 6. Kernel

```bash
scripts/kernel-prepare.sh       # NothingOSS 6.1 @ BASE_COMMIT + patches/kernel, clang r487747c
scripts/build-kernel.sh lindroid-ksu-bbr configs/lindroid-ksu-bbr-titan2.config
```

Result: `out/boot-lindroid-ksu-bbr.img`. This is a kernel-only boot image: the ramdisk stays in the
stock `init_boot`. Stock modules from `vendor_dlkm` load unchanged; when editing the
config, do not enable options that break the KMI (`CGROUP_DEVICE`, `CGROUP_PIDS`, `FANOTIFY`,
`NET_L3_MASTER_DEV`) — the list is at the top of `configs/lindroid-ksu-bbr-titan2.config`.

## 7. Binaries for the Lindroid container

`vendor-titan2/lindroid/gpu/libvulkan-hybris.so` and `vendor-titan2/lindroid/hybris-fix/libtls-padding.so`
are stored in the repository prebuilt and can be rebuilt from source:

```bash
scripts/build-lindroid-hybris-vulkan.sh    # from external/libhybris of the Android tree (Apache-2.0)
scripts/build-lindroid-hybris-fix.sh       # from tls-padding.c (needs titan2-kbuild and the clang from step 6)
```

## 8. Flashing

See [INSTALL.md](INSTALL.md). With your own build, a script does the same:

```bash
VBMETA=<stock vbmeta.img> scripts/flash-crdroid16.sh <system.img> [boot.img]   # wipes data
```

Updating without losing data — section 9 of the installation guide.

## Changing the patches

The patches are plain `git format-patch` output. To change one: edit the project in the tree
`android/crdroid-16/<path>`, commit, and export again:

```bash
git -C android/crdroid-16/frameworks/base format-patch -o patches/titan2-a16/frameworks_base <base>..HEAD
```
