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
import androidx.preference.Preference;
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
    static final String P_WIDGETS = "widgets";
    static final String P_PAGES = "pages";
    static final String P_FACET = "facet";

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
            case P_WIDGETS:
                title = R.string.rear_widgets_title;
                f = new WidgetsFragment();
                break;
            case P_FACET:
                title = R.string.rear_facet_title;
                f = new FacetFragment();
                break;
            case P_PAGES:
                title = R.string.rear_pages_title;
                f = RearOrderFragment.create(RearOrderFragment.MODE_PAGES);
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
        private ListPreference mLockTimeout;
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
            sysSwitch(scr, RearKeys.SYS_TOUCH_GUARD, R.string.rear_touch_guard_title,
                    R.string.rear_touch_guard_summary).setDependency(RearKeys.SYS_ENABLED);

            // --- lock ---
            PreferenceCategory lock = cat(screen, R.string.rear_cat_lock);
            list(lock, RearKeys.LOCK_MODE, R.string.rear_lock_mode_title,
                    R.array.rear_lock_mode_entries, new CharSequence[] {RearKeys.LOCK_OFF,
                            RearKeys.LOCK_CLOCK, RearKeys.LOCK_CLOCK_COUNT,
                            RearKeys.LOCK_CLOCK_TITLES});
            sw(lock, RearKeys.UNLOCK_FROM_REAR, R.string.rear_unlock_title,
                    R.string.rear_unlock_summary);
            // read by SystemUI (Titan2RearUnlockService) and KeyboardLight: Settings.System
            sysSwitch(lock, RearKeys.SYS_UNLOCK_WAKE_MAIN, R.string.rear_unlock_wake_main_title,
                    R.string.rear_unlock_wake_main_summary, 0)
                    .setDependency(RearKeys.UNLOCK_FROM_REAR);
            sysSwitch(lock, RearKeys.SYS_UNLOCK_KBD_LIGHT, R.string.rear_unlock_kbd_light_title,
                    R.string.rear_unlock_kbd_light_summary, 0)
                    .setDependency(RearKeys.SYS_UNLOCK_WAKE_MAIN);
            ListPreference lockTimeout = new ListPreference(c);
            lockTimeout.setKey(RearKeys.SYS_LOCK_TIMEOUT);
            lockTimeout.setPersistent(false);
            lockTimeout.setTitle(R.string.rear_lock_timeout_title);
            lockTimeout.setDialogTitle(R.string.rear_lock_timeout_title);
            lockTimeout.setEntries(R.array.rear_lock_timeout_entries);
            lockTimeout.setEntryValues(new CharSequence[] {"0", "30000", "60000", "120000",
                    "300000", "600000", String.valueOf(RearKeys.LOCK_TIMEOUT_SYSTEM)});
            lockTimeout.setValue(String.valueOf(RearConfig.sysInt(c, RearKeys.SYS_LOCK_TIMEOUT,
                    RearKeys.LOCK_TIMEOUT_DEFAULT)));
            lockTimeout.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
            lockTimeout.setOnPreferenceChangeListener((p, v) -> {
                RearConfig.putSysInt(c, RearKeys.SYS_LOCK_TIMEOUT, Integer.parseInt((String) v));
                return true;
            });
            lock.addPreference(lockTimeout);
            mLockTimeout = lockTimeout;
            lockTimeout.setDependency(RearKeys.SYS_ENABLED);
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
            link(shell, R.string.rear_widgets_title, R.string.rear_widgets_summary,
                    page(c, P_WIDGETS));
            link(shell, R.string.rear_facet_title, R.string.rear_facet_summary,
                    page(c, P_FACET));
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
                    RearKeys.SYS_WAKE_FLIP, RearKeys.SYS_SYSTEM_DECORS,
                    RearKeys.SYS_UNLOCK_WAKE_MAIN, RearKeys.SYS_UNLOCK_KBD_LIGHT,
                    RearKeys.SYS_TOUCH_GUARD,
                    RearKeys.SYS_LOCK_TIMEOUT}) {
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
            String lt = String.valueOf(RearConfig.sysInt(c, RearKeys.SYS_LOCK_TIMEOUT,
                    RearKeys.LOCK_TIMEOUT_DEFAULT));
            if (!lt.equals(mLockTimeout.getValue())) mLockTimeout.setValue(lt);
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

    /** Notification light effects on the rear screen (RearShell facet.FacetController). */
    public static class FacetFragment extends RearPrefs {
        private static final ComponentName LISTENER = new ComponentName(
                RearContract.SHELL_PACKAGE, RearContract.SHELL_PACKAGE
                        + ".RearNotificationListener");
        private static final String ACTION_PREVIEW = "org.titan2.rear.FACET_PREVIEW";

        @Override
        protected void build(PreferenceScreen screen) {
            Context c = getContext();
            SwitchPreferenceCompat on = sw(screen, RearKeys.FACET_ENABLED,
                    R.string.rear_facet_enable_title, R.string.rear_facet_enable_summary);
            on.setOnPreferenceChangeListener((p, v) -> {
                // the effects come from the shell's notification listener: grant it access
                if ((Boolean) v) {
                    try {
                        c.getSystemService(NotificationManager.class)
                                .setNotificationListenerAccessGranted(LISTENER, true);
                    } catch (RuntimeException e) {
                        android.util.Log.w("Titan2Parts", "notification access: " + e);
                    }
                }
                return true;
            });

            PreferenceCategory look = cat(screen, R.string.rear_cat_facet_look);
            list(look, RearKeys.FACET_EFFECT, R.string.rear_facet_effect,
                    R.array.rear_facet_effect_entries, new CharSequence[] {"pulse", "sides",
                            "message", "crt", "random"}).setDependency(RearKeys.FACET_ENABLED);
            list(look, RearKeys.FACET_COLOR, R.string.rear_facet_color,
                    R.array.rear_facet_color_entries, new CharSequence[] {"app", "accent",
                            "white"}).setDependency(RearKeys.FACET_ENABLED);
            CharSequence[] reps = {"1", "2", "3"};
            list(look, RearKeys.FACET_REPEAT, R.string.rear_facet_repeat, reps, reps)
                    .setDependency(RearKeys.FACET_ENABLED);
            Preference preview = new Preference(c);
            preview.setTitle(R.string.rear_facet_preview);
            preview.setSummary(R.string.rear_facet_preview_summary);
            preview.setOnPreferenceClickListener(p -> {
                c.sendBroadcast(new Intent(ACTION_PREVIEW).setPackage(RearContract.SHELL_PACKAGE));
                return true;
            });
            look.addPreference(preview);
            preview.setDependency(RearKeys.FACET_ENABLED);

            PreferenceCategory when = cat(screen, R.string.rear_cat_facet_when);
            list(when, RearKeys.FACET_POSE, R.string.rear_facet_pose,
                    R.array.rear_facet_pose_entries, new CharSequence[] {RearKeys.POSE_FACE_UP,
                            RearKeys.POSE_ALWAYS}).setDependency(RearKeys.FACET_ENABLED);
            sw(when, RearKeys.FACET_MAIN_ON, R.string.rear_facet_main_on,
                    R.string.rear_facet_main_on_summary).setDependency(RearKeys.FACET_ENABLED);
            sw(when, RearKeys.FACET_DND, R.string.rear_facet_dnd, 0)
                    .setDependency(RearKeys.FACET_ENABLED);
            screen.addPreference(KeyPrefs.footer(c, getString(R.string.rear_facet_note)));
        }
    }

    /**
     * Widget pages: their order, pocket protection on the lock screen, and per widget whether
     * it shows while locked and which blocks it has.
     */
    public static class WidgetsFragment extends RearPrefs {
        @Override
        protected void build(PreferenceScreen screen) {
            Context c = getContext();
            PreferenceCategory pages = cat(screen, R.string.rear_cat_pages);
            link(pages, R.string.rear_pages_title, R.string.rear_pages_summary,
                    page(c, P_PAGES));

            PreferenceCategory guard = cat(screen, R.string.rear_cat_guard);
            sw(guard, RearKeys.LOCK_HOLD, R.string.rear_lock_hold_title,
                    R.string.rear_lock_hold_summary);
            sw(guard, RearKeys.LOCK_PALM, R.string.rear_lock_palm_title,
                    R.string.rear_lock_palm_summary);

            PreferenceCategory music = cat(screen, R.string.rear_w_music);
            onLock(music, RearKeys.W_MUSIC);
            sw(music, RearKeys.MUSIC_ART, R.string.rear_music_art, 0);
            sw(music, RearKeys.MUSIC_ARTIST, R.string.rear_music_artist, 0);
            sw(music, RearKeys.MUSIC_PROGRESS, R.string.rear_music_progress, 0);
            sw(music, RearKeys.MUSIC_APP, R.string.rear_music_app, 0);

            PreferenceCategory weather = cat(screen, R.string.rear_w_weather);
            onLock(weather, RearKeys.W_WEATHER);
            sw(weather, RearKeys.WEATHER_CITY, R.string.rear_weather_city, 0);
            sw(weather, RearKeys.WEATHER_DETAILS, R.string.rear_weather_details,
                    R.string.rear_weather_details_summary);
            sw(weather, RearKeys.WEATHER_HOURLY, R.string.rear_weather_hourly, 0);
            sw(weather, RearKeys.WEATHER_FORECAST, R.string.rear_weather_forecast, 0);

            PreferenceCategory steps = cat(screen, R.string.rear_w_steps);
            onLock(steps, RearKeys.W_STEPS);
            CharSequence[] goals = {"3000", "5000", "6000", "8000", "10000", "12000", "15000",
                    "20000"};
            list(steps, RearKeys.STEPS_GOAL, R.string.rear_steps_goal, goals, goals);
            sw(steps, RearKeys.STEPS_DISTANCE, R.string.rear_steps_distance, 0);
            CharSequence[] strides = {"55", "60", "65", "70", "75", "80", "85", "90"};
            CharSequence[] strideLabels = new CharSequence[strides.length];
            for (int i = 0; i < strides.length; i++) {
                strideLabels[i] = getString(R.string.rear_cm, strides[i]);
            }
            list(steps, RearKeys.STEPS_STRIDE, R.string.rear_steps_stride, strideLabels, strides)
                    .setDependency(RearKeys.STEPS_DISTANCE);

            PreferenceCategory compass = cat(screen, R.string.rear_w_compass);
            onLock(compass, RearKeys.W_COMPASS);
            sw(compass, RearKeys.COMPASS_DEGREES, R.string.rear_compass_degrees, 0);
            sw(compass, RearKeys.COMPASS_TICKS, R.string.rear_compass_ticks, 0);

            PreferenceCategory level = cat(screen, R.string.rear_w_level);
            onLock(level, RearKeys.W_LEVEL);
            sw(level, RearKeys.LEVEL_DEGREES, R.string.rear_level_degrees, 0);

            PreferenceCategory timer = cat(screen, R.string.rear_w_timer);
            onLock(timer, RearKeys.W_TIMER);
            list(timer, RearKeys.TIMER_PRESETS, R.string.rear_timer_presets,
                    R.array.rear_timer_preset_entries, new CharSequence[] {
                            "60,180,300,600,900", "30,60,120,300", "300,600,900,1800",
                            "600,1200,1800,3600"});
            sw(timer, RearKeys.TIMER_SOUND, R.string.rear_timer_sound, 0);
            sw(timer, RearKeys.TIMER_VIBRATE, R.string.rear_timer_vibrate, 0);

            PreferenceCategory stopwatch = cat(screen, R.string.rear_w_stopwatch);
            onLock(stopwatch, RearKeys.W_STOPWATCH);
            sw(stopwatch, RearKeys.STOPWATCH_LAPS, R.string.rear_stopwatch_laps, 0);
        }

        /**
         * "Show on the rear screen" (the widget is in RearKeys.PAGES; a new one goes to the end)
         * and "show when locked", which depends on it.
         */
        private void onLock(PreferenceCategory g, String widget) {
            Context c = getContext();
            SwitchPreferenceCompat shown = new SwitchPreferenceCompat(c);
            shown.setKey("page_" + widget);
            shown.setPersistent(false);
            shown.setTitle(R.string.rear_widget_shown);
            shown.setChecked(RearConfig.list(c, RearKeys.PAGES).contains(widget));
            shown.setOnPreferenceChangeListener((p, v) -> {
                java.util.List<String> pages = new java.util.ArrayList<>(
                        RearConfig.list(c, RearKeys.PAGES));
                pages.remove(widget);
                if ((Boolean) v) pages.add(widget);
                RearConfig.putList(c, RearKeys.PAGES, pages);
                return true;
            });
            g.addPreference(shown);
            sw(g, RearKeys.lockKey(widget), R.string.rear_widget_on_lock,
                    R.string.rear_widget_on_lock_summary).setDependency(shown.getKey());
        }

        @Override
        public void onResume() {
            super.onResume();
            // the order page may have changed the list
            java.util.List<String> pages = RearConfig.list(getContext(), RearKeys.PAGES);
            for (String w : RearKeys.ALL_PAGES) {
                SwitchPreferenceCompat p = findPreference("page_" + w);
                if (p != null) p.setChecked(pages.contains(w));
            }
        }
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
