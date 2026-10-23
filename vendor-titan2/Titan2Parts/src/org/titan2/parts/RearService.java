package org.titan2.parts;

import android.app.ActivityOptions;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;
import android.view.Display;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Rear screen modes for the side keys: selfie with the main camera, QR, light.
 * Required order (RearDisplay): panel flag -> display -> wake its group ->
 * RearActivity on it (the window sets the brightness itself); exit is a repeated command or a timeout.
 * If the rear screen is enabled as a second display (RearController), the shell is back on it after
 * the mode, otherwise the display and panel are turned off.
 */
public class RearService extends Service {
    private static final String TAG = "Titan2Rear";
    static final String ACTION = "org.titan2.parts.REAR";
    static final String EXTRA_MODE = "mode";
    static final String MODE_SELFIE = "selfie";
    static final String MODE_QR = "qr";
    static final String MODE_LIGHT = "light";
    /** Broadcast to RearActivity: close or (selfie) take a picture. */
    static final String ACTION_FINISH = "org.titan2.parts.REAR_FINISH";
    static final String ACTION_SHUTTER = "org.titan2.parts.REAR_SHUTTER";

    private static final long TIMEOUT_MS = 90_000;
    private static final long DISPLAY_WAIT_MS = 3000;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mTimeout = this::off;
    /** Polls for the display after enableConnectedDisplay; cancelled by off() and a new on(). */
    private Runnable mWaiter;
    private DisplayManager mDm;
    private String mMode;
    /** A mode is running: RearController does not turn the display off under it. */
    private static volatile boolean sActive;
    /**
     * Turning the display off after a mode: closing its window waits (up to a second) and must
     * not block the main thread. One thread keeps the off/on order.
     */
    private static final ExecutorService sOffExecutor = Executors.newSingleThreadExecutor();

    /** Panel power is known (backlight flag via agolddaemon): the modes are available. */
    static final boolean SUPPORTED = true;

    static boolean isActive() {
        return sActive;
    }

    /** From KeyHandler (system_server): toggle a mode. */
    static void request(Context context, String mode) {
        // explicit component: the service has no intent-filter, and the caller is system_server (KeyHandler),
        // so new Intent(context, RearService.class) would give the "android" package
        Intent i = new Intent(ACTION).setClassName("org.titan2.parts", RearService.class.getName())
                .putExtra(EXTRA_MODE, mode);
        context.startServiceAsUser(i, UserHandle.SYSTEM);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mDm = getSystemService(DisplayManager.class);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String mode = intent != null ? intent.getStringExtra(EXTRA_MODE) : null;
        if (mode == null) {
            return START_NOT_STICKY;
        }
        if (mode.equals(mMode)) {
            if (MODE_SELFIE.equals(mode)) {
                sendBroadcast(new Intent(ACTION_SHUTTER).setPackage(getPackageName()));
                touch();
            } else {
                off();
            }
        } else {
            on(mode);
        }
        return START_NOT_STICKY;
    }

    /** Extend the timeout (a picture in selfie mode). */
    private void touch() {
        mHandler.removeCallbacks(mTimeout);
        mHandler.postDelayed(mTimeout, TIMEOUT_MS);
    }

    private void on(String mode) {
        final int id = RearDisplay.findId(this);
        if (id == Display.INVALID_DISPLAY) {
            Log.w(TAG, "rear display not found");
            if (mMode == null) stopSelf();
            return;
        }
        if (mMode != null) {
            sendBroadcast(new Intent(ACTION_FINISH).setPackage(getPackageName()));
        }
        mMode = mode;
        sActive = true;
        // after a pending turn-off of the previous mode (same thread, in order)
        final DisplayManager dm = mDm;
        sOffExecutor.execute(() -> {
            RearDisplay.setPanel(true);
            dm.enableConnectedDisplay(id);
        });
        // the display appears asynchronously, and after a re-enable it may get a new logical id:
        // look it up again until it is available for windows
        final long deadline = SystemClock.uptimeMillis() + DISPLAY_WAIT_MS;
        if (mWaiter != null) mHandler.removeCallbacks(mWaiter);
        mWaiter = new Runnable() {
            @Override
            public void run() {
                final int cur = RearDisplay.findId(RearService.this);
                Display d = cur != Display.INVALID_DISPLAY ? mDm.getDisplay(cur) : null;
                if (d != null && d.isValid()) {
                    mWaiter = null;
                    RearDisplay.wake(RearService.this, cur, "mode");
                    show(cur, mode);
                } else if (SystemClock.uptimeMillis() < deadline) {
                    mHandler.postDelayed(this, 100);
                } else {
                    Log.w(TAG, "rear display " + cur + " did not come up");
                    off();
                }
            }
        };
        mHandler.post(mWaiter);
    }

    private void show(int displayId, String mode) {
        Intent a = new Intent(this, RearActivity.class).putExtra(EXTRA_MODE, mode)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        ActivityOptions o = ActivityOptions.makeBasic().setLaunchDisplayId(displayId);
        startActivityAsUser(a, o.toBundle(), UserHandle.CURRENT);
        touch();
    }

    private void off() {
        mHandler.removeCallbacks(mTimeout);
        if (mWaiter != null) {
            mHandler.removeCallbacks(mWaiter);
            mWaiter = null;
        }
        sendBroadcast(new Intent(ACTION_FINISH).setPackage(getPackageName()));
        mMode = null;
        sActive = false;
        final int id = RearDisplay.findId(this);
        if (id != Display.INVALID_DISPLAY) {
            if (RearDisplay.isEnabled(this)) {
                RearController.launchShell(this, id);
            } else {
                // RearActivity finishes on ACTION_FINISH asynchronously; a window still on the
                // display when it is disabled would be moved to the main display
                final Context app = getApplicationContext();
                sOffExecutor.execute(() -> {
                    RearDisplay.closeApps(id);
                    // a new mode or the enabled rear screen may own the display by now
                    if (sActive || RearDisplay.isEnabled(app)) {
                        return;
                    }
                    app.getSystemService(DisplayManager.class).disableConnectedDisplay(id);
                    RearDisplay.setPanel(false);
                });
            }
        }
        stopSelf();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
