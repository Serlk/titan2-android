package org.titan2.parts;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;
import android.os.Bundle;
import android.os.SystemProperties;
import androidx.preference.ListPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

/**
 * Settings -> System -> "Performance": CPU profile, I/O scheduler, boot log.
 * Only persist.sys.titan2.* properties: applied by init (titan2-parts.rc -> titan2-perf.sh,
 * titan2-bootlog.sh), the app has no access to cpufreq, block and /metadata.
 */
public class PerformanceSettingsActivity extends CollapsingToolbarBaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                            new PerformanceFragment())
                    .commit();
        }
    }

    public static class PerformanceFragment extends SettingsBasePreferenceFragment {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(getContext());
            setPreferenceScreen(screen);

            screen.addPreference(propList("persist.sys.titan2.cpu_profile", "balance",
                    R.string.cpu_profile_title, R.array.cpu_profile_entries,
                    new CharSequence[] {"balance", "powersave", "performance"}));
            // the kernel accepts only available ones; titan2-perf.sh skips an unavailable one
            screen.addPreference(propList("persist.sys.titan2.io_sched", "mq-deadline",
                    R.string.io_sched_title, R.array.io_sched_entries,
                    new CharSequence[] {"mq-deadline", "kyber", "bfq", "none"}));

            screen.addPreference(propSwitch("persist.sys.titan2.verbose_boot",
                    R.string.verbose_boot_title, R.string.verbose_boot_summary));

            SwitchPreferenceCompat log = new SwitchPreferenceCompat(getContext());
            log.setPersistent(false);
            log.setKey("persist.sys.titan2.bootlog");
            log.setTitle(R.string.bootlog_title);
            log.setSummary(R.string.bootlog_summary);
            log.setChecked("1".equals(SystemProperties.get("persist.sys.titan2.bootlog")));
            log.setOnPreferenceChangeListener((preference, newValue) -> {
                SystemProperties.set("persist.sys.titan2.bootlog", (Boolean) newValue ? "1" : "0");
                return true;
            });
            screen.addPreference(log);
        }

        private SwitchPreferenceCompat propSwitch(String prop, int title, int summary) {
            SwitchPreferenceCompat pref = new SwitchPreferenceCompat(getContext());
            pref.setPersistent(false);
            pref.setKey(prop);
            pref.setTitle(title);
            pref.setSummary(summary);
            pref.setChecked("1".equals(SystemProperties.get(prop)));
            pref.setOnPreferenceChangeListener((preference, newValue) -> {
                SystemProperties.set(prop, (Boolean) newValue ? "1" : "0");
                return true;
            });
            return pref;
        }

        private ListPreference propList(String prop, String def, int title, int entries,
                CharSequence[] values) {
            ListPreference pref = new ListPreference(getContext());
            pref.setPersistent(false);
            pref.setKey(prop);
            pref.setTitle(title);
            pref.setDialogTitle(title);
            pref.setEntries(entries);
            pref.setEntryValues(values);
            pref.setValue(SystemProperties.get(prop, def));
            pref.setSummary("%s");
            pref.setOnPreferenceChangeListener((preference, newValue) -> {
                SystemProperties.set(prop, (String) newValue);
                return true;
            });
            return pref;
        }
    }
}
