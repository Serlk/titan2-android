package org.titan2.parts;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;
import android.os.Bundle;
import android.os.SystemProperties;
import androidx.preference.Preference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

/** "Cursor assistant": switch and explanation. */
public class CursorAssistantActivity extends CollapsingToolbarBaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                            new CursorFragment())
                    .commit();
        }
    }

    public static class CursorFragment extends SettingsBasePreferenceFragment {
        private SwitchPreferenceCompat mSwitch;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(getContext());
            setPreferenceScreen(screen);

            SwitchPreferenceCompat sw = new SwitchPreferenceCompat(getContext());
            sw.setPersistent(false);
            sw.setTitle(R.string.cursor_assistant_title);
            sw.setChecked(SystemProperties.get(TouchpadController.PROP_CURSOR, "1").equals("1"));
            sw.setOnPreferenceChangeListener((preference, newValue) -> {
                SystemProperties.set(TouchpadController.PROP_CURSOR, (Boolean) newValue ? "1" : "0");
                return true;
            });
            screen.addPreference(sw);
            mSwitch = sw;

            Preference info = new Preference(getContext());
            info.setSummary(R.string.cursor_assistant_info);
            info.setSelectable(false);
            screen.addPreference(info);
        }

        /** The keyboard presets change the property too. */
        @Override
        public void onResume() {
            super.onResume();
            mSwitch.setChecked(
                    SystemProperties.get(TouchpadController.PROP_CURSOR, "1").equals("1"));
        }
    }
}
