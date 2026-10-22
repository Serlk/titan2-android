# The Titan 2 layer over the GSI (mounted into the tree as vendor/titan2, included from device/phh/treble/base.mk).
# Plektra: the IME for the hardware keyboard (GPL-3.0, https://github.com/pkb-rocks/plektra).
PRODUCT_PACKAGES += Plektra
# Stock MTK camera (all 4 cameras, zoom/MTK modes), platform signature. Proprietary: only if
# extracted from your own stock firmware (scripts/prepare-vendor-titan2.sh), otherwise the crDroid camera.
ifneq ($(wildcard vendor/titan2/MtkCamera/Android.bp),)
PRODUCT_PACKAGES += MtkCamera
endif
# FM radio: the open MTK FMRadio from LineageOS (packages/apps/FMRadio); the antenna is the headphones
PRODUCT_PACKAGES += FMRadio
# Keyboard and side keys: codes valid for A16 (the firmware used its own FUNC*/AGUI_SYM labels).
# In system_ext: InputDevice searches /product, /system_ext, /odm, /vendor and only then /system,
# so a copy in /system would lose to a vendor file of the same name.
PRODUCT_COPY_FILES += \
    vendor/titan2/keylayout/TitanKey.kl:$(TARGET_COPY_OUT_SYSTEM_EXT)/usr/keylayout/TitanKey.kl \
    vendor/titan2/keylayout/mtk-pmic-keys.kl:$(TARGET_COPY_OUT_SYSTEM_EXT)/usr/keylayout/mtk-pmic-keys.kl \
    vendor/titan2/keylayout/gpio_key-func.kl:$(TARGET_COPY_OUT_SYSTEM_EXT)/usr/keylayout/gpio_key-func.kl \
    vendor/titan2/keylayout/touchPad.idc:$(TARGET_COPY_OUT_SYSTEM_EXT)/usr/idc/touchPad.idc \
    vendor/titan2/keylayout/sub_touch.idc:$(TARGET_COPY_OUT_SYSTEM_EXT)/usr/idc/sub_touch.idc
# Google Phone (updated through Play): only in the GApps build and if extracted from stock
ifneq ($(wildcard vendor/titan2/GoogleDialer/Android.bp),)
# TARGET_GAPPS_ARCH is set only by the bg* targets (lineage_arm64_bgN4.mk, before base.mk)
ifneq ($(TARGET_GAPPS_ARCH),)
PRODUCT_PACKAGES += GoogleDialer
endif
endif
# Titan2Parts: extra keys (handler in system_server), key remapping, battery, 5 GHz
PRODUCT_PACKAGES += Titan2Parts titan2-bootconsole
# Rear screen shell (SECONDARY_HOME on the port 3 display, launched by RearController) and its home overlay
PRODUCT_PACKAGES += RearShell Titan2RearHomeOverlay
SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += vendor/titan2/sepolicy
PRODUCT_COPY_FILES += \
    vendor/titan2/init/titan2-parts.rc:$(TARGET_COPY_OUT_SYSTEM)/etc/init/titan2-parts.rc \
    vendor/titan2/init/titan2-perf.sh:$(TARGET_COPY_OUT_SYSTEM)/etc/titan2/titan2-perf.sh \
    vendor/titan2/init/titan2-bootlog.sh:$(TARGET_COPY_OUT_SYSTEM)/etc/titan2/titan2-bootlog.sh
# Scripts for the Lindroid Linux container (visible in it as /system_ext/usr/share/titan2/):
#   sudo sh /system_ext/usr/share/titan2/titan2-radio-setup   (RTL-SDR V4, Flipper, rtl_433, ...)
PRODUCT_COPY_FILES += \
    vendor/titan2/lindroid/titan2-radio-setup:$(TARGET_COPY_OUT_SYSTEM_EXT)/usr/share/titan2/titan2-radio-setup \
    vendor/titan2/lindroid/titan2-usb-serial:$(TARGET_COPY_OUT_SYSTEM_EXT)/usr/share/titan2/titan2-usb-serial \
    vendor/titan2/lindroid/hybris-fix/libtls-padding.so:$(TARGET_COPY_OUT_SYSTEM_EXT)/usr/share/titan2/libtls-padding.so
