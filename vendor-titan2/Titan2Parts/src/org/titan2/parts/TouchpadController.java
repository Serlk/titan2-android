package org.titan2.parts;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Map;

/**
 * Keyboard touchpad (system_server, created by KeyHandler).
 *
 * A reader thread reads the evdev node of the "touchPad" device (no grab; the framework ignores
 * the device through its idc) and turns it into frames: touch-down, movement report, lift. All
 * gesture handling runs on one handler thread: cursor assistant, finger mode, wheel
 * scrolling and fling, mouse modes, swipe for the keyboard light, double-tap wake
 * and the Lindroid hand-over.
 */
final class TouchpadController {
    private static final String TAG = "Titan2Touchpad";

    // settings and property names
    static final String PROP_SCROLL = "persist.sys.scroll_assistant";
    static final String PROP_CURSOR = "persist.sys.cusor_move_assistant";
    static final String SETTING_APPS = "touchpad_force_scroll_app";
    static final String SETTING_DOUBLE_TAP = "touchpad_double_tap_scron";

    // per-app modes (touchpad_force_scroll_app values)
    static final int MODE_OFF = -1;
    static final int MODE_SLIDE_CLICK = 0;
    static final int MODE_SLIDE_1 = 1;
    static final int MODE_SLIDE_2 = 2;
    static final int MODE_MOUSE_1 = 3;
    static final int MODE_MOUSE_2 = 4;

    private static final String PROP_NODE = "sys.titan2.touchpad";
    private static final String PROP_IME = "sys.titan2.ime_visible";
    private static final String PROP_FOCUS = "sys.titan2.focus";
    private static final String PROP_LINDROID = "sys.titan2.lindroid.touchpad";
    private static final String LINDROID_PKG = "org.lindroid.ui";
    private static final String SYSTEMUI_PKG = "com.android.systemui";
    private static final String SHADE_WINDOW = "NotificationShade";
    private static final String CAMERA_PKG = "com.mediatek.camera";
    private static final String GESTURE_NODE = "/dev/touch_pad_gesture";
    private static final String LIB = "/system_ext/lib64/libtitan2_uinput.so";

    // evdev
    private static final int EV_SYN = 0x00, EV_KEY = 0x01, EV_ABS = 0x03;
    private static final int SYN_REPORT = 0, SYN_DROPPED = 3;
    private static final int BTN_TOUCH = 0x14a, BTN_TOOL_FINGER = 0x145;
    private static final int ABS_MT_POSITION_X = 0x35, ABS_MT_POSITION_Y = 0x36;
    /** struct input_event on a 64-bit kernel: timeval (16) + type, code (2 + 2) + value (4). */
    private static final int EVENT_SIZE = 24;
    private static final long NODE_WAIT_MS = 5000;
    private static final long REOPEN_MS = 2000;

    // timing and thresholds, in ms and pad units
    private static final long DOUBLE_TAP_MS = 300;
    private static final float DOUBLE_TAP_DIST = 80;
    private static final float CURSOR_H_STEP = 30, CURSOR_V_STEP = 100, CURSOR_RATIO = 5;
    private static final long GUARD_MS = 800;
    private static final float FINGER_START_DIST = 50;
    private static final float CLICK_DIST = 16, CLICK_EDGE = 128;
    private static final long CLICK_MS = 200;
    private static final int DRAG_MIN_MOVES = 5;
    private static final float DRAG_MIN_DIST = 50;
    private static final float CAMERA_CANCEL_Y = 1100;
    private static final long SLOP_SKIP_MS = 500;
    private static final float SCROLL_SCALE = 0.012f;
    private static final long VELOCITY_WINDOW_MS = 100;
    private static final long FLING_STEP_MS = 8;
    private static final float FLING_DIVISOR = 1300f;
    private static final float FLING_DECAY = 0.965f;
    private static final float FLING_MIN_VELOCITY = 1f;
    private static final float SWIPE_LIGHT_FRACTION = 0.5f;
    private static final long MOUSE_CHECK_MS = 1000;
    private static final long INIT_RETRY_MS = 5000;

    // frame kinds (reader thread -> handler)
    private static final int MSG_FRAME = 1, MSG_KEY = 2, MSG_WAKE = 3, MSG_RESET = 4;
    private static final int FRAME_DOWN = 0, FRAME_MOVE = 1, FRAME_UP = 2;

    // what the current gesture drives
    private static final int KIND_NONE = 0, KIND_WHEEL = 1, KIND_FINGER = 2, KIND_POINTER = 3;

    private final Context mContext;
    private final KeyboardLight mLight;
    private final ContentResolver mResolver;
    private final Handler mHandler;
    private final VirtualMouse mMouse = new VirtualMouse();
    private InputManager mInputManager;
    private PowerManager mPowerManager;
    private DisplayManager mDisplayManager;
    private boolean mStarted;

