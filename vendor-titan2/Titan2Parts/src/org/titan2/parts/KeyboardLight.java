package org.titan2.parts;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.os.Handler;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

/**
 * Keyboard backlight (system_server, created by KeyHandler): on with key activity, off after
 * the timeout, manual or ambient-light level, indicator flashes.
 *
 * Everything runs on the key handler thread. The only output is the property sys.titan2.kbd_light,
 * which init writes to the keypad_led node (titan2-parts.rc): system_server may not write sysfs.
 */
final class KeyboardLight {
    private static final String TAG = "Titan2KbdLight";

    // settings and property names
    static final String SETTING_AUTO = "keyboard_led_auto_switch";
    static final String SETTING_TIMEOUT = "keyboard_brightness_timeout";
    static final String SETTING_TIMEOUT_BACKUP = "keyboard_brightness_timeout_backup";
    static final String SETTING_BRIGHTNESS = "keyboard_led_brightness";
    static final String PROP_SLIDE_ON = "persist.sys.keyboard_light_slide_on";
    static final String DEFAULT_TIMEOUT = "5000";
    static final String DEFAULT_BRIGHTNESS = "50";

    /** Output: init copies the value to /sys/devices/platform/keypad_led/keyled_brightness. */
    private static final String PROP_LEVEL = "sys.titan2.kbd_light";
    /** Lindroid keyboard state, set by the Linux keyboard service: caps|nav|alt|sym|off. */
    private static final String PROP_LINDROID_KBD = "sys.titan2.lindroid.kbd";
    private static final String PROP_FOCUS = "sys.titan2.focus";
    private static final String LINDROID_PKG = "org.lindroid.ui";

    private static final long SCREEN_ON_DELAY_MS = 500;
    private static final int SENSOR_PERIOD_US = 250_000;
    private static final long FLASH_PULSE_MS = 120;
    private static final int FLASH_MIN_LEVEL = 60;
    private static final long LINDROID_REPEAT_MS = 1500;
    /** sys.titan2.* properties set by native code give no Java change callback: poll them too. */
    private static final long LINDROID_POLL_MS = 500;
    private static final long INIT_RETRY_MS = 5000;

    private final Context mContext;
    private final Handler mHandler;
    private final ContentResolver mResolver;

    // ---- handler thread state ----
    private boolean mInitDone;
    private DisplayManager mDisplayManager;
    private SensorManager mSensorManager;
    private Sensor mLightSensor;
    private boolean mSensorRegistered;
    /** Last lux reading, -1 until the first one after registering. */
    private float mLux = -1;
    private int mDisplayState = Display.STATE_UNKNOWN;
    /** The light is logically on (after activity, until the timer or the screen turns it off). */
    private boolean mOn;
    private long mTimeoutMs;
    /** Last value written to the property, -1 before the first write. */
    private int mWritten = -1;
    private int mFlashLeft;
    private boolean mFlashDark;
    private boolean mFlashing;
    private String mLindroidState = "off";
    /** Devices named TitanKey (ids are not reused within a boot). */
    private final SparseBooleanArray mBuiltIn = new SparseBooleanArray();

    KeyboardLight(Context context, Handler handler) {
        mContext = context;
        mHandler = handler;
        mResolver = context.getContentResolver();
        // system_server is still starting when the key handler is created: set up later
        mHandler.post(mInit);
    }

    // ================= Setup =================

    private final Runnable mInit = new Runnable() {
        @Override
        public void run() {
            try {
                init();
                mInitDone = true;
            } catch (RuntimeException e) {
                Log.w(TAG, "init failed, retrying", e);
                mHandler.postDelayed(this, INIT_RETRY_MS);
            }
        }
    };

