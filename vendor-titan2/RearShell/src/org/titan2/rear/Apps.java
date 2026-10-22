package org.titan2.rear;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.graphics.Rect;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;
import android.view.View;

import org.titan2.rear.common.RearKeys;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** App list (LauncherApps, all profiles) and launching on the rear display. */
public final class Apps {
    private static final String TAG = "RearShell";

    public static final class Entry {
        public final LauncherActivityInfo info;
        public final ComponentName component;
        public final UserHandle user;
        public final String label;

        Entry(LauncherActivityInfo info) {
            this.info = info;
            this.component = info.getComponentName();
            this.user = info.getUser();
            this.label = String.valueOf(info.getLabel());
        }

        /** favorites key: component (+ profile id if not the main one) */
        public String key() {
            String k = component.flattenToString();
            return user.equals(android.os.Process.myUserHandle()) ? k
                    : k + "#" + user.getIdentifier();
        }
    }

    private Apps() {}

    /** All apps with a launcher activity; allowed == null: no filter; sort: DRAWER_SORT. */
    public static List<Entry> load(Context c, Set<String> allowed, String sort) {
        LauncherApps la = c.getSystemService(LauncherApps.class);
        UserManager um = c.getSystemService(UserManager.class);
        List<Entry> out = new ArrayList<>();
        for (UserHandle u : um.getUserProfiles()) {
            List<LauncherActivityInfo> list;
            try {
                list = la.getActivityList(null, u);
            } catch (RuntimeException e) {
                continue; // the profile is locked (before the first unlock)
            }
            for (LauncherActivityInfo i : list) {
                String pkg = i.getComponentName().getPackageName();
                if (pkg.equals(c.getPackageName())) continue;
                if (allowed != null && !allowed.contains(pkg)) continue;
                out.add(new Entry(i));
            }
        }
        Collator col = Collator.getInstance();
        Collections.sort(out, (a, b) -> col.compare(a.label, b.label));
        if (RearKeys.SORT_ALPHA_DESC.equals(sort)) {
            Collections.reverse(out);
        } else if (RearKeys.SORT_NEW.equals(sort) || RearKeys.SORT_OLD.equals(sort)) {
            boolean newFirst = RearKeys.SORT_NEW.equals(sort);
            Collections.sort(out, (a, b) -> {
                long x = a.info.getFirstInstallTime(), y = b.info.getFirstInstallTime();
                return newFirst ? Long.compare(y, x) : Long.compare(x, y);
            });
        }
        return out;
    }

    /** Favorites in the user's order (missing keys are skipped). */
    public static List<Entry> favorites(List<Entry> all, List<String> keys) {
        Map<String, Entry> byKey = new HashMap<>();
        for (Entry e : all) byKey.put(e.key(), e);
        List<Entry> out = new ArrayList<>();
        for (String k : keys) {
            Entry e = byKey.get(k);
            if (e != null) out.add(e);
        }
        return out;
    }

    /**
     * Launch on the rear display in a new task. source: the icon, the window "grows" out of it
     * (scale up; expressive: clip reveal); without animations a plain launch.
     */
    public static boolean launch(Context c, Entry e, int displayId, View source) {
        ActivityOptions o;
        Rect bounds = null;
        if (source != null && Motion.enabled()) {
            int w = source.getWidth(), h = source.getHeight();
            o = Motion.expressive() ? ActivityOptions.makeClipRevealAnimation(source, 0, 0, w, h)
                    : Motion.subtle() ? ActivityOptions.makeBasic()
                    : ActivityOptions.makeScaleUpAnimation(source, 0, 0, w, h);
            int[] loc = new int[2];
            source.getLocationOnScreen(loc);
            bounds = new Rect(loc[0], loc[1], loc[0] + w, loc[1] + h);
        } else {
            o = ActivityOptions.makeBasic();
        }
        o.setLaunchDisplayId(displayId);
        try {
            c.getSystemService(LauncherApps.class)
                    .startMainActivity(e.component, e.user, bounds, o.toBundle());
            return true;
        } catch (RuntimeException ex) {
            Log.w(TAG, "launch " + e.component + ": " + ex);
            return false;
        }
    }
}
