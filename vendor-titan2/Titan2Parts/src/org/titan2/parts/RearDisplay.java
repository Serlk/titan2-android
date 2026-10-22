package org.titan2.parts;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.os.UserHandle;
import android.util.Log;
import android.view.Display;

import vendor.mediatek.hardware.agolddaemon.IAgoldDaemon;

/**
 * Titan 2 rear screen (410x502, panel FLS_AC213G992_FLIP, touch sub_touch). Rear panel
 * control goes through the vendor agolddaemon (-> /dev/agold-sub-panel, /dev/touch):
 * - SendMessageToIoctl(700, 0, 1|0, 0): allow the panel backlight and touch power (without it
 *   the driver turns any brightness into 0, and the touch is off entirely);
 * - SendMessageToIoctl(100, 0, v, v): double tap on the dark rear screen
 *   (the touch in gesture mode sends KEY_TV, scancode 377);
 * - the display itself is its own power group (frameworks_base: ro.titan2.rear_display_port), put to sleep
 *   and woken with PowerManager.goToSleep/wakeUp with a displayId.
 * The class is used in system_server too (KeyHandler), hence no androidx.
 */
final class RearDisplay {
    private static final String TAG = "Titan2Rear";
    static final int WIDTH = 410, HEIGHT = 502;
    static final String TOUCH_DEVICE = "sub_touch";
    /** KEY_TV from the rear screen touch on a double tap in gesture mode. */
    static final int SCAN_DOUBLE_TAP = 377;

    // Settings.System (written by the RearShell shell and Titan2Parts)
    static final String SETTING_ENABLED = "titan2_rear_enabled";
    static final String SETTING_WAKE_DOUBLE_TAP = "titan2_rear_wake_double_tap";
    static final String SETTING_WAKE_DOUBLE_TAP_POSE = "titan2_rear_wake_double_tap_pose";
    static final String SETTING_WAKE_FLIP = "titan2_rear_wake_flip";
    /**
     * The rear screen faces up: Z acceleration (axis out of the main screen) is at most this,
     * m/s^2, i.e. the back is tilted to the sky by at least ~18 deg. Upright (pocket) Z ~ 0.
     */
    private static final float REAR_UP_Z = -3f;
    /** First wait for a fresh sample; the first sample after a resume can take longer. */
    private static final long POSE_TIMEOUT_MS = 300;
    /** Second, final wait (after POSE_TIMEOUT_MS) when there is no recent cached sample. */
    private static final long POSE_TIMEOUT_LONG_MS = 1500;
    /** A cached sample at most this old is used instead of waiting. */
    private static final long POSE_CACHE_MS = 2000;

    /** Last accelerometer Z in this process and its time (elapsedRealtimeNanos base). */
    private static final Object sPoseLock = new Object();
    private static float sPoseZ;
    private static long sPoseNanos;

    private static final int IOCTL_SUB_PANEL = 700;
    private static final int IOCTL_TOUCH_GESTURE = 100;

    private RearDisplay() {}

