package org.titan2.parts;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

/**
 * "USB for charging only on the lock screen". crDroid on a GSI has no "Restrict USB" option,
 * so we do it ourselves: when locked, data is turned off via the USB HAL
 * (enableUsbDataSignal, MTK USB HAL v2), charging works; after unlock it is turned back on.
 * While enabled, adb is unavailable on a locked phone too.
 */
final class UsbGuard {
    private static final String TAG = "Titan2UsbGuard";
    static final String SETTING = "titan2_usb_guard";   // Settings.Global, 1 is on

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Boolean mDataEnabled;

    UsbGuard(Context context) {
        mContext = context;
    }

    static boolean isEnabled(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), SETTING, 0) == 1;
    }

    static void setEnabled(Context context, boolean enabled) {
        Settings.Global.putInt(context.getContentResolver(), SETTING, enabled ? 1 : 0);
    }

    void start() {
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_USER_PRESENT);
        f.addAction(Intent.ACTION_SCREEN_ON);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // the screen goes off before the lock kicks in, so check with a margin
                mHandler.postDelayed(UsbGuard.this::evaluate, 1000);
            }
        }, f, null, mHandler);
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(SETTING), false, new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        evaluate();
                    }
                });
        // the lock may come long after SCREEN_OFF ("lock after N minutes"), with no broadcast:
        // without this USB data stayed on while locked
        try {
            mContext.getSystemService(KeyguardManager.class).addKeyguardLockedStateListener(
                    mContext.getMainExecutor(), locked -> evaluate());
        } catch (RuntimeException e) {
            Log.w(TAG, "keyguard state listener", e);
        }
        evaluate();
    }

    private void evaluate() {
        boolean locked = mContext.getSystemService(KeyguardManager.class).isDeviceLocked();
        boolean enable = !(isEnabled(mContext) && locked);
        if (mDataEnabled != null && mDataEnabled == enable) {
            return;
        }
        boolean ok = mContext.getSystemService(UsbManager.class).enableUsbDataSignal(enable);
        Log.i(TAG, (enable ? "USB data on" : "USB data off (locked)") + (ok ? "" : " — HAL refused"));
        if (ok) {
            mDataEnabled = enable;
        }
    }
}
