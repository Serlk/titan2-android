package org.titan2.parts;

import android.content.Context;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;

/** aw9523_key: wake the screen with the Alt key (driver node + a firmware-compatible key in Settings.System). */
final class AltWake {
    private static final String TAG = "Titan2AltWake";
    static final String NODE =
            "/sys/devices/platform/soc/11e01000.i2c/i2c-6/6-0058/wake_up_enable";
    static final String SETTING = "alt_key_wakeup_screen";   // "1"/"0"

    private AltWake() {}

    static boolean isAvailable() {
        return new File(NODE).exists();
    }

    static boolean isEnabled() {
        return "1".equals(readNode(NODE));
    }

    /** false: the node was not written (the toggle must not move). */
    static boolean setEnabled(Context context, boolean enabled) {
        String v = enabled ? "1" : "0";
        if (!writeNode(NODE, v)) {
            return false;
        }
        Settings.System.putStringForUser(context.getContentResolver(), SETTING, v,
                UserHandle.USER_SYSTEM);
        return true;
    }

    /** At boot "0" disables the driver wake, "1" keeps the driver default. */
    static void restore(Context context) {
        if ("0".equals(Settings.System.getStringForUser(context.getContentResolver(), SETTING,
                UserHandle.USER_SYSTEM))) {
            writeNode(NODE, "0");
        }
    }

    private static String readNode(String path) {
        try {
            return new String(Files.readAllBytes(new File(path).toPath())).trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static boolean writeNode(String path, String value) {
        try (FileWriter w = new FileWriter(path)) {
            w.write(value);
            return true;
        } catch (IOException e) {
            Log.w(TAG, "write " + path, e);
            return false;
        }
    }
}