    // ---- handler thread state ----
    private final Point mSize = new Point();
    private int mW = 1440, mH = 1440;
    private float mSlop = 21, mScrollFactor = 172;
    private boolean mInitDone, mMonitorDone, mGestureDone;
    private InputMonitor mMonitor;
    private InputEventReceiver mMonitorReceiver;
    private long mScreenLiftTime = Long.MIN_VALUE / 2;
    private final SparseIntArray mKeyboards = new SparseIntArray(); // device id -> KB_*
    private static final int KB_OTHER = 1, KB_EXTERNAL = 2, KB_BUILTIN = 3;
    private String mAppsRaw;
    private Map<String, Integer> mApps;

    // gesture (touch-down .. lift)
    private boolean mDown;
    private float mDownX, mDownY, mLastX, mLastY;
    private long mDownTime;
    private float mMaxDist;
    private boolean mMoved;
    private boolean mScrollOn;
    private int mMode;
    private int mKind;
    private boolean mFingerThreshold;
    private boolean mCamera;
    private boolean mFrozen;          // finger mode ended by a key
    private boolean mClickCancelled;  // a key was pressed: no click on lift
    private boolean mWheelBlocked;    // a key other than Shift: no wheel until the next touch-down

    // cursor assistant
    private boolean mCursorActive, mCursorMode;
    private boolean mHaveLift;
    private long mLiftTime;
    private float mLiftX, mLiftY;
    private float mRefX, mRefY;

    // finger mode
    private final MotionEvent.PointerProperties[] mTouchProps = {new MotionEvent.PointerProperties()};
    private final MotionEvent.PointerCoords[] mTouchCoords = {new MotionEvent.PointerCoords()};
    private boolean mVfDown, mVfDone, mVfCameraCancel;
    private long mVfDownTime;
    private int mVfMoves;

    // wheel and fling
    private final MotionEvent.PointerProperties[] mScrollProps = {new MotionEvent.PointerProperties()};
    private final MotionEvent.PointerCoords[] mScrollCoords = {new MotionEvent.PointerCoords()};
    private boolean mWStarted, mWScrolling, mSkipSlop, mLastHorizontal;
    private float mWStartX, mWStartY, mWRefX, mWRefY;
    private long mLastScrollLift = Long.MIN_VALUE / 2;
    private static final int SAMPLES = 32;
    private final long[] mSampleT = new long[SAMPLES];
    private final float[] mSampleX = new float[SAMPLES], mSampleY = new float[SAMPLES];
    private int mSampleCount, mSamplePos;
    private boolean mFlinging;
    private float mFlingVx, mFlingVy;
    private long mFlingLast;

    // mouse modes
    private float mPtrRemX, mPtrRemY;

    TouchpadController(Context context, KeyboardLight light) {
        mContext = context;
        mLight = light;
        mResolver = context.getContentResolver();
        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mHandler = new Handler(thread.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                // system_server: an uncaught exception here would restart the whole system
                try {
                    onMessage(msg);
                } catch (RuntimeException e) {
                    Log.w(TAG, "message " + msg.what, e);
                }
            }
        };
        for (MotionEvent.PointerProperties p : new MotionEvent.PointerProperties[] {
                mTouchProps[0], mScrollProps[0]}) {
            p.id = 0;
        }
        mTouchProps[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
        mScrollProps[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
    }

    // ================= Setup =================

    /** Double-tap wake exists only with the gesture node of the alternative pad controller. */
    static boolean hasDoubleTapWake() {
        try {
            return new File(GESTURE_NODE).exists();
        } catch (SecurityException e) {
            return false;
        }
    }

    /** JNI in jni/titan2_uinput.c (ioctl on the gesture node): 0 or -errno. */
    private static native int nativeGestureSwitch(int on);

    private static boolean sLibLoaded, sLibFailed;

    private static synchronized boolean loadLib() {
        if (!sLibLoaded && !sLibFailed) {
            try {
                System.load(LIB);
                sLibLoaded = true;
            } catch (UnsatisfiedLinkError | SecurityException e) {
                sLibFailed = true;
                Log.w(TAG, "load " + LIB, e);
            }
        }
        return sLibLoaded;
    }

    /** Called once by KeyHandler: start reading the device and the helpers. */
    void start() {
        if (mStarted) return;
        mStarted = true;
        mHandler.post(mInit);
        Thread reader = new Thread(this::readLoop, TAG + "Reader");
        reader.setDaemon(true);
        reader.start();
    }

    /** system_server is still starting when the key handler is created: retry until ready. */
    private final Runnable mInit = new Runnable() {
        @Override
        public void run() {
            boolean retry = false;
            try {
                if (!mInitDone) {
                    mInputManager = mContext.getSystemService(InputManager.class);
                    mPowerManager = mContext.getSystemService(PowerManager.class);
                    mDisplayManager = mContext.getSystemService(DisplayManager.class);
                    readDimensions();
                    mInitDone = true;
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "init", e);
                retry = true;
            }
            if (mInitDone && !mMonitorDone) {
                try {
                    startScreenMonitor();
                    mMonitorDone = true;
                } catch (RuntimeException e) {
                    Log.w(TAG, "screen touch monitor not ready", e);
                    retry = true;
                }
            }
            if (mInitDone && !mGestureDone) {
                try {
                    startDoubleTapWake();
                    mGestureDone = true;
                } catch (RuntimeException e) {
                    Log.w(TAG, "double tap setting not ready", e);
                    retry = true;
                }
            }
            if (retry) mHandler.postDelayed(this, INIT_RETRY_MS);
        }
    };

    private void readDimensions() {
        try {
            mSlop = mContext.getResources().getDimensionPixelSize(
                    com.android.internal.R.dimen.config_viewConfigurationTouchSlop);
            mScrollFactor = mContext.getResources().getDimensionPixelSize(
                    com.android.internal.R.dimen.config_verticalScrollFactor);
        } catch (RuntimeException e) {
            float density = mContext.getResources().getDisplayMetrics().density;
            mSlop = 8 * density;
            mScrollFactor = 64 * density;
        }
    }

    // ================= Reader thread =================

    private void readLoop() {
        final byte[] buf = new byte[EVENT_SIZE * 64];
        while (true) {
            final String path = SystemProperties.get(PROP_NODE, "");
            if (!path.startsWith("/dev/input/event")) {
                SystemClock.sleep(NODE_WAIT_MS);
                continue;
            }
            try (FileInputStream in = new FileInputStream(path)) {
                Log.i(TAG, "reading " + path);
                readFrames(in, buf);
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "touchpad " + path + ": " + e);
            }
            // the gesture in progress cannot finish: release whatever it holds
            mHandler.sendEmptyMessage(MSG_RESET);
            SystemClock.sleep(REOPEN_MS);
        }
    }

