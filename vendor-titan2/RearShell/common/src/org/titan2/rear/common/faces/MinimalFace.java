package org.titan2.rear.common.faces;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

/** Minimal: hours above minutes, minutes in the accent color. Parameter: leadingZero. */
public class MinimalFace implements ClockFace {
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect mBounds = new Rect();

    public MinimalFace() {
        mPaint.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    public String id() {
        return "minimal";
    }

    @Override
    public boolean needsSeconds(ClockState s) {
        return false;
    }

    @Override
    public void draw(Canvas c, RectF a, ClockState s) {
        boolean lead = s.params.optBoolean("leadingZero", s.h24);
        String h = lead ? Faces.two(s.hour()) : String.valueOf(s.hour());
        String m = Faces.two(s.minute());
        s.applyDigits(mPaint, 100f);
        Faces.fit(mPaint, "88", a.width() * 0.8f, a.height() * 0.42f);
        mPaint.getTextBounds("88", 0, 2, mBounds);
        float gap = mBounds.height() * 0.15f;
        float top = a.centerY() - mBounds.height() - gap / 2;
        mPaint.setColor(s.color("clock.hours", s.theme.onSurface));
        c.drawText(h, a.centerX(), top + mBounds.height(), mPaint);
        mPaint.setColor(s.color("clock.minutes", s.theme.accent));
        c.drawText(m, a.centerX(), top + 2 * mBounds.height() + gap, mPaint);
    }
}
