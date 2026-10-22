package org.titan2.parts;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;
import android.content.ContentResolver;
import android.os.Bundle;
import android.os.SystemProperties;
import android.os.UserHandle;
import androidx.preference.ListPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;
import androidx.preference.PreferenceScreen;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;
import android.provider.Settings;

/**
 * Titan 2 -> "Keyboard backlight" settings: auto adjust, duration, brightness (the slider is
 * disabled in auto mode), and "Turn on the backlight with a swipe".
 */
public class KeyboardLedSettingsActivity extends CollapsingToolbarBaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                            new LedFragment())
                    .commit();
        }
    }

    public static class LedFragment extends SettingsBasePreferenceFragment {
        static final String[] TIMEOUTS = {"0", "3000", "5000", "10000", "15000", "30000", "-1"};

        private SeekBarPreference mBrightness;
        private SwitchPreferenceCompat mSlide;
        private SwitchPreferenceCompat mAuto;
        private ListPreference mTimeout;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            final ContentResolver cr = getContext().getContentResolver();
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(getContext());
            setPreferenceScreen(screen);

            SwitchPreferenceCompat auto = new SwitchPreferenceCompat(getContext());
            auto.setPersistent(false);
            auto.setKey(KeyboardLight.SETTING_AUTO);
            auto.setTitle(R.string.kbd_led_auto);
            auto.setSummary(R.string.kbd_led_auto_summary);
            auto.setChecked("1".equals(get(cr, KeyboardLight.SETTING_AUTO, "0")));
            auto.setOnPreferenceChangeListener((preference, newValue) -> {
                put(cr, KeyboardLight.SETTING_AUTO, (Boolean) newValue ? "1" : "0");
                updateBrightness((Boolean) newValue);
                return true;
            });
            screen.addPreference(auto);

            ListPreference timeout = new ListPreference(getContext());
            timeout.setPersistent(false);
            timeout.setKey(KeyboardLight.SETTING_TIMEOUT);
            timeout.setTitle(R.string.kbd_led_timeout);
            timeout.setDialogTitle(R.string.kbd_led_timeout);
            timeout.setEntries(R.array.kbd_led_timeout_entries);
            timeout.setEntryValues(TIMEOUTS);
            timeout.setValue(get(cr, KeyboardLight.SETTING_TIMEOUT, KeyboardLight.DEFAULT_TIMEOUT));
            timeout.setSummary("%s");
            timeout.setOnPreferenceChangeListener((preference, newValue) -> {
                // the timeout is also stored in _backup for the quick settings tile
                put(cr, KeyboardLight.SETTING_TIMEOUT, (String) newValue);
                put(cr, KeyboardLight.SETTING_TIMEOUT_BACKUP, (String) newValue);
                updateSlide((String) newValue);
                return true;
            });
            screen.addPreference(timeout);

            mBrightness = new SeekBarPreference(getContext());
            mBrightness.setPersistent(false);
            mBrightness.setKey(KeyboardLight.SETTING_BRIGHTNESS);
            mBrightness.setTitle(R.string.kbd_led_brightness);
            mBrightness.setMax(100);
            mBrightness.setValue(brightness(cr));
            // the value is written on every slider move
            mBrightness.setUpdatesContinuously(true);
            mBrightness.setOnPreferenceChangeListener((preference, newValue) -> {
                put(cr, KeyboardLight.SETTING_BRIGHTNESS, String.valueOf(newValue));
                return true;
            });
            screen.addPreference(mBrightness);
            updateBrightness(auto.isChecked());

            mSlide = new SwitchPreferenceCompat(getContext());
            mSlide.setPersistent(false);
            mSlide.setTitle(R.string.slide_light_title);
            mSlide.setOnPreferenceChangeListener((preference, newValue) -> {
                SystemProperties.set(KeyboardLight.PROP_SLIDE_ON, String.valueOf(newValue));
                return true;
            });
            screen.addPreference(mSlide);
            updateSlide(timeout.getValue());
            mAuto = auto;
            mTimeout = timeout;
        }

        /** The quick settings tile and the keyboard presets change these too: no stale state. */
        @Override
        public void onResume() {
            super.onResume();
            final ContentResolver cr = getContext().getContentResolver();
            mAuto.setChecked("1".equals(get(cr, KeyboardLight.SETTING_AUTO, "0")));
            mTimeout.setValue(get(cr, KeyboardLight.SETTING_TIMEOUT,
                    KeyboardLight.DEFAULT_TIMEOUT));
            mBrightness.setValue(brightness(cr));
            updateBrightness(mAuto.isChecked());
            updateSlide(mTimeout.getValue());
        }

        private static int brightness(ContentResolver cr) {
            try {
                return Integer.parseInt(get(cr, KeyboardLight.SETTING_BRIGHTNESS,
                        KeyboardLight.DEFAULT_BRIGHTNESS));
            } catch (NumberFormatException e) {
                return 50;
            }
        }

        /** With auto adjust the light sensor sets the brightness: the slider is disabled and says why. */
        private void updateBrightness(boolean auto) {
            mBrightness.setEnabled(!auto);
            mBrightness.setSummary(auto ? getString(R.string.kbd_led_brightness_auto) : null);
        }

        private void updateSlide(String timeout) {
            if ("0".equals(timeout)) {
                // backlight "Always off": nothing to turn on with a swipe
                mSlide.setSummary(R.string.slide_light_summary_off);
                mSlide.setChecked(false);
                mSlide.setEnabled(false);
            } else {
                mSlide.setSummary(R.string.slide_light_summary);
                mSlide.setEnabled(true);
                mSlide.setChecked(SystemProperties.get(KeyboardLight.PROP_SLIDE_ON, "true")
                        .equals("true"));
            }
        }

        private static String get(ContentResolver cr, String key, String def) {
            String v = Settings.System.getStringForUser(cr, key, UserHandle.USER_SYSTEM);
            return v != null ? v : def;
        }

        private static void put(ContentResolver cr, String key, String value) {
            Settings.System.putStringForUser(cr, key, value, UserHandle.USER_SYSTEM);
        }
    }
}
