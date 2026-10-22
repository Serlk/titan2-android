package org.titan2.parts;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;

import org.titan2.rear.common.Effective;
import org.titan2.rear.common.RearContract;
import org.titan2.rear.common.RearKeys;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Settings of the rear screen shell (RearShell). Source of truth: the Titan2Parts SharedPreferences
 * rear_config in device-protected storage (the app is defaultToDeviceProtectedStorage):
 * available before the first unlock. The shell reads them via RearConfigProvider; any
 * change (settings screens write straight to these SharedPreferences) -> notifyChange.
 * Types: toggles are stored as boolean, the rest as strings; everything is exposed as strings.
 */
final class RearConfig {
    static final String FILE = "rear_config";
    private static final String MIGRATED = "_migrated_v1";

    /** keep a reference: SharedPreferences holds listeners weakly */
    private static SharedPreferences.OnSharedPreferenceChangeListener sListener;

    private RearConfig() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Once per process: changes -> notifyChange for the shell; migration of old keys. */
    static synchronized void init(Context c) {
        if (sListener != null) return;
        final Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        migrate(app);
        sListener = (p, key) -> {
            ContentResolver cr = app.getContentResolver();
            cr.notifyChange(key == null ? RearContract.SETTINGS : RearContract.setting(key), null);
            if (key == null) cr.notifyChange(RearContract.THEMES, null);
        };
        prefs(app).registerOnSharedPreferenceChangeListener(sListener);
    }

    /** Keys from Settings.System of the previous shell version (lock_mode, allowed_apps). */
    private static void migrate(Context c) {
        SharedPreferences p = prefs(c);
        if (p.getBoolean(MIGRATED, false)) return;
        SharedPreferences.Editor e = p.edit().putBoolean(MIGRATED, true);
        ContentResolver cr = c.getContentResolver();
        String lock = Settings.System.getStringForUser(cr, RearKeys.LEGACY_SYS_LOCK_MODE,
                UserHandle.USER_SYSTEM);
        if (!TextUtils.isEmpty(lock)) e.putString(RearKeys.LOCK_MODE, lock);
        String allowed = Settings.System.getStringForUser(cr, RearKeys.LEGACY_SYS_ALLOWED,
                UserHandle.USER_SYSTEM);
        if (!TextUtils.isEmpty(allowed)) {
            e.putString(RearKeys.APPS_ALLOWED, allowed);
            e.putString(RearKeys.APPS_FILTER, RearKeys.APPS_SELECTED);
        }
        e.apply();
    }

    /** All saved keys as strings (internal ones, with "_", are not exposed). */
    static Map<String, String> all(Context c) {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, ?> en : prefs(c).getAll().entrySet()) {
            if (en.getKey().startsWith("_")) continue;
            String v = toString(en.getValue());
            if (v != null) out.put(en.getKey(), v);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static String toString(Object v) {
        if (v == null) return null;
        if (v instanceof Set) return TextUtils.join(",", (Set<String>) v);
        return String.valueOf(v);
    }

    /** The value (or the default from RearKeys). */
    static String get(Context c, String key) {
        Object v = prefs(c).getAll().get(key);
        String s = toString(v);
        return s != null ? s : RearKeys.def(key);
    }

    static boolean bool(Context c, String key) {
        return RearKeys.parseBool(get(c, key), RearKeys.parseBool(RearKeys.def(key), false));
    }

    static List<String> list(Context c, String key) {
        List<String> out = new ArrayList<>();
        String v = get(c, key);
        if (TextUtils.isEmpty(v)) return out;
        for (String s : v.split(",")) {
            if (!s.trim().isEmpty()) out.add(s.trim());
        }
        return out;
    }

    /** Write a string. If the key is already stored as boolean, keep the type. */
    static void put(Context c, String key, String value) {
        SharedPreferences p = prefs(c);
        Object old = p.getAll().get(key);
        SharedPreferences.Editor e = p.edit();
        if (old instanceof Boolean) e.putBoolean(key, RearKeys.parseBool(value, false));
        else e.putString(key, value);
        e.apply();
    }

    static void putList(Context c, String key, List<String> values) {
        put(c, key, TextUtils.join(",", values));
    }

    /** Value source for Effective (preview as on the rear screen). */
    static Effective.Values values(Context c) {
        final Map<String, String> m = all(c);
        return key -> {
            String v = m.get(key);
            return v != null ? v : RearKeys.def(key);
        };
    }

    // --- Settings.System: what the system part reads ---

    static int sysInt(Context c, String key, int def) {
        return Settings.System.getIntForUser(c.getContentResolver(), key, def,
                UserHandle.USER_SYSTEM);
    }

    static void putSysInt(Context c, String key, int value) {
        Settings.System.putIntForUser(c.getContentResolver(), key, value, UserHandle.USER_SYSTEM);
    }
}
