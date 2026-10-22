package org.titan2.rear.common;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Rear screen setting keys and defaults (the same for Titan2Parts and
 * RearShell). All provider values are strings; booleans are "true"/"false", lists are comma-separated.
 * The value FROM_THEME ("theme") means "as in the theme".
 */
public final class RearKeys {
    private RearKeys() {}

    // --- Settings.System (read by the system part: system_server, Titan2Parts) ---
    public static final String SYS_ENABLED = "titan2_rear_enabled";
    public static final String SYS_TIMEOUT = "titan2_rear_screen_off_timeout";
    public static final String SYS_WAKE_DOUBLE_TAP = "titan2_rear_wake_double_tap";
    public static final String SYS_WAKE_DOUBLE_TAP_POSE = "titan2_rear_wake_double_tap_pose";
    public static final String SYS_WAKE_FLIP = "titan2_rear_wake_flip";
    /** Status bar, shade, navigation bar and back gesture on the rear display (default off). */
    public static final String SYS_SYSTEM_DECORS = "titan2_rear_system_decors";
    /** obsolete Settings.System keys (moved to the provider once) */
    public static final String LEGACY_SYS_LOCK_MODE = "titan2_rear_lock_mode";
    public static final String LEGACY_SYS_ALLOWED = "titan2_rear_allowed_apps";
    public static final int TIMEOUT_DEFAULT = 30000;
    public static final int TIMEOUT_NEVER = Integer.MAX_VALUE;

    public static final String FROM_THEME = "theme";
    public static final String ON = "on";
    public static final String OFF = "off";

    // --- theme ---
    public static final String THEME = "theme";
    public static final String THEME_DEFAULT = "builtin:default_dark";

    // --- lock ---
    public static final String LOCK_MODE = "lock_mode";
    public static final String LOCK_OFF = "off";
    public static final String LOCK_CLOCK = "clock";
    public static final String LOCK_CLOCK_COUNT = "clock_count";
    public static final String LOCK_CLOCK_TITLES = "clock_titles";
    /** a swipe up on the clock opens PIN/pattern entry */
    public static final String UNLOCK_FROM_REAR = "unlock_from_rear";
    public static final String LOCK_TORCH = "lock_torch";

    // --- apps ---
    /** all: all apps; selected: only from APPS_ALLOWED */
    public static final String APPS_FILTER = "apps_filter";
    public static final String APPS_ALL = "all";
    public static final String APPS_SELECTED = "selected";
    public static final String APPS_ALLOWED = "apps_allowed";
    /** ordered list: component (+ "#userId" for the work profile) */
    public static final String FAVORITES = "favorites";
    /** favorites pages between the clock and the app list */
    public static final String HOME_FAVORITES = "home_favorites";

    // --- clock ---
    public static final String CLOCK_FACE = "clock_face";            // theme | face id
    public static final String CLOCK_SECONDS = "clock_seconds";      // theme | on | off
    public static final String CLOCK_24H = "clock_24h";              // theme | system | on | off
    public static final String CLOCK_DATE = "clock_date";            // theme | on | off
    public static final String CLOCK_DATE_FORMAT = "clock_date_format"; // theme | pattern
    public static final String CLOCK_BATTERY = "clock_battery";      // theme | on | off
    public static final String CLOCK_NOTIFICATIONS = "clock_notifications";
    public static final String CLOCK_TAP = "clock_tap";              // action
    public static final String CLOCK_DOUBLE_TAP = "clock_double_tap";
    public static final String CLOCK_LONG_PRESS = "clock_long_press";
    public static final String SYSTEM = "system";

    // --- home screen gestures ---
    public static final String HOME_SWIPE_UP = "home_swipe_up";
    public static final String HOME_SWIPE_DOWN = "home_swipe_down";
    /** swipe down anywhere on the page, not only from the top edge */
    public static final String HOME_SWIPE_DOWN_ANYWHERE = "home_swipe_down_anywhere";

    // --- navigation gestures over apps ---
    public static final String GESTURES = "gestures_enabled";
    public static final String GESTURE_SENS = "gesture_sensitivity"; // low | normal | high
    public static final String GESTURE_BOTTOM = "gesture_bottom";
    public static final String GESTURE_BOTTOM_HOLD = "gesture_bottom_hold";
    public static final String GESTURE_LEFT = "gesture_left";
    public static final String GESTURE_RIGHT = "gesture_right";
    public static final String GESTURE_HAPTIC = "gesture_haptic";

    // --- quick panel ---
    public static final String QS_TILES = "qs_tiles";
    public static final String QS_BRIGHTNESS = "qs_brightness";
    public static final String TILE_TORCH = "torch";
    public static final String TILE_WIFI = "wifi";
    public static final String TILE_DND = "dnd";
    public static final String TILE_RINGER = "ringer";
    public static final String TILE_SLEEP = "sleep";
    public static final String TILE_SETTINGS = "settings";
    public static final String[] ALL_TILES = {
            TILE_TORCH, TILE_WIFI, TILE_DND, TILE_RINGER, TILE_SLEEP, TILE_SETTINGS,
    };

