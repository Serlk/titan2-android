package org.titan2.rear.facet;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Log;
import android.view.Display;

import org.titan2.rear.Cfg;
import org.titan2.rear.RearDisplays;
import org.titan2.rear.common.RearKeys;

import java.util.HashSet;
import java.util.Set;

/**
 * Notification light effects: an alerting notification plays an effect on
 * the rear screen. If the rear screen is off it is woken for the effect only (the main screen
 * stays as it is) and put to sleep again after it. Conditions, all in the settings:
 * alerting notifications only (importance default or higher, not a silent update), Do not
 * disturb, the pose (by default only when the rear screen faces up, so nothing lights in a
 * pocket), the main screen being off, at most one effect per MIN_GAP_MS.
 */
public final class FacetController {
    private static final String TAG = "RearShell";
    private static final long MIN_GAP_MS = 3000;
    /** The rear faces up: Z acceleration at most this (as the double-tap pose check). */
    private static final float REAR_UP_Z = -3f;
    private static final long POSE_TIMEOUT_MS = 400;

    /** The rear home screen that draws the effect. */
    public interface Host {
        /** Plays an effect; sleepAfter: put the rear screen to sleep when it ends. */
        void playFacet(FacetRequest r, boolean sleepAfter);

        int accentColor();

        boolean isLocked();
    }

    /** What to play. */
    public static final class FacetRequest {
        public String effect;
        public int color;
        public Drawable icon;
        public String app;
        public String title;
        public int repeat;
    }

    private static FacetController sInstance;
    private static Host sHost;

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Set<String> mKeys = new HashSet<>();
    private long mLast;

    public static synchronized FacetController get(Context c) {
        if (sInstance == null) sInstance = new FacetController(c.getApplicationContext());
        return sInstance;
    }

    public static void setHost(Host h) {
        sHost = h;
    }

    public static void clearHost(Host h) {
        if (sHost == h) sHost = null;
    }

    private FacetController(Context c) {
        mContext = c;
    }

    /** From the notification listener (any thread). */
    public void onPosted(StatusBarNotification sbn, RankingMap ranking) {
        final boolean update;
        synchronized (mKeys) {
            update = !mKeys.add(sbn.getKey());
        }
        Ranking r = new Ranking();
        boolean ranked = ranking != null && ranking.getRanking(sbn.getKey(), r);
        mHandler.post(() -> consider(sbn, ranked ? r : null, update));
    }

    /** A notification that was already there (listener connected): not new. */
    public void onSeen(StatusBarNotification sbn) {
        synchronized (mKeys) {
            mKeys.add(sbn.getKey());
        }
    }

    public void onRemoved(StatusBarNotification sbn) {
        synchronized (mKeys) {
            mKeys.remove(sbn.getKey());
        }
    }

    /** A preview from the settings: plays the configured effect regardless of the conditions. */
    public void preview() {
        mHandler.post(() -> {
            FacetRequest req = new FacetRequest();
            Cfg cfg = Cfg.get(mContext);
            req.effect = cfg.get(RearKeys.FACET_EFFECT);
            req.repeat = RearKeys.parseInt(cfg.get(RearKeys.FACET_REPEAT), 2);
            req.app = mContext.getString(org.titan2.rear.R.string.facet_preview_app);
            req.title = mContext.getString(org.titan2.rear.R.string.facet_preview_title);
            req.icon = mContext.getDrawable(org.titan2.rear.R.drawable.ic_rear);
            Host h = sHost;
            req.color = h != null ? h.accentColor() : 0xFF4FC3F7;
            show(req);
        });
    }

