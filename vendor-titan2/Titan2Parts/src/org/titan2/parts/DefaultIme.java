package org.titan2.parts;

import android.content.ContentResolver;
import android.content.Context;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

/**
 * Plektra is the default keyboard from the first boot (otherwise the system picks the on-screen IME, and
 * on the hardware keyboard you cannot even type the Wi-Fi password in the setup wizard). Done
 * once: the user's choice is left alone afterwards.
 */
final class DefaultIme {
    private static final String TAG = "Titan2Ime";
    private static final String PACKAGE_PREFIX = "rocks.pkb.plektra";
    private static final String DONE = "titan2_default_ime_done";

    private DefaultIme() {}

    static void apply(Context context) {
        ContentResolver cr = context.getContentResolver();
        if ("1".equals(Settings.Secure.getStringForUser(cr, DONE, UserHandle.USER_SYSTEM))) {
            return;
        }
        InputMethodManager imm = context.getSystemService(InputMethodManager.class);
        String id = null;
        for (InputMethodInfo info : imm.getInputMethodList()) {
            if (info.getPackageName().startsWith(PACKAGE_PREFIX)) {
                id = info.getId();
                break;
            }
        }
        if (id == null) {
            return;   // Plektra is not scanned yet, try again on the next start
        }
        String enabled = Settings.Secure.getStringForUser(cr,
                Settings.Secure.ENABLED_INPUT_METHODS, UserHandle.USER_SYSTEM);
        if (TextUtils.isEmpty(enabled)) {
            enabled = id;
        } else if (!(":" + enabled + ":").contains(":" + id + ":")
                && !(":" + enabled).contains(":" + id + ";")) {
            enabled = enabled + ":" + id;
        }
        Settings.Secure.putStringForUser(cr, Settings.Secure.ENABLED_INPUT_METHODS, enabled,
                UserHandle.USER_SYSTEM);
        Settings.Secure.putStringForUser(cr, Settings.Secure.DEFAULT_INPUT_METHOD, id,
                UserHandle.USER_SYSTEM);
        Settings.Secure.putStringForUser(cr, DONE, "1", UserHandle.USER_SYSTEM);
        Log.i(TAG, "default IME: " + id);
    }
}