    private void init() {
        if (mDisplayManager == null) {
            mDisplayManager = mContext.getSystemService(DisplayManager.class);
            mDisplayManager.registerDisplayListener(mDisplayListener, mHandler);
        }
        if (mSensorManager == null) {
            mSensorManager = mContext.getSystemService(SensorManager.class);
            mLightSensor = mSensorManager != null
                    ? mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT) : null;
        }
        ContentObserver observer = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                try {
                    onSettingsChanged();
                } catch (RuntimeException e) {
                    Log.w(TAG, "settings", e);
                }
            }
        };
        for (String key : new String[] {SETTING_AUTO, SETTING_TIMEOUT, SETTING_BRIGHTNESS}) {
            mResolver.registerContentObserver(Settings.System.getUriFor(key), false, observer,
                    UserHandle.USER_SYSTEM);
        }
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                try {
                    turnOff();
                    updateSensor();
                } catch (RuntimeException e) {
                    Log.w(TAG, "shutdown", e);
                }
            }
        }, new IntentFilter(Intent.ACTION_SHUTDOWN), null, mHandler);
        // Lindroid keyboard state: properties set by native code (the Linux service) send no
        // change callback, so poll while the Lindroid window is focused and the display is not
        // off. The callback, key presses and screen on (re)start the poll.
        SystemProperties.addChangeCallback(() -> mHandler.post(mLindroidKick));
        mHandler.post(mLindroidKick);
        mTimeoutMs = readTimeout();
        mDisplayState = displayState();
        if (mDisplayState == Display.STATE_ON) {
            mHandler.postDelayed(mScreenOn, SCREEN_ON_DELAY_MS);
        }
        updateSensor();
    }

    private final DisplayManager.DisplayListener mDisplayListener =
            new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
        }

        @Override
        public void onDisplayRemoved(int displayId) {
        }

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId != Display.DEFAULT_DISPLAY) return;
            try {
                onDisplayStateChanged(displayState());
            } catch (RuntimeException e) {
                Log.w(TAG, "display", e);
            }
        }
    };

    private int displayState() {
        Display d = mDisplayManager != null
                ? mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY) : null;
        return d != null ? d.getState() : Display.STATE_UNKNOWN;
    }

    // ================= Screen on/off =================

    private void onDisplayStateChanged(int state) {
        final int old = mDisplayState;
        if (state == old) return;
        mDisplayState = state;
        if (state == Display.STATE_OFF) {
            mHandler.removeCallbacks(mScreenOn);
            turnOff();
        } else if (state == Display.STATE_ON) {
            mHandler.removeCallbacks(mScreenOn);
            mHandler.postDelayed(mScreenOn, SCREEN_ON_DELAY_MS);
        }
        updateSensor();
        mLindroidKick.run();
    }

    private final Runnable mScreenOn = () -> {
        try {
            if (displayState() == Display.STATE_ON && !rearUnlockWake()) {
                activity();
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "screen on", e);
        }
    };

    /** SystemUI (Titan2RearUnlockService): elapsedRealtime of an unlock on the rear screen
     * that also turned the main screen on. */
    private static final String SETTING_REAR_UNLOCK_AT = "titan2_rear_unlock_at";
    private static final String SETTING_REAR_UNLOCK_KBD = "titan2_rear_unlock_kbd_light";
    /** The main screen turned on this soon after a rear unlock: that unlock turned it on. */
    private static final long REAR_UNLOCK_WAKE_MS = 3000;

    /**
     * The main screen was turned on by an unlock on the rear screen and the keyboard light is
     * not wanted for that (titan2_rear_unlock_kbd_light, default off). A key press still lights it.
     */
    private boolean rearUnlockWake() {
        if (getSetting(SETTING_REAR_UNLOCK_KBD, "0").trim().equals("1")) return false;
        try {
            final long at = Long.parseLong(getSetting(SETTING_REAR_UNLOCK_AT, "0").trim());
            final long age = SystemClock.elapsedRealtime() - at;
            return at > 0 && age >= 0 && age < REAR_UNLOCK_WAKE_MS;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // ================= Activity =================

    /** Every key ACTION_DOWN, from KeyHandler (input thread: returns at once). */
    void onKey(KeyEvent event) {
        if (event.getRepeatCount() != 0) return;
        final int deviceId = event.getDeviceId();
        if (deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD || deviceId <= 0) return;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_MACRO_1:
            case KeyEvent.KEYCODE_MACRO_2:
                return;
            default:
                break;
        }
        final int scan = event.getScanCode();
        if (scan == KeyConfig.SCANCODES[KeyConfig.SLOT_FUNC1]
                || scan == KeyConfig.SCANCODES[KeyConfig.SLOT_FUNC2]) {
            return;
        }
        mHandler.post(() -> {
            try {
                if (isBuiltIn(deviceId)) activity();
                mLindroidKick.run();
            } catch (RuntimeException e) {
                Log.w(TAG, "key", e);
            }
        });
    }

    /** Wide horizontal swipe on the keyboard touchpad (TouchpadController); any thread. */
    void onTouchpadSwipe() {
        mHandler.post(() -> {
            try {
                if (SystemProperties.get(PROP_SLIDE_ON, "true").contains("true")) activity();
            } catch (RuntimeException e) {
                Log.w(TAG, "swipe", e);
            }
        });
    }

    private boolean isBuiltIn(int deviceId) {
        int i = mBuiltIn.indexOfKey(deviceId);
        if (i >= 0) return mBuiltIn.valueAt(i);
        InputDevice dev = mContext.getSystemService(InputManager.class).getInputDevice(deviceId);
        boolean titan = dev != null && !dev.isVirtual()
                && KeyConfig.KEYBOARD_DEVICE.equals(dev.getName());
        if (dev != null) mBuiltIn.put(deviceId, titan);
        return titan;
    }

    private void activity() {
        if (!mInitDone || displayState() == Display.STATE_OFF) return;
        mTimeoutMs = readTimeout();
        mHandler.removeCallbacks(mTimeout);
        if (mTimeoutMs == 0) {
            turnOff();
            return;
        }
        mOn = true;
        write(targetLevel());
        if (mTimeoutMs > 0) {
            mHandler.postDelayed(mTimeout, mTimeoutMs);
        }
    }

    private final Runnable mTimeout = () -> {
        try {
            turnOff();
        } catch (RuntimeException e) {
            Log.w(TAG, "timeout", e);
        }
    };

    private void turnOff() {
        mHandler.removeCallbacks(mTimeout);
        mOn = false;
        write(0);
    }

    // ================= Settings and level =================

    private String getSetting(String key, String def) {
        String v = Settings.System.getStringForUser(mResolver, key, UserHandle.USER_SYSTEM);
        return v != null ? v : def;
    }

    /** ms; 0 = always off; -1 = no timer. */
    private long readTimeout() {
        try {
            long t = Long.parseLong(getSetting(SETTING_TIMEOUT, DEFAULT_TIMEOUT).trim());
            return t < 0 ? -1 : t;
        } catch (NumberFormatException e) {
            return Long.parseLong(DEFAULT_TIMEOUT);
        }
    }

    private boolean autoMode() {
        return "1".equals(getSetting(SETTING_AUTO, "0").trim());
    }

    private int manualLevel() {
        try {
            int v = Integer.parseInt(getSetting(SETTING_BRIGHTNESS, DEFAULT_BRIGHTNESS).trim());
            return Math.max(0, Math.min(100, v));
        } catch (NumberFormatException e) {
            return Integer.parseInt(DEFAULT_BRIGHTNESS);
        }
    }

    private int targetLevel() {
        if (autoMode() && mLux >= 0) {
            int level = ((int) Math.floor(mLux)) / 10;
            return Math.max(1, Math.min(100, level));
        }
        return manualLevel();
    }

    private void onSettingsChanged() {
        updateSensor();
        if (!mOn) {
            mTimeoutMs = readTimeout();
            return;
        }
        final long timeout = readTimeout();
        if (timeout == 0) {
            mTimeoutMs = 0;
            turnOff();
            return;
        }
        write(targetLevel());
        if (timeout != mTimeoutMs) {
            mTimeoutMs = timeout;
            mHandler.removeCallbacks(mTimeout);
            if (timeout > 0) mHandler.postDelayed(mTimeout, timeout);
        }
    }

    /** The sensor listens only in automatic mode with the screen on. */
    private void updateSensor() {
        final boolean want = mLightSensor != null && autoMode()
                && mDisplayState == Display.STATE_ON;
        if (want == mSensorRegistered) return;
        if (want) {
            mSensorRegistered = mSensorManager.registerListener(mSensorListener, mLightSensor,
                    SENSOR_PERIOD_US, mHandler);
        } else {
            mSensorManager.unregisterListener(mSensorListener);
            mSensorRegistered = false;
            mLux = -1;
        }
    }

    private final SensorEventListener mSensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            final float lux = event.values[0];
            if (lux == mLux) return;
            mLux = lux;
            try {
                if (mOn && autoMode()) write(targetLevel());
            } catch (RuntimeException e) {
                Log.w(TAG, "sensor", e);
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    /** Level the node should have now (outside of a flash). */
    private int currentLevel() {
        return mOn ? targetLevel() : 0;
    }

    private void write(int level) {
        if (mFlashing) {
            // the flash sequence restores currentLevel() at its end
            return;
        }
        if (level == mWritten) return;
        writeRaw(level);
    }

    private void writeRaw(int level) {
        mWritten = level;
        SystemProperties.set(PROP_LEVEL, Integer.toString(level));
    }

    // ================= Indicator flashes =================

    /** n short flashes; handler thread (KeyHandler posts it, or calls it from its own runnables). */
    void flash(int times) {
        if (times <= 0 || !mInitDone || displayState() == Display.STATE_OFF) return;
        mHandler.removeCallbacks(mFlashStep);
        // "on" means the node level is above 0: a light on at level 0 is dark and flashes bright
        final int base = currentLevel();
        mFlashDark = base > 0;
        mFlashLeft = times * 2;
        mFlashing = true;
        mFlashStep.run();
    }

    private final Runnable mFlashStep = new Runnable() {
        @Override
        public void run() {
            try {
                if (mFlashLeft <= 0 || displayState() == Display.STATE_OFF) {
                    mFlashing = false;
                    mFlashLeft = 0;
                    writeRaw(displayState() == Display.STATE_OFF ? 0 : currentLevel());
                    return;
                }
                final boolean pulse = (mFlashLeft % 2) == 0;
                final int level = pulse
                        ? (mFlashDark ? 0 : Math.max(targetLevel(), FLASH_MIN_LEVEL))
                        : currentLevel();
                mFlashLeft--;
                writeRaw(level);
                mHandler.postDelayed(this, FLASH_PULSE_MS);
            } catch (RuntimeException e) {
                mFlashing = false;
                Log.w(TAG, "flash", e);
            }
        }
    };

    // ================= Lindroid keyboard state =================

    private final Runnable mLindroidPoll = new Runnable() {
        @Override
        public void run() {
            mLindroidPolling = false;
            mLindroidCheck.run();
            if (lindroidFocused() && mDisplayState != Display.STATE_OFF) {
                mLindroidPolling = true;
                mHandler.postDelayed(this, LINDROID_POLL_MS);
            }
        }
    };

    private boolean mLindroidPolling;

    /** Check now and keep polling while it is needed (handler thread). */
    private final Runnable mLindroidKick = new Runnable() {
        @Override
        public void run() {
            if (mLindroidPolling) return;
            mLindroidPoll.run();
        }
    };

    private static boolean lindroidFocused() {
        return SystemProperties.get(PROP_FOCUS, "").startsWith(LINDROID_PKG);
    }

    private final Runnable mLindroidCheck = new Runnable() {
        @Override
        public void run() {
            try {
                String state = SystemProperties.get(PROP_LINDROID_KBD, "off");
                if (state.isEmpty() || !lindroidFocused()) {
                    state = "off";
                }
                if (state.equals(mLindroidState)) return;
                mLindroidState = state;
                mHandler.removeCallbacks(mLindroidRepeat);
                if (!"off".equals(state)) {
                    flash(2);
                    mHandler.postDelayed(mLindroidRepeat, LINDROID_REPEAT_MS);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "lindroid", e);
            }
        }
    };

    private final Runnable mLindroidRepeat = new Runnable() {
        @Override
        public void run() {
            // mLindroidState is kept current by the change callback and the poll
            if ("off".equals(mLindroidState)) return;
            flash(1);
            mHandler.postDelayed(this, LINDROID_REPEAT_MS);
        }
    };
}