    private void readFrames(FileInputStream in, byte[] buf) throws IOException {
        int rawX = 0, rawY = 0;
        boolean touch = false, wasTouch = false, posChanged = false, dropped = false;
        int fill = 0;
        while (true) {
            final int n = in.read(buf, fill, buf.length - fill);
            if (n <= 0) throw new IOException("read " + n);
            fill += n;
            int off = 0;
            for (; off + EVENT_SIZE <= fill; off += EVENT_SIZE) {
                final int type = u16(buf, off + 16);
                final int code = u16(buf, off + 18);
                final int value = s32(buf, off + 20);
                if (type == EV_ABS) {
                    if (code == ABS_MT_POSITION_X) {
                        if (value != rawX) posChanged = true;
                        rawX = value;
                    } else if (code == ABS_MT_POSITION_Y) {
                        if (value != rawY) posChanged = true;
                        rawY = value;
                    }
                } else if (type == EV_KEY) {
                    if (code == BTN_TOUCH) {
                        touch = value != 0;
                    } else if (code != BTN_TOOL_FINGER && value == 1) {
                        // the alternative controller's wake gesture
                        mHandler.sendEmptyMessage(MSG_WAKE);
                    }
                } else if (type == EV_SYN) {
                    if (code == SYN_DROPPED) {
                        dropped = true;
                    } else if (code == SYN_REPORT) {
                        if (dropped) {
                            // the partial frame after an overrun is discarded
                            dropped = false;
                        } else {
                            final int kind;
                            if (touch && !wasTouch) {
                                kind = FRAME_DOWN;
                            } else if (touch && posChanged) {
                                // a frame without a new position changes nothing
                                kind = FRAME_MOVE;
                            } else if (!touch && wasTouch) {
                                kind = FRAME_UP;
                            } else {
                                kind = -1;
                            }
                            wasTouch = touch;
                            posChanged = false;
                            if (kind >= 0) {
                                Message m = mHandler.obtainMessage(MSG_FRAME, kind,
                                        (Math.max(0, rawX) << 16) | (Math.max(0, rawY) & 0xffff),
                                        SystemClock.uptimeMillis());
                                mHandler.sendMessage(m);
                            }
                        }
                    }
                }
            }
            // evdev returns whole events; keep a partial one just in case
            if (off < fill) System.arraycopy(buf, off, buf, 0, fill - off);
            fill -= off;
        }
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8);
    }

    private static int s32(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8) | ((b[o + 2] & 0xff) << 16)
                | (b[o + 3] << 24);
    }

    // ================= Messages =================

    private void onMessage(Message msg) {
        switch (msg.what) {
            case MSG_FRAME: {
                final int rx = msg.arg2 >>> 16, ry = msg.arg2 & 0xffff;
                final long t = (Long) msg.obj;
                if (msg.arg1 == FRAME_DOWN) {
                    onTouchDown(rx, ry, t);
                } else if (msg.arg1 == FRAME_MOVE) {
                    if (mDown) onMove(rx, ry, t);
                } else if (mDown) {
                    onLift(rx, ry, t);
                }
                break;
            }
            case MSG_KEY:
                onKey(msg.arg1 & 0xffff, msg.arg1 >>> 16, msg.arg2);
                break;
            case MSG_WAKE:
                onWakeGesture();
                break;
            case MSG_RESET:
                if (mVfDown) sendTouch(MotionEvent.ACTION_CANCEL, mLastX, mLastY);
                mVfDown = false;
                mDown = false;
                stopFling();
                break;
            default:
                break;
        }
    }

    /** Every key ACTION_DOWN, from KeyHandler (input thread: returns at once). */
    void onKeyActivity(KeyEvent event) {
        if (event.getRepeatCount() != 0) return;
        final int keyCode = event.getKeyCode() & 0xffff;
        final int scan = event.getScanCode() & 0xffff;
        mHandler.obtainMessage(MSG_KEY, (scan << 16) | keyCode, event.getDeviceId())
                .sendToTarget();
    }

    // ================= Touch-down =================

    private float padX(int raw) {
        return raw * (float) mW / 1441f;
    }

    private float padY(int raw) {
        final float v = 2f * mH / 3f - 50f;
        return raw * v / 721f;
    }

    private void readScreenSize() {
        int w = 1440, h = 1440;
        try {
            Display d = mDisplayManager != null
                    ? mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY) : null;
            if (d != null) {
                d.getRealSize(mSize);
                if (mSize.x > 0 && mSize.y > 0) {
                    w = mSize.x;
                    h = mSize.y;
                }
            }
        } catch (RuntimeException e) {
            // fall back to 1440x1440
        }
        mW = w;
        mH = h;
    }

    private void onTouchDown(int rx, int ry, long t) {
        if (!mInitDone) return;
        if (mDown && mVfDown) {
            // a lift was lost: never two DOWNs for the virtual finger
            sendTouch(MotionEvent.ACTION_CANCEL, mLastX, mLastY);
        }
        readScreenSize();
        final float x = padX(rx), y = padY(ry);
        mDown = true;
        mDownX = mLastX = x;
        mDownY = mLastY = y;
        mDownTime = t;
        mMaxDist = 0;
        mMoved = false;
        mFrozen = false;
        mClickCancelled = false;
        mWheelBlocked = false;
        mVfDown = false;
        mVfDone = false;
        mVfMoves = 0;
        mWStarted = false;
        mWScrolling = false;
        mPtrRemX = mPtrRemY = 0;
        // decided once per gesture, at touch-down
        mSkipSlop = mFlinging || t - mLastScrollLift < SLOP_SKIP_MS;
        stopFling();

        // switches, IME, focus and mode apply to the whole gesture
        final String focus = SystemProperties.get(PROP_FOCUS, "");
        final boolean ime = "1".equals(SystemProperties.get(PROP_IME, "0"));
        final boolean lindroid = focus.startsWith(LINDROID_PKG)
                && "1".equals(SystemProperties.get(PROP_LINDROID, "0"));
        mScrollOn = SystemProperties.get(PROP_SCROLL, "1").contains("1");
        final boolean cursorOn = SystemProperties.get(PROP_CURSOR, "1").contains("1");
        if (!ime) {
            // text-cursor mode has no meaning without a text field: clear it
            mCursorMode = false;
        }
        mCursorActive = !lindroid && cursorOn && ime;
        mRefX = x;
        mRefY = y;

        final String pkg = packageOf(focus);
        mMode = modeFor(focus, pkg);
        updateMouse(mScrollOn && (mMode == MODE_MOUSE_1 || mMode == MODE_MOUSE_2));
        mCamera = pkg.contains(CAMERA_PKG);
        mFingerThreshold = false;
        if (lindroid || !mScrollOn || ime) {
            // Lindroid owns the pad (only the swipe for light), or a text field is active (the
            // cursor assistant is the only consumer)
            mKind = KIND_NONE;
            return;
        }
        switch (mMode) {
            case MODE_OFF:
                mKind = KIND_NONE;
                break;
            case MODE_SLIDE_2:
                mKind = KIND_WHEEL;
                break;
            case MODE_MOUSE_1:
            case MODE_MOUSE_2: {
                final boolean zone = mMode == MODE_MOUSE_1 ? x < 2f * mW / 5f : x > 3f * mW / 5f;
                mKind = zone ? KIND_POINTER : KIND_FINGER;
                mFingerThreshold = !zone;
                break;
            }
            default:
                mKind = KIND_FINGER;
                break;
        }
    }

    private static String packageOf(String focus) {
        final int slash = focus.indexOf('/');
        return slash >= 0 ? focus.substring(0, slash) : focus;
    }

    /** Mode of the focused app. */
    private int modeFor(String focus, String pkg) {
        final boolean shade = SHADE_WINDOW.equals(focus) || SHADE_WINDOW.equals(pkg)
                || SYSTEMUI_PKG.equals(pkg);
        // the NotificationShade window uses the entry of com.android.systemui
        final Integer entry = appEntry(shade ? SYSTEMUI_PKG : pkg);
        if (shade) {
            // always finger mode; an entry of 2 (wheel) falls back to slide mode 1, Off stays Off
            if (entry == null || entry == MODE_SLIDE_2) return MODE_SLIDE_1;
            return entry;
        }
        return entry != null ? entry : MODE_SLIDE_2;
    }

    private Integer appEntry(String pkg) {
        final String raw = Settings.System.getStringForUser(mResolver, SETTING_APPS,
                UserHandle.USER_SYSTEM);
        if (raw == null) {
            mAppsRaw = null;
            mApps = null;
            return null;
        }
        if (!raw.equals(mAppsRaw)) {
            mApps = TouchpadApps.parse(raw);
            mAppsRaw = raw;
        }
        return mApps.get(pkg);
    }

    // ================= Movement =================

    private void onMove(int rx, int ry, long t) {
        final float x = padX(rx), y = padY(ry);
        final float px = mLastX, py = mLastY;
        mLastX = x;
        mLastY = y;
        mMoved = true;
        mMaxDist = Math.max(mMaxDist, Math.max(Math.abs(x - mDownX), Math.abs(y - mDownY)));
        if (mCursorActive) {
            if (mCursorMode) moveCursor(x, y);
            return;
        }
        switch (mKind) {
            case KIND_POINTER:
                // pointer motion is never frozen and not guarded
                movePointer(x - px, y - py);
                break;
            case KIND_WHEEL:
                wheelMove(x, y, t);
                break;
            case KIND_FINGER:
                fingerMove(x, y, t);
                break;
            default:
                break;
        }
    }

    private boolean guarded(long t) {
        return t - mScreenLiftTime < GUARD_MS;
    }

    // ---- cursor ----

    private void moveCursor(float x, float y) {
        final float dx = x - mRefX, dy = y - mRefY;
        final float ax = Math.abs(dx), ay = Math.abs(dy);
        if (ax >= CURSOR_RATIO * ay) {
            if (ax >= CURSOR_H_STEP) {
                sendArrow(dx > 0 ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT);
                mRefX = x;
                mRefY = y;
            }
        } else if (ay >= CURSOR_RATIO * ax) {
            if (ay >= CURSOR_V_STEP) {
                sendArrow(dy > 0 ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_UP);
                mRefX = x;
                mRefY = y;
            }
        } else {
            // diagonal: no key, but the reference follows
            mRefX = x;
            mRefY = y;
        }
    }

    // ---- pointer ----

    private void movePointer(float dx, float dy) {
        mPtrRemX += dx;
        mPtrRemY += dy;
        final int ix = (int) mPtrRemX, iy = (int) mPtrRemY;
        mPtrRemX -= ix;
        mPtrRemY -= iy;
        mMouse.move(ix, iy);
    }

    // ---- finger ----

    private void fingerMove(float x, float y, long t) {
        if (mFrozen || mVfDone) return;
        if (guarded(t)) {
            // the gesture starts again after the guard, as if from its first movement
            if (mVfDown) {
                sendTouch(MotionEvent.ACTION_CANCEL, x, y);
                mVfDown = false;
            }
            mVfMoves = 0;
            return;
        }
        if (!mVfDown) {
            if (mFingerThreshold && mMaxDist < FINGER_START_DIST) {
                return;
            }
            mVfDownTime = SystemClock.uptimeMillis();
            mVfMoves = 0;
            mVfDown = true;
            sendTouch(MotionEvent.ACTION_DOWN, x, y);
            // the bottom part of the pad, nearest the user: the camera controls
            mVfCameraCancel = mMode != MODE_SLIDE_CLICK && mCamera
                    && screenY(y) > CAMERA_CANCEL_Y;
            return;
        }
        if (mVfCameraCancel) {
            sendTouch(MotionEvent.ACTION_CANCEL, x, y);
            mVfDown = false;
            mVfDone = true;
            return;
        }
        sendTouch(MotionEvent.ACTION_MOVE, x, y);
        mVfMoves++;
    }

    private float screenX(float x) {
        return Math.max(0, Math.min(mW - 1, x));
    }

    private float screenY(float y) {
        return Math.max(0, Math.min(mH - 1, y + mH / 4f));
    }

    // ---- wheel ----

    private void wheelMove(float x, float y, long t) {
        if (mWheelBlocked) return;
        if (guarded(t)) {
            // interrupted: the next movement after the guard starts the gesture again
            mWStarted = false;
            mWScrolling = false;
            return;
        }
        if (!mWStarted) {
            mWStarted = true;
            mWScrolling = false;
            mWStartX = mWRefX = x;
            mWStartY = mWRefY = y;
            mSampleCount = 0;
            addSample(t, x, y);
            return;
        }
        addSample(t, x, y);
        final float dx = x - mWRefX, dy = y - mWRefY;
        final boolean horizontal = Math.abs(dx) > Math.abs(dy);
        float d = horizontal ? dx : dy;
        if (!mWScrolling) {
            // touch slop: the distance piles up from the start point (the reference stays)
            final float slop = mSkipSlop ? 0 : mSlop;
            if (Math.abs(d) <= slop) return;
            d -= Math.signum(d) * slop;
            mWScrolling = true;
        }
        mWRefX = x;
        mWRefY = y;
        mLastHorizontal = horizontal;
        sendScroll(horizontal, SCROLL_SCALE * d);
    }

    private void addSample(long t, float x, float y) {
        mSampleT[mSamplePos] = t;
        mSampleX[mSamplePos] = x;
        mSampleY[mSamplePos] = y;
        mSamplePos = (mSamplePos + 1) % SAMPLES;
        if (mSampleCount < SAMPLES) mSampleCount++;
    }

    // ================= Lift =================

    private void onLift(int rx, int ry, long t) {
        final float x = padX(rx), y = padY(ry);
        mDown = false;
        mLastX = x;
        mLastY = y;
        mMaxDist = Math.max(mMaxDist, Math.max(Math.abs(x - mDownX), Math.abs(y - mDownY)));

        // independent of every switch, mode and the guard
        if (Math.abs(x - mDownX) > mW * SWIPE_LIGHT_FRACTION) {
            mLight.onTouchpadSwipe();
        }

        if (mCursorActive) {
            // each lift compares itself with the one before it
            if (mHaveLift && t - mLiftTime < DOUBLE_TAP_MS
                    && Math.abs(x - mLiftX) <= DOUBLE_TAP_DIST
                    && Math.abs(y - mLiftY) <= DOUBLE_TAP_DIST) {
                mCursorMode = !mCursorMode;
            }
            mHaveLift = true;
            mLiftTime = t;
            mLiftX = x;
            mLiftY = y;
            return;
        }

        switch (mKind) {
            case KIND_WHEEL:
                wheelLift(x, y, t);
                break;
            case KIND_FINGER:
                fingerLift(x, y, t);
                break;
            default:
                break;
        }
    }

    private void fingerLift(float x, float y, long t) {
        final boolean guard = guarded(t);
        if (mVfDown) {
            mVfDown = false;
            final boolean up;
            if (guard) {
                up = false;
            } else if (mMode == MODE_SLIDE_CLICK) {
                up = true;
            } else {
                // a short drag must not become a tap in the app (distance at the lift)
                up = mVfMoves >= DRAG_MIN_MOVES && (Math.abs(x - mDownX) > DRAG_MIN_DIST
                        || Math.abs(y - mDownY) > DRAG_MIN_DIST);
            }
            sendTouch(up ? MotionEvent.ACTION_UP : MotionEvent.ACTION_CANCEL, x, y);
        }
        if (mFrozen || mClickCancelled || guard) return;
        if (mMode == MODE_SLIDE_CLICK) {
            // click on tap
            if (!mMoved && Math.abs(x - mDownX) <= CLICK_DIST && Math.abs(y - mDownY) <= CLICK_DIST
                    && x > CLICK_EDGE && x < mW - CLICK_EDGE && t - mDownTime < CLICK_MS) {
                mVfDownTime = SystemClock.uptimeMillis();
                sendTouch(MotionEvent.ACTION_DOWN, x, y);
                SystemClock.sleep(1);
                sendTouch(MotionEvent.ACTION_UP, x, y);
            }
        } else if (mFingerThreshold && mMaxDist < FINGER_START_DIST) {
            // a tap outside the pointer zone clicks at the pointer
            mMouse.click();
        }
    }

    private void wheelLift(float x, float y, long t) {
        if (!mWStarted || !mWScrolling) return;
        mLastScrollLift = t;
        if (mWheelBlocked || guarded(t)) return;
        addSample(t, x, y);
        // least squares over the last 100 ms, in units per second
        float vx = velocity(mSampleX, t), vy = velocity(mSampleY, t);
        final float totalX = x - mWStartX, totalY = y - mWStartY;
        if (vx * totalX < 0) vx = 0;
        if (vy * totalY < 0) vy = 0;
        if (mLastHorizontal && Math.abs(vy) > Math.abs(vx)) {
            vy = 0;
        } else if (!mLastHorizontal && Math.abs(vx) > Math.abs(vy)) {
            vx = 0;
        }
        if (Math.max(Math.abs(vx), Math.abs(vy)) > FLING_MIN_VELOCITY) {
            mFlingVx = vx;
            mFlingVy = vy;
            mFlingLast = t;
            mFlinging = true;
            mHandler.removeCallbacks(mFlingStep);
            mHandler.postDelayed(mFlingStep, FLING_STEP_MS);
        }
    }

    /** Slope of a linear least-squares fit of the samples in the window, per second. */
    private float velocity(float[] values, long now) {
        double st = 0, sv = 0;
        int n = 0;
        for (int i = 0; i < mSampleCount; i++) {
            final int k = (mSamplePos - 1 - i + SAMPLES) % SAMPLES;
            if (now - mSampleT[k] > VELOCITY_WINDOW_MS) break;
            st += mSampleT[k] - now;
            sv += values[k];
            n++;
        }
        if (n < 2) return 0;
        final double mt = st / n, mv = sv / n;
        double num = 0, den = 0;
        for (int i = 0; i < n; i++) {
            final int k = (mSamplePos - 1 - i + SAMPLES) % SAMPLES;
            final double dt = (mSampleT[k] - now) - mt;
            num += dt * (values[k] - mv);
            den += dt * dt;
        }
        return den > 0 ? (float) (num / den * 1000.0) : 0;
    }

    private final Runnable mFlingStep = new Runnable() {
        @Override
        public void run() {
            try {
                flingStep();
            } catch (RuntimeException e) {
                mFlinging = false;
                Log.w(TAG, "fling", e);
            }
        }
    };

    private void flingStep() {
        if (!mFlinging) return;
        final long now = SystemClock.uptimeMillis();
        final long dt = now - mFlingLast;
        mFlingLast = now;
        final float dx = mFlingVx * dt / FLING_DIVISOR, dy = mFlingVy * dt / FLING_DIVISOR;
        final boolean horizontal = Math.abs(dx) > Math.abs(dy);
        final float amount = SCROLL_SCALE * (horizontal ? dx : dy);
        if (Math.abs(amount) * mScrollFactor < 1f) {
            mFlinging = false;
            return;
        }
        sendScroll(horizontal, amount);
        mFlingVx *= FLING_DECAY;
        mFlingVy *= FLING_DECAY;
        if (Math.max(Math.abs(mFlingVx), Math.abs(mFlingVy)) <= FLING_MIN_VELOCITY) {
            mFlinging = false;
            return;
        }
        mHandler.postDelayed(mFlingStep, FLING_STEP_MS);
    }

    private void stopFling() {
        mFlinging = false;
        mHandler.removeCallbacks(mFlingStep);
    }

    // ================= Keys =================

    private void onKey(int keyCode, int scan, int deviceId) {
        // only keys from real full keyboards count here (TitanKey or another alphabetic
        // keyboard); volume and side keys do not leave text-cursor mode
        final int kb = keyboardKind(deviceId);
        if (kb == KB_OTHER) return;
        final boolean arrow = keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (!arrow) {
            mCursorMode = false;
            mHaveLift = false;
        }
        if (mDown && mScrollOn) {
            // no click on this lift; in finger mode the virtual finger ends now
            mClickCancelled = true;
            if (mKind == KIND_FINGER) {
                mFrozen = true;
                if (mVfDown) {
                    sendTouch(MotionEvent.ACTION_CANCEL, mLastX, mLastY);
                    mVfDown = false;
                }
            }
        }
        // only the built-in keyboard stops the wheel, and not with Shift
        final boolean shift = kb == KB_BUILTIN
                && scan == KeyConfig.SCANCODES[KeyConfig.SLOT_SHIFT];
        if (kb == KB_BUILTIN && !shift) {
            // typing never scrolls the page under the user
            mWheelBlocked = true;
            stopFling();
        }
    }

    /** KB_BUILTIN (TitanKey), KB_EXTERNAL (another real full keyboard) or KB_OTHER. */
    private int keyboardKind(int deviceId) {
        if (deviceId <= 0 || deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD) return KB_OTHER;
        int kind = mKeyboards.get(deviceId, 0);
        if (kind != 0) return kind;
        InputDevice dev = mInputManager != null ? mInputManager.getInputDevice(deviceId) : null;
        if (dev == null) return KB_OTHER;
        if (dev.isVirtual()) {
            kind = KB_OTHER;
        } else if (KeyConfig.KEYBOARD_DEVICE.equals(dev.getName())) {
            kind = KB_BUILTIN;
        } else if (dev.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
            kind = KB_EXTERNAL;
        } else {
            kind = KB_OTHER;
        }
        mKeyboards.put(deviceId, kind);
        return kind;
    }

    // ================= Real screen touches =================

    private void startScreenMonitor() {
        final InputMonitor monitor = mInputManager.monitorGestureInput(TAG,
                Display.DEFAULT_DISPLAY);
        mMonitor = monitor;
        mMonitorReceiver = new InputEventReceiver(monitor.getInputChannel(),
                mHandler.getLooper()) {
            @Override
            public void onInputEvent(InputEvent event) {
                try {
                    if (event instanceof MotionEvent) onScreenTouch((MotionEvent) event);
                } catch (RuntimeException e) {
                    Log.w(TAG, "screen touch", e);
                } finally {
                    finishInputEvent(event, false);
                }
            }
        };
    }

    private void onScreenTouch(MotionEvent ev) {
        final int action = ev.getActionMasked();
        if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) return;
        if (!ev.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) return;
        // our own injected events come from the virtual device id
        final int id = ev.getDeviceId();
        if (id <= 0 || id == KeyCharacterMap.VIRTUAL_KEYBOARD) return;
        final InputDevice dev = InputDevice.getDevice(id);
        if (dev == null || dev.isVirtual()) return;
        mScreenLiftTime = SystemClock.uptimeMillis();
        stopFling();
    }

    // ================= Mouse device =================

    private final Runnable mMouseCheck = new Runnable() {
        @Override
        public void run() {
            try {
                if (!mMouse.isOpen() || mDown) {
                    if (mMouse.isOpen()) mHandler.postDelayed(this, MOUSE_CHECK_MS);
                    return;
                }
                final String focus = SystemProperties.get(PROP_FOCUS, "");
                final int mode = modeFor(focus, packageOf(focus));
                updateMouse(SystemProperties.get(PROP_SCROLL, "1").contains("1")
                        && (mode == MODE_MOUSE_1 || mode == MODE_MOUSE_2));
            } catch (RuntimeException e) {
                Log.w(TAG, "mouse check", e);
            }
        }
    };

    /** The mouse exists only while it is needed: removing it hides the pointer. */
    private void updateMouse(boolean want) {
        if (want) {
            if (!mMouse.isOpen()) mMouse.open();
            mHandler.removeCallbacks(mMouseCheck);
            if (mMouse.isOpen()) mHandler.postDelayed(mMouseCheck, MOUSE_CHECK_MS);
        } else if (mMouse.isOpen()) {
            mHandler.removeCallbacks(mMouseCheck);
            mMouse.close();
        }
    }

    // ================= Double tap wake =================

    private void startDoubleTapWake() {
        if (!hasDoubleTapWake()) return;
        mResolver.registerContentObserver(Settings.System.getUriFor(SETTING_DOUBLE_TAP), false,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        try {
                            applyDoubleTap();
                        } catch (RuntimeException e) {
                            Log.w(TAG, "double tap", e);
                        }
                    }
                }, UserHandle.USER_SYSTEM);
        applyDoubleTap();
    }

    private void applyDoubleTap() {
        final boolean on = "1".equals(Settings.System.getStringForUser(mResolver,
                SETTING_DOUBLE_TAP, UserHandle.USER_SYSTEM));
        if (!loadLib()) return;
        final int ret = nativeGestureSwitch(on ? 1 : 0);
        if (ret != 0) {
            Log.w(TAG, "gesture switch " + on + ": errno " + -ret);
        }
    }

    private void onWakeGesture() {
        if (mPowerManager == null || !hasDoubleTapWake() || mPowerManager.isInteractive()) return;
        mPowerManager.wakeUp(SystemClock.uptimeMillis(), PowerManager.WAKE_REASON_GESTURE,
                "titan2:touchpad");
    }

    // ================= Injection =================

    /** Virtual finger: touchscreen source, finger tool, one pointer, pressure 1. */
    private void sendTouch(int action, float padX, float padY) {
        final MotionEvent.PointerCoords c = mTouchCoords[0];
        c.clear();
        c.x = screenX(padX);
        c.y = screenY(padY);
        c.pressure = 1f;
        c.size = 1f;
        final MotionEvent ev = MotionEvent.obtain(mVfDownTime, SystemClock.uptimeMillis(),
                action, 1, mTouchProps, mTouchCoords, 0, 0, 1f, 1f,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, InputDevice.SOURCE_TOUCHSCREEN,
                Display.DEFAULT_DISPLAY, 0);
        inject(ev);
    }

    /** Scroll under a fixed point: x = gesture start, y = 0.8 H, mouse source. */
    private void sendScroll(boolean horizontal, float amount) {
        if (amount == 0) return;
        final MotionEvent.PointerCoords c = mScrollCoords[0];
        c.clear();
        c.x = screenX(mWStartX);
        c.y = Math.min(mH - 1, 0.8f * mH);
        // finger toward the user (+y) and finger right (+x) are positive
        c.setAxisValue(horizontal ? MotionEvent.AXIS_HSCROLL : MotionEvent.AXIS_VSCROLL, amount);
        final long now = SystemClock.uptimeMillis();
        final MotionEvent ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1,
                mScrollProps, mScrollCoords, 0, 0, 1f, 1f, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                InputDevice.SOURCE_MOUSE, Display.DEFAULT_DISPLAY, 0);
        inject(ev);
    }

    private void inject(MotionEvent ev) {
        try {
            mInputManager.injectInputEvent(ev, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        } finally {
            ev.recycle();
        }
    }

    /** Arrow key down + up from the virtual keyboard, system flag. */
    private void sendArrow(int keyCode) {
        final long now = SystemClock.uptimeMillis();
        for (int action : ARROW_ACTIONS) {
            KeyEvent ev = new KeyEvent(now, now, action, keyCode, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM,
                    InputDevice.SOURCE_KEYBOARD);
            mInputManager.injectInputEvent(ev, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        }
    }

    private static final int[] ARROW_ACTIONS = {KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP};
}
