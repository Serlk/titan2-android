package org.titan2.parts;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.view.View;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.settingslib.widget.SelectorWithWidgetPreference;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Shared by the key screens: radio buttons, app list, function names. */
final class KeyPrefs {

    /** Text for a ListPreference summary: its getSummary runs it through String.format. */
    static String literal(CharSequence text) {
        return text == null ? null : text.toString().replace("%", "%%");
    }

    /**
     * Rebuild the screen after a choice without resetting the scroll: setPreferenceScreen sends
     * the list back to the top, and on the square screen the chosen item went out of view ("not selected").
     */
    static void rebuildKeepingScroll(PreferenceFragmentCompat fragment, Runnable build) {
        RecyclerView list = fragment.getListView();
        if (list == null || !(list.getLayoutManager() instanceof LinearLayoutManager)) {
            if (fragment.getView() != null) {
                fragment.getView().post(build);
            }
            return;
        }
        LinearLayoutManager lm = (LinearLayoutManager) list.getLayoutManager();
        int pos = lm.findFirstVisibleItemPosition();
        View first = lm.findViewByPosition(pos);
        int top = first == null ? 0 : first.getTop() - list.getPaddingTop();
        list.post(() -> {
            build.run();
            list.post(() -> lm.scrollToPositionWithOffset(Math.max(pos, 0), top));
        });
    }
    private KeyPrefs() {}

    /** Radio item as in Settings; a repeated click on the checked one does nothing. */
    static class RadioPreference extends SelectorWithWidgetPreference {
        RadioPreference(Context context) {
            super(context);
            setPersistent(false);
        }

        @Override
        public void onClick() {
            if (!isChecked() && callChangeListener(true)) {
                setChecked(true);
            }
        }
    }

    /** A non-clickable caption (SettingsLib-style footer). */
    static Preference footer(Context context, CharSequence text) {
        Preference p = new Preference(context);
        p.setSummary(text);
        p.setSelectable(false);
        p.setPersistent(false);
        return p;
    }

    /** {label, package} of all apps with a launcher icon, alphabetically (Collator). */
    static List<String[]> launcherApps(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<String[]> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (seen.add(pkg)) {
                rows.add(new String[] {ri.loadLabel(pm).toString(), pkg});
            }
        }
        Collator collator = Collator.getInstance(Locale.getDefault());
        collator.setStrength(Collator.PRIMARY);
        rows.sort((a, b) -> collator.compare(a[0], b[0]));
        return rows;
    }

    static String appLabel(Context context, String pkg) {
        PackageManager pm = context.getPackageManager();
        try {
            return pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    static String keyTitle(Context context, int slot) {
        final int[] titles = {R.string.func1_key_title, R.string.func2_key_title,
                R.string.sym_key_title, R.string.fn_key_title, R.string.shift_key_title,
                R.string.alt_key_title, R.string.back_key_title, R.string.recent_key_title};
        return context.getString(titles[slot]);
    }

    static String functionTitle(Context context, int function) {
        switch (function) {
            case KeyConfig.FUNC_CTRL: return context.getString(R.string.func_ctrl);
            case KeyConfig.FUNC_SYM: return context.getString(R.string.func_sym);
            case KeyConfig.FUNC_MAGIC: return context.getString(R.string.func_magic);
            case KeyConfig.FUNC_MEDIA: return context.getString(R.string.func_media);
            case KeyConfig.FUNC_TAB: return context.getString(R.string.func_tab);
            case KeyConfig.FUNC_HOME: return context.getString(R.string.func_home);
            case KeyConfig.FUNC_SHIFT: return context.getString(R.string.func_shift);
            case KeyConfig.FUNC_ALT: return context.getString(R.string.func_alt);
            case KeyConfig.FUNC_BACK: return context.getString(R.string.func_back);
            case KeyConfig.FUNC_POWER: return context.getString(R.string.func_power);
            case KeyConfig.FUNC_RECENT: return context.getString(R.string.func_recent);
            default: return context.getString(R.string.func_none);
        }
    }

    /** Name of a Shortcut mode built-in function or app. */
    static String actionTitle(Context context, String action) {
        if (action == null) return context.getString(R.string.sc_none);
        switch (action) {
            case KeyConfig.NONE: return context.getString(R.string.sc_none);
            case KeyConfig.TORCH: return context.getString(R.string.sc_flashlight);
            case KeyConfig.RECORD: return context.getString(R.string.sc_record);
            case KeyConfig.SCREENSHOT: return context.getString(R.string.sc_screenshot);
            case KeyConfig.SCREEN_RECORD: return context.getString(R.string.sc_screen_record);
            case KeyConfig.SILENT: return context.getString(R.string.sc_silent);
            case KeyConfig.SCREEN_OFF: return context.getString(R.string.sc_screen_off);
            case KeyConfig.VIBRATE: return context.getString(R.string.sc_vibration);
            case KeyConfig.KEY_BACK: return context.getString(R.string.func_back);
            case KeyConfig.KEY_HOME: return context.getString(R.string.func_home);
            case KeyConfig.KEY_RECENT: return context.getString(R.string.func_recent);
            case KeyConfig.CAMERA: return context.getString(R.string.action_camera);
            case KeyConfig.NOTIFICATIONS: return context.getString(R.string.action_notifications);
            case KeyConfig.RINGER: return context.getString(R.string.action_ringer);
            case KeyConfig.PRIVACY: return context.getString(R.string.action_privacy);
            case KeyConfig.REAR_SELFIE: return context.getString(R.string.action_rear_selfie);
            case KeyConfig.REAR_QR: return context.getString(R.string.action_rear_qr);
            case KeyConfig.REAR_LIGHT: return context.getString(R.string.action_rear_light);
            default:
                if (action.startsWith(KeyConfig.APP_PREFIX)) {
                    return context.getString(R.string.action_app,
                            appLabel(context, action.substring(KeyConfig.APP_PREFIX.length())));
                }
                return action;
        }
    }
}
