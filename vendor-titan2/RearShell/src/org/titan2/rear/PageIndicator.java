package org.titan2.rear;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Page dots under the pager; the active one smoothly "moves" with the scroll. */
public class PageIndicator extends View {
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float mDp;
    private int mCount;
    private float mPos;
    private int mColor = 0x80FFFFFF;
    private int mActive = 0xFFFFFFFF;

    public PageIndicator(Context c) {
        super(c);
        mDp = c.getResources().getDisplayMetrics().density;
    }

    public void setup(int count, int color, int active) {
        mCount = count;
        mColor = color;
        mActive = active;
        setVisibility(count > 1 ? VISIBLE : GONE);
        invalidate();
    }

    public void setPosition(float pos) {
        mPos = pos;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (mCount < 2) return;
        float r = 3 * mDp, gap = 10 * mDp;
        float w = (mCount - 1) * gap;
        float x0 = getWidth() / 2f - w / 2f, cy = getHeight() / 2f;
        mPaint.setColor(mColor);
        for (int i = 0; i < mCount; i++) {
            c.drawCircle(x0 + i * gap, cy, r, mPaint);
        }
        // the active dot is a stretched "pill" between pages
        float p = Math.max(0, Math.min(mCount - 1, mPos));
        int i = (int) p;
        float f = p - i;
        float left = x0 + i * gap + Math.max(0f, f - 0.5f) * 2 * gap;
        float right = x0 + i * gap + Math.min(1f, f * 2) * gap;
        mPaint.setColor(mActive);
        c.drawRoundRect(left - r * 1.3f, cy - r * 1.3f, right + r * 1.3f, cy + r * 1.3f,
                r * 1.3f, r * 1.3f, mPaint);
    }
}