    private void consider(StatusBarNotification sbn, Ranking r, boolean update) {
        Cfg cfg = Cfg.get(mContext);
        if (!cfg.bool(RearKeys.FACET_ENABLED) || sHost == null) return;
        Notification n = sbn.getNotification();
        String pkg = sbn.getPackageName();
        if (pkg.equals(mContext.getPackageName()) || pkg.equals("org.titan2.parts")) return;
        if (sbn.isOngoing() || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        if (update && (n.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0) return;
        if (r != null) {
            if (r.getImportance() < NotificationManager.IMPORTANCE_DEFAULT) return;
            if (cfg.bool(RearKeys.FACET_DND) && !r.matchesInterruptionFilter()) return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - mLast < MIN_GAP_MS) return;
        if (!cfg.bool(RearKeys.FACET_MAIN_ON) && mainScreenOn()) return;

        FacetRequest req = request(sbn, r, cfg);
        if (RearKeys.POSE_ALWAYS.equals(cfg.get(RearKeys.FACET_POSE)) || rearAwake()) {
            mLast = now;
            show(req);
        } else {
            mLast = now;
            whenFacingUp(() -> show(req));
        }
    }

    private FacetRequest request(StatusBarNotification sbn, Ranking r, Cfg cfg) {
        FacetRequest req = new FacetRequest();
        Notification n = sbn.getNotification();
        req.effect = cfg.get(RearKeys.FACET_EFFECT);
        req.repeat = RearKeys.parseInt(cfg.get(RearKeys.FACET_REPEAT), 2);
        Host h = sHost;
        int accent = h != null ? h.accentColor() : 0xFF4FC3F7;
        switch (cfg.get(RearKeys.FACET_COLOR)) {
            case "white":
                req.color = 0xFFFFFFFF;
                break;
            case "accent":
                req.color = accent;
                break;
            default:
                // the app's notification color; too dark ones would not show on black
                req.color = n.color != 0 && luminance(n.color) > 0.15f ? n.color : accent;
                break;
        }
        PackageManager pm = mContext.getPackageManager();
        try {
            ApplicationInfo ai = pm.getApplicationInfo(sbn.getPackageName(), 0);
            req.app = pm.getApplicationLabel(ai).toString();
            req.icon = pm.getApplicationIcon(ai);
        } catch (PackageManager.NameNotFoundException e) {
            req.app = sbn.getPackageName();
        }
        req.title = title(n, r, cfg, h != null && h.isLocked());
        return req;
    }

    /**
     * The title, with the lock screen privacy rules: unlocked always; locked only with the
     * "clock and titles" lock mode, lock screen notifications on and a public notification
     * (or its public version).
     */
    private String title(Notification n, Ranking r, Cfg cfg, boolean locked) {
        if (!locked) {
            CharSequence t = n.extras.getCharSequence(Notification.EXTRA_TITLE);
            return t != null ? t.toString() : null;
        }
        if (!RearKeys.LOCK_CLOCK_TITLES.equals(cfg.get(RearKeys.LOCK_MODE))) return null;
        if (Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS, 1,
                UserHandle.myUserId()) == 0) {
            return null;
        }
        int visibility = n.visibility;
        if (r != null) {
            int override = r.getLockscreenVisibilityOverride();
            if (override != Ranking.VISIBILITY_NO_OVERRIDE && override < visibility) {
                visibility = override;
            }
        }
        CharSequence t = null;
        if (visibility == Notification.VISIBILITY_PUBLIC) {
            t = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        } else if (visibility == Notification.VISIBILITY_PRIVATE && n.publicVersion != null) {
            t = n.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE);
        }
        return TextUtils.isEmpty(t) ? null : t.toString();
    }

    private void show(FacetRequest req) {
        Host h = sHost;
        if (h == null) return;
        int id = RearDisplays.findId(mContext);
        if (id == Display.INVALID_DISPLAY || !RearDisplays.isEnabled(mContext, id)) return;
        boolean awake = rearAwake();
        // the overlay goes in before the wake-up, so the first frame is already the effect
        h.playFacet(req, !awake);
        if (!awake) {
            mContext.getSystemService(PowerManager.class).wakeUp(SystemClock.uptimeMillis(),
                    PowerManager.WAKE_REASON_APPLICATION, "titan2:rear-facet", id);
        }
    }

    private boolean rearAwake() {
        int id = RearDisplays.findId(mContext);
        return id != Display.INVALID_DISPLAY
                && mContext.getSystemService(PowerManager.class).isInteractive(id);
    }

    private boolean mainScreenOn() {
        Display d = mContext.getSystemService(DisplayManager.class)
                .getDisplay(Display.DEFAULT_DISPLAY);
        return d != null && d.getState() == Display.STATE_ON;
    }

    /** One accelerometer sample decides; no sample in time: nothing (it may be a pocket). */
    private void whenFacingUp(Runnable r) {
        SensorManager sm = mContext.getSystemService(SensorManager.class);
        Sensor acc = sm != null ? sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) : null;
        if (acc == null) {
            r.run();
            return;
        }
        final boolean[] done = {false};
        SensorEventListener l = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent e) {
                if (done[0]) return;
                done[0] = true;
                sm.unregisterListener(this);
                if (e.values[2] <= REAR_UP_Z) {
                    r.run();
                } else {
                    Log.d(TAG, "facet skipped, the rear screen does not face up");
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
            }
        };
        sm.registerListener(l, acc, SensorManager.SENSOR_DELAY_GAME, mHandler);
        mHandler.postDelayed(() -> {
            if (done[0]) return;
            done[0] = true;
            sm.unregisterListener(l);
        }, POSE_TIMEOUT_MS);
    }

    private static float luminance(int c) {
        return (0.2126f * ((c >> 16) & 0xFF) + 0.7152f * ((c >> 8) & 0xFF)
                + 0.0722f * (c & 0xFF)) / 255f;
    }
}
