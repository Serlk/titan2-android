package org.titan2.parts;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;

/**
 * The rear screen as a regular second display with the RearShell shell (Titan2App, persistent process).
 * Enabled (titan2_rear_enabled=1): the panel backlight is allowed, the display is on, the shell home
 * screen is on it; it sleeps with its own power group per titan2_rear_screen_off_timeout
 * (frameworks_base), wakes on a double tap (KeyHandler) and on flipping the phone (here).
 * Disabled: the display and touch are powered off, only the side key modes work (RearService).
 * Runs on its own thread: the agolddaemon calls wait for the daemon (boot) and must not block
 * the main thread of the persistent process.
 */
final class RearController implements SensorEventListener {
    private static final String TAG = "Titan2Rear";
    static final String SHELL_PACKAGE = "org.titan2.rear";
    /** The phone lies main screen down: Z acceleration, m/s^2. */
    private static final float FACE_DOWN_Z = -7.5f;
    private static final long FACE_DOWN_MS = 400;

    private final Context mContext;
    private final Handler mHandler;
    private final SensorManager mSensors;
    private boolean mEnabled;
    /** Last seen titan2_rear_enabled, for the change log. */
    private int mLastSetting = -1;
    /** The rear display was not there yet: apply() again once a display is added. */
    private boolean mWaitingForDisplay;
    private boolean mFlipRegistered;
    private long mFaceDownSince;
    private boolean mFaceDownHandled;

    RearController(Context context) {
        mContext = context;
        mSensors = context.getSystemService(SensorManager.class);
        HandlerThread t = new HandlerThread(TAG);
        t.start();
        mHandler = new Handler(t.getLooper());
    }

    void start() {
        ContentObserver o = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                apply();
            }
        };
        mContext.getSystemService(DisplayManager.class).registerDisplayListener(
                new DisplayManager.DisplayListener() {
                    @Override
                    public void onDisplayAdded(int displayId) {
                        if (mWaitingForDisplay) apply();
                    }

                    @Override
                    public void onDisplayRemoved(int displayId) {
                    }

                    @Override
                    public void onDisplayChanged(int displayId) {
                    }
                }, mHandler);
        for (String key : new String[] {RearDisplay.SETTING_ENABLED,
                RearDisplay.SETTING_WAKE_DOUBLE_TAP, RearDisplay.SETTING_WAKE_FLIP}) {
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(key), false, o, UserHandle.USER_SYSTEM);
        }
        mHandler.post(this::apply);
    }

    private void apply() {
        final int setting = RearDisplay.getInt(mContext, RearDisplay.SETTING_ENABLED, 0);
        if (setting != mLastSetting) {
            Log.i(TAG, RearDisplay.SETTING_ENABLED + " seen: " + mLastSetting + " -> " + setting);
            mLastSetting = setting;
        }
        final int id = RearDisplay.findId(mContext);
        if (id == Display.INVALID_DISPLAY) {
            if (!mWaitingForDisplay) Log.w(TAG, "rear display not found, waiting for it");
            mWaitingForDisplay = true;
            return;
        }
        mWaitingForDisplay = false;
        final boolean enabled = setting == 1;
        final DisplayManager dm = mContext.getSystemService(DisplayManager.class);
        if (enabled) {
            RearDisplay.setPanel(true);
            RearDisplay.setDoubleTap(
                    RearDisplay.getInt(mContext, RearDisplay.SETTING_WAKE_DOUBLE_TAP, 1) == 1);
            if (!mEnabled) {
                // the system starts the shell itself (config_secondaryHomePackage) once the
                // display is added; an early explicit start would land on the main display
                dm.enableConnectedDisplay(id);
            }
        } else {
            RearDisplay.setDoubleTap(false);
            // a side key mode owns the display: RearService turns it off when the mode ends
            if (!RearService.isActive()) {
                dm.disableConnectedDisplay(id);
                RearDisplay.setPanel(false);
            }
        }
        mEnabled = enabled;
        setFlip(enabled
                && RearDisplay.getInt(mContext, RearDisplay.SETTING_WAKE_FLIP, 1) == 1);
    }

    /** The shell home screen on the rear display. */
    static void launchShell(Context context, int displayId) {
        // a home activity whose display is missing or off is started on the main display instead
        Display d = context.getSystemService(DisplayManager.class).getDisplay(displayId);
        if (displayId == Display.DEFAULT_DISPLAY || d == null || d.getState() != Display.STATE_ON) {
            return;
        }
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_SECONDARY_HOME)
                .setPackage(SHELL_PACKAGE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivityAsUser(i,
                    ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle(),
                    UserHandle.CURRENT);
        } catch (RuntimeException e) {
            Log.w(TAG, "rear shell", e);
        }
    }

    private void setFlip(boolean on) {
        if (on == mFlipRegistered) {
            return;
        }
        Sensor acc = mSensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (acc == null) {
            return;
        }
        if (on) {
            mSensors.registerListener(this, acc, SensorManager.SENSOR_DELAY_NORMAL, mHandler);
        } else {
            mSensors.unregisterListener(this);
        }
        mFlipRegistered = on;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        RearDisplay.notePose(e);
        final long now = SystemClock.uptimeMillis();
        if (e.values[2] > FACE_DOWN_Z) {
            mFaceDownSince = 0;
            mFaceDownHandled = false;
            return;
        }
        if (mFaceDownSince == 0) {
            mFaceDownSince = now;
        } else if (!mFaceDownHandled && now - mFaceDownSince >= FACE_DOWN_MS) {
            mFaceDownHandled = true;
            final int id = RearDisplay.findId(mContext);
            if (id != Display.INVALID_DISPLAY && !RearDisplay.isAwake(mContext, id)) {
                RearDisplay.wake(mContext, id, "flip");
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
