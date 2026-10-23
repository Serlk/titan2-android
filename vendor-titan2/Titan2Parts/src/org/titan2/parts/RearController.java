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
import android.os.PowerManager;
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
 * Disabled: the apps on it are closed, the display and touch are powered off, only the side key
 * modes work (RearService).
 * Flip to wake: the accelerometer is not a wakeup sensor, so a suspended phone sees no samples.
 * It is sampled while the main screen is on and for FLIP_GRACE_MS after it turns off (with a
 * partial wakelock, so "screen off, then put it down" works); after that the wakeup tilt
 * detector (low power, in the sensor hub) wakes the phone on a change of orientation, and the
 * accelerometer is sampled again for FLIP_TILT_MS to see whether it now lies face down.
 * Runs on its own thread: the agolddaemon calls wait for the daemon (boot) and must not block
 * the main thread of the persistent process.
 */
final class RearController implements SensorEventListener {
    private static final String TAG = "Titan2Rear";
    static final String SHELL_PACKAGE = "org.titan2.rear";
    /** The phone lies main screen down: Z acceleration, m/s^2. */
    private static final float FACE_DOWN_Z = -7.5f;
    private static final long FACE_DOWN_MS = 400;
    /** Sampling after the main screen turns off. */
    private static final long FLIP_GRACE_MS = 15_000;
    /** Sampling after a tilt event while the main screen is off (the tilt detector reports
     * after a ~2 s average, so the phone usually already lies still by then). */
    private static final long FLIP_TILT_MS = 4_000;

    private final Context mContext;
    private final Handler mHandler;
    private final SensorManager mSensors;
    private boolean mEnabled;
    /** Last seen titan2_rear_enabled, for the change log. */
    private int mLastSetting = -1;
    /** The rear display was not there yet: apply() again once a display is added. */
    private boolean mWaitingForDisplay;
    private final PowerManager.WakeLock mWakeLock;
    private final Sensor mAcc;
    /** Wakeup tilt detector (TYPE_TILT_DETECTOR), or null. */
    private final Sensor mTilt;
    /** Flip to wake is on (rear enabled and titan2_rear_wake_flip). */
    private boolean mFlip;
    /** The main display is on (the phone is awake anyway). */
    private boolean mMainOn = true;
    /** The accelerometer is sampled until this time with the main screen off (uptime). */
    private long mSampleUntil;
    private boolean mAccRegistered;
    private boolean mTiltRegistered;
    private long mFaceDownSince;
    private boolean mFaceDownHandled;
    private final Runnable mSampleEnd = () -> {
        mSampleUntil = 0;
        updateSensors();
    };

