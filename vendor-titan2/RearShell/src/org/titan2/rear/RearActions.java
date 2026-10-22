package org.titan2.rear;

import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.hardware.input.InputManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import org.titan2.rear.common.RearContract;
import org.titan2.rear.common.RearKeys;

/** System actions for the rear display: "Home", "Back", "Recents", torch, sleep. */
public final class RearActions {
    private static final String TAG = "RearShell";

    private RearActions() {}

    /** A navigation gesture action (over apps). */
    public static void run(Context c, String action, int displayId) {
        boolean locked = c.getSystemService(KeyguardManager.class).isKeyguardLocked();
        switch (action) {
            case RearKeys.ACT_HOME:
                home(c, displayId);
                break;
            case RearKeys.ACT_BACK:
                back(c, displayId);
                break;
            case RearKeys.ACT_RECENTS:
                // no task list on the lock screen
                if (!locked) start(c, RearRecentsActivity.class, displayId);
                break;
            case RearKeys.ACT_TORCH:
                if (!locked || Cfg.get(c).bool(RearKeys.LOCK_TORCH)) Torch.get(c).toggle();
                break;
            case RearKeys.ACT_SLEEP:
                sleep(c, displayId);
                break;
            default:
                break;
        }
    }

    public static void home(Context c, int displayId) {
        start(c, RearHomeActivity.class, displayId);
    }

    private static void start(Context c, Class<?> cls, int displayId) {
        Intent i = new Intent(c, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ActivityOptions o = ActivityOptions.makeBasic().setLaunchDisplayId(displayId);
        try {
            c.startActivity(i, o.toBundle());
        } catch (RuntimeException e) {
            Log.w(TAG, "start " + cls.getSimpleName() + ": " + e);
        }
    }

    /** KEYCODE_BACK (down+up) with the rear display id: goes to the focused window on it. */
    public static void back(Context c, int displayId) {
        InputManager im = c.getSystemService(InputManager.class);
        long now = SystemClock.uptimeMillis();
        for (int action : new int[] {KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
            KeyEvent ev = new KeyEvent(now, now, action, KeyEvent.KEYCODE_BACK, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                    KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                    InputDevice.SOURCE_KEYBOARD);
            ev.setDisplayId(displayId);
            im.injectInputEvent(ev, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        }
    }

    /** Turn the rear screen off (its own power group; DEVICE_POWER via the platform signature). */
    public static void sleep(Context c, int displayId) {
        try {
            c.getSystemService(PowerManager.class).goToSleep(displayId,
                    SystemClock.uptimeMillis(), PowerManager.GO_TO_SLEEP_REASON_APPLICATION, 0);
        } catch (RuntimeException e) {
            Log.w(TAG, "sleep: " + e);
        }
    }

    /** Rear screen settings (Titan2Parts) on the main display. */
    public static boolean openSettings(Context c) {
        Intent i = new Intent(RearContract.ACTION_REAR_SETTINGS)
                .setPackage(RearContract.PARTS_PACKAGE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ActivityOptions o = ActivityOptions.makeBasic()
                .setLaunchDisplayId(Display.DEFAULT_DISPLAY);
        try {
            c.startActivity(i, o.toBundle());
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "settings: " + e);
            return false;
        }
    }
}
