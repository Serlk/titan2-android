package org.titan2.parts;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;
import android.content.Intent;
import android.os.Bundle;
import android.os.UserHandle;
import androidx.preference.Preference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;
import android.provider.Settings;

/** Titan 2 -> "Touchpad": scroll and cursor assistants, wake on double tap. */
public class TouchpadActivity extends CollapsingToolbarBaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                            new TouchpadFragment())
                    .commit();
        }
    }

    public static class TouchpadFragment extends SettingsBasePreferenceFragment {
        private SwitchPreferenceCompat mTap;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(getContext());
            setPreferenceScreen(screen);

            Preference scroll = new Preference(getContext());
            scroll.setTitle(R.string.scroll_assistant_title);
            scroll.setIntent(new Intent(getContext(), ScrollAssistantActivity.class));
            screen.addPreference(scroll);

            Preference cursor = new Preference(getContext());
            cursor.setTitle(R.string.cursor_assistant_title);
            cursor.setIntent(new Intent(getContext(), CursorAssistantActivity.class));
            screen.addPreference(cursor);

            if (TouchpadController.hasDoubleTapWake()) {
                SwitchPreferenceCompat tap = new SwitchPreferenceCompat(getContext());
                tap.setPersistent(false);
                tap.setTitle(R.string.double_tap_wake_title);
                tap.setSummary(R.string.double_tap_wake_summary);
                tap.setChecked("1".equals(Settings.System.getStringForUser(
                        getContext().getContentResolver(), TouchpadController.SETTING_DOUBLE_TAP,
                        UserHandle.USER_SYSTEM)));
                tap.setOnPreferenceChangeListener((preference, newValue) -> {
                    // applied by TouchpadController (system_server) via a ContentObserver
                    Settings.System.putStringForUser(getContext().getContentResolver(),
                            TouchpadController.SETTING_DOUBLE_TAP, (Boolean) newValue ? "1" : "0",
                            UserHandle.USER_SYSTEM);
                    return true;
                });
                screen.addPreference(tap);
                mTap = tap;
            }
        }

        @Override
        public void onResume() {
            super.onResume();
            if (mTap != null) {
                mTap.setChecked("1".equals(Settings.System.getStringForUser(
                        getContext().getContentResolver(), TouchpadController.SETTING_DOUBLE_TAP,
                        UserHandle.USER_SYSTEM)));
            }
        }
    }
}
