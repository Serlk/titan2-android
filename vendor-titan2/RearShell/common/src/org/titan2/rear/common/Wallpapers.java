package org.titan2.rear.common;

import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;

/** Theme background without an image: a color or a linear gradient (the angle is rounded to 45 deg). */
public final class Wallpapers {
    private Wallpapers() {}

    public static Drawable background(ThemeSpec t) {
        if ("gradient".equals(t.wallpaperType) && t.gradient.length >= 2) {
            GradientDrawable g = new GradientDrawable(orientation(t.gradientAngle), t.gradient);
            g.setGradientType(GradientDrawable.LINEAR_GRADIENT);
            return g;
        }
        return new ColorDrawable("color".equals(t.wallpaperType) ? t.wallpaperColor
                : t.background);
    }

    /** Angle as in CSS: 0 is bottom to top, 90 is left to right, 180 is top to bottom. */
    static GradientDrawable.Orientation orientation(int angle) {
        int a = ((Math.round(((angle % 360) + 360) % 360 / 45f)) * 45) % 360;
        switch (a) {
            case 0:
                return GradientDrawable.Orientation.BOTTOM_TOP;
            case 45:
                return GradientDrawable.Orientation.BL_TR;
            case 90:
                return GradientDrawable.Orientation.LEFT_RIGHT;
            case 135:
                return GradientDrawable.Orientation.TL_BR;
            case 180:
                return GradientDrawable.Orientation.TOP_BOTTOM;
            case 225:
                return GradientDrawable.Orientation.TR_BL;
            case 270:
                return GradientDrawable.Orientation.RIGHT_LEFT;
            default:
                return GradientDrawable.Orientation.BR_TL;
        }
    }
}
