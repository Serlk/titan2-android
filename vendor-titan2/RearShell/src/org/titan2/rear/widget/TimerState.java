package org.titan2.rear.widget;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import org.titan2.rear.Cfg;
import org.titan2.rear.RearDisplays;
import org.titan2.rear.RearHomeActivity;
import org.titan2.rear.common.RearKeys;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Countdown timer state (kept in preferences, so it survives the page and the process) and
 * its alarm: an exact AlarmManager alarm fires the receiver even in suspend; the shell
 * process is persistent, so it plays the sound / vibration itself, wakes the rear screen and
 * shows the timer page. The ringing stops on "Stop" or after RING_MS.
 */
public final class TimerState {
    private static final String TAG = "RearShell";
    private static final String PREFS = "widget_timer";
    static final String ACTION_FIRE = "org.titan2.rear.TIMER_FIRE";
    private static final long RING_MS = 60_000;

    public interface Listener {
        void onTimerChanged();
    }

    private static TimerState sInstance;

    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> mListeners = new CopyOnWriteArrayList<>();
    private Ringtone mRingtone;
    private boolean mRinging;
    private final Runnable mStopRinging = this::stopRinging;

    public static synchronized TimerState get(Context c) {
        if (sInstance == null) sInstance = new TimerState(c.getApplicationContext());
        return sInstance;
    }

    private TimerState(Context c) {
        mContext = c;
        mPrefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void addListener(Listener l) {
        mListeners.addIfAbsent(l);
    }

    public void removeListener(Listener l) {
        mListeners.remove(l);
    }

    private void changed() {
        for (Listener l : mListeners) l.onTimerChanged();
    }

    /** The set duration, ms. */
    public long duration() {
        return mPrefs.getLong("duration", 300_000);
    }

    public boolean running() {
        return mPrefs.getLong("end", 0) > 0;
    }

    public boolean ringing() {
        return mRinging;
    }

    /** Remaining ms: counting down, paused, or the full duration when reset. */
    public long remaining() {
        long end = mPrefs.getLong("end", 0);
        if (end > 0) return Math.max(0, end - SystemClock.elapsedRealtime());
        long paused = mPrefs.getLong("paused", -1);
        return paused >= 0 ? paused : duration();
    }

    public boolean paused() {
        return !running() && mPrefs.getLong("paused", -1) >= 0;
    }

    public void setDuration(long ms) {
        mPrefs.edit().putLong("duration", Math.max(1000, ms)).putLong("paused", -1).apply();
        if (running()) cancelAlarm();
        mPrefs.edit().putLong("end", 0).apply();
        changed();
    }

    public void start() {
        stopRinging();
        long end = SystemClock.elapsedRealtime() + remaining();
        mPrefs.edit().putLong("end", end).putLong("paused", -1).apply();
        setAlarm(end);
        changed();
    }

    public void pause() {
        if (!running()) return;
        long left = remaining();
        cancelAlarm();
        mPrefs.edit().putLong("end", 0).putLong("paused", left).apply();
        changed();
    }

    public void reset() {
        cancelAlarm();
        stopRinging();
        mPrefs.edit().putLong("end", 0).putLong("paused", -1).apply();
        changed();
    }

    private PendingIntent alarmIntent() {
        Intent i = new Intent(ACTION_FIRE).setClass(mContext, Receiver.class);
        return PendingIntent.getBroadcast(mContext, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void setAlarm(long end) {
        AlarmManager am = mContext.getSystemService(AlarmManager.class);
        am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, end, alarmIntent());
    }

    private void cancelAlarm() {
        mContext.getSystemService(AlarmManager.class).cancel(alarmIntent());
    }

    /** The alarm fired. */
    void fire() {
        if (!running()) return;
        mPrefs.edit().putLong("end", 0).putLong("paused", -1).apply();
        Cfg cfg = Cfg.get(mContext);
        mRinging = true;
        if (cfg.bool(RearKeys.TIMER_SOUND)) {
            Uri uri = RingtoneManager.getActualDefaultRingtoneUri(mContext,
                    RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            mRingtone = RingtoneManager.getRingtone(mContext, uri);
            if (mRingtone != null) {
                mRingtone.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                mRingtone.setLooping(true);
                mRingtone.play();
            }
        }
        if (cfg.bool(RearKeys.TIMER_VIBRATE)) {
            Vibrator v = mContext.getSystemService(Vibrator.class);
            if (v != null) {
                v.vibrate(VibrationEffect.createWaveform(new long[] {0, 600, 400}, 0));
            }
        }
        mHandler.postDelayed(mStopRinging, RING_MS);
        // wake the rear screen and show the timer page
        int id = RearDisplays.findId(mContext);
        if (id >= 0) {
            mContext.getSystemService(PowerManager.class).wakeUp(SystemClock.uptimeMillis(),
                    PowerManager.WAKE_REASON_APPLICATION, "titan2:rear-timer", id);
        }
        RearHomeActivity.showPage(mContext, RearKeys.W_TIMER);
        changed();
    }

    public void stopRinging() {
        if (!mRinging) return;
        mRinging = false;
        mHandler.removeCallbacks(mStopRinging);
        if (mRingtone != null) {
            mRingtone.stop();
            mRingtone = null;
        }
        Vibrator v = mContext.getSystemService(Vibrator.class);
        if (v != null) v.cancel();
        changed();
    }

    /** The exact alarm (not exported; the PendingIntent is the shell's own). */
    public static final class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent intent) {
            if (ACTION_FIRE.equals(intent.getAction())) {
                Log.i(TAG, "timer finished");
                TimerState.get(c).fire();
            }
        }
    }
}
