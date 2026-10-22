package org.titan2.rear;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.util.Log;

/**
 * Rear display brightness for the quick panel. Brightness is owned by the system part (Titan2Parts),
 * so this is a hook point: the default implementation is DisplayManager.setBrightness(id, f)
 * (CONTROL_DISPLAY_BRIGHTNESS, available with the platform signature).
 * TODO(system part): if the rear display does not get its own DisplayPowerController
 * (a separate display group), replace with our own mechanism (a Settings key / a Titan2Parts service).
 */
public interface BrightnessControl {
    /** 0..1, or NaN if unavailable */
    float get();

    void set(float value);

    static BrightnessControl forDisplay(Context c, int displayId) {
        DisplayManager dm = c.getSystemService(DisplayManager.class);
        return new BrightnessControl() {
            @Override
            public float get() {
                try {
                    return dm.getBrightness(displayId);
                } catch (RuntimeException e) {
                    Log.w("RearShell", "getBrightness: " + e);
                    return Float.NaN;
                }
            }

            @Override
            public void set(float value) {
                try {
                    dm.setBrightness(displayId, Math.max(0f, Math.min(1f, value)));
                } catch (RuntimeException e) {
                    Log.w("RearShell", "setBrightness: " + e);
                }
            }
        };
    }
}