    /** Logical id of the rear display or Display.INVALID_DISPLAY. */
    static int findId(Context context) {
        DisplayManager dm = context.getSystemService(DisplayManager.class);
        for (Display d : dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_ALL_INCLUDING_DISABLED)) {
            if (d.getDisplayId() == Display.DEFAULT_DISPLAY) {
                continue;
            }
            Display.Mode m = d.getMode();
            if (m.getPhysicalWidth() == WIDTH && m.getPhysicalHeight() == HEIGHT) {
                return d.getDisplayId();
            }
        }
        return Display.INVALID_DISPLAY;
    }

    static boolean isEnabled(Context context) {
        return getInt(context, SETTING_ENABLED, 0) == 1;
    }

    /** Writes titan2_rear_enabled; every change is logged with the caller. */
    static void setEnabled(Context context, boolean on, String caller) {
        final int v = on ? 1 : 0;
        final int old = getInt(context, SETTING_ENABLED, 0);
        Log.i(TAG, SETTING_ENABLED + " " + old + " -> " + v + " by " + caller
                + " (" + android.os.Debug.getCallers(4) + ")");
        Settings.System.putIntForUser(context.getContentResolver(), SETTING_ENABLED, v,
                UserHandle.USER_SYSTEM);
    }

    static int getInt(Context context, String key, int def) {
        return Settings.System.getIntForUser(context.getContentResolver(), key, def,
                UserHandle.USER_SYSTEM);
    }

    /** Rear screen backlight and touch power (panel driver flag). */
    static boolean setPanel(boolean on) {
        return ioctl(IOCTL_SUB_PANEL, 0, on ? 1 : 0, 0);
    }

    /** Double tap on the dark rear screen. */
    static boolean setDoubleTap(boolean on) {
        int v = on ? 1 : 0;
        return ioctl(IOCTL_TOUCH_GESTURE, 0, v, v);
    }

    private static boolean ioctl(int type, int a, int b, int c) {
        final long token = Binder.clearCallingIdentity();
        try {
            IBinder b0 = ServiceManager.waitForDeclaredService(IAgoldDaemon.DESCRIPTOR + "/default");
            if (b0 == null) {
                Log.w(TAG, "agolddaemon not found");
                return false;
            }
            IAgoldDaemon d = IAgoldDaemon.Stub.asInterface(Binder.allowBlocking(b0));
            d.SendMessageToIoctl(type, a, b, c);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "agolddaemon " + type, e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    static void wake(Context context, int displayId, String why) {
        context.getSystemService(PowerManager.class).wakeUp(SystemClock.uptimeMillis(),
                PowerManager.WAKE_REASON_GESTURE, "titan2:rear-" + why, displayId);
    }

    /** Remember an accelerometer sample (any listener in this process). */
    static void notePose(SensorEvent e) {
        synchronized (sPoseLock) {
            sPoseZ = e.values[2];
            sPoseNanos = e.timestamp;
        }
    }

    /** Z of a cached sample at most maxAgeMs old, or NaN. */
    private static float cachedZ(long maxAgeMs) {
        synchronized (sPoseLock) {
            if (sPoseNanos == 0) {
                return Float.NaN;
            }
            long age = (SystemClock.elapsedRealtimeNanos() - sPoseNanos) / 1_000_000;
            return age >= 0 && age <= maxAgeMs ? sPoseZ : Float.NaN;
        }
    }

    private static void wakeIfUp(Context context, int displayId, String why, float z,
            String source) {
        if (z <= REAR_UP_Z) {
            wake(context, displayId, why);
        } else {
            Log.d(TAG, why + " ignored, z=" + z + " (" + source + ")");
        }
    }

    /**
     * Wakes the rear screen if it faces up. Waits for one fresh accelerometer sample; if none
     * arrives within POSE_TIMEOUT_MS, a cached sample up to POSE_CACHE_MS old decides, otherwise
     * it waits up to POSE_TIMEOUT_LONG_MS more. Without the sensor, or with no sample at all,
     * it wakes: the pose check only filters pocket taps and must not eat a real double tap.
     */
    static void wakeIfFacingUp(Context context, int displayId, String why, Handler handler) {
        final SensorManager sm = context.getSystemService(SensorManager.class);
        final Sensor acc = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (acc == null) {
            Log.w(TAG, why + ": no accelerometer, waking without the pose check");
            wake(context, displayId, why);
            return;
        }
        // one decision per tap: the sample or the timeout, both on the handler thread
        final boolean[] done = {false};
        final SensorEventListener l = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent e) {
                notePose(e);
                if (done[0]) {
                    return;
                }
                done[0] = true;
                sm.unregisterListener(this);
                handler.removeCallbacksAndMessages(this);
                wakeIfUp(context, displayId, why, e.values[2], "sensor");
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
            }
        };
        if (!sm.registerListener(l, acc, SensorManager.SENSOR_DELAY_GAME, handler)) {
            Log.w(TAG, why + ": accelerometer registration failed");
            float z = cachedZ(POSE_CACHE_MS);
            if (Float.isNaN(z)) {
                wake(context, displayId, why);
            } else {
                wakeIfUp(context, displayId, why, z, "cached");
            }
            return;
        }
        final long start = SystemClock.uptimeMillis();
        handler.postAtTime(new Runnable() {
            private boolean mLong;

            @Override
            public void run() {
                if (done[0]) {
                    return;
                }
                if (!mLong) {
                    float z = cachedZ(POSE_CACHE_MS);
                    if (!Float.isNaN(z)) {
                        done[0] = true;
                        sm.unregisterListener(l);
                        wakeIfUp(context, displayId, why, z, "cached");
                        return;
                    }
                    Log.i(TAG, why + ": no accelerometer sample in " + POSE_TIMEOUT_MS
                            + " ms, waiting longer");
                    mLong = true;
                    handler.postAtTime(this, l, start + POSE_TIMEOUT_MS + POSE_TIMEOUT_LONG_MS);
                    return;
                }
                done[0] = true;
                sm.unregisterListener(l);
                Log.w(TAG, why + ": no accelerometer sample in "
                        + (POSE_TIMEOUT_MS + POSE_TIMEOUT_LONG_MS) + " ms, waking anyway");
                wake(context, displayId, why);
            }
        }, l, start + POSE_TIMEOUT_MS);
    }

    static void sleep(Context context, int displayId) {
        context.getSystemService(PowerManager.class).goToSleep(displayId,
                SystemClock.uptimeMillis(), PowerManager.GO_TO_SLEEP_REASON_APPLICATION,
                PowerManager.GO_TO_SLEEP_FLAG_NO_DOZE);
    }

    static boolean isAwake(Context context, int displayId) {
        return context.getSystemService(PowerManager.class).isInteractive(displayId);
    }
}
