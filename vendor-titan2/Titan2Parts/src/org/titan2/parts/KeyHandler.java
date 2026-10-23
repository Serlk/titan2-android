package org.titan2.parts;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.MediaStore;
import android.telecom.TelecomManager;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.ViewConfiguration;

import com.android.internal.os.DeviceKeyHandler;

/**
 * Runs inside system_server: PhoneWindowManager.interceptKeyBeforeQueueing hands every key
 * press here (also with the screen off). null means the event is consumed.
 *
 * Keyboard keys in Programmable mode are remapped not here but in InputReader
 * (KeyRemapper, see KeyConfig.applyRemap), before the IME.
 */
public class KeyHandler implements DeviceKeyHandler {
    private static final String TAG = "Titan2KeyHandler";
    /** Long space (layout switch): hold time. */
    private static final long SPACE_LONG_MS = 500;

    // set by frameworks_base 0003 / 0005
    private static final String PROP_IME = "sys.titan2.ime_visible";
    private static final String PROP_FOCUS = "sys.titan2.focus";

    private final Context mContext;
    private final ContentResolver mResolver;
    private final Actions mActions;
    private final Handler mHandler;
    private final TouchpadController mTouchpad;
    /** The rear touch is off while the front (screen, keys, touchpad) is in use. */
    private final RearTouchGuard mRearGuard;
    private final KeyboardLight mLight;
    private final PowerManager mPowerManager;
    private boolean mRemapSynced; // mHandler only
    private volatile String mRemapFocus;

    // Devices: TitanKey or not (ids are not reused within a boot)
    private final SparseBooleanArray mKeyboardDevices = new SparseBooleanArray();
    // PWM calls the handler a second time (interceptKeyBeforeDispatching) for events it let through
    private final long[] mSeen = new long[16];
    private int mSeenPos;

    public KeyHandler(Context context) {
        mContext = context;
        mResolver = context.getContentResolver();
        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mHandler = new Handler(thread.getLooper());
        mActions = new Actions(context, mHandler);
        mPowerManager = context.getSystemService(PowerManager.class);
        mLight = new KeyboardLight(context, mHandler);
        mTouchpad = new TouchpadController(context, mLight);
        mRearGuard = new RearTouchGuard(context, mHandler);
        mTouchpad.setFrontActivityListener(mRearGuard::onTouchpad);
        mTouchpad.start();
        // app profiles: the KeyRemapper remap changes when the focused window changes.
        // There are no change notifications for sys.titan2.focus: poll every 500 ms while profiles exist.
        mHandler.postDelayed(mFocusPoll, FOCUS_POLL_MS);
        // home apps and the default camera for letter shortcuts and the space bar
        mHandler.postDelayed(mRefreshPackages, PACKAGES_FIRST_MS);
    }

    private static final long PACKAGES_FIRST_MS = 10_000;

    private static final long FOCUS_POLL_MS = 500;
    private final Runnable mFocusPoll = new Runnable() {
        @Override
        public void run() {
            if (!KeyConfig.profileApps(mResolver).isEmpty() && mPowerManager.isInteractive()) {
                onFocusMaybeChanged();
            }
            mHandler.postDelayed(this, FOCUS_POLL_MS);
        }
    };

    private void onFocusMaybeChanged() {
        final String focus = focusedPackage();
        final String old = mRemapFocus;
        if (focus.equals(old)) return;
        mRemapFocus = focus;
        mHandler.post(() -> {
            java.util.List<String> apps = KeyConfig.profileApps(mResolver);
            if (!mRemapSynced || apps.contains(focus) || apps.contains(old)) {
                mRemapSynced = true;
                KeyConfig.applyConfiguredRemaps(mContext, focus, old);
            }
        });
    }

