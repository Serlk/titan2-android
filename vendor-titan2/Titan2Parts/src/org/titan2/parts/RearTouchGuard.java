package org.titan2.parts;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * The rear touch is off while the phone is being used from the front (system_server, created
 * by KeyHandler): while a finger is on the main screen and AFTER_TOUCH_MS after it, and
 * AFTER_KEY_MS after a key or the keyboard touchpad. The fingers holding the phone rest on the
 * rear screen then; this way they neither touch the rear apps nor double-tap it awake.
 * The sub_touch device is disabled and enabled again through InputManager (an InputReader
 * switch: a touch in progress on it is cancelled). Setting titan2_rear_touch_guard (default on).
 */
final class RearTouchGuard {
    private static final String TAG = "Titan2RearGuard";
    static final String SETTING = "titan2_rear_touch_guard";
    private static final long AFTER_TOUCH_MS = 500;
    private static final long AFTER_KEY_MS = 800;
    private static final long INIT_RETRY_MS = 5000;

    private final Context mContext;
    private final Handler mHandler;
    private final ContentResolver mResolver;
    private InputManager mInputManager;
    private InputMonitor mMonitor;
    private InputEventReceiver mReceiver;

    private volatile boolean mEnabled = true;
    /** A finger is on the main screen. */
    private volatile boolean mMainDown;
    /** The rear touch stays off until this uptime (after the finger / key). */
    private volatile long mBusyUntil;
    /** sub_touch is disabled by us. */
    private boolean mDisabled;
    private int mRearId = -1;

    private final Runnable mCheck = this::update;

    RearTouchGuard(Context context, Handler handler) {
        mContext = context;
        mHandler = handler;
        mResolver = context.getContentResolver();
        // system_server is still starting when the key handler is created
        mHandler.post(mInit);
    }

    private final Runnable mInit = new Runnable() {
        @Override
        public void run() {
            try {
                init();
            } catch (RuntimeException e) {
                Log.w(TAG, "init failed, retrying", e);
                mHandler.postDelayed(this, INIT_RETRY_MS);
            }
        }
    };

    private void init() {
        mInputManager = mContext.getSystemService(InputManager.class);
        mResolver.registerContentObserver(Settings.System.getUriFor(SETTING), false,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        readSetting();
                        update();
                    }
                }, UserHandle.USER_SYSTEM);
        mInputManager.registerInputDeviceListener(new InputManager.InputDeviceListener() {
            @Override
            public void onInputDeviceAdded(int id) {
                mRearId = -1;
            }

            @Override
            public void onInputDeviceRemoved(int id) {
                if (id == mRearId) {
                    mRearId = -1;
                    mDisabled = false;
                }
            }

            @Override
            public void onInputDeviceChanged(int id) {
            }
        }, mHandler);
        readSetting();
        // a previous system_server may have left it disabled
        int id = rearId();
        if (id >= 0) mInputManager.enableInputDevice(id);
        mMonitor = mInputManager.monitorGestureInput(TAG, Display.DEFAULT_DISPLAY);
        mReceiver = new InputEventReceiver(mMonitor.getInputChannel(), mHandler.getLooper()) {
            @Override
            public void onInputEvent(InputEvent event) {
                try {
                    if (event instanceof MotionEvent) onMainTouch((MotionEvent) event);
                } catch (RuntimeException e) {
                    Log.w(TAG, "main touch", e);
                } finally {
                    finishInputEvent(event, false);
                }
            }
        };
    }

    private void readSetting() {
        mEnabled = Settings.System.getIntForUser(mResolver, SETTING, 1,
                UserHandle.USER_SYSTEM) != 0;
    }

    private int rearId() {
        if (mRearId >= 0) return mRearId;
        for (int id : mInputManager.getInputDeviceIds()) {
            InputDevice d = mInputManager.getInputDevice(id);
            if (d != null && RearDisplay.TOUCH_DEVICE.equals(d.getName())) {
                mRearId = id;
                break;
            }
        }
        return mRearId;
    }

    /** A real (not injected) device, and not the rear touch itself. */
    private boolean front(int deviceId) {
        if (deviceId <= 0 || deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD) return false;
        if (deviceId == rearId()) return false;
        InputDevice d = InputDevice.getDevice(deviceId);
        return d != null && !d.isVirtual();
    }

    private void onMainTouch(MotionEvent ev) {
        if (!ev.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) || !front(ev.getDeviceId())) return;
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mMainDown = true;
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            mMainDown = false;
            busy(AFTER_TOUCH_MS);
            return;
        } else {
            return;
        }
        update();
    }

    /** Every key ACTION_DOWN, from KeyHandler (any thread). */
    void onKey(KeyEvent event) {
        final int deviceId = event.getDeviceId();
        mHandler.post(() -> {
            if (front(deviceId)) busy(AFTER_KEY_MS);
        });
    }

    /** A keyboard touchpad gesture (TouchpadController); any thread. */
    void onTouchpad() {
        mHandler.post(() -> busy(AFTER_KEY_MS));
    }

    /** The front is in use: a rear double tap now is a holding finger. */
    boolean frontBusy() {
        return mEnabled && (mMainDown || SystemClock.uptimeMillis() < mBusyUntil);
    }

    private void busy(long ms) {
        mBusyUntil = Math.max(mBusyUntil, SystemClock.uptimeMillis() + ms);
        update();
    }

    private void update() {
        mHandler.removeCallbacks(mCheck);
        if (mInputManager == null) return;
        boolean off = frontBusy();
        int id = rearId();
        if (id < 0) return;
        if (off != mDisabled) {
            try {
                if (off) {
                    mInputManager.disableInputDevice(id);
                } else {
                    mInputManager.enableInputDevice(id);
                }
                mDisabled = off;
            } catch (RuntimeException e) {
                Log.w(TAG, (off ? "disable" : "enable") + " rear touch", e);
            }
        }
        if (off && !mMainDown) {
            mHandler.postAtTime(mCheck, mBusyUntil);
        }
    }
}
