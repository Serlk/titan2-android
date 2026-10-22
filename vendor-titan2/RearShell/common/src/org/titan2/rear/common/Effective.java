package org.titan2.rear.common;

/**
 * Effective values: the user setting over the theme (the value "theme" means as in the theme).
 * The same for the shell and the preview in Titan2Parts.
 */
public final class Effective {
    /** Settings source (provider, SharedPreferences): the value or the default. */
    public interface Values {
        String get(String key);
    }

    private Effective() {}

    private static String v(Values s, String key) {
        String x = s.get(key);
        return x == null ? RearKeys.def(key) : x;
    }

    private static boolean tri(Values s, String key, boolean theme) {
        Boolean b = RearKeys.tri(v(s, key));
        return b != null ? b : theme;
    }

    public static String face(ThemeSpec t, Values s) {
        String f = v(s, RearKeys.CLOCK_FACE);
        return RearKeys.FROM_THEME.equals(f) || f.isEmpty() ? t.face : f;
    }

    public static boolean seconds(ThemeSpec t, Values s) {
        return tri(s, RearKeys.CLOCK_SECONDS, t.seconds);
    }

    /** null: as in the system */
    public static Boolean h24(ThemeSpec t, Values s) {
        String x = v(s, RearKeys.CLOCK_24H);
        if (RearKeys.SYSTEM.equals(x)) return null;
        Boolean b = RearKeys.tri(x);
        return b != null ? b : t.h24;
    }

    public static boolean date(ThemeSpec t, Values s) {
        return tri(s, RearKeys.CLOCK_DATE, t.date);
    }

    public static String dateFormat(ThemeSpec t, Values s) {
        String f = v(s, RearKeys.CLOCK_DATE_FORMAT);
        return RearKeys.FROM_THEME.equals(f) || f.trim().isEmpty() ? t.dateFormat : f;
    }

    public static boolean battery(ThemeSpec t, Values s) {
        return tri(s, RearKeys.CLOCK_BATTERY, t.battery);
    }

    public static boolean notifications(ThemeSpec t, Values s) {
        return tri(s, RearKeys.CLOCK_NOTIFICATIONS, t.notifications);
    }

    public static int columns(ThemeSpec t, Values s) {
        return ThemeSpec.clamp(RearKeys.parseInt(v(s, RearKeys.GRID_COLUMNS), t.columns), 1, 6);
    }

    public static int rows(ThemeSpec t, Values s) {
        return ThemeSpec.clamp(RearKeys.parseInt(v(s, RearKeys.GRID_ROWS), t.rows), 1, 6);
    }

    public static boolean labels(ThemeSpec t, Values s) {
        return tri(s, RearKeys.LABELS, t.labels);
    }

    public static String iconShape(ThemeSpec t, Values s) {
        String x = v(s, RearKeys.ICON_SHAPE);
        return RearKeys.FROM_THEME.equals(x) ? t.iconShape : x;
    }

    public static String iconStyle(ThemeSpec t, Values s) {
        String x = v(s, RearKeys.ICON_STYLE);
        return RearKeys.FROM_THEME.equals(x) ? t.iconStyle : x;
    }

    public static float iconSize(ThemeSpec t, Values s) {
        return ThemeSpec.clampF(RearKeys.parseFloat(v(s, RearKeys.ICON_SIZE), t.iconSize),
                0.6f, 1.4f);
    }

    /** Animation duration multiplier (0: no animations); the system scale is separate. */
    public static float animScale(ThemeSpec t, Values s) {
        if (!RearKeys.parseBool(v(s, RearKeys.ANIM_ENABLED), true)) return 0f;
        if ("none".equals(animStyle(t, s))) return 0f;
        float speed = RearKeys.parseFloat(v(s, RearKeys.ANIM_SPEED), t.animSpeed);
        if (speed <= 0) return 0f;
        return 1f / ThemeSpec.clampF(speed, 0.25f, 4f);
    }

    public static String animStyle(ThemeSpec t, Values s) {
        String x = v(s, RearKeys.ANIM_STYLE);
        return RearKeys.FROM_THEME.equals(x) ? t.animStyle : x;
    }
}
