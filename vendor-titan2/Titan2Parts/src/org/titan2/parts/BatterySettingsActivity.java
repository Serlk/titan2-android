package org.titan2.parts;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;
import androidx.preference.PreferenceScreen;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/** Settings -> Battery -> "Titan 2 battery": charge limit and battery health. */
public class BatterySettingsActivity extends CollapsingToolbarBaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                            new BatteryFragment())
                    .commit();
        }
    }

    public static class BatteryFragment extends SettingsBasePreferenceFragment {
        private static final String PS = "/sys/class/power_supply/battery/";
        private static final long REFRESH_MS = 2000;
        private static final String[] LIMITS = {"0", "80", "85", "90", "95"};

        private final Handler mHandler = new Handler(Looper.getMainLooper());
        private final Runnable mRefresh = new Runnable() {
            @Override
            public void run() {
                refresh();
                mHandler.postDelayed(this, REFRESH_MS);
            }
        };
        private Preference mLevel, mCurrent, mVoltage, mTemp, mCycles, mCapacity, mWear;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(getContext());
            setPreferenceScreen(screen);

            ListPreference limit = new ListPreference(getContext());
            limit.setPersistent(false);
            limit.setKey(ChargeLimiter.SETTING);
            limit.setTitle(R.string.charge_limit_title);
            limit.setDialogTitle(R.string.charge_limit_title);
            CharSequence[] entries = new CharSequence[LIMITS.length];
            entries[0] = getString(R.string.charge_limit_off);
            for (int i = 1; i < LIMITS.length; i++) {
                entries[i] = LIMITS[i] + "%";
            }
            limit.setEntries(entries);
            limit.setEntryValues(LIMITS);
            limit.setValue(String.valueOf(ChargeLimiter.getLimit(getContext())));
            limit.setSummary("%s");
            limit.setOnPreferenceChangeListener((preference, newValue) -> {
                ChargeLimiter.setLimit(getContext(), Integer.parseInt((String) newValue));
                return true;
            });
            screen.addPreference(limit);

            Preference note = new Preference(getContext());
            note.setSummary(getString(R.string.charge_limit_summary, ChargeLimiter.HYSTERESIS));
            note.setSelectable(false);
            screen.addPreference(note);

            PreferenceCategory info = new PreferenceCategory(getContext());
            info.setTitle(R.string.battery_info);
            screen.addPreference(info);
            mLevel = addInfo(info, R.string.battery_level);
            mCurrent = addInfo(info, R.string.battery_current);
            mVoltage = addInfo(info, R.string.battery_voltage);
            mTemp = addInfo(info, R.string.battery_temp);
            mCycles = addInfo(info, R.string.battery_cycles);
            mCapacity = addInfo(info, R.string.battery_capacity);
            mWear = addInfo(info, R.string.battery_wear);
        }

        private Preference addInfo(PreferenceCategory cat, int title) {
            Preference p = new Preference(getContext());
            p.setTitle(title);
            p.setSelectable(false);
            cat.addPreference(p);
            return p;
        }

        @Override
        public void onResume() {
            super.onResume();
            mHandler.post(mRefresh);
        }

        @Override
        public void onPause() {
            super.onPause();
            mHandler.removeCallbacks(mRefresh);
        }

        private void refresh() {
            Intent b = getContext().registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b != null) {
                int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                mLevel.setSummary(level + "% · " + statusText(status));
                mVoltage.setSummary(getString(R.string.unit_mv,
                        b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)));
                mTemp.setSummary(getString(R.string.unit_celsius,
                        b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f));
            }
            BatteryManager bm = getContext().getSystemService(BatteryManager.class);
            int ua = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            mCurrent.setSummary(getString(R.string.unit_ma, ua / 1000));

            long cycles = readLong("cycle_count");
            mCycles.setSummary(cycles >= 0 ? String.valueOf(cycles) : "—");
            long full = readLong("charge_full");
            long design = readLong("charge_full_design");
            // the MTK driver reports the design capacity 10 times too small (459000 with charge_full 4593000)
            if (design > 0 && full > 0 && design * 5 < full) {
                design *= 10;
            }
            if (full > 0 && design > 0) {
                mCapacity.setSummary(getString(R.string.battery_capacity_value,
                        full / 1000, design / 1000));
                mWear.setSummary(Math.max(0, 100 - full * 100 / design) + "%");
            } else {
                mCapacity.setSummary("—");
                mWear.setSummary("—");
            }
        }

        private String statusText(int status) {
            switch (status) {
                case BatteryManager.BATTERY_STATUS_CHARGING:
                    return getString(R.string.status_charging);
                case BatteryManager.BATTERY_STATUS_FULL:
                    return getString(R.string.status_full);
                case BatteryManager.BATTERY_STATUS_NOT_CHARGING:
                    return getString(R.string.status_not_charging);
                default:
                    return getString(R.string.status_discharging);
            }
        }

        private static long readLong(String name) {
            try (BufferedReader r = new BufferedReader(new FileReader(PS + name))) {
                return Long.parseLong(r.readLine().trim());
            } catch (IOException | NumberFormatException | NullPointerException e) {
                return -1;
            }
        }
    }
}