    RearController(Context context) {
        mContext = context;
        mSensors = context.getSystemService(SensorManager.class);
        mAcc = mSensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mTilt = mSensors.getDefaultSensor(Sensor.TYPE_TILT_DETECTOR, true);
        mWakeLock = context.getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Titan2Parts:rear-flip");
        mWakeLock.setReferenceCounted(false);
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
                        if (displayId == Display.DEFAULT_DISPLAY) {
                            updateMainDisplay();
                        } else {
                            updateRearDisplay(displayId);
                        }
                    }
                }, mHandler,
                // display state changes are an event type of their own
                DisplayManager.EVENT_TYPE_DISPLAY_ADDED | DisplayManager.EVENT_TYPE_DISPLAY_CHANGED
                        | DisplayManager.EVENT_TYPE_DISPLAY_REMOVED
                        | DisplayManager.EVENT_TYPE_DISPLAY_STATE);
        for (String key : new String[] {RearDisplay.SETTING_ENABLED,
                RearDisplay.SETTING_WAKE_DOUBLE_TAP, RearDisplay.SETTING_WAKE_FLIP}) {
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(key), false, o, UserHandle.USER_SYSTEM);
        }
        mHandler.post(() -> {
            updateMainDisplay();
            apply();
        });
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
                // otherwise the system moves them to the main display
                if (mEnabled) RearDisplay.closeApps(id);
                dm.disableConnectedDisplay(id);
                RearDisplay.setPanel(false);
            }
        }
        mEnabled = enabled;
        mFlip = enabled && RearDisplay.getInt(mContext, RearDisplay.SETTING_WAKE_FLIP, 1) == 1;
        updateSensors();
    }

    /**
     * The rear touch IC is powered only while the rear screen is on. A double-tap switch sent
     * while it is off is only noted by the driver and lost when the IC is powered off, so the
     * screen then sleeps with "power off IC" and a double tap does nothing. Re-send the switch
     * each time the rear screen turns on (after the touch resume work), so the next sleep puts
     * the IC into gesture mode.
     */
    private static final long DOUBLE_TAP_RESEND_MS = 1000;
    private boolean mRearOn;
    private final Runnable mResendDoubleTap = this::resendDoubleTap;

    private void resendDoubleTap() {
        if (mEnabled && mRearOn) {
            RearDisplay.setDoubleTap(
                    RearDisplay.getInt(mContext, RearDisplay.SETTING_WAKE_DOUBLE_TAP, 1) == 1);
        }
    }

    private void updateRearDisplay(int displayId) {
        if (displayId != RearDisplay.findId(mContext)) {
            return;
        }
        Display d = mContext.getSystemService(DisplayManager.class).getDisplay(displayId);
        final boolean on = d != null && d.getState() == Display.STATE_ON;
        if (on == mRearOn) {
            return;
        }
        mRearOn = on;
        mHandler.removeCallbacks(mResendDoubleTap);
        if (on) {
            mHandler.postDelayed(mResendDoubleTap, DOUBLE_TAP_RESEND_MS);
        }
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

    private void updateMainDisplay() {
        Display d = mContext.getSystemService(DisplayManager.class)
                .getDisplay(Display.DEFAULT_DISPLAY);
        final boolean on = d == null || d.getState() == Display.STATE_ON;
        if (on == mMainOn) {
            return;
        }
        mMainOn = on;
        Log.d(TAG, "main display " + (on ? "on" : "off"));
        if (on) {
            stopSampling();
        } else {
            // the main screen was just turned off: the phone may be put down right after
            sample(FLIP_GRACE_MS);
        }
        updateSensors();
    }

    /** Sample the accelerometer for ms with the main screen off, holding the CPU awake. */
    private void sample(long ms) {
        final long now = SystemClock.uptimeMillis();
        if (now + ms <= mSampleUntil) {
            return;
        }
        mSampleUntil = now + ms;
        mWakeLock.acquire(ms + 1000);
        mHandler.removeCallbacks(mSampleEnd);
        mHandler.postDelayed(mSampleEnd, ms);
    }

    private void stopSampling() {
        mHandler.removeCallbacks(mSampleEnd);
        mSampleUntil = 0;
    }

    /** Registers the sensors for the current state; the wakelock lives only while sampling. */
    private void updateSensors() {
        final boolean sampling = !mMainOn && SystemClock.uptimeMillis() < mSampleUntil;
        final boolean acc = mFlip && mAcc != null && (mMainOn || sampling);
        final boolean tilt = mFlip && mTilt != null && !mMainOn;
        if (acc != mAccRegistered) {
            if (acc) {
                // the pose may have changed unseen (suspended): count face down afresh
                mFaceDownSince = 0;
                mSensors.registerListener(this, mAcc, SensorManager.SENSOR_DELAY_NORMAL, mHandler);
            } else {
                mSensors.unregisterListener(this, mAcc);
            }
            mAccRegistered = acc;
        }
        if (tilt != mTiltRegistered) {
            if (tilt) {
                mSensors.registerListener(this, mTilt, SensorManager.SENSOR_DELAY_NORMAL, mHandler);
            } else {
                mSensors.unregisterListener(this, mTilt);
            }
            mTiltRegistered = tilt;
        }
        if (!acc || mMainOn) {
            if (!acc) stopSampling();
            if (mWakeLock.isHeld()) mWakeLock.release();
        }
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_TILT_DETECTOR) {
            if (!mMainOn && mFlip) {
                // the orientation changed: a new chance to lie face down; the sensor service
                // holds the CPU only until this returns
                Log.d(TAG, "tilt, sampling the accelerometer");
                mFaceDownHandled = false;
                sample(FLIP_TILT_MS);
                updateSensors();
            }
            return;
        }
        RearDisplay.notePose(e);
        final long now = SystemClock.uptimeMillis();
        if (e.values[2] > FACE_DOWN_Z) {
            if (mFaceDownSince != 0) Log.d(TAG, "face up, z=" + e.values[2]);
            mFaceDownSince = 0;
            mFaceDownHandled = false;
            return;
        }
        if (mFaceDownSince == 0) {
            Log.d(TAG, "face down, z=" + e.values[2] + (mFaceDownHandled ? " (handled)" : ""));
            mFaceDownSince = now;
        } else if (!mFaceDownHandled && now - mFaceDownSince >= FACE_DOWN_MS) {
            mFaceDownHandled = true;
            final int id = RearDisplay.findId(mContext);
            final boolean awake = id != Display.INVALID_DISPLAY && RearDisplay.isAwake(mContext, id);
            Log.i(TAG, "face down for " + FACE_DOWN_MS + " ms, rear display " + id
                    + (awake ? " already awake" : ", waking it"));
            if (id != Display.INVALID_DISPLAY && !awake) {
                RearDisplay.wake(mContext, id, "flip");
            }
            if (!mMainOn) {
                // done until the next tilt
                stopSampling();
                updateSensors();
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
