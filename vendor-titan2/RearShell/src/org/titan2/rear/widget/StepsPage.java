package org.titan2.rear.widget;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Process;
import android.util.Log;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.titan2.rear.R;
import org.titan2.rear.common.RearKeys;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Steps today from the hardware step counter (counts in the sensor hub with the screen off;
 * read only while the page is shown). The counter runs since boot, so the day total is
 * kept as acc + (counter - base); a reboot (counter going back) folds the old run into acc.
 */
public class StepsPage extends WidgetPage implements SensorEventListener {
    private static final String TAG = "RearShell";
    private static final String PREFS = "widget_steps";

    private final SensorManager mSensors;
    private final Sensor mCounter;
    private final SharedPreferences mPrefs;
    private RingView mRing;
    private TextView mSteps, mGoal, mDistance, mMessage;
    private int mToday = -1;

    public StepsPage(Context c) {
        super(c);
        mSensors = c.getSystemService(SensorManager.class);
        mCounter = mSensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        mPrefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override
    public String id() {
        return RearKeys.W_STEPS;
    }

    @Override
    protected void build() {
        Context c = getContext();
        FrameLayout ringBox = new FrameLayout(c);
        mRing = new RingView(c);
        mRing.setStyle(mStyle.track, mStyle.accent, px(10));
        ringBox.addView(mRing, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout center = new LinearLayout(c);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER);
        mSteps = new TextView(c);
        mStyle.text(mSteps, 34, mStyle.text, true);
        mSteps.setGravity(Gravity.CENTER);
        center.addView(mSteps);
        mGoal = new TextView(c);
        mStyle.text(mGoal, 12, mStyle.textDim, false);
        mGoal.setGravity(Gravity.CENTER);
        center.addView(mGoal);
        ringBox.addView(center, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(ringBox, new LinearLayout.LayoutParams(px(156), px(156)));
        mDistance = new TextView(c);
        mStyle.text(mDistance, 13, mStyle.textDim, false);
        mDistance.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, -2);
        dl.topMargin = px(10);
        col.addView(mDistance, dl);
        mDistance.setVisibility(mCfg.bool(RearKeys.STEPS_DISTANCE) ? VISIBLE : GONE);
        addView(col, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        mMessage = new TextView(c);
        mStyle.text(mMessage, 13, mStyle.textDim, false);
        mMessage.setGravity(Gravity.CENTER);
        mMessage.setText(R.string.widget_steps_none);
        mMessage.setVisibility(mCounter == null ? VISIBLE : GONE);
        col.setVisibility(mCounter == null ? GONE : VISIBLE);
        addView(mMessage, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
    }

    @Override
    protected void onActive() {
        if (mCounter == null || !ensurePermission()) return;
        mSensors.registerListener(this, mCounter, SensorManager.SENSOR_DELAY_NORMAL);
    }

    @Override
    protected void onInactive() {
        mSensors.unregisterListener(this);
    }

    /** ACTIVITY_RECOGNITION is a runtime permission: the platform-signed shell grants it to
     * itself (GRANT_RUNTIME_PERMISSIONS), no dialog on the rear screen. */
    private boolean ensurePermission() {
        Context c = getContext();
        if (c.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        try {
            c.getPackageManager().grantRuntimePermission(c.getPackageName(),
                    Manifest.permission.ACTIVITY_RECOGNITION, Process.myUserHandle());
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "steps permission: " + e);
            return false;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        mToday = today((long) e.values[0]);
        onRefresh();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private int today(long counter) {
        String day = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        long base = mPrefs.getLong("base", counter);
        long acc = mPrefs.getLong("acc", 0);
        long last = mPrefs.getLong("last", counter);
        if (!day.equals(mPrefs.getString("day", ""))) {
            // a new day: what was counted since the last reading goes to the old day
            acc = 0;
            base = last <= counter ? last : counter;
        } else if (counter < last) {
            // the counter restarted (reboot)
            acc += last - base;
            base = 0;
        }
        mPrefs.edit().putString("day", day).putLong("base", base).putLong("acc", acc)
                .putLong("last", counter).apply();
        return (int) Math.max(0, acc + counter - base);
    }

    @Override
    protected void onRefresh() {
        if (mSteps == null || mCounter == null) return;
        int goal = Math.max(1, RearKeys.parseInt(mCfg.get(RearKeys.STEPS_GOAL), 8000));
        int steps = Math.max(0, mToday);
        mSteps.setText(mToday < 0 ? "—" : String.format(Locale.getDefault(), "%,d", steps));
        mGoal.setText(getContext().getString(R.string.widget_steps_goal,
                String.format(Locale.getDefault(), "%,d", goal)));
        mRing.setValue(steps / (float) goal);
        int stride = RearKeys.parseInt(mCfg.get(RearKeys.STEPS_STRIDE), 75);
        double km = steps * stride / 100_000.0;
        mDistance.setText(getContext().getString(R.string.widget_steps_distance,
                String.format(Locale.getDefault(), "%.2f", km)));
    }
}
