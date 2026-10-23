package org.titan2.rear.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
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

/**
 * Compass. The heading is the direction of the device's top edge (lying flat) or of the view
 * through the rear screen (held upright), taken from the rotation matrix directly, so it is
 * right whichever face is up. The rose is drawn so that north points north on the rear screen.
 */
public class CompassPage extends WidgetPage implements SensorEventListener {
    private static final float SMOOTH = 0.15f;

    private final SensorManager mSensors;
    private final Sensor mRotation;
    private final float[] mMatrix = new float[9];
    private Dial mDial;
    private TextView mHeading, mHint;
    private float mAzimuth = Float.NaN;
    private boolean mUnreliable;

    public CompassPage(Context c) {
        super(c);
        mSensors = c.getSystemService(SensorManager.class);
        Sensor r = mSensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (r == null) r = mSensors.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR);
        mRotation = r;
    }

    @Override
    public String id() {
        return RearKeys.W_COMPASS;
    }

    @Override
    protected void build() {
        Context c = getContext();
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        mDial = new Dial(c);
        col.addView(mDial, new LinearLayout.LayoutParams(px(170), px(170)));
        mHeading = new TextView(c);
        mStyle.text(mHeading, 22, mStyle.text, true);
        mHeading.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
        hl.topMargin = px(8);
        col.addView(mHeading, hl);
        mHeading.setVisibility(mCfg.bool(RearKeys.COMPASS_DEGREES) ? VISIBLE : GONE);
        mHint = new TextView(c);
        mStyle.text(mHint, 11, mStyle.warning, false);
        mHint.setGravity(Gravity.CENTER);
        mHint.setText(R.string.widget_compass_calibrate);
        mHint.setVisibility(GONE);
        col.addView(mHint, new LinearLayout.LayoutParams(-1, -2));
        addView(col, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        if (mRotation == null) {
            mHeading.setVisibility(VISIBLE);
            mHeading.setText(R.string.widget_sensor_missing);
        }
    }

    @Override
    protected void onActive() {
        if (mRotation != null) {
            mSensors.registerListener(this, mRotation, SensorManager.SENSOR_DELAY_UI);
        }
    }

    @Override
    protected void onInactive() {
        mSensors.unregisterListener(this);
        mAzimuth = Float.NaN;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        SensorManager.getRotationMatrixFromVector(mMatrix, e.values);
        // world = R * device; device axis columns: X (0,3,6), Y (1,4,7), Z (2,5,8)
        float east, north;
        if (Math.abs(mMatrix[7]) > 0.8f) {
            // held upright: the view goes through the rear screen, along device +Z
            east = mMatrix[2];
            north = mMatrix[5];
        } else {
            east = mMatrix[1];
            north = mMatrix[4];
        }
        float az = (float) Math.toDegrees(Math.atan2(east, north));
        if (Float.isNaN(mAzimuth)) {
            mAzimuth = az;
        } else {
            float d = ((az - mAzimuth + 540f) % 360f) - 180f;
            mAzimuth = (mAzimuth + d * SMOOTH + 360f) % 360f;
        }
        onRefresh();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        mUnreliable = accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW;
        if (mHint != null) mHint.setVisibility(mUnreliable ? VISIBLE : GONE);
    }

    @Override
    protected void onRefresh() {
        if (mDial == null || Float.isNaN(mAzimuth)) return;
        mDial.invalidate();
        int deg = Math.round(mAzimuth) % 360;
        String[] dirs = getResources().getStringArray(R.array.widget_compass_directions);
        mHeading.setText(deg + "°  " + dirs[Math.round(deg / 45f) % 8]);
    }

    /** The rose turns so that its N points north; a fixed marker at the top. */
    private final class Dial extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mNeedle = new Path();

        Dial(Context c) {
            super(c);
            mPaint.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float r = Math.min(cx, cy) - px(4);
            float az = Float.isNaN(mAzimuth) ? 0f : mAzimuth;
            String[] letters = getResources().getStringArray(R.array.widget_compass_cardinal);

            // the fixed marker (where the phone points)
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(mStyle.accent);
            mNeedle.reset();
            mNeedle.moveTo(cx, cy - r - px(2));
            mNeedle.lineTo(cx - px(7), cy - r + px(10));
            mNeedle.lineTo(cx + px(7), cy - r + px(10));
            mNeedle.close();
            c.drawPath(mNeedle, mPaint);

            c.save();
            c.rotate(-az, cx, cy);
            if (mCfg.bool(RearKeys.COMPASS_TICKS)) {
                mPaint.setStyle(Paint.Style.STROKE);
                for (int a = 0; a < 360; a += 10) {
                    boolean major = a % 90 == 0;
                    mPaint.setColor(major ? mStyle.text : mStyle.track);
                    mPaint.setStrokeWidth(px(major ? 2.5f : 1.5f));
                    float len = px(major ? 12 : 7);
                    c.save();
                    c.rotate(a, cx, cy);
                    c.drawLine(cx, cy - r + px(14), cx, cy - r + px(14) + len, mPaint);
                    c.restore();
                }
            }
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setTypeface(Typeface.create(mStyle.font, Math.min(1000,
                    mStyle.theme.weight + 300), false));
            mPaint.setTextSize(px(16) * mStyle.theme.textScale);
            for (int i = 0; i < 4; i++) {
                c.save();
                c.rotate(i * 90, cx, cy);
                mPaint.setColor(i == 0 ? mStyle.warning : mStyle.text);
                c.drawText(letters[i], cx, cy - r + px(44), mPaint);
                c.restore();
            }
            // the north needle
            mPaint.setColor(mStyle.warning);
            mNeedle.reset();
            mNeedle.moveTo(cx, cy - r * 0.45f);
            mNeedle.lineTo(cx - px(6), cy);
            mNeedle.lineTo(cx + px(6), cy);
            mNeedle.close();
            c.drawPath(mNeedle, mPaint);
            mPaint.setColor(mStyle.track);
            mNeedle.reset();
            mNeedle.moveTo(cx, cy + r * 0.45f);
            mNeedle.lineTo(cx - px(6), cy);
            mNeedle.lineTo(cx + px(6), cy);
            mNeedle.close();
            c.drawPath(mNeedle, mPaint);
            c.restore();
            mPaint.setColor(mStyle.text);
            c.drawCircle(cx, cy, px(4), mPaint);
        }
    }
}
