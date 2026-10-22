package org.titan2.rear;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.SystemProperties;
import android.view.Display;
import android.view.DisplayAddress;

/**
 * Finding the rear display (the id is not fixed: it changes when the display is removed and
 * added again). The physical port ro.titan2.rear_display_port identifies it, as in the system
 * part; without the property: a built-in (not virtual) non-default display with a 410x502 mode.
 * A virtual display of the same size (screen recording, another app) must never be taken for it.
 */
public final class RearDisplays {
    public static final int WIDTH = 410;
    public static final int HEIGHT = 502;
    /** uniqueId of the built-in panel, in case the mode is reported rotated */
    private static final String UNIQUE_ID = "local:4627039422300187651";

    private RearDisplays() {}

    public static Display find(Context c) {
        DisplayManager dm = c.getSystemService(DisplayManager.class);
        int port = SystemProperties.getInt("ro.titan2.rear_display_port", -1);
        Display bySize = null;
        for (Display d : dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_ALL_INCLUDING_DISABLED)) {
            if (d.getDisplayId() == Display.DEFAULT_DISPLAY
                    || d.getType() != Display.TYPE_INTERNAL) {
                continue;
            }
            DisplayAddress a = d.getAddress();
            if (port >= 0 && a instanceof DisplayAddress.Physical
                    && ((DisplayAddress.Physical) a).getPort() == port) {
                return d;
            }
            Display.Mode m = d.getMode();
            int w = m.getPhysicalWidth(), h = m.getPhysicalHeight();
            if (bySize == null && ((w == WIDTH && h == HEIGHT) || (w == HEIGHT && h == WIDTH)
                    || UNIQUE_ID.equals(d.getUniqueId()))) {
                bySize = d;
            }
        }
        return bySize;
    }

    /** The display is enabled (DisplayManager.getDisplays() leaves disabled displays out). */
    public static boolean isEnabled(Context c, int displayId) {
        for (Display d : c.getSystemService(DisplayManager.class).getDisplays()) {
            if (d.getDisplayId() == displayId) return true;
        }
        return false;
    }

    public static int findId(Context c) {
        Display d = find(c);
        return d != null ? d.getDisplayId() : Display.INVALID_DISPLAY;
    }
}
