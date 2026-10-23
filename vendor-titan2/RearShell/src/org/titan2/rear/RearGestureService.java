package org.titan2.rear;

import android.app.Service;
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.UserHandle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.MotionEvent;

import org.titan2.rear.common.RearKeys;

/**
 * Rear display navigation gestures over any app (Android gesture navigation
 * does not work on secondary displays). The InputManager.monitorGestureInput input monitor
 * (like EdgeBackGestureHandler in SystemUI) sees all touches of the display; a recognized gesture
 * is taken from the app with pilferPointers.
 *   swipe up from the bottom edge: the gesture_bottom action ("Home" by default);
 *   swipe up and hold: gesture_bottom_hold ("Recents");
 *   swipe inwards from the left / right edge: gesture_left / gesture_right ("Back").
 * Actions, sensitivity and enabling come from the Titan2Parts settings (Cfg). The service lives
 * in the persistent process; the monitor is recreated when the display or settings appear/change.
 * With the system decorations on the rear display (titan2_rear_system_decors) SystemUI's own
 * back gesture handles the side edges there, so the side zones stand down (otherwise one swipe
 * would fire twice); the bottom zone stays (there is no system home gesture on that display).
 */
public class RearGestureService extends Service
        implements DisplayManager.DisplayListener, Cfg.Listener {
    private static final String TAG = "RearShell";
    private static final long HOLD_MS = 450;
    /** Retry of a failed attach: the window manager adds the display after the display event. */
    private static final long RETRY_MS = 250;
    private static final int RETRY_MAX = 20;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private DisplayManager mDm;
    private InputManager mIm;
    private Cfg mCfg;
    private String mSens;
    private boolean mEnabled;
    /** system decorations (and the system back gesture) are on the rear display */
    private boolean mSystemDecors;
    private InputMonitor mMonitor;
    private Receiver mReceiver;
    private int mDisplayId = Display.INVALID_DISPLAY;
    private int mRetries;
    private final Runnable mRetry = this::attach;

    // thresholds in pixels (from dp and sensitivity)
    private float mEdge, mTrigger, mHoldSlop;
    private int mWidth, mHeight;

    // current touch state
    private static final int Z_NONE = 0, Z_BOTTOM = 1, Z_LEFT = 2, Z_RIGHT = 3;
    private int mZone;
    private float mDownX, mDownY, mAnchorX, mAnchorY;
    private boolean mRecognized, mFired, mCancelled;

    private final ContentObserver mDecorsObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            mSystemDecors = readSystemDecors();
        }
    };

    private final Runnable mHold = () -> {
        if (mRecognized && !mFired && mZone == Z_BOTTOM) {
            mFired = true;
            fire(RearKeys.GESTURE_BOTTOM_HOLD);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mDm = getSystemService(DisplayManager.class);
        mIm = getSystemService(InputManager.class);
        mCfg = Cfg.get(this);
        mCfg.addListener(this);
        mDm.registerDisplayListener(this, mHandler);
        getContentResolver().registerContentObserver(
                Settings.System.getUriFor(RearKeys.SYS_SYSTEM_DECORS), false, mDecorsObserver);
        mSystemDecors = readSystemDecors();
        attach();
    }

    private boolean readSystemDecors() {
        // the system part reads it for USER_SYSTEM too (Titan2RearDecor)
        return Settings.System.getIntForUser(getContentResolver(), RearKeys.SYS_SYSTEM_DECORS, 0,
                UserHandle.USER_SYSTEM) == 1;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        attach();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        mDm.unregisterDisplayListener(this);
        getContentResolver().unregisterContentObserver(mDecorsObserver);
        mCfg.removeListener(this);
        detach();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Attach the monitor to the rear display (or detach if there is no display, it is disabled
     * or gestures are off). A monitor belongs to the window manager's display content: one made
     * for a disabled display, or while it is being removed, never sees a touch, so a disabled
     * display gets none, and a display added again always gets a new one (onDisplayAdded).
     */
    private void attach() {
        mHandler.removeCallbacks(mRetry);
        Display d = RearDisplays.find(this);
        boolean enabled = mCfg.bool(RearKeys.GESTURES);
        mEnabled = enabled;
        mSens = mCfg.get(RearKeys.GESTURE_SENS);
        int id = d != null && enabled && RearDisplays.isEnabled(this, d.getDisplayId())
                ? d.getDisplayId() : Display.INVALID_DISPLAY;
        if (d != null) {
            Point p = new Point();
            d.getRealSize(p);
            mWidth = p.x;
            mHeight = p.y;
            float dp = densityOf(d);
            String s = mSens;
            float k = "high".equals(s) ? 0.7f : "low".equals(s) ? 1.4f : 1f;
            mEdge = 20 * dp / k;
            mTrigger = 36 * dp * k;
            mHoldSlop = 10 * dp;
        }
        if (id == mDisplayId && (mMonitor != null || id == Display.INVALID_DISPLAY)) {
            return;
        }
        detach();
        mDisplayId = id;
        if (id == Display.INVALID_DISPLAY) {
            return;
        }
        try {
            mMonitor = mIm.monitorGestureInput("titan2-rear-gestures", id);
            mReceiver = new Receiver(mMonitor);
            mRetries = 0;
            Log.i(TAG, "gesture monitor on display " + id);
        } catch (RuntimeException e) {
            mMonitor = null;
            mDisplayId = Display.INVALID_DISPLAY;
            // the display event can come before the window manager has the display
            if (mRetries++ < RETRY_MAX) {
                Log.w(TAG, "monitorGestureInput(" + id + ") failed, retrying: " + e);
                mHandler.postDelayed(mRetry, RETRY_MS);
            } else {
                Log.e(TAG, "monitorGestureInput(" + id + ") failed", e);
            }
        }
    }

    private static float densityOf(Display d) {
        android.util.DisplayMetrics m = new android.util.DisplayMetrics();
        d.getRealMetrics(m);
        return m.density;
    }

    private void detach() {
        mHandler.removeCallbacks(mHold);
        mHandler.removeCallbacks(mRetry);
        if (mReceiver != null) {
            mReceiver.dispose();
            mReceiver = null;
        }
        if (mMonitor != null) {
            mMonitor.dispose();
            mMonitor = null;
        }
        mDisplayId = Display.INVALID_DISPLAY;
    }

    @Override
    public void onDisplayAdded(int displayId) {
        // enabled again (the id may stay the same): the old monitor is gone with the old
        // display content even though its channel still looks fine
        if (displayId == mDisplayId || displayId == RearDisplays.findId(this)) {
            detach();
            mRetries = 0;
        }
        attach();
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        if (displayId == mDisplayId) detach();
        attach();
    }

    @Override
    public void onDisplayChanged(int displayId) {
        if (displayId != Display.DEFAULT_DISPLAY) attach();
    }

    @Override
    public void onConfigChanged(boolean themeChanged) {
        if (mEnabled != mCfg.bool(RearKeys.GESTURES)
                || !mCfg.get(RearKeys.GESTURE_SENS).equals(mSens)) {
            detach();
            attach();
        }
    }

    private void fire(String key) {
        String action = mCfg.get(key);
        int id = mDisplayId;
        if (id != Display.INVALID_DISPLAY && !RearKeys.ACT_NONE.equals(action)) {
            Log.d(TAG, "gesture " + key + ": " + action + " on display " + id);
            if (mCfg.bool(RearKeys.GESTURE_HAPTIC)) haptic();
            RearActions.run(this, action, id);
        }
    }

    private void haptic() {
        Vibrator v = getSystemService(Vibrator.class);
        if (v != null && v.hasVibrator()) {
            v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));
        }
    }

    private void pilfer() {
        if (mMonitor == null) return;
        try {
            mIm.pilferPointers(mMonitor.getInputChannel().getToken());
        } catch (RuntimeException e) {
            Log.w(TAG, "pilferPointers: " + e);
        }
    }

    private boolean hasAction(String key) {
        return !RearKeys.ACT_NONE.equals(mCfg.get(key));
    }

    private void onMotion(MotionEvent ev) {
        float x = ev.getX(), y = ev.getY();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mHandler.removeCallbacks(mHold);
                mDownX = mAnchorX = x;
                mDownY = mAnchorY = y;
                mRecognized = mFired = mCancelled = false;
                if (y > mHeight - mEdge && (hasAction(RearKeys.GESTURE_BOTTOM)
                        || hasAction(RearKeys.GESTURE_BOTTOM_HOLD))) {
                    mZone = Z_BOTTOM;
                } else if (x < mEdge && !mSystemDecors && hasAction(RearKeys.GESTURE_LEFT)
                        && !RearHomeActivity.takesSideSwipes()) {
                    mZone = Z_LEFT;
                } else if (x > mWidth - mEdge && !mSystemDecors
                        && hasAction(RearKeys.GESTURE_RIGHT)
                        && !RearHomeActivity.takesSideSwipes()) {
                    // on the home pages a side swipe turns the page, it is not "Back"
                    mZone = Z_RIGHT;
                } else {
                    mZone = Z_NONE;
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                // a second finger before recognition: not a navigation gesture
                if (!mRecognized) mZone = Z_NONE;
                break;
            case MotionEvent.ACTION_MOVE:
                if (mZone == Z_NONE || mCancelled) break;
                if (!mRecognized) {
                    float dx = x - mDownX, dy = y - mDownY;
                    if (mZone == Z_BOTTOM) {
                        if (-dy > mTrigger && -dy > Math.abs(dx)) recognize(x, y);
                        else if (Math.abs(dx) > mTrigger) mCancelled = true;
                    } else {
                        float in = mZone == Z_LEFT ? dx : -dx;
                        if (in > mTrigger && in > Math.abs(dy)) recognize(x, y);
                        else if (Math.abs(dy) > mTrigger) mCancelled = true;
                    }
                } else if (mZone == Z_BOTTOM && !mFired
                        && Math.hypot(x - mAnchorX, y - mAnchorY) > mHoldSlop) {
                    // the finger is still moving: restart the hold countdown
                    mAnchorX = x;
                    mAnchorY = y;
                    mHandler.removeCallbacks(mHold);
                    mHandler.postDelayed(mHold, HOLD_MS);
                }
                break;
            case MotionEvent.ACTION_UP:
                mHandler.removeCallbacks(mHold);
                if (mRecognized && !mFired) {
                    mFired = true;
                    if (mZone == Z_BOTTOM) fire(RearKeys.GESTURE_BOTTOM);
                    else fire(mZone == Z_LEFT ? RearKeys.GESTURE_LEFT : RearKeys.GESTURE_RIGHT);
                }
                mZone = Z_NONE;
                break;
            case MotionEvent.ACTION_CANCEL:
                mHandler.removeCallbacks(mHold);
                mZone = Z_NONE;
                break;
            default:
                break;
        }
    }

    private void recognize(float x, float y) {
        mRecognized = true;
        mAnchorX = x;
        mAnchorY = y;
        pilfer();
        if (mZone == Z_BOTTOM && hasAction(RearKeys.GESTURE_BOTTOM_HOLD)) {
            mHandler.postDelayed(mHold, HOLD_MS);
        }
    }

    private class Receiver extends InputEventReceiver {
        Receiver(InputMonitor m) {
            super(m.getInputChannel(), Looper.getMainLooper());
        }

        @Override
        public void onInputEvent(InputEvent event) {
            try {
                if (event instanceof MotionEvent
                        && ((MotionEvent) event).isFromSource(
                                android.view.InputDevice.SOURCE_TOUCHSCREEN)) {
                    onMotion((MotionEvent) event);
                }
            } finally {
                finishInputEvent(event, false);
            }
        }
    }
}
