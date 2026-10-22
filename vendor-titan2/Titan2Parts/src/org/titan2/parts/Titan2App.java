package org.titan2.parts;

import android.app.Application;
import android.os.UserHandle;

/** Persistent process (android:persistent): charge limit and the Wi-Fi country override. */
public class Titan2App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        if (UserHandle.myUserId() != UserHandle.USER_SYSTEM) {
            return;
        }
        DefaultIme.apply(this);
        // the "Titan 2 keyboard setup" screen, right after the first setup wizard
        KeyboardSetupActivity.maybeShow(this);
        getContentResolver().registerContentObserver(
                android.provider.Settings.Secure.getUriFor(
                        android.provider.Settings.Secure.USER_SETUP_COMPLETE), false,
                new android.database.ContentObserver(new android.os.Handler(getMainLooper())) {
                    @Override
                    public void onChange(boolean selfChange) {
                        KeyboardSetupActivity.maybeShow(Titan2App.this);
                    }
                }, UserHandle.USER_SYSTEM);
        // Plektra is not direct-boot-aware: before unlock it may be missing from the IME list
        registerReceiver(new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context c, android.content.Intent intent) {
                DefaultIme.apply(c);
            }
        }, new android.content.IntentFilter(android.content.Intent.ACTION_USER_UNLOCKED));
        new ChargeLimiter(this).start();
        new UsbGuard(this).start();
        Firewall.start(this);
        TouchpadApps.start(this);
        AltWake.restore(this);
        new RearController(this).start();
        if (WifiCountry.isEnabled(this)) {
            WifiCountry.apply(this, true);
        }
    }
}
