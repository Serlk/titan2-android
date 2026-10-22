package org.titan2.parts;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.Display;

import androidx.fragment.app.Fragment;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

import org.titan2.rear.common.RearContract;
import org.titan2.rear.common.RearKeys;
import org.titan2.rear.common.ThemeJson;

/**
 * Titan 2 -> "Rear screen": everything about the rear screen and its RearShell shell: enable, sleep
 * and wake, brightness, lock, clock, gestures, quick panel, apps, themes. The shell has no
 * settings of its own: it reads them from here (RearConfigProvider). One host screen,
 * sections are fragments per EXTRA_PAGE. Also opened from the rear screen (long press on the clock)
 * via the org.titan2.parts.REAR_SETTINGS action.
 */
public class RearScreenActivity extends CollapsingToolbarBaseActivity {
    static final String EXTRA_PAGE = "page";
    static final String EXTRA_THEME = "theme_id";
    static final String P_MAIN = "main";
    static final String P_CLOCK = "clock";
    static final String P_GESTURES = "gestures";
    static final String P_HOME = "home";
    static final String P_APPS = "apps";
    static final String P_FAVORITES = "favorites";
    static final String P_TILES = "tiles";
    static final String P_THEMES = "themes";
    static final String P_EDITOR = "editor";

    static Intent page(Context c, String page) {
        return new Intent(c, RearScreenActivity.class).putExtra(EXTRA_PAGE, page);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String page = getIntent().getStringExtra(EXTRA_PAGE);
        if (page == null) page = P_MAIN;
        int title;
        Fragment f;
        switch (page) {
            case P_CLOCK:
                title = R.string.rear_clock_title;
                f = new ClockFragment();
                break;
            case P_GESTURES:
                title = R.string.rear_gestures_title;
                f = new GesturesFragment();
                break;
            case P_HOME:
                title = R.string.rear_home_title;
                f = new HomeFragment();
                break;
            case P_APPS:
                title = R.string.rear_apps_title;
                f = new RearAppsFragment();
                break;
            case P_FAVORITES:
                title = R.string.rear_favorites_title;
                f = RearOrderFragment.create(RearOrderFragment.MODE_FAVORITES);
                break;
            case P_TILES:
                title = R.string.rear_tiles_title;
                f = RearOrderFragment.create(RearOrderFragment.MODE_TILES);
                break;
            case P_THEMES:
                title = R.string.rear_themes_title;
                f = new RearThemesFragment();
                break;
            case P_EDITOR:
                title = R.string.rear_editor_title;
                f = new RearThemeEditorFragment();
                Bundle a = new Bundle();
                a.putString(EXTRA_THEME, getIntent().getStringExtra(EXTRA_THEME));
                f.setArguments(a);
                break;
            default:
                title = R.string.rear_title;
                f = new RearFragment();
                break;
        }
        setTitle(title);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame, f)
                    .commit();
        }
    }

    /** Main page: screen, sleep/wake, lock, shell sections. */
    public static class RearFragment extends RearPrefs {
        private static final ComponentName LISTENER = new ComponentName(
                RearContract.SHELL_PACKAGE, RearContract.SHELL_PACKAGE
                        + ".RearNotificationListener");
        private SeekBarPreference mBrightness;
        private SwitchPreferenceCompat mNotif;
        private SwitchPreferenceCompat mEnabled;
        private ListPreference mTimeout;
        /** Settings.System switches of this page -> default, refreshed in onResume. */
        private final java.util.Map<SwitchPreferenceCompat, Integer> mSysSwitches =
                new java.util.LinkedHashMap<>();
        /** The shell or the system may change them while the page is open (it is opened
         * from the rear screen itself). */
        private final ContentObserver mSysObserver =
                new ContentObserver(new Handler(Looper.getMainLooper())) {
                    @Override
                    public void onChange(boolean selfChange) {
                        refreshSys();
                    }
                };

        @Override
        protected void build(PreferenceScreen screen) {
            Context c = getContext();
            addPreview(screen);

            // --- screen (Settings.System: read by the system part) ---
            SwitchPreferenceCompat enabled = new SwitchPreferenceCompat(c);
            enabled.setKey(RearKeys.SYS_ENABLED);
            enabled.setPersistent(false);
            enabled.setTitle(R.string.rear_enabled_title);
            enabled.setSummary(R.string.rear_enabled_summary);
            enabled.setChecked(RearDisplay.isEnabled(c));
            enabled.setOnPreferenceChangeListener((p, v) -> {
                // RearController (Titan2App) turns it on and off via a ContentObserver
                RearDisplay.setEnabled(c, (Boolean) v, "RearScreenActivity switch");
                return true;
            });
            screen.addPreference(enabled);
            mEnabled = enabled;

            PreferenceCategory scr = cat(screen, R.string.rear_cat_screen);
            ListPreference timeout = new ListPreference(c);
            timeout.setKey(RearKeys.SYS_TIMEOUT);
            timeout.setPersistent(false);
            timeout.setTitle(R.string.rear_timeout_title);
            timeout.setDialogTitle(R.string.rear_timeout_title);
            timeout.setEntries(R.array.rear_timeout_entries);
            timeout.setEntryValues(new CharSequence[] {"15000", "30000", "60000", "120000",
                    "300000", String.valueOf(RearKeys.TIMEOUT_NEVER)});
            timeout.setValue(String.valueOf(RearConfig.sysInt(c, RearKeys.SYS_TIMEOUT,
                    RearKeys.TIMEOUT_DEFAULT)));
            timeout.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
            timeout.setOnPreferenceChangeListener((p, v) -> {
                RearConfig.putSysInt(c, RearKeys.SYS_TIMEOUT, Integer.parseInt((String) v));
                return true;
            });
            scr.addPreference(timeout);
            mTimeout = timeout;
            // the dependency is resolved at call time, so only after addPreference
            timeout.setDependency(RearKeys.SYS_ENABLED);

            mBrightness = new SeekBarPreference(c);
            mBrightness.setKey("rear_brightness");
            mBrightness.setPersistent(false);
            mBrightness.setTitle(R.string.rear_brightness_title);
            mBrightness.setMax(100);
            mBrightness.setUpdatesContinuously(true);
            mBrightness.setOnPreferenceChangeListener((p, v) -> {
                int id = RearDisplay.findId(c);
                if (id == Display.INVALID_DISPLAY) return false;
                c.getSystemService(DisplayManager.class).setBrightness(id,
                        Math.max(0.01f, (Integer) v / 100f));
                return true;
            });
            scr.addPreference(mBrightness);

            sysSwitch(scr, RearKeys.SYS_WAKE_DOUBLE_TAP, R.string.rear_wake_tap_title,
                    R.string.rear_wake_tap_summary).setDependency(RearKeys.SYS_ENABLED);
            sysSwitch(scr, RearKeys.SYS_WAKE_DOUBLE_TAP_POSE, R.string.rear_wake_pose_title,
                    R.string.rear_wake_pose_summary).setDependency(RearKeys.SYS_WAKE_DOUBLE_TAP);
            sysSwitch(scr, RearKeys.SYS_WAKE_FLIP, R.string.rear_wake_flip_title,
                    R.string.rear_wake_flip_summary).setDependency(RearKeys.SYS_ENABLED);
            sysSwitch(scr, RearKeys.SYS_SYSTEM_DECORS, R.string.rear_system_decors_title,
                    R.string.rear_system_decors_summary, 0).setDependency(RearKeys.SYS_ENABLED);

            // --- lock ---
            PreferenceCategory lock = cat(screen, R.string.rear_cat_lock);
            list(lock, RearKeys.LOCK_MODE, R.string.rear_lock_mode_title,
                    R.array.rear_lock_mode_entries, new CharSequence[] {RearKeys.LOCK_OFF,
                            RearKeys.LOCK_CLOCK, RearKeys.LOCK_CLOCK_COUNT,
                            RearKeys.LOCK_CLOCK_TITLES});
            sw(lock, RearKeys.UNLOCK_FROM_REAR, R.string.rear_unlock_title,
                    R.string.rear_unlock_summary);
            sw(lock, RearKeys.LOCK_TORCH, R.string.rear_lock_torch_title,
                    R.string.rear_lock_torch_summary);
            mNotif = new SwitchPreferenceCompat(c);
            mNotif.setKey("rear_notification_access");
            mNotif.setPersistent(false);
            mNotif.setTitle(R.string.rear_notif_access_title);
            mNotif.setSummary(R.string.rear_notif_access_summary);
            mNotif.setOnPreferenceChangeListener((p, v) -> {
                try {
                    c.getSystemService(NotificationManager.class)
                            .setNotificationListenerAccessGranted(LISTENER, (Boolean) v);
                    return true;
                } catch (RuntimeException e) {
                    Intent i = new Intent(
                            android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS);
                    i.putExtra(android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                            LISTENER.flattenToString());
                    startActivity(i);
                    return false;
                }
            });
            lock.addPreference(mNotif);

            // --- shell ---
            PreferenceCategory shell = cat(screen, R.string.rear_cat_shell);
            link(shell, R.string.rear_themes_title, R.string.rear_themes_summary,
                    page(c, P_THEMES));
            link(shell, R.string.rear_clock_title, R.string.rear_clock_summary,
                    page(c, P_CLOCK));
            link(shell, R.string.rear_home_title, R.string.rear_home_summary,
                    page(c, P_HOME));
            link(shell, R.string.rear_gestures_title, R.string.rear_gestures_summary,
                    page(c, P_GESTURES));
            link(shell, R.string.rear_tiles_title, R.string.rear_tiles_summary,
                    page(c, P_TILES));
            link(shell, R.string.rear_apps_title, R.string.rear_apps_summary,
                    page(c, P_APPS));
            link(shell, R.string.rear_favorites_title, R.string.rear_favorites_summary,
                    page(c, P_FAVORITES));

            PreferenceCategory keys = cat(screen, R.string.rear_cat_keys);
            link(keys, R.string.rear_modes_title, R.string.rear_modes_summary,
                    KeySettingsActivity.sideKeysIntent(c));
        }

        @Override
        public void onResume() {
            super.onResume();
            Context c = getContext();
            int id = RearDisplay.findId(c);
            float b = Float.NaN;
            if (id != Display.INVALID_DISPLAY) {
                try {
                    b = c.getSystemService(DisplayManager.class).getBrightness(id);
                } catch (RuntimeException ignored) {
                }
            }
            mBrightness.setEnabled(!Float.isNaN(b));
            mBrightness.setSummary(Float.isNaN(b) ? getString(R.string.rear_brightness_off) : null);
            if (!Float.isNaN(b)) mBrightness.setValue(Math.round(b * 100));
            boolean granted = false;
            try {
                granted = c.getSystemService(NotificationManager.class)
                        .isNotificationListenerAccessGranted(LISTENER);
            } catch (RuntimeException ignored) {
            }
            mNotif.setChecked(granted);
            refreshSys();
            for (String key : new String[] {RearKeys.SYS_ENABLED, RearKeys.SYS_TIMEOUT,
                    RearKeys.SYS_WAKE_DOUBLE_TAP, RearKeys.SYS_WAKE_DOUBLE_TAP_POSE,
                    RearKeys.SYS_WAKE_FLIP, RearKeys.SYS_SYSTEM_DECORS}) {
                c.getContentResolver().registerContentObserver(Settings.System.getUriFor(key),
                        false, mSysObserver, UserHandle.USER_SYSTEM);
            }
        }

        @Override
        public void onPause() {
            getContext().getContentResolver().unregisterContentObserver(mSysObserver);
            super.onPause();
        }

        /** Settings.System values -> the switches (changed elsewhere while paused or open). */
        private void refreshSys() {
            Context c = getContext();
            if (c == null || mEnabled == null) return;
            mEnabled.setChecked(RearDisplay.isEnabled(c));
            String t = String.valueOf(RearConfig.sysInt(c, RearKeys.SYS_TIMEOUT,
                    RearKeys.TIMEOUT_DEFAULT));
            if (!t.equals(mTimeout.getValue())) mTimeout.setValue(t);
            for (java.util.Map.Entry<SwitchPreferenceCompat, Integer> e
                    : mSysSwitches.entrySet()) {
                e.getKey().setChecked(
                        RearConfig.sysInt(c, e.getKey().getKey(), e.getValue()) != 0);
            }
        }

        private SwitchPreferenceCompat sysSwitch(PreferenceCategory g, String key, int title,
                int summary) {
            return sysSwitch(g, key, title, summary, 1);
        }

        private SwitchPreferenceCompat sysSwitch(PreferenceCategory g, String key, int title,
                int summary, int def) {
            Context c = getContext();
            SwitchPreferenceCompat p = new SwitchPreferenceCompat(c);
            p.setKey(key);
            p.setPersistent(false);
            p.setTitle(title);
            p.setSummary(summary);
            g.addPreference(p);
            p.setChecked(RearConfig.sysInt(c, key, def) != 0);
            mSysSwitches.put(p, def);
            p.setOnPreferenceChangeListener((pref, v) -> {
                RearConfig.putSysInt(c, key, (Boolean) v ? 1 : 0);
                return true;
            });
            return p;
        }
    }

    /** Clock: the face and what to show (over the theme), taps. */
    public static class ClockFragment extends RearPrefs {
        @Override
        protected void build(PreferenceScreen screen) {
            addPreview(screen);
            PreferenceCategory face = cat(screen, R.string.rear_cat_face);
            list(face, RearKeys.CLOCK_FACE, R.string.rear_face_title,
                    withTheme(getResources().getTextArray(R.array.rear_face_entries)),
                    withThemeValue(ThemeJson.FACE_IDS));
            CharSequence[] tri = getResources().getTextArray(R.array.rear_tri_entries);
            list(face, RearKeys.CLOCK_SECONDS, R.string.rear_seconds_title, tri, TRI);
            list(face, RearKeys.CLOCK_24H, R.string.rear_24h_title, R.array.rear_24h_entries,
                    new CharSequence[] {RearKeys.FROM_THEME, RearKeys.SYSTEM, RearKeys.ON,
                            RearKeys.OFF});
            PreferenceCategory info = cat(screen, R.string.rear_cat_clock_info);
            list(info, RearKeys.CLOCK_DATE, R.string.rear_date_title, tri, TRI);
            ListPreference fmt = list(info, RearKeys.CLOCK_DATE_FORMAT,
                    R.string.rear_date_format_title, R.array.rear_date_entries, DATE_FORMATS);
            // a custom pattern (not from the list): show it as is
            fmt.setSummaryProvider(p -> {
                ListPreference l = (ListPreference) p;
                return l.getEntry() != null ? l.getEntry() : l.getValue();
            });
            androidx.preference.EditTextPreference custom =
                    new androidx.preference.EditTextPreference(getContext());
            // own key in the hierarchy, so it is not confused with the ListPreference; written manually
            custom.setKey("rear_date_custom");
            custom.setPersistent(false);
            String cur = RearConfig.get(getContext(), RearKeys.CLOCK_DATE_FORMAT);
            custom.setText(RearKeys.FROM_THEME.equals(cur) ? "" : cur);
            custom.setTitle(R.string.rear_date_custom_title);
            custom.setDialogMessage(R.string.rear_date_custom_summary);
            custom.setSummary(R.string.rear_date_custom_summary);
            custom.setOnPreferenceChangeListener((p, v) -> {
                String s = ((String) v).trim();
                RearConfig.put(getContext(), RearKeys.CLOCK_DATE_FORMAT,
                        s.isEmpty() ? RearKeys.FROM_THEME : s);
                // recompute the format list
                ListPreference l = findPreference(RearKeys.CLOCK_DATE_FORMAT);
                if (l != null) l.setValue(RearConfig.get(getContext(),
                        RearKeys.CLOCK_DATE_FORMAT));
                return false;
            });
            info.addPreference(custom);
            list(info, RearKeys.CLOCK_BATTERY, R.string.rear_battery_title, tri, TRI);
            list(info, RearKeys.CLOCK_NOTIFICATIONS, R.string.rear_notif_count_title, tri, TRI);

            PreferenceCategory touch = cat(screen, R.string.rear_cat_clock_touch);
            CharSequence[] acts = getResources().getTextArray(R.array.rear_home_action_entries);
            list(touch, RearKeys.CLOCK_TAP, R.string.rear_clock_tap_title, acts,
                    RearKeys.HOME_ACTIONS);
            list(touch, RearKeys.CLOCK_DOUBLE_TAP, R.string.rear_clock_double_tap_title, acts,
                    RearKeys.HOME_ACTIONS);
            list(touch, RearKeys.CLOCK_LONG_PRESS, R.string.rear_clock_long_press_title, acts,
                    RearKeys.HOME_ACTIONS);
        }

        static final CharSequence[] DATE_FORMATS = {RearKeys.FROM_THEME, "EEE d MMM",
                "EEEE d MMMM", "d MMMM", "dd MM", "dd MM yyyy", "EEEE", "EEE d"};
    }

    /** Gestures: navigation over apps and home screen gestures. */
    public static class GesturesFragment extends RearPrefs {
        @Override
        protected void build(PreferenceScreen screen) {
            PreferenceCategory nav = cat(screen, R.string.rear_cat_nav);
            sw(nav, RearKeys.GESTURES, R.string.rear_gestures_enable_title,
                    R.string.rear_gestures_enable_summary);
            list(nav, RearKeys.GESTURE_SENS, R.string.rear_sens_title, R.array.rear_sens_entries,
                    new CharSequence[] {"low", "normal", "high"})
                    .setDependency(RearKeys.GESTURES);
            CharSequence[] nacts = getResources().getTextArray(R.array.rear_nav_action_entries);
            String[] keys = {RearKeys.GESTURE_BOTTOM, RearKeys.GESTURE_BOTTOM_HOLD,
                    RearKeys.GESTURE_LEFT, RearKeys.GESTURE_RIGHT};
            int[] titles = {R.string.rear_g_bottom, R.string.rear_g_hold, R.string.rear_g_left,
                    R.string.rear_g_right};
            for (int i = 0; i < keys.length; i++) {
                list(nav, keys[i], titles[i], nacts, RearKeys.NAV_ACTIONS)
                        .setDependency(RearKeys.GESTURES);
            }
            sw(nav, RearKeys.GESTURE_HAPTIC, R.string.rear_haptic_title, 0)
                    .setDependency(RearKeys.GESTURES);

            PreferenceCategory home = cat(screen, R.string.rear_cat_home_gestures);
            CharSequence[] hacts = getResources().getTextArray(R.array.rear_home_action_entries);
            list(home, RearKeys.HOME_SWIPE_UP, R.string.rear_swipe_up_title, hacts,
                    RearKeys.HOME_ACTIONS);
            list(home, RearKeys.HOME_SWIPE_DOWN, R.string.rear_swipe_down_title, hacts,
                    RearKeys.HOME_ACTIONS);
            sw(home, RearKeys.HOME_SWIPE_DOWN_ANYWHERE, R.string.rear_swipe_anywhere_title,
                    R.string.rear_swipe_anywhere_summary);
            screen.addPreference(KeyPrefs.footer(getContext(),
                    getString(R.string.rear_gestures_note)));
        }
    }

    /** Home screen, app list, animations. */
    public static class HomeFragment extends RearPrefs {
        @Override
        protected void build(PreferenceScreen screen) {
            addPreview(screen);
            PreferenceCategory home = cat(screen, R.string.rear_cat_home);
            sw(home, RearKeys.HOME_FAVORITES, R.string.rear_home_favorites_title,
                    R.string.rear_home_favorites_summary);
            CharSequence[] nums = {"1", "2", "3", "4", "5", "6"};
            list(home, RearKeys.GRID_COLUMNS, R.string.rear_columns_title, withTheme(nums),
                    withThemeValue(nums));
            list(home, RearKeys.GRID_ROWS, R.string.rear_rows_title, withTheme(nums),
                    withThemeValue(nums));
            list(home, RearKeys.LABELS, R.string.rear_labels_title,
                    getResources().getTextArray(R.array.rear_tri_entries), TRI);
            list(home, RearKeys.ICON_SHAPE, R.string.rear_icon_shape_title,
                    withTheme(getResources().getTextArray(R.array.rear_icon_shape_entries)),
                    withThemeValue(org.titan2.rear.common.ThemeJson.ICON_SHAPES));
            list(home, RearKeys.ICON_STYLE, R.string.rear_icon_style_title,
                    withTheme(getResources().getTextArray(R.array.rear_icon_style_entries)),
                    withThemeValue(org.titan2.rear.common.ThemeJson.ICON_STYLES));
            CharSequence[] sizes = {"0.6", "0.7", "0.8", "0.9", "1.0", "1.1", "1.2", "1.3", "1.4"};
            CharSequence[] sizeNames = new CharSequence[sizes.length];
            for (int i = 0; i < sizes.length; i++) {
                sizeNames[i] = Math.round(Float.parseFloat(sizes[i].toString()) * 100) + "%";
            }
            list(home, RearKeys.ICON_SIZE, R.string.rear_icon_size_title, withTheme(sizeNames),
                    withThemeValue(sizes));
            list(home, RearKeys.DRAWER_SORT, R.string.rear_sort_title, R.array.rear_sort_entries,
                    new CharSequence[] {RearKeys.SORT_ALPHA, RearKeys.SORT_ALPHA_DESC,
                            RearKeys.SORT_NEW, RearKeys.SORT_OLD});

            PreferenceCategory anim = cat(screen, R.string.rear_cat_anim);
            sw(anim, RearKeys.ANIM_ENABLED, R.string.rear_anim_title, R.string.rear_anim_summary);
            CharSequence[] speeds = {"0.5", "0.75", "1.0", "1.25", "1.5", "2.0"};
            CharSequence[] speedNames = new CharSequence[speeds.length];
            for (int i = 0; i < speeds.length; i++) speedNames[i] = "×" + speeds[i];
            list(anim, RearKeys.ANIM_SPEED, R.string.rear_anim_speed_title, withTheme(speedNames),
                    withThemeValue(speeds)).setDependency(RearKeys.ANIM_ENABLED);
            list(anim, RearKeys.ANIM_STYLE, R.string.rear_anim_style_title,
                    withTheme(getResources().getTextArray(R.array.rear_anim_style_entries)),
                    withThemeValue(org.titan2.rear.common.ThemeJson.ANIM_STYLES))
                    .setDependency(RearKeys.ANIM_ENABLED);
        }
    }
}
