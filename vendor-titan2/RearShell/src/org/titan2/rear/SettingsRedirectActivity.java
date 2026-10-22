package org.titan2.rear;

import android.app.Activity;
import android.os.Bundle;

/**
 * The old org.titan2.rear.SETTINGS action: the shell has no settings of its own any more, open
 * the rear screen settings in Titan2Parts (main display) and close.
 */
public class SettingsRedirectActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        RearActions.openSettings(this);
        finish();
    }
}
