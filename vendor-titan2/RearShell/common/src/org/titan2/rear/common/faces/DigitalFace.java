package org.titan2.rear.common.faces;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

/**
 * Large HH:MM digits; the variant with seconds shows smaller seconds in the accent color.
 * Parameters (clock.faces.digital): separator, blink (blinking separator), leadingZero.
 */
public class DigitalFace implements ClockFace {
    private final boolean mForceSeconds;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSec = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect mBounds = new Rect();

    public DigitalFace(boolean seconds) {
        mForceSeconds = seconds;
        mPaint.setTextAlign(Paint.Align.LEFT);
        mSec.setTextAlign(Paint.Align.LEFT);
    }

    @Override
    public String id() {
        return mForceSeconds ? "digital_seconds" : "digital";
    }

    @Override
    public boolean needsSeconds(ClockState s) {
        return mForceSeconds || s.seconds || s.params.optBoolean("blink", false);
    }

    @Override
    public void draw(Canvas c, RectF a, ClockState s) {
        boolean lead = s.params.optBoolean("leadingZero", s.h24);
        String sep = s.params.optString("separator", ":");
        // measured several times per frame: a theme cannot make it arbitrarily long
        if (sep.codePointCount(0, sep.length()) > 4) {
            sep = sep.substring(0, sep.offsetByCodePoints(0, 4));
        }
        String h = lead ? Faces.two(s.hour()) : String.valueOf(s.hour());
        String m = Faces.two(s.minute());
        String hm = h + sep + m;
        boolean sec = mForceSeconds || s.seconds;
        s.applyDigits(mPaint, 100f);
        float maxW = a.width() * (sec ? 0.74f : 0.94f);
        Faces.fit(mPaint, hm, maxW, a.height() * 0.62f);
        float size = mPaint.getTextSize();
        mPaint.getTextBounds(hm, 0, hm.length(), mBounds);
        float w = mPaint.measureText(hm);
        float total = sec ? w + size * 0.45f : w;
        float x = a.centerX() - total / 2;
        float base = a.centerY() + mBounds.height() / 2f;

        int hours = s.color("clock.hours", s.theme.onSurface);
        int minutes = s.color("clock.minutes", s.theme.onSurface);
        int sepColor = s.color("clock.separator", hours);
        mPaint.setColor(hours);
        c.drawText(h, x, base, mPaint);
        x += mPaint.measureText(h);
        boolean blinkOff = s.params.optBoolean("blink", false) && s.oddSecond;
        mPaint.setColor(sepColor);
        if (!blinkOff) c.drawText(sep, x, base, mPaint);
        x += mPaint.measureText(sep);
        mPaint.setColor(minutes);
        c.drawText(m, x, base, mPaint);
        x += mPaint.measureText(m);
        if (sec) {
            s.applyDigits(mSec, size * 0.4f);
            mSec.setColor(s.color("clock.seconds", s.theme.accent));
            c.drawText(Faces.two(s.second()), x + size * 0.05f, base, mSec);
        }
    }
}
