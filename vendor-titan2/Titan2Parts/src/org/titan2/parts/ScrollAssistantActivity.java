package org.titan2.parts;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.SystemProperties;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Scroll assistant": a switch,
 * a description of the modes, the launcher app list (by name) with a mode for each and "for all".
 * List position = mode + 1 (-1 "Close" ... 4 "Mouse mode 2"), an app without an entry is "Mode 2".
 */
public class ScrollAssistantActivity extends CollapsingToolbarBaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                            new ScrollFragment())
                    .commit();
        }
    }

    public static class ScrollFragment extends SettingsBasePreferenceFragment {
        private static final String[] MODES = {"-1", "0", "1", "2", "3", "4"};

        private Preference mInfo;
        private PreferenceCategory mApps;
        private final List<ListPreference> mAppPrefs = new ArrayList<>();

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(getContext());
            setPreferenceScreen(screen);

            SwitchPreferenceCompat sw = new SwitchPreferenceCompat(getContext());
            sw.setPersistent(false);
            sw.setTitle(R.string.scroll_assistant_title);
            boolean on = SystemProperties.get(TouchpadController.PROP_SCROLL, "1").equals("1");
            sw.setChecked(on);
            sw.setOnPreferenceChangeListener((preference, newValue) -> {
                boolean v = (Boolean) newValue;
                SystemProperties.set(TouchpadController.PROP_SCROLL, v ? "1" : "0");
                showApps(v);
                return true;
            });
            screen.addPreference(sw);

            mInfo = new Preference(getContext());
            mInfo.setSelectable(false);
            screen.addPreference(mInfo);

            mApps = new PreferenceCategory(getContext());
            mApps.setTitle(R.string.scroll_assistant_app_list);
            showApps(on);
        }

        private void showApps(boolean on) {
            if (on) {
                mInfo.setSummary(getString(R.string.scroll_assistant_info)
                        + getString(R.string.scroll_assistant_help) + "\n"
                        + getString(R.string.scroll_assistant_summary));
                getPreferenceScreen().addPreference(mApps);
                fillApps();
            } else {
                mInfo.setSummary(R.string.scroll_assistant_info);
                mApps.removeAll();
                mAppPrefs.clear();
                getPreferenceScreen().removePreference(mApps);
            }
        }

        private void fillApps() {
            mApps.removeAll();
            mAppPrefs.clear();
            final ContentResolver cr = getContext().getContentResolver();
            Map<String, Integer> map = TouchpadApps.load(cr);

            ListPreference all = new ListPreference(getContext());
            all.setPersistent(false);
            // the key is required: the AndroidX dialog looks the entry up by it (for app entries, the package)
            all.setKey("apply_all");
            all.setTitle(R.string.scroll_assistant_apply_all);
            all.setDialogTitle(R.string.scroll_assistant_apply_all);
            all.setEntries(R.array.touchpad_event_type);
            all.setEntryValues(MODES);
            String common = TouchpadApps.commonMode(getContext());
            if (common != null) {
                all.setValue(common);
                all.setSummary("%s");
            }
            all.setOnPreferenceChangeListener((preference, newValue) -> {
                // in one write: previously save() for every app triggered the observer N times
                TouchpadApps.setAll(getContext(), Integer.parseInt((String) newValue));
                for (ListPreference p : mAppPrefs) {
                    p.setValue((String) newValue);
                }
                preference.setSummary("%s");
                return true;
            });
            mApps.addPreference(all);

            PackageManager pm = getContext().getPackageManager();
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> infos = pm.queryIntentActivities(launcher, 0);
            Collator collator = Collator.getInstance();
            collator.setStrength(Collator.PRIMARY);
            infos.sort((a, b) -> collator.compare(String.valueOf(a.loadLabel(pm)),
                    String.valueOf(b.loadLabel(pm))));
            Set<String> seen = new LinkedHashSet<>();
            for (ResolveInfo ri : infos) {
                String pkg = ri.activityInfo.packageName;
                if (TouchpadApps.EXCLUDED.contains(pkg) || !seen.add(pkg)) {
                    continue;
                }
                Integer mode = map != null ? map.get(pkg) : null;
                ListPreference p = new ListPreference(getContext());
                p.setPersistent(false);
                p.setKey(pkg);
                p.setTitle(ri.loadLabel(pm));
                p.setIcon(ri.loadIcon(pm));
                p.setDialogTitle(ri.loadLabel(pm));
                p.setEntries(R.array.touchpad_event_type);
                p.setEntryValues(MODES);
                p.setValue(String.valueOf(mode != null ? mode : TouchpadController.MODE_SLIDE_2));
                p.setSummary("%s");
                p.setOnPreferenceChangeListener((preference, newValue) -> {
                    save(pkg, Integer.parseInt((String) newValue));
                    return true;
                });
                mApps.addPreference(p);
                mAppPrefs.add(p);
            }
        }

        /** Every mode is stored, including mode 2. */
        private void save(String pkg, int mode) {
            ContentResolver cr = getContext().getContentResolver();
            Map<String, Integer> map = TouchpadApps.load(cr);
            if (map == null) {
                map = new java.util.LinkedHashMap<>();
            }
            Integer old = map.get(pkg);
            if (old != null && old == mode) {
                return;
            }
            map.put(pkg, mode);
            TouchpadApps.save(cr, map);
        }
    }
}
