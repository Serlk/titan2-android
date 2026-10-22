package org.titan2.parts;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.util.Log;

/**
 * Wi-Fi country code override. The MTK driver allows the hotspot only 2.4 GHz for RU;
 * with US it also gives 5 GHz (36-48, 149-165). The WifiCountryCode override lives only in memory,
 * so Titan2App sets it again on every start.
 */
final class WifiCountry {
    private WifiCountry() {}

    private static final String TAG = "Titan2WifiCountry";
    static final String SETTING = "titan2_wifi_country_override";
    static final String COUNTRY = "US";

    static boolean isEnabled(Context context) {
        return COUNTRY.equals(Settings.Global.getString(context.getContentResolver(), SETTING));
    }

    static void setEnabled(Context context, boolean enabled) {
        Settings.Global.putString(context.getContentResolver(), SETTING,
                enabled ? COUNTRY : null);
        apply(context, enabled);
    }

    static void apply(Context context, boolean enabled) {
        WifiManager wm = context.getSystemService(WifiManager.class);
        try {
            if (enabled) {
                wm.setOverrideCountryCode(COUNTRY);
            } else {
                wm.clearOverrideCountryCode();
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "country override failed", e);
        }
    }
}
