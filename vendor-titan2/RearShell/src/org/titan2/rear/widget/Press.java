package org.titan2.rear.widget;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * Widget buttons. Unlocked: a tap. Locked with pocket protection on: the action fires only
 * after the finger has stayed on the button for HOLD_MS (a fill shows the progress), so a
 * brush in a pocket or a hand does nothing.
 */
public final class Press {
    static final long HOLD_MS = 400;

    private Press() {}

    public static void bind(View v, boolean hold, WidgetStyle style, Runnable action) {
        if (!hold) {
            v.setOnTouchListener(null);
            v.setOnClickListener(x -> action.run());
            return;
        }
        v.setOnClickListener(null);
        v.setOnTouchListener(new Holder(v, style, action));
    }

    private static final class Holder implements View.OnTouchListener {
        private final View mView;
        private final Runnable mAction;
        private final Fill mFill;
        private long mDown;
        private boolean mFired;

        private final Runnable mStep = new Runnable() {
            @Override
            public void run() {
                if (mDown == 0 || mFired) return;
                float p = (SystemClock.uptimeMillis() - mDown) / (float) HOLD_MS;
                if (p >= 1f) {
                    mFired = true;
                    mFill.set(0f);
                    mView.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                    mAction.run();
                    return;
                }
                mFill.set(p);
                mView.postOnAnimation(this);
            }
        };

        Holder(View v, WidgetStyle style, Runnable action) {
            mView = v;
            mAction = action;
            mFill = new Fill((style.accent & 0xFFFFFF) | 0x66000000);
            v.setForeground(mFill);
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mDown = SystemClock.uptimeMillis();
                    mFired = false;
                    v.postOnAnimation(mStep);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (e.getX() < 0 || e.getY() < 0 || e.getX() > v.getWidth()
                            || e.getY() > v.getHeight()) {
                        cancel();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    cancel();
                    return true;
                default:
                    return true;
            }
        }

        private void cancel() {
            mDown = 0;
            mView.removeCallbacks(mStep);
            mFill.set(0f);
        }
    }

    /** A circular fill from the center, 0..1. */
    private static final class Fill extends Drawable {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float mLevel;

        Fill(int color) {
            mPaint.setColor(color);
        }

        void set(float level) {
            mLevel = level;
            invalidateSelf();
        }

        @Override
        public void draw(Canvas c) {
            if (mLevel <= 0f) return;
            Rect b = getBounds();
            float r = (float) Math.hypot(b.width(), b.height()) / 2f * mLevel;
            c.save();
            c.clipRect(b);
            c.drawCircle(b.exactCenterX(), b.exactCenterY(), r, mPaint);
            c.restore();
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
