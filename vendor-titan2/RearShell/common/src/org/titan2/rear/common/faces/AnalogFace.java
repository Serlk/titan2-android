package org.titan2.rear.common.faces;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import org.titan2.rear.common.ThemeSpec;

/**
 * Analog clock: ticks, hour/minute hands, a second hand if seconds are enabled.
 * Parameters (clock.faces.analog): ticks (all | hours | none), numbers, handWidth.
 */
public class AnalogFace implements ClockFace {
    private static final String[] NUM = {"12", "3", "6", "9"};
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);

    public AnalogFace() {
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        mText.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    public String id() {
        return "analog";
    }

    @Override
    public boolean needsSeconds(ClockState s) {
        return s.seconds;
    }

    @Override
    public void draw(Canvas c, RectF a, ClockState s) {
        float r = Math.min(a.width(), a.height()) / 2f * 0.92f;
        float cx = a.centerX(), cy = a.centerY();
        String ticks = s.params.optString("ticks", "all");
        // lenient like ThemeSpec: out of range (or NaN) becomes the nearest allowed value
        float hw = ThemeSpec.clampF((float) s.params.optDouble("handWidth", 1.0), 0.5f, 2f);
        int tick = s.color("clock.ticks", s.theme.onSurfaceVariant);
        int tickMajor = s.color("clock.ticksMajor", s.theme.onSurface);
        mPaint.setStyle(Paint.Style.STROKE);
        if (!"none".equals(ticks)) {
            for (int i = 0; i < 60; i++) {
                boolean major = i % 5 == 0;
                if (!major && "hours".equals(ticks)) continue;
                mPaint.setColor(major ? tickMajor : tick);
                mPaint.setStrokeWidth(major ? r * 0.035f : r * 0.012f);
                double ang = Math.toRadians(i * 6);
                float r0 = r * (major ? 0.86f : 0.92f);
                c.drawLine(cx + (float) Math.sin(ang) * r0, cy - (float) Math.cos(ang) * r0,
                        cx + (float) Math.sin(ang) * r, cy - (float) Math.cos(ang) * r, mPaint);
            }
        }
        if (s.params.optBoolean("numbers", false)) {
            mText.setTypeface(s.font);
            mText.setTextSize(r * 0.2f);
            mText.setColor(s.color("clock.numbers", s.theme.onSurface));
            float rr = r * 0.68f;
            float off = -(mText.ascent() + mText.descent()) / 2;
            c.drawText(NUM[0], cx, cy - rr + off, mText);
            c.drawText(NUM[1], cx + rr, cy + off, mText);
            c.drawText(NUM[2], cx, cy + rr + off, mText);
            c.drawText(NUM[3], cx - rr, cy + off, mText);
        }
        float min = s.minute() + s.second() / 60f;
        float hour = (s.now.get(java.util.Calendar.HOUR) + min / 60f);
        hand(c, cx, cy, hour * 30f, r * 0.5f, r * 0.06f * hw,
                s.color("clock.handHour", s.theme.onSurface));
        hand(c, cx, cy, min * 6f, r * 0.78f, r * 0.04f * hw,
                s.color("clock.handMinute", s.theme.onSurface));
        if (s.seconds) {
            hand(c, cx, cy, s.second() * 6f, r * 0.85f, r * 0.015f * hw,
                    s.color("clock.handSecond", s.theme.accent));
        }
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(s.color("clock.center", s.theme.accent));
        c.drawCircle(cx, cy, r * 0.05f * Math.max(1f, hw), mPaint);
    }

    private void hand(Canvas c, float cx, float cy, float deg, float len, float width, int color) {
        double ang = Math.toRadians(deg);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setColor(color);
        mPaint.setStrokeWidth(width);
        c.drawLine(cx, cy, cx + (float) Math.sin(ang) * len, cy - (float) Math.cos(ang) * len,
                mPaint);
    }
}
