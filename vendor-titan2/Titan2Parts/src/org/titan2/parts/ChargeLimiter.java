package org.titan2.parts;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;
import android.util.Log;

import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Charge limit: at the threshold charging is turned off with the MTK "do not charge" command,
 * and turned on again after dropping HYSTERESIS percent. On unplug charging is allowed at once,
 * so the next connection starts with normal charging.
 */
final class ChargeLimiter {
    private static final String TAG = "Titan2ChargeLimiter";
    static final String SETTING = "titan2_charge_limit";   // Settings.Global, 0 is off
    static final int HYSTERESIS = 3;
    // "<current_unlimited> <cmd_discharging>"; titan2-on-boot.sh makes system the owner
    private static final String NODE = "/proc/mtk_battery_cmd/current_cmd";
    private static final long RETRY_MS = 30_000;

    private final Context mContext;
    private final Handler mHandler;
    private Boolean mBlocked;   // null: the node state is not written yet
    private int mLevel = -1;
    private boolean mPlugged;
    private final Runnable mRetry = this::evaluate;

    ChargeLimiter(Context context) {
        mContext = context;
        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mHandler = new Handler(thread.getLooper());
    }

    static int getLimit(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), SETTING, 0);
    }

    static void setLimit(Context context, int limit) {
        Settings.Global.putInt(context.getContentResolver(), SETTING, limit);
    }

    void start() {
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(SETTING), false, new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        evaluate();
                    }
                });
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                mLevel = scale > 0 ? level * 100 / scale : level;
                mPlugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
                evaluate();
            }
        }, new IntentFilter(Intent.ACTION_BATTERY_CHANGED), null, mHandler);
    }

    private void evaluate() {
        int limit = getLimit(mContext);
        boolean block;
        if (limit <= 0 || !mPlugged || mLevel < 0) {
            block = false;
        } else if (mLevel >= limit) {
            block = true;
        } else if (mLevel <= limit - HYSTERESIS) {
            block = false;
        } else {
            block = Boolean.TRUE.equals(mBlocked);
        }
        if (mBlocked == null || mBlocked != block) {
            setBlocked(block);
        }
    }

    private void setBlocked(boolean block) {
        try (FileOutputStream out = new FileOutputStream(NODE)) {
            out.write((block ? "0 1" : "0 0").getBytes());
            mBlocked = block;
            Log.i(TAG, "level " + mLevel + "%, limit " + getLimit(mContext) + "% -> "
                    + (block ? "charging off" : "charging on"));
        } catch (IOException e) {
            // before titan2-on-boot.sh the node is still root:root, retry later
            Log.w(TAG, "cannot write " + NODE + ": " + e.getMessage());
            mHandler.removeCallbacks(mRetry);
            mHandler.postDelayed(mRetry, RETRY_MS);
        }
    }
}
