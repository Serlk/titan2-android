package org.titan2.parts;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-app touchpad mode list (touchpad_force_scroll_app): defaults at boot, installs, removals.
 * Runs in the Titan2Parts process; TouchpadController (system_server) only reads the setting.
 */
final class TouchpadApps {
    private static final String TAG = "Titan2TouchpadApps";

    /** Packages that never get a default entry and are not listed in the settings. */
    static final List<String> EXCLUDED = Arrays.asList(
            "com.android.settings", "com.google.android.calculator", "com.android.fmradio",
            "com.agui.toolbox", "com.agui.nfc", "com.agui.game", "com.agui.studentmodel",
            "com.google.android.apps.safetyhub", "com.google.android.apps.adm",
            "com.android.dialer", "com.google.android.dialer");

    /** Removed from the list at every start. */
    private static final String DROPPED = "com.google.android.dialer";
    /** Mode of apps found at the first start and of newly installed apps: finger mode, no clicks. */
    private static final int MODE_NEW = 1;
    private static final int MODE_MIN = -1, MODE_MAX = 4;

    private static Handler sHandler;

    private TouchpadApps() {}

    /** At process start: defaults, then follow installs and removals. */
    static void start(Context context) {
        final Context app = context.getApplicationContext();
        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        sHandler = new Handler(thread.getLooper());
        sHandler.post(() -> {
            try {
                applyDefaults(app);
            } catch (RuntimeException e) {
                Log.w(TAG, "defaults", e);
            }
        });
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        filter.addDataScheme("package");
        app.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                try {
                    onPackageChanged(app, intent);
                } catch (RuntimeException e) {
                    Log.w(TAG, "package change", e);
                }
            }
        }, filter, null, sHandler);
    }

    private static void applyDefaults(Context context) {
        final ContentResolver cr = context.getContentResolver();
        final Set<String> apps = launcherApps(context);
        Map<String, Integer> map = load(cr);
        boolean changed = false;
        if (map == null) {
            // first start: nearly every app scrolls like a touchscreen drag
            map = new LinkedHashMap<>();
            for (String pkg : apps) {
                map.put(pkg, MODE_NEW);
            }
            changed = true;
        } else {
            // apps installed while we were not watching
            for (String pkg : apps) {
                if (!map.containsKey(pkg)) {
                    map.put(pkg, TouchpadController.MODE_SLIDE_2);
                    changed = true;
                }
            }
        }
        if (map.remove(DROPPED) != null) {
            changed = true;
        }
        if (changed) {
            save(cr, map);
        }
    }

    private static void onPackageChanged(Context context, Intent intent) {
        final Uri data = intent.getData();
        final String pkg = data != null ? data.getSchemeSpecificPart() : null;
        if (pkg == null || pkg.isEmpty()) return;
        final ContentResolver cr = context.getContentResolver();
        final Map<String, Integer> map = load(cr);
        if (map == null) {
            // not initialised yet: the next start creates the list
            return;
        }
        if (Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())) {
            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            if (EXCLUDED.contains(pkg)) return;
            if (!map.containsKey(pkg)) {
                map.put(pkg, MODE_NEW);
                save(cr, map);
            }
        } else if (Intent.ACTION_PACKAGE_FULLY_REMOVED.equals(intent.getAction())) {
            if (map.remove(pkg) != null) {
                save(cr, map);
            }
        }
    }

    /** Packages with a MAIN + LAUNCHER activity, once each, without the excluded ones. */
    private static Set<String> launcherApps(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Set<String> out = new LinkedHashSet<>();
        for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (!EXCLUDED.contains(pkg)) out.add(pkg);
        }
        return out;
    }

    /** One mode for all listed apps and every existing entry, one write. */
    static void setAll(Context context, int mode) {
        final ContentResolver cr = context.getContentResolver();
        Map<String, Integer> map = load(cr);
        if (map == null) {
            map = new LinkedHashMap<>();
        }
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            e.setValue(mode);
        }
        for (String pkg : launcherApps(context)) {
            map.put(pkg, mode);
        }
        save(cr, map);
    }

    /** Mode shared by all listed apps (no entry counts as mode 2), or null. */
    static String commonMode(Context context) {
        final Map<String, Integer> map = load(context.getContentResolver());
        Integer common = null;
        for (String pkg : launcherApps(context)) {
            Integer mode = map != null ? map.get(pkg) : null;
            if (mode == null) mode = TouchpadController.MODE_SLIDE_2;
            if (common == null) {
                common = mode;
            } else if (!common.equals(mode)) {
                return null;
            }
        }
        return common != null ? String.valueOf(common) : null;
    }

    /** Parsed list in stored order; null when the setting is absent (not initialised). */
    static Map<String, Integer> load(ContentResolver cr) {
        final String raw = Settings.System.getStringForUser(cr,
                TouchpadController.SETTING_APPS, UserHandle.USER_SYSTEM);
        return raw != null ? parse(raw) : null;
    }

    /**
     * "pkg:mode,pkg:mode": whitespace ignored, an entry without ':' is a legacy mode 0, entries
     * that do not parse are skipped, the first entry of a package wins.
     */
    static Map<String, Integer> parse(String raw) {
        final Map<String, Integer> map = new LinkedHashMap<>();
        for (String item : raw.split(",")) {
            final String entry = stripWhitespace(item);
            if (entry.isEmpty()) continue;
            final int colon = entry.indexOf(':');
            final String pkg;
            int mode;
            if (colon < 0) {
                pkg = entry;
                mode = 0;
            } else {
                pkg = entry.substring(0, colon);
                try {
                    mode = Integer.parseInt(entry.substring(colon + 1));
                } catch (NumberFormatException e) {
                    continue;
                }
            }
            if (pkg.isEmpty() || mode < MODE_MIN || mode > MODE_MAX) continue;
            map.putIfAbsent(pkg, mode);
        }
        return map;
    }

    private static String stripWhitespace(String s) {
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                if (sb == null) {
                    sb = new StringBuilder(s.length());
                    sb.append(s, 0, i);
                }
            } else if (sb != null) {
                sb.append(s.charAt(i));
            }
        }
        return sb != null ? sb.toString() : s;
    }

    /** Write the list back: "pkg:mode" joined by ',', no spaces, in map order. */
    static void save(ContentResolver cr, Map<String, Integer> map) {
        final StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        Settings.System.putStringForUser(cr, TouchpadController.SETTING_APPS, sb.toString(),
                UserHandle.USER_SYSTEM);
    }
}
