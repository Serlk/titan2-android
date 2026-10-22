package org.titan2.parts;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import androidx.preference.Preference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Settings -> Network & internet -> "Firewall": internet access per app. */
public class FirewallActivity extends CollapsingToolbarBaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                            new FirewallFragment())
                    .commit();
        }
    }

    public static class FirewallFragment extends SettingsBasePreferenceFragment {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(getContext());
            setPreferenceScreen(screen);

            Preference note = new Preference(getContext());
            note.setSummary(R.string.firewall_note);
            note.setSelectable(false);
            screen.addPreference(note);

            PackageManager pm = getContext().getPackageManager();
            Set<String> blocked = Firewall.blocked(getContext());
            List<PackageInfo> pkgs = pm.getInstalledPackages(
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS));
            List<Object[]> rows = new ArrayList<>();
            for (PackageInfo p : pkgs) {
                if (p.requestedPermissions == null || !java.util.Arrays.asList(
                        p.requestedPermissions).contains(android.Manifest.permission.INTERNET)) {
                    continue;
                }
                // shared system uids (system, phone, ...): a rule would hit the system itself
                if (p.applicationInfo.uid < android.os.Process.FIRST_APPLICATION_UID) {
                    continue;
                }
                // system services without an icon are not shown: they are easy to break
                boolean system = (p.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                if (system && pm.getLaunchIntentForPackage(p.packageName) == null) {
                    continue;
                }
                rows.add(new Object[] {p.applicationInfo.loadLabel(pm).toString(), p});
            }
            rows.sort((a, b) -> ((String) a[0]).compareToIgnoreCase((String) b[0]));
            for (Object[] r : rows) {
                PackageInfo p = (PackageInfo) r[1];
                SwitchPreferenceCompat sw = new SwitchPreferenceCompat(getContext());
                sw.setPersistent(false);
                sw.setKey(p.packageName);
                sw.setTitle((String) r[0]);
                sw.setSummary(p.packageName);
                sw.setIcon(p.applicationInfo.loadIcon(pm));
                sw.setChecked(!blocked.contains(p.packageName));
                sw.setOnPreferenceChangeListener((preference, newValue) -> {
                    Firewall.setBlocked(getContext(), p.packageName, !(Boolean) newValue);
                    return true;
                });
                screen.addPreference(sw);
            }
        }
    }
}
