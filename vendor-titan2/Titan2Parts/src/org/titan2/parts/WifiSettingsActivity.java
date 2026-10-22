package org.titan2.parts;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemProperties;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

/** Settings -> Network & internet -> "Titan 2 network": 5 GHz for the hotspot, TCP algorithm. */
public class WifiSettingsActivity extends CollapsingToolbarBaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                            new WifiFragment())
                    .commit();
        }
    }

    public static class WifiFragment extends SettingsBasePreferenceFragment {
        private static final String TCP_PROP = "persist.sys.titan2.tcp_cc";

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(getContext());
            setPreferenceScreen(screen);

            SwitchPreferenceCompat pref = new SwitchPreferenceCompat(getContext());
            pref.setPersistent(false);
            pref.setKey(WifiCountry.SETTING);
            pref.setTitle(R.string.softap_5g_title);
            pref.setSummary(R.string.softap_5g_summary);
            pref.setChecked(WifiCountry.isEnabled(getContext()));
            pref.setOnPreferenceChangeListener((preference, newValue) -> {
                WifiCountry.setEnabled(getContext(), (Boolean) newValue);
                return true;
            });
            screen.addPreference(pref);

            // TCP algorithm: applied by init (titan2-parts.rc) on persist.sys.titan2.tcp_cc
            ListPreference tcp = new ListPreference(getContext());
            tcp.setPersistent(false);
            tcp.setKey(TCP_PROP);
            tcp.setTitle(R.string.tcp_cc_title);
            tcp.setDialogTitle(R.string.tcp_cc_title);
            tcp.setEntries(R.array.tcp_cc_entries);
            tcp.setEntryValues(new CharSequence[] {"cubic", "bbr"});
            tcp.setValue(SystemProperties.get(TCP_PROP, "cubic"));
            tcp.setSummary("%s");
            tcp.setOnPreferenceChangeListener((preference, newValue) -> {
                SystemProperties.set(TCP_PROP, (String) newValue);
                return true;
            });
            screen.addPreference(tcp);

            Preference firewall = new Preference(getContext());
            firewall.setTitle(R.string.firewall_title);
            firewall.setSummary(R.string.firewall_summary);
            firewall.setIntent(new Intent(getContext(), FirewallActivity.class));
            screen.addPreference(firewall);
        }
    }
}
