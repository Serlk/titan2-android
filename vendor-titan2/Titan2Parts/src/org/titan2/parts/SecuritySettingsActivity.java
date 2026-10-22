package org.titan2.parts;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;
import android.os.Bundle;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

/** Settings -> Security -> "USB: charging only while locked" (UsbGuard). */
public class SecuritySettingsActivity extends CollapsingToolbarBaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                            new SecurityFragment())
                    .commit();
        }
    }

    public static class SecurityFragment extends SettingsBasePreferenceFragment {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(getContext());
            setPreferenceScreen(screen);

            SwitchPreferenceCompat pref = new SwitchPreferenceCompat(getContext());
            pref.setPersistent(false);
            pref.setKey(UsbGuard.SETTING);
            pref.setTitle(R.string.usb_guard_title);
            pref.setSummary(R.string.usb_guard_summary);
            pref.setChecked(UsbGuard.isEnabled(getContext()));
            pref.setOnPreferenceChangeListener((preference, newValue) -> {
                UsbGuard.setEnabled(getContext(), (Boolean) newValue);
                return true;
            });
            screen.addPreference(pref);
        }
    }
}