# plektra-linux: the Titan 2 keyboard in Linux "like in Plektra" (Sym/Alt/Fn, GPL-3.0). Installed into the rootfs
# by the lxc template (vendor_lindroid/0006) via install.sh; into an existing container with
# scripts/install-plektra-linux.sh or: sudo sh /system_ext/usr/share/titan2/plektra-linux/install.sh /system_ext/usr/share/titan2/plektra-linux /
PLEKTRA_LINUX_FILES := plektra-linux.py plektra-linux-im.py plektra_render.py install.sh \
    plektra_dbus.py plektra_android.py plektra_touchpad.py kwin-focus.js dict/en.tsv dict/ru.tsv \
    plektra_config.py org.plektra.settings.desktop settings/metadata.json \
    $(addprefix settings/contents/ui/,main.qml SettingsPage.qml schema.js MapEditor.qml \
        NavEditor.qml SymPagesPage.qml ProfilesPage.qml MacrosPage.qml) \
    plektra-linux.service org.plektra.linux.im.desktop config.example.json \
    README.md COPYING mappings/device_sym_key_mappings.json mappings/sym_key_mappings.json \
    mappings/sym_key_mappings_page2.json mappings/ctrl_key_mappings.json \
    xkb/symbols/titan2 xkb/rules/evdev
PRODUCT_COPY_FILES += $(foreach f,$(PLEKTRA_LINUX_FILES),\
    vendor/titan2/lindroid/plektra-linux/$(f):$(TARGET_COPY_OUT_SYSTEM_EXT)/usr/share/titan2/plektra-linux/$(f))
# Container GPU: libvulkan.so.1 from libhybris (Vulkan Mali 1.3) + zink-run (OpenGL on top of it);
# Steam via FEX-Emu, optional: the "Install Steam" menu entry in the container. Both install.sh are called by
# the lxc template (vendor_lindroid/0010); manually: sudo sh /system_ext/usr/share/titan2/<dir>/install.sh <dir> /
PRODUCT_COPY_FILES += \
    vendor/titan2/lindroid/gpu/install.sh:$(TARGET_COPY_OUT_SYSTEM_EXT)/usr/share/titan2/lindroid-gpu/install.sh \
    vendor/titan2/lindroid/gpu/libvulkan-hybris.so:$(TARGET_COPY_OUT_SYSTEM_EXT)/usr/share/titan2/lindroid-gpu/libvulkan-hybris.so \
    vendor/titan2/lindroid/gpu/zink-run:$(TARGET_COPY_OUT_SYSTEM_EXT)/usr/share/titan2/lindroid-gpu/zink-run \
    vendor/titan2/lindroid/steam/install.sh:$(TARGET_COPY_OUT_SYSTEM_EXT)/usr/share/titan2/lindroid-steam/install.sh \
    vendor/titan2/lindroid/steam/titan2-steam-setup.sh:$(TARGET_COPY_OUT_SYSTEM_EXT)/usr/share/titan2/lindroid-steam/titan2-steam-setup.sh
# Lindroid (Linux in LXC with GPU): vendor/lindroid from android-manifests/lindroid-a16.xml
$(call inherit-product-if-exists, vendor/lindroid/lindroid.mk)
# crDroid icon styles (signal, Wi-Fi, system icon packs)
$(call inherit-product, vendor/titan2/themes.mk)
# crDroid "Reality display engine": the MTK HWC renders its color matrix as a white screen, hide it
PRODUCT_SYSTEM_PROPERTIES += ro.sys.display_engine.disabled=true
# The square 1440x1440 screen at low density (~900dp) turns the system and apps into a
# "tablet": shade/QS with margins, the launcher menu as a floating sheet, Settings in two panes.
# The framework caps smallestScreenWidthDp to this value (frameworks_base 0004), the launcher,
# SystemUI and Settings follow it: phone layouts across the full width. 0 disables it.
PRODUCT_SYSTEM_PROPERTIES += persist.sys.titan2.max_sw_dp=599
# Rear screen (port 3): its own display group and focus (frameworks_base), its own sleep timeout
PRODUCT_SYSTEM_PROPERTIES += ro.titan2.rear_display_port=3
