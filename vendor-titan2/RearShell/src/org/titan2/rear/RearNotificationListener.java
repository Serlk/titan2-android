package org.titan2.rear;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import org.titan2.rear.facet.FacetController;

import java.util.ArrayList;
import java.util.List;

/**
 * Notification counter for the clock, and the source of the notification light effects
 * (facet.FacetController). Works only if the user granted notification access
 * (link in the settings); without access the counter is simply not shown (count = -1).
 * Counts dismissable (not ongoing) ones, not group summaries.
 */
public class RearNotificationListener extends NotificationListenerService {

    /** A line for the "titles" mode: app icon + text. */
    public static final class Item {
        public final Drawable icon;
        public final String text;

        Item(Drawable icon, String text) {
            this.icon = icon;
            this.text = text;
        }
    }

    public interface Callback {
        void onNotificationsChanged();
    }

    private static final Handler sMain = new Handler(Looper.getMainLooper());
    private static final List<Callback> sCallbacks = new ArrayList<>();
    private static RearNotificationListener sInstance;
    private static int sCount = -1;

    public static ComponentName component(Context c) {
        return new ComponentName(c, RearNotificationListener.class);
    }

    public static int count() {
        return sCount;
    }

    public static void addCallback(Callback cb) {
        sCallbacks.add(cb);
    }

    public static void removeCallback(Callback cb) {
        sCallbacks.remove(cb);
    }

    /**
     * Titles for the lock screen, with the same privacy rules as the system lock screen: nothing
     * if "notifications on the lock screen" is off; VISIBILITY_SECRET notifications (by the app
     * or by the channel/user override in the ranking) are skipped, for VISIBILITY_PRIVATE the
     * public version or only the app name is used.
     */
    public static List<Item> items(Context c, int max) {
        List<Item> out = new ArrayList<>();
        RearNotificationListener l = sInstance;
        if (l == null) {
            return out;
        }
        if (Settings.Secure.getIntForUser(c.getContentResolver(),
                Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS, 1,
                UserHandle.myUserId()) == 0) {
            return out;
        }
        StatusBarNotification[] all;
        RankingMap ranking;
        try {
            all = l.getActiveNotifications();
            ranking = l.getCurrentRanking();
        } catch (RuntimeException e) {
            return out;
        }
        if (all == null) {
            return out;
        }
        PackageManager pm = c.getPackageManager();
        Ranking r = new Ranking();
        for (StatusBarNotification sbn : all) {
            if (!counts(sbn)) continue;
            Notification n = sbn.getNotification();
            int visibility = n.visibility;
            if (ranking != null && ranking.getRanking(sbn.getKey(), r)) {
                int override = r.getLockscreenVisibilityOverride();
                // the override can only make it more private (SECRET < PRIVATE < PUBLIC)
                if (override != NotificationListenerService.Ranking.VISIBILITY_NO_OVERRIDE
                        && override < visibility) {
                    visibility = override;
                }
            }
            if (visibility <= Notification.VISIBILITY_SECRET) continue;
            String app = sbn.getPackageName();
            Drawable icon = null;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(app, 0);
                app = pm.getApplicationLabel(ai).toString();
                icon = pm.getApplicationIcon(ai);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            CharSequence title = null;
            if (visibility == Notification.VISIBILITY_PUBLIC) {
                title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
            } else if (n.publicVersion != null) {
                title = n.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE);
            }
            out.add(new Item(icon, TextUtils.isEmpty(title) ? app : app + ": " + title));
            if (out.size() >= max) break;
        }
        return out;
    }

    private static boolean counts(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        return !sbn.isOngoing() && (n.flags & Notification.FLAG_GROUP_SUMMARY) == 0;
    }

    private void recount() {
        int c = 0;
        try {
            StatusBarNotification[] all = getActiveNotifications();
            if (all != null) {
                for (StatusBarNotification sbn : all) {
                    if (counts(sbn)) c++;
                }
            }
        } catch (RuntimeException e) {
            c = -1;
        }
        final int count = c;
        sMain.post(() -> {
            sCount = count;
            for (Callback cb : new ArrayList<>(sCallbacks)) {
                cb.onNotificationsChanged();
            }
        });
    }

    @Override
    public void onListenerConnected() {
        sInstance = this;
        // the notifications already shown are not new: their updates must not light up
        try {
            StatusBarNotification[] all = getActiveNotifications();
            if (all != null) {
                for (StatusBarNotification sbn : all) FacetController.get(this).onSeen(sbn);
            }
        } catch (RuntimeException ignored) {
        }
        recount();
    }

    @Override
    public void onListenerDisconnected() {
        sInstance = null;
        sMain.post(() -> {
            sCount = -1;
            for (Callback cb : new ArrayList<>(sCallbacks)) {
                cb.onNotificationsChanged();
            }
        });
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        FacetController.get(this).onPosted(sbn, rankingMap);
        recount();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap) {
        FacetController.get(this).onRemoved(sbn);
        recount();
    }
}
