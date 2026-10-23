package org.titan2.rear.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** A circular progress ring (steps goal, timer). Children are placed over it by the page. */
public class RingView extends View {
    private final Paint mTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mProgress = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private float mValue;

    public RingView(Context c) {
        super(c);
        for (Paint p : new Paint[] {mTrack, mProgress}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
        }
    }

    public void setStyle(int track, int progress, float strokePx) {
        mTrack.setColor(track);
        mProgress.setColor(progress);
        mTrack.setStrokeWidth(strokePx);
        mProgress.setStrokeWidth(strokePx);
        invalidate();
    }

    /** 0..1 */
    public void setValue(float v) {
        v = Math.max(0f, Math.min(1f, v));
        if (v == mValue) return;
        mValue = v;
        invalidate();
    }

    public void setProgressColor(int color) {
        if (mProgress.getColor() == color) return;
        mProgress.setColor(color);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float s = mTrack.getStrokeWidth() / 2f;
        float size = Math.min(getWidth(), getHeight());
        float l = (getWidth() - size) / 2f + s, t = (getHeight() - size) / 2f + s;
        mRect.set(l, t, l + size - 2 * s, t + size - 2 * s);
        c.drawArc(mRect, 0, 360, false, mTrack);
        if (mValue > 0f) c.drawArc(mRect, -90, 360 * mValue, false, mProgress);
    }
}