    @Override
    public KeyEvent handleKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            mTouchpad.onKeyActivity(event);
            mLight.onKey(event);
            mRearGuard.onKey(event);
        }
        if (seenBefore(event)) {
            // second pass of the same event (before dispatch to the window): already decided
            return event;
        }
        if (handleRearDoubleTap(event)) {
            return null;
        }
        // app switch: the profile remap is applied asynchronously, by the next press
        onFocusMaybeChanged();
        try {
            trackModifiers(event);
            final int slot = slotFor(event);
            if (slot >= 0) {
                return handleProgrammableKey(slot, event);
            }
            final int keyCode = event.getKeyCode();
            if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
                if (handleChord(event) || handleLetter(event)) {
                    return null;
                }
            } else if (keyCode == KeyEvent.KEYCODE_SPACE
                    && event.getDeviceId() != KeyCharacterMap.VIRTUAL_KEYBOARD) {
                handleSpace(event);
                if (handleSpaceLong(event)) {
                    return null;
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "key " + event, e);
        }
        return event;
    }

    /**
     * Double tap on the dark rear screen: the sub_touch touchscreen sends KEY_TV in gesture mode.
     * Only the rear display group is woken (the main one is left alone); the event goes no further.
     */
    private boolean handleRearDoubleTap(KeyEvent event) {
        if (event.getScanCode() != RearDisplay.SCAN_DOUBLE_TAP) {
            return false;
        }
        InputDevice dev = event.getDevice();
        if (dev == null || !RearDisplay.TOUCH_DEVICE.equals(dev.getName())) {
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && mRearGuard.frontBusy()) {
            // the phone is used from the front: a finger holding it, not a double tap
            Log.d(TAG, "rear double tap ignored, the front is in use");
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && RearDisplay.isEnabled(mContext)
                && RearDisplay.getInt(mContext, RearDisplay.SETTING_WAKE_DOUBLE_TAP, 1) == 1) {
            final int id = RearDisplay.findId(mContext);
            if (id != android.view.Display.INVALID_DISPLAY && RearDisplay.getInt(mContext,
                    RearDisplay.SETTING_WAKE_DOUBLE_TAP_POSE, 1) == 1) {
                RearDisplay.wakeIfFacingUp(mContext, id, "double-tap", mHandler);
            } else if (id != android.view.Display.INVALID_DISPLAY) {
                RearDisplay.wake(mContext, id, "double-tap");
            }
        }
        return true;
    }

    /** Remember the event; true if it has already passed through the handler. */
    private synchronized boolean seenBefore(KeyEvent e) {
        long sig = e.getEventTime() * 31 + e.getDownTime();
        sig = sig * 31 + e.getAction();
        sig = sig * 31 + e.getKeyCode();
        sig = sig * 31 + e.getScanCode();
        sig = sig * 31 + e.getDeviceId();
        sig = sig * 31 + e.getRepeatCount();
        for (long s : mSeen) {
            if (s == sig) return true;
        }
        mSeen[mSeenPos] = sig;
        mSeenPos = (mSeenPos + 1) % mSeen.length;
        return false;
    }

    private boolean isKeyboardDevice(int deviceId) {
        int i = mKeyboardDevices.indexOfKey(deviceId);
        if (i >= 0) return mKeyboardDevices.valueAt(i);
        InputDevice dev = mContext.getSystemService(InputManager.class).getInputDevice(deviceId);
        boolean titan = dev != null && KeyConfig.KEYBOARD_DEVICE.equals(dev.getName());
        mKeyboardDevices.put(deviceId, titan);
        return titan;
    }

    /** Index of the key in KeyConfig.KEYS or -1. */
    private int slotFor(KeyEvent event) {
        final int scan = event.getScanCode();
        for (int i = 0; i < KeyConfig.KEYS.length; i++) {
            if (KeyConfig.isSideKey(i)) {
                if (event.getKeyCode() == KeyConfig.BASE_KEYCODES[i]
                        || scan == KeyConfig.SCANCODES[i]) {
                    return i;
                }
            } else if (scan == KeyConfig.SCANCODES[i] && isKeyboardDevice(event.getDeviceId())) {
                return i;
            }
        }
        return -1;
    }

    // ================= Programmable keys, Media function =================

    /** What the down of a programmable key was handled as; its repeats and up follow it. */
    private static final int PK_UNSET = 0, PK_PASS = 1, PK_CONSUME = 2, PK_MAGIC = 3,
            PK_MEDIA = 4, PK_INJECT = 5, PK_PTT = 6, PK_SHORTCUT = 7;
    private static final long SHORTCUT_HOLD_MS = 500;
    private static final long SHORTCUT_WAIT_MS = 500;
    private static final long MEDIA_WAIT_MS = 500;
    private static final long SCREEN_OFF_WAKELOCK_MS = 1000;
    /** Zello: a key in Shortcut mode is its PTT key while it is focused. */
    private static final String ZELLO_PKG = "com.loudtalks";

    private static final int SLOTS = KeyConfig.KEYS.length;
    // input thread
    private final int[] mPkKind = new int[SLOTS];
    private final int[] mPkInjectCode = new int[SLOTS];
    private final String[] mPkPttApp = new String[SLOTS];
    private final boolean[] mPkPttPass = new boolean[SLOTS];
    /** Bit per slot: a key in the Magic function is held. */
    private int mMagicHeld;
    private PowerManager.WakeLock mWakeLock;

    // handler thread: Shortcut mode series
    private final int[] mScCount = new int[SLOTS];
    private final boolean[] mScHeld = new boolean[SLOTS];
    /** The long action ran or the series was cancelled: no short or double from it. */
    private final boolean[] mScEnded = new boolean[SLOTS];
    private final String[] mScPkg = new String[SLOTS];
    private final Runnable[] mScHold = new Runnable[SLOTS];
    private final Runnable[] mScDecide = new Runnable[SLOTS];
    // handler thread: Media function
    private final int[] mMediaTaps = new int[SLOTS];
    private final boolean[] mMediaHeld = new boolean[SLOTS];
    private final boolean[] mMediaPending = new boolean[SLOTS];
    private final Runnable[] mMediaTimer = new Runnable[SLOTS];

    {
        for (int i = 0; i < SLOTS; i++) {
            final int slot = i;
            mScHold[i] = () -> onShortcutHold(slot);
            mScDecide[i] = () -> onShortcutDecide(slot);
            mMediaTimer[i] = () -> onMediaTimer(slot);
        }
    }

    /** Runs r on the handler thread; an exception is logged, never thrown into system_server. */
    private void post(Runnable r) {
        mHandler.post(() -> {
            try {
                r.run();
            } catch (RuntimeException e) {
                Log.w(TAG, "handler", e);
            }
        });
    }

    /** Focused package without a window suffix ("pkg/..."). */
    private String focusPkg() {
        final String focus = focusedPackage();
        final int slash = focus.indexOf('/');
        return slash >= 0 ? focus.substring(0, slash) : focus;
    }

    /**
     * Programmable keys: mode, then Programmable (another key, Magic, Media), Shortcut
     * (short/long/double actions) or PTT. Returns the event to pass it, null to consume.
     */
    private KeyEvent handleProgrammableKey(int slot, KeyEvent event) {
        if (event.getDeviceId() == KeyCharacterMap.VIRTUAL_KEYBOARD) {
            // our own injected keys are never programmable keys
            return event;
        }
        final int action = event.getAction();
        final boolean down = action == KeyEvent.ACTION_DOWN;
        final boolean first = down && event.getRepeatCount() == 0;
        if (first) {
            classify(slot);
        } else if (mPkKind[slot] == PK_UNSET) {
            // an up or repeat without a down we saw: leave it alone
            return event;
        }
        final int kind = mPkKind[slot];
        final boolean up = action == KeyEvent.ACTION_UP;
        if (up) mPkKind[slot] = PK_UNSET;
        switch (kind) {
            case PK_PASS:
                // keyboard key remapped in InputReader (KeyConfig.applyRemap)
                return event;
            case PK_MAGIC:
                if (first) {
                    mMagicHeld |= 1 << slot;
                } else if (up) {
                    mMagicHeld &= ~(1 << slot);
                }
                return null;
            case PK_MEDIA:
                if (first) {
                    post(() -> onMediaDown(slot));
                } else if (up) {
                    post(() -> onMediaUp(slot));
                }
                return null;
            case PK_INJECT: {
                // side key: the same press with another key code, from the virtual keyboard
                final KeyEvent copy = new KeyEvent(event);
                final int code = mPkInjectCode[slot];
                post(() -> mActions.injectAs(copy, code));
                return null;
            }
            case PK_PTT: {
                final String app = mPkPttApp[slot];
                if (app == null) return null;
                if (first) {
                    post(() -> mActions.sendPtt(app, true));
                } else if (up) {
                    post(() -> mActions.sendPtt(app, false));
                }
                return mPkPttPass[slot] ? event : null;
            }
            case PK_SHORTCUT:
                if (first) {
                    if (!mPowerManager.isInteractive()) {
                        // the timers must run with the screen off too
                        wakeLock().acquire(SCREEN_OFF_WAKELOCK_MS);
                    }
                    final String pkg = focusPkg();
                    post(() -> onShortcutDown(slot, pkg));
                } else if (up) {
                    post(() -> onShortcutUp(slot));
                }
                return null;
            case PK_CONSUME:
            default:
                return null;
        }
    }

    /** At the first down of a press: decide what the press is (input thread, settings only). */
    private void classify(int slot) {
        final String pkg = focusPkg();
        final String mode = KeyConfig.getMode(mResolver, slot, pkg);
        mPkPttApp[slot] = null;
        mPkPttPass[slot] = false;
        if (KeyConfig.MODE_PTT.equals(mode)) {
            final String app = KeyConfig.getPttApp(mResolver, slot, pkg);
            mPkKind[slot] = PK_PTT;
            if (app != null && !app.isEmpty()) {
                mPkPttApp[slot] = app;
                mPkPttPass[slot] = app.equals(pkg);
            }
            return;
        }
        if (KeyConfig.MODE_SHORTCUT.equals(mode)) {
            if (ZELLO_PKG.equals(pkg)) {
                mPkKind[slot] = PK_PTT;
                mPkPttApp[slot] = ZELLO_PKG;
                mPkPttPass[slot] = true;
            } else {
                mPkKind[slot] = PK_SHORTCUT;
            }
            return;
        }
        final int f = KeyConfig.getFunction(mResolver, slot, pkg);
        switch (f) {
            case KeyConfig.FUNC_NONE:
                mPkKind[slot] = PK_CONSUME;
                break;
            case KeyConfig.FUNC_MAGIC:
                mPkKind[slot] = PK_MAGIC;
                break;
            case KeyConfig.FUNC_MEDIA:
                mPkKind[slot] = PK_MEDIA;
                break;
            default:
                if (KeyConfig.isSideKey(slot)) {
                    mPkKind[slot] = PK_INJECT;
                    mPkInjectCode[slot] = KeyConfig.FUNC_KEYCODES[f];
                } else {
                    mPkKind[slot] = PK_PASS;
                }
                break;
        }
    }

    private PowerManager.WakeLock wakeLock() {
        if (mWakeLock == null) {
            mWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
            mWakeLock.setReferenceCounted(false);
        }
        return mWakeLock;
    }

    // ---- Shortcut mode, handler thread ----

    private void onShortcutDown(int slot, String pkg) {
        if (mScCount[slot] == 0) {
            // first press of a series
            mScCount[slot] = 1;
            mScEnded[slot] = false;
            mScPkg[slot] = pkg;
            mScHeld[slot] = true;
            mHandler.removeCallbacks(mScDecide[slot]);
            mHandler.postDelayed(mScHold[slot], SHORTCUT_HOLD_MS);
            return;
        }
        if (mScEnded[slot]) return;
        // another press while waiting: decided 500 ms after its release
        mHandler.removeCallbacks(mScDecide[slot]);
        mScCount[slot]++;
        mScHeld[slot] = true;
    }

    private void onShortcutUp(int slot) {
        mScHeld[slot] = false;
        if (mScCount[slot] == 0) return;
        mHandler.removeCallbacks(mScHold[slot]);
        if (mScEnded[slot]) {
            mScCount[slot] = 0;
            mScEnded[slot] = false;
            return;
        }
        mHandler.removeCallbacks(mScDecide[slot]);
        mHandler.postDelayed(mScDecide[slot], SHORTCUT_WAIT_MS);
    }

    private void onShortcutHold(int slot) {
        if (!mScHeld[slot] || mScCount[slot] == 0 || mScEnded[slot]) return;
        // long: no short or double from this series, even if long has no action
        mScEnded[slot] = true;
        runShortcut(slot, "long");
    }

    private void onShortcutDecide(int slot) {
        final int n = mScCount[slot];
        mScCount[slot] = 0;
        mScEnded[slot] = false;
        if (n == 1) {
            runShortcut(slot, "short");
        } else if (n == 2) {
            runShortcut(slot, "double");
        }
    }

    private void runShortcut(int slot, String press) {
        try {
            final String action = KeyConfig.getAction(mResolver, KeyConfig.KEYS[slot], press,
                    mScPkg[slot]);
            if ((KeyConfig.REAR_QR.equals(action) || KeyConfig.PRIVACY.equals(action))
                    && mContext.getSystemService(KeyguardManager.class).isKeyguardLocked()) {
                // these need an unlocked device
                return;
            }
            mActions.run(action);
        } catch (RuntimeException e) {
            Log.w(TAG, "shortcut " + KeyConfig.KEYS[slot] + " " + press, e);
        }
    }

    /**
     * A chord that fired while the key was held cancels that key's pending
     * Shortcut-mode press (handler thread).
     */
    private void cancelShortcut(int slot) {
        mHandler.removeCallbacks(mScHold[slot]);
        mHandler.removeCallbacks(mScDecide[slot]);
        if (mScCount[slot] == 0) return;
        if (mScHeld[slot]) {
            // the release ends the series without an action
            mScEnded[slot] = true;
        } else {
            mScCount[slot] = 0;
            mScEnded[slot] = false;
        }
    }

    // ---- Media function, handler thread ----

    private void onMediaDown(int slot) {
        mMediaHeld[slot] = true;
        mMediaPending[slot] = true;
        mHandler.removeCallbacks(mMediaTimer[slot]);
        mHandler.postDelayed(mMediaTimer[slot], MEDIA_WAIT_MS);
    }

    private void onMediaUp(int slot) {
        mMediaHeld[slot] = false;
        // the release of a hold that already gave "previous" counts for nothing
        if (mMediaPending[slot]) mMediaTaps[slot]++;
    }

    private void onMediaTimer(int slot) {
        final int taps = mMediaTaps[slot];
        mMediaTaps[slot] = 0;
        mMediaPending[slot] = false;
        try {
            if (taps == 0) {
                if (mMediaHeld[slot]) mActions.sendKeyDownUp(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
            } else if (taps == 1) {
                mActions.sendKeyDownUp(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
            } else {
                mActions.sendKeyDownUp(KeyEvent.KEYCODE_MEDIA_NEXT);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "media", e);
        }
    }

    // ================= Letter shortcuts =================

    private boolean imeVisible() {
        return "1".equals(SystemProperties.get(PROP_IME, "0"));
    }

    private String focusedPackage() {
        return SystemProperties.get(PROP_FOCUS, "");
    }

    private static final long LETTER_LONG_MS = 1000;
    private static final long LETTER_SHORT_MS = 500;
    private static final long PACKAGES_MAX_AGE_MS = 30_000;
    private static final String SETTINGS_PKG = "com.android.settings";

    // input thread: the letter whose down was consumed (its repeats and up are consumed too)
    private int mLetterDown = -1;
    // handler thread
    private char mLetter;
    private boolean mLetterHeld, mLetterLongFired;
    private final Runnable mLetterLong = this::onLetterLong;
    private final Runnable mLetterShort = this::onLetterShort;

    // home apps and the default camera, refreshed on the handler thread
    private volatile java.util.Set<String> mHomePackages;
    private volatile String mDefaultCamera;
    private volatile long mPackagesTime;
    private boolean mPackagesRefreshPosted;
    private final Runnable mRefreshPackages = this::refreshPackages;

    /** Letter shortcuts (Magic key held, or on a home screen). true: the letter is consumed. */
    private boolean handleLetter(KeyEvent event) {
        final int keyCode = event.getKeyCode();
        final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (!down || event.getRepeatCount() > 0) {
            if (mLetterDown != keyCode) return false;
            if (!down) {
                mLetterDown = -1;
                post(this::onLetterUp);
            }
            return true;
        }
        if (!KeyConfig.lettersEnabled(mResolver) || imeVisible()) {
            return false;
        }
        final char letter = (char) ('A' + keyCode - KeyEvent.KEYCODE_A);
        if (!KeyConfig.letterAssigned(mResolver, letter)) {
            return false;
        }
        if (mMagicHeld == 0 && !isHomeFocused()) {
            return false;
        }
        if (!mPowerManager.isInteractive()
                || mContext.getSystemService(KeyguardManager.class).isKeyguardLocked()) {
            return false;
        }
        mLetterDown = keyCode;
        post(() -> onLetterDown(letter));
        return true;
    }

    private void onLetterDown(char letter) {
        // a new letter press cancels a pending short
        mHandler.removeCallbacks(mLetterShort);
        mHandler.removeCallbacks(mLetterLong);
        mLetter = letter;
        mLetterHeld = true;
        mLetterLongFired = false;
        mHandler.postDelayed(mLetterLong, LETTER_LONG_MS);
    }

    private void onLetterUp() {
        if (!mLetterHeld) return;
        mLetterHeld = false;
        mHandler.removeCallbacks(mLetterLong);
        if (!mLetterLongFired) {
            mHandler.postDelayed(mLetterShort, LETTER_SHORT_MS);
        }
    }

    private void onLetterLong() {
        mLetterLongFired = true;
        runLetter("long");
    }

    private void onLetterShort() {
        runLetter("short");
    }

    private void runLetter(String press) {
        try {
            // the switch may have been turned off meanwhile
            if (!KeyConfig.lettersEnabled(mResolver)) return;
            final String target = KeyConfig.getLetter(mResolver, mLetter, press);
            if (target != null) mActions.run(target);
        } catch (RuntimeException e) {
            Log.w(TAG, "letter " + mLetter + " " + press, e);
        }
    }

    /** The focused package is a home app (answers the HOME intent), not Settings. */
    private boolean isHomeFocused() {
        maybeRefreshPackages();
        final java.util.Set<String> homes = mHomePackages;
        final String pkg = focusPkg();
        return homes != null && homes.contains(pkg) && !SETTINGS_PKG.equals(pkg);
    }

    /** Input thread: never queries the package manager itself, only asks the handler to. */
    private void maybeRefreshPackages() {
        if (mHomePackages != null
                && SystemClock.uptimeMillis() - mPackagesTime < PACKAGES_MAX_AGE_MS) {
            return;
        }
        synchronized (mRefreshPackages) {
            if (mPackagesRefreshPosted) return;
            mPackagesRefreshPosted = true;
        }
        mHandler.post(mRefreshPackages);
    }

    private void refreshPackages() {
        synchronized (mRefreshPackages) {
            mPackagesRefreshPosted = false;
        }
        try {
            final int user = ActivityManager.getCurrentUser();
            final PackageManager pm = mContext.getPackageManager();
            final java.util.Set<String> homes = new java.util.HashSet<>();
            final Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            for (ResolveInfo ri : pm.queryIntentActivitiesAsUser(home, 0, user)) {
                if (ri.activityInfo != null) homes.add(ri.activityInfo.packageName);
            }
            mHomePackages = homes;
            final ResolveInfo cam = pm.resolveActivityAsUser(
                    new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
                    PackageManager.MATCH_DEFAULT_ONLY, user);
            // with several cameras and no default, the resolver (package "android") answers
            mDefaultCamera = cam != null && cam.activityInfo != null
                    && !"android".equals(cam.activityInfo.packageName)
                    ? cam.activityInfo.packageName : null;
            mPackagesTime = SystemClock.uptimeMillis();
        } catch (RuntimeException e) {
            Log.w(TAG, "package query", e);
        }
    }

    // ================= Sym+letter chords =================

    // Keyboard keys held right now (by scan code, before remapping): input thread
    private boolean mSymHeld, mFnHeld;
    private int mChordDown = -1;
    // Caps/Alt-lock indicator via the backlight: mirrors the Plektra logic (double press within 500 ms)
    private static final long LOCK_DOUBLE_MS = 500;
    private long mShiftLastTap, mAltLastTap;
    private boolean mShiftLocked, mAltLocked, mShiftClean, mAltClean;

    private void trackModifiers(KeyEvent event) {
        if (event.getDeviceId() == KeyCharacterMap.VIRTUAL_KEYBOARD
                || !isKeyboardDevice(event.getDeviceId())) {
            return;
        }
        final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        final int scan = event.getScanCode();
        if (scan == KeyConfig.SCANCODES[KeyConfig.SLOT_SYM]) {
            mSymHeld = down;
        } else if (scan == KeyConfig.SCANCODES[KeyConfig.SLOT_FN]) {
            mFnHeld = down;
        }
        trackLocks(event, down, scan);
    }

    /**
     * Plektra locks Shift (Caps) and Alt with a double press within 500 ms and releases with a single one; its
     * state is not available to us, so we mirror the logic from the events and blink the backlight:
     * lock is two flashes, release is one. Only while a text field is active.
     */
    private void trackLocks(KeyEvent event, boolean down, int scan) {
        if (!imeVisible()) {
            mShiftLocked = mAltLocked = false;
            mShiftLastTap = mAltLastTap = 0;
            return;
        }
        final boolean shift = scan == KeyConfig.SCANCODES[KeyConfig.SLOT_SHIFT];
        final boolean alt = scan == KeyConfig.SCANCODES[KeyConfig.SLOT_ALT];
        if (!shift && !alt) {
            if (down) {
                // a letter under a single Shift/Alt: the "one-shot" modifier is used up
                mShiftClean = mAltClean = false;
                mShiftLastTap = mAltLastTap = 0;
            }
            return;
        }
        if (down) {
            if (event.getRepeatCount() == 0) {
                if (shift) mShiftClean = true; else mAltClean = true;
            }
            return;
        }
        final long now = SystemClock.uptimeMillis();
        if (shift && mShiftClean) {
            if (mShiftLocked) {
                mShiftLocked = false;
                flash(1);
                mShiftLastTap = 0;
            } else if (now - mShiftLastTap < LOCK_DOUBLE_MS) {
                mShiftLocked = true;
                flash(2);
                mShiftLastTap = 0;
            } else {
                mShiftLastTap = now;
            }
        } else if (alt && mAltClean) {
            if (mAltLocked) {
                mAltLocked = false;
                flash(1);
                mAltLastTap = 0;
            } else if (now - mAltLastTap < LOCK_DOUBLE_MS) {
                mAltLocked = true;
                flash(2);
                mAltLastTap = 0;
            } else {
                mAltLastTap = now;
            }
        }
    }

    private void flash(int times) {
        mHandler.post(() -> mLight.flash(times));
    }

    /**
     * Sym+letter (or Fn+Sym+letter): a text snippet or launching an app/shortcut. By default
     * only outside text fields, where Sym belongs to Plektra (symbol pages); optionally everywhere,
     * then the assigned letter does not reach Plektra, but Sym itself does.
     */
    private boolean handleChord(KeyEvent event) {
        final int keyCode = event.getKeyCode();
        final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (!down || event.getRepeatCount() > 0) {
            if (mChordDown == keyCode) {
                if (!down) mChordDown = -1;
                return true;
            }
            return false;
        }
        if (!mSymHeld || KeyConfig.getInt(mResolver, KeyConfig.CHORD_ENABLE, 0) != 1) {
            return false;
        }
        final boolean withFn = KeyConfig.getInt(mResolver, KeyConfig.CHORD_MODIFIER, 0) == 1;
        if (withFn != mFnHeld) {
            return false;
        }
        if (imeVisible() && KeyConfig.getInt(mResolver, KeyConfig.CHORD_IN_FIELDS, 0) != 1) {
            return false;
        }
        final char letter = (char) ('A' + keyCode - KeyEvent.KEYCODE_A);
        final String target = KeyConfig.getChord(mResolver, letter);
        if (target == null) {
            return false;
        }
        mChordDown = keyCode;
        mHandler.post(() -> {
            cancelShortcut(KeyConfig.SLOT_SYM);
            if (withFn) cancelShortcut(KeyConfig.SLOT_FN);
            Log.d(TAG, "chord " + letter + " -> " + target);
            if (target.startsWith(KeyConfig.TEXT_PREFIX)) {
                mActions.pasteText(Actions.expandTemplate(mContext,
                        target.substring(KeyConfig.TEXT_PREFIX.length())));
            } else {
                mActions.run(target);
            }
        });
        return true;
    }

    // ================= Long space -> layout =================

    private boolean mSpaceDeferred, mSpaceLongFired;
    private final Runnable mSpaceLong = this::onSpaceLongTimeout;

    private void onSpaceLongTimeout() {
        mSpaceLongFired = true;
        mActions.sendKeyDownUp(KeyEvent.KEYCODE_LANGUAGE_SWITCH);
        mLight.flash(1);
    }

    /**
     * If enabled and a text field is active: space is held back; released before 500 ms, the space is
     * passed on (injected, same scan code), held: next layout/language (KEYCODE_LANGUAGE_SWITCH,
     * same as Ctrl+Space in A16: KeyGestureController LANGUAGE_SWITCH). Space autorepeat is
     * lost in this mode, so it is off by default.
     */
    private boolean handleSpaceLong(KeyEvent event) {
        final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (down && event.getRepeatCount() == 0) {
            if (KeyConfig.getInt(mResolver, KeyConfig.SPACE_LONG_LAYOUT, 0) != 1 || !imeVisible()) {
                mSpaceDeferred = false;
                return false;
            }
            mSpaceDeferred = true;
            mHandler.post(() -> {
                mSpaceLongFired = false;
                mHandler.removeCallbacks(mSpaceLong);
                mHandler.postDelayed(mSpaceLong, SPACE_LONG_MS);
            });
            return true;
        }
        if (!mSpaceDeferred) {
            return false;
        }
        if (!down) {
            mSpaceDeferred = false;
            final KeyEvent copy = new KeyEvent(event);
            mHandler.post(() -> {
                mHandler.removeCallbacks(mSpaceLong);
                if (!mSpaceLongFired) {
                    mActions.injectTap(copy);
                }
                mSpaceLongFired = false;
            });
        }
        return true;
    }

    // ================= Space bar =================

    private static final long SPACE_SINGLE_MS = 500;
    private static final String[] CALL_PKGS = {"com.android.dialer", "com.google.android.dialer"};
    private static final String[] CAMERA_PKGS = {"com.mediatek.camera", "org.lineageos.aperture"};

    // handler thread
    private boolean mSpaceSinglePending, mSpaceGoHome;
    private boolean mSpaceAnswer, mSpaceReject, mSpaceCapture;
    private final Runnable mSpaceSingle = this::onSpaceSingle;
    private final Runnable mSpaceDouble = this::onSpaceDouble;

    /** Space bar: Home, answer/end a call, camera shutter (never consumes the key). */
    private void handleSpace(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0
                || imeVisible() || !mPowerManager.isInteractive()) {
            return;
        }
        final boolean goHome = KeyConfig.getInt(mResolver, KeyConfig.SPACE_GOHOME, 0) == 1;
        final boolean answer = KeyConfig.getInt(mResolver, KeyConfig.SPACE_ANSWER, 0) == 1;
        final boolean reject = KeyConfig.getInt(mResolver, KeyConfig.SPACE_REJECT, 0) == 1;
        final boolean capture = KeyConfig.getInt(mResolver, KeyConfig.SPACE_CAPTURE, 0) == 1;
        if (!goHome && !answer && !reject && !capture) return;
        final String pkg = focusPkg();
        final boolean callScreen = isOneOf(pkg, CALL_PKGS);
        boolean camera = false;
        if (capture) {
            maybeRefreshPackages();
            camera = isOneOf(pkg, CAMERA_PKGS) || pkg.equals(mDefaultCamera);
        }
        final boolean doubleAvailable = ((answer || reject) && callScreen) || (capture && camera);
        if (!doubleAvailable) {
            if (goHome) post(() -> mActions.sendKeyDownUp(KeyEvent.KEYCODE_HOME));
            return;
        }
        final boolean a = answer && callScreen, r = reject && callScreen, c = capture && camera;
        post(() -> onSpacePress(goHome, a, r, c));
    }

    private static boolean isOneOf(String pkg, String[] list) {
        for (String p : list) {
            if (p.equals(pkg)) return true;
        }
        return false;
    }

    private void onSpacePress(boolean goHome, boolean answer, boolean reject, boolean capture) {
        if (mSpaceSinglePending) {
            // second press: the single press is cancelled, the double press runs after the
            // system multi-press timeout
            mSpaceSinglePending = false;
            mHandler.removeCallbacks(mSpaceSingle);
            mSpaceAnswer = answer;
            mSpaceReject = reject;
            mSpaceCapture = capture;
            mHandler.removeCallbacks(mSpaceDouble);
            mHandler.postDelayed(mSpaceDouble, ViewConfiguration.getMultiPressTimeout());
            return;
        }
        mSpaceSinglePending = true;
        mSpaceGoHome = goHome;
        mHandler.postDelayed(mSpaceSingle, SPACE_SINGLE_MS);
    }

    private void onSpaceSingle() {
        mSpaceSinglePending = false;
        if (!mSpaceGoHome) return;
        try {
            mActions.sendKeyDownUp(KeyEvent.KEYCODE_HOME);
        } catch (RuntimeException e) {
            Log.w(TAG, "space home", e);
        }
    }

    private void onSpaceDouble() {
        try {
            final TelecomManager tm = mContext.getSystemService(TelecomManager.class);
            final boolean ringing = tm != null && tm.isRinging();
            if (mSpaceAnswer && ringing) {
                tm.acceptRingingCall();
            } else if (mSpaceReject && tm != null && !ringing && tm.isInCall()) {
                tm.endCall();
            } else if (mSpaceCapture) {
                mActions.sendKeyDownUp(KeyEvent.KEYCODE_CAMERA);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "space double", e);
        }
    }
}