    // --- grid and app list ---
    public static final String GRID_COLUMNS = "grid_columns";        // theme | 1..6
    public static final String GRID_ROWS = "grid_rows";
    public static final String LABELS = "labels";                    // theme | on | off
    public static final String ICON_SHAPE = "icon_shape";            // theme | none | circle | squircle | rounded
    public static final String ICON_STYLE = "icon_style";            // theme | color | mono
    public static final String ICON_SIZE = "icon_size";              // theme | 0.6..1.4
    public static final String DRAWER_SORT = "drawer_sort";          // alpha | alpha_desc | new | old
    public static final String SORT_ALPHA = "alpha";
    public static final String SORT_ALPHA_DESC = "alpha_desc";
    public static final String SORT_NEW = "new";
    public static final String SORT_OLD = "old";

    // --- animations ---
    public static final String ANIM_ENABLED = "anim_enabled";
    public static final String ANIM_SPEED = "anim_speed";            // theme | multiplier
    public static final String ANIM_STYLE = "anim_style";            // theme | standard | expressive | subtle

    // --- actions (gestures, clock taps) ---
    public static final String ACT_NONE = "none";
    public static final String ACT_HOME = "home";
    public static final String ACT_BACK = "back";
    public static final String ACT_RECENTS = "recents";
    public static final String ACT_DRAWER = "drawer";
    public static final String ACT_QUICK_PANEL = "quick_panel";
    public static final String ACT_NEXT_FACE = "next_face";
    public static final String ACT_TORCH = "torch";
    public static final String ACT_SLEEP = "sleep";
    public static final String ACT_SETTINGS = "settings";

    /** navigation gesture actions (over any app) */
    public static final String[] NAV_ACTIONS = {
            ACT_HOME, ACT_BACK, ACT_RECENTS, ACT_TORCH, ACT_SLEEP, ACT_NONE,
    };
    /** home screen actions */
    public static final String[] HOME_ACTIONS = {
            ACT_DRAWER, ACT_QUICK_PANEL, ACT_RECENTS, ACT_NEXT_FACE, ACT_TORCH, ACT_SLEEP,
            ACT_SETTINGS, ACT_NONE,
    };

    public static final Map<String, String> DEFAULTS;

    static {
        Map<String, String> d = new HashMap<>();
        d.put(THEME, THEME_DEFAULT);
        d.put(LOCK_MODE, LOCK_CLOCK_COUNT);
        d.put(UNLOCK_FROM_REAR, "true");
        d.put(LOCK_TORCH, "true");
        d.put(APPS_FILTER, APPS_ALL);
        d.put(APPS_ALLOWED, "");
        d.put(FAVORITES, "");
        d.put(HOME_FAVORITES, "true");
        d.put(CLOCK_FACE, FROM_THEME);
        d.put(CLOCK_SECONDS, FROM_THEME);
        d.put(CLOCK_24H, FROM_THEME);
        d.put(CLOCK_DATE, FROM_THEME);
        d.put(CLOCK_DATE_FORMAT, FROM_THEME);
        d.put(CLOCK_BATTERY, FROM_THEME);
        d.put(CLOCK_NOTIFICATIONS, FROM_THEME);
        d.put(CLOCK_TAP, ACT_NONE);
        d.put(CLOCK_DOUBLE_TAP, ACT_NONE);
        d.put(CLOCK_LONG_PRESS, ACT_SETTINGS);
        d.put(HOME_SWIPE_UP, ACT_DRAWER);
        d.put(HOME_SWIPE_DOWN, ACT_QUICK_PANEL);
        d.put(HOME_SWIPE_DOWN_ANYWHERE, "true");
        d.put(GESTURES, "true");
        d.put(GESTURE_SENS, "normal");
        d.put(GESTURE_BOTTOM, ACT_HOME);
        d.put(GESTURE_BOTTOM_HOLD, ACT_RECENTS);
        d.put(GESTURE_LEFT, ACT_BACK);
        d.put(GESTURE_RIGHT, ACT_BACK);
        d.put(GESTURE_HAPTIC, "true");
        d.put(QS_TILES, "torch,wifi,dnd,ringer,sleep,settings");
        d.put(QS_BRIGHTNESS, "true");
        d.put(GRID_COLUMNS, FROM_THEME);
        d.put(GRID_ROWS, FROM_THEME);
        d.put(LABELS, FROM_THEME);
        d.put(ICON_SHAPE, FROM_THEME);
        d.put(ICON_STYLE, FROM_THEME);
        d.put(ICON_SIZE, FROM_THEME);
        d.put(DRAWER_SORT, SORT_ALPHA);
        d.put(ANIM_ENABLED, "true");
        d.put(ANIM_SPEED, FROM_THEME);
        d.put(ANIM_STYLE, FROM_THEME);
        DEFAULTS = Collections.unmodifiableMap(d);
    }

    public static String def(String key) {
        String v = DEFAULTS.get(key);
        return v != null ? v : "";
    }

    public static boolean parseBool(String v, boolean def) {
        if (v == null || v.isEmpty()) return def;
        return "true".equals(v) || "1".equals(v) || ON.equals(v);
    }

    /** Three states theme/on/off: null means as in the theme. */
    public static Boolean tri(String v) {
        if (v == null || FROM_THEME.equals(v) || v.isEmpty()) return null;
        return ON.equals(v) || "true".equals(v) || "1".equals(v);
    }

    public static int parseInt(String v, int def) {
        try {
            return v == null ? def : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static float parseFloat(String v, float def) {
        try {
            return v == null ? def : Float.parseFloat(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
