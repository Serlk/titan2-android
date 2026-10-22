package org.titan2.parts;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.preference.Preference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;
import androidx.preference.PreferenceScreen;

/**
 * Settings -> "Titan 2": the only Titan2Parts entry in Settings (main page),
 * all sections are reached from here. Every setting lives on exactly one screen.
 */
public class TitanSettingsActivity extends CollapsingToolbarBaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                            new HubFragment())
                    .commit();
        }
    }

    public static class HubFragment extends SettingsBasePreferenceFragment {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            Context ctx = getContext();
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(ctx);
            setPreferenceScreen(screen);

            add(screen, R.string.keyboard_section_title, R.string.keyboard_section_summary,
                    KeySettingsActivity.keyboardIntent(ctx));
            add(screen, R.string.side_keys_title, R.string.side_keys_summary,
                    KeySettingsActivity.sideKeysIntent(ctx));
            add(screen, R.string.touchpad_title, R.string.touchpad_summary,
                    new Intent(ctx, TouchpadActivity.class));
            add(screen, R.string.kbd_led_title, R.string.kbd_led_summary,
                    new Intent(ctx, KeyboardLedSettingsActivity.class));
            add(screen, R.string.rear_title, R.string.rear_summary,
                    new Intent(ctx, RearScreenActivity.class));
            add(screen, R.string.network_title, R.string.network_summary,
                    new Intent(ctx, WifiSettingsActivity.class));
            add(screen, R.string.battery_title, R.string.battery_summary,
                    new Intent(ctx, BatterySettingsActivity.class));
            add(screen, R.string.system_title, R.string.perf_summary,
                    new Intent(ctx, PerformanceSettingsActivity.class));
            add(screen, R.string.security_title, R.string.usb_guard_title,
                    new Intent(ctx, SecuritySettingsActivity.class));
        }

        private void add(PreferenceScreen screen, int title, int summary, Intent intent) {
            Preference p = new Preference(getContext());
            p.setTitle(title);
            p.setSummary(summary);
            p.setIntent(intent);
            screen.addPreference(p);
        }
    }
}
