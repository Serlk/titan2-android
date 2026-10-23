package org.titan2.rear.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.titan2.rear.R;
import org.titan2.rear.common.RearKeys;

import java.util.Locale;

/**
 * Spirit level. Lying flat: a bubble on two axes; upright: a horizon line with the roll
 * angle to the nearest 90 degrees. The rear screen faces away from the main one, so its
 * X axis is the device's -X (mirrored), its Y the device's Y.
 */
public class LevelPage extends WidgetPage implements SensorEventListener {
    private static final float SMOOTH = 0.2f;
    /** Within this many degrees the level counts as level (accent color). */
    private static final float LEVEL_DEG = 1f;

    private final SensorManager mSensors;
    private final Sensor mAcc;
    private final float[] mG = new float[3];
    private boolean mHaveSample;
    private Bubble mBubble;
    private TextView mDegrees;

    public LevelPage(Context c) {
        super(c);
        mSensors = c.getSystemService(SensorManager.class);
        mAcc = mSensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    @Override
    public String id() {
        return RearKeys.W_LEVEL;
    }

    @Override
    protected void build() {
        Context c = getContext();
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        mBubble = new Bubble(c);
        col.addView(mBubble, new LinearLayout.LayoutParams(px(170), px(170)));
        mDegrees = new TextView(c);
        mStyle.text(mDegrees, 20, mStyle.text, true);
        mDegrees.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, -2);
        dl.topMargin = px(8);
        col.addView(mDegrees, dl);
        mDegrees.setVisibility(mCfg.bool(RearKeys.LEVEL_DEGREES) ? VISIBLE : GONE);
        addView(col, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        if (mAcc == null) {
            mDegrees.setVisibility(VISIBLE);
            mDegrees.setText(R.string.widget_sensor_missing);
        }
    }

    @Override
    protected void onActive() {
        mHaveSample = false;
        if (mAcc != null) mSensors.registerListener(this, mAcc, SensorManager.SENSOR_DELAY_UI);
    }

    @Override
    protected void onInactive() {
        mSensors.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        for (int i = 0; i < 3; i++) {
            mG[i] = mHaveSample ? mG[i] + (e.values[i] - mG[i]) * SMOOTH : e.values[i];
        }
        mHaveSample = true;
        onRefresh();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    /** Lying flat (either face up): the gravity is mostly along Z. */
    private boolean flat() {
        float n = norm();
        return n > 0 && Math.abs(mG[2]) / n > 0.5f;
    }

    private float norm() {
        return (float) Math.sqrt(mG[0] * mG[0] + mG[1] * mG[1] + mG[2] * mG[2]);
    }

    /** Tilt angles of the rear screen's X and Y axes, degrees (flat mode). */
    private float tiltX() {
        return (float) Math.toDegrees(Math.atan2(-mG[0], Math.abs(mG[2])));
    }

    private float tiltY() {
        return (float) Math.toDegrees(Math.atan2(mG[1], Math.abs(mG[2])));
    }

    /** Upright: the roll of the screen relative to the horizon, folded to -45..45. */
    private float roll() {
        float a = (float) Math.toDegrees(Math.atan2(-mG[0], mG[1]));
        a = ((a % 90f) + 90f) % 90f;
        return a > 45f ? a - 90f : a;
    }

    @Override
    protected void onRefresh() {
        if (mBubble == null || !mHaveSample) return;
        mBubble.invalidate();
        if (flat()) {
            mDegrees.setText(String.format(Locale.getDefault(), "%.1f°   %.1f°",
                    Math.abs(tiltX()), Math.abs(tiltY())));
        } else {
            mDegrees.setText(String.format(Locale.getDefault(), "%.1f°", Math.abs(roll())));
        }
    }

    private final class Bubble extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Bubble(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas c) {
            if (!mHaveSample) return;
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float r = Math.min(cx, cy) - px(4);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(px(2));
            mPaint.setColor(mStyle.track);
            if (flat()) {
                float tx = tiltX(), ty = tiltY();
                boolean level = Math.abs(tx) < LEVEL_DEG && Math.abs(ty) < LEVEL_DEG;
                c.drawCircle(cx, cy, r, mPaint);
                c.drawCircle(cx, cy, r * 0.25f, mPaint);
                c.drawLine(cx - r, cy, cx + r, cy, mPaint);
                c.drawLine(cx, cy - r, cx, cy + r, mPaint);
                // the bubble goes to the higher side; full scale at 20 degrees
                float range = r - px(16);
                float bx = clamp(tx / 20f) * range, by = clamp(-ty / 20f) * range;
                mPaint.setStyle(Paint.Style.FILL);
                mPaint.setColor(level ? mStyle.accent : mStyle.text);
                c.drawCircle(cx + bx, cy + by, px(14), mPaint);
            } else {
                float a = roll();
                boolean level = Math.abs(a) < LEVEL_DEG;
                c.drawCircle(cx, cy, r, mPaint);
                c.save();
                // the world's up is "a" clockwise from the screen's up: so is the horizon
                c.rotate(a, cx, cy);
                mPaint.setStrokeWidth(px(4));
                mPaint.setColor(level ? mStyle.accent : mStyle.text);
                c.drawLine(cx - r, cy, cx + r, cy, mPaint);
                c.restore();
                mPaint.setStrokeWidth(px(2));
                mPaint.setColor(mStyle.track);
                c.drawLine(cx - r * 0.3f, cy, cx + r * 0.3f, cy, mPaint);
            }
        }

        private float clamp(float v) {
            return Math.max(-1f, Math.min(1f, v));
        }
    }
}
