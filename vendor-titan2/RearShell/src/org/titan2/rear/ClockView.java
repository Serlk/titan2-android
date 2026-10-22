package org.titan2.rear;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import org.titan2.rear.common.faces.ClockFace;
import org.titan2.rear.common.faces.ClockRenderer;

import java.util.List;

/**
 * Clock page: face, date, charge/notifications (ClockRenderer) and notification titles
 * in the "titles" lock mode. A face change is a smooth transition (the old one fades out, the new one
 * fades in). Tap, double tap and long press run actions from the settings.
 */
public class ClockView extends View {
    public interface Actions {
        void onClockTap(boolean doubleTap);

        void onClockLongPress();

        /** whether there is a double tap action (otherwise a single tap fires at once) */
        boolean hasDoubleTap();
    }

    private final ClockRenderer mRenderer = new ClockRenderer();
    private final TextPaint mItemPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final float mDp;
    private final GestureDetector mGestures;
    private Actions mActions;

    private int mBattery = -1;
    private boolean mCharging;
    private int mUnread = -1;
    /** the "notification titles" mode (on the lock screen, explicitly less private) */
    private List<RearNotificationListener.Item> mItems;

    // face change
    private ClockFace mOldFace;
    private float mFade = 1f;
    private ValueAnimator mFadeAnim;

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            invalidate();
            updateDescription();
            scheduleTick();
        }
    };

    public ClockView(Context c) {
        super(c);
        mDp = c.getResources().getDisplayMetrics().density;
        mGestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (mActions != null && !mActions.hasDoubleTap()) {
                    mActions.onClockTap(false);
                    return true;
                }
                return false;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (mActions != null && mActions.hasDoubleTap()) mActions.onClockTap(false);
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (mActions != null) mActions.onClockTap(true);
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (mActions != null) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                    mActions.onClockLongPress();
                }
            }
        });
    }

    public void setActions(Actions a) {
        mActions = a;
    }

    public ClockRenderer renderer() {
        return mRenderer;
    }

    /** After configure(): restart the tick; animateFace: smooth face change. */
    public void refresh(ClockFace previous, boolean animateFace) {
        if (previous != null && previous != mRenderer.face
                && !previous.id().equals(mRenderer.face.id()) && animateFace
                && isShown() && Motion.enabled()) {
            mOldFace = previous;
            if (mFadeAnim != null) mFadeAnim.cancel();
            mFadeAnim = ValueAnimator.ofFloat(0f, 1f);
            mFadeAnim.setDuration(Motion.dur(Motion.LONG));
            mFadeAnim.setInterpolator(Motion.STANDARD);
            mFadeAnim.addUpdateListener(a -> {
                mFade = a.getAnimatedFraction();
                if (mFade >= 1f) mOldFace = null;
                invalidate();
            });
            mFade = 0f;
            mFadeAnim.start();
        }
        restartTick();
    }

    public void setBattery(int level, boolean charging) {
        mBattery = level;
        mCharging = charging;
        invalidate();
    }

    public void setUnread(int count, List<RearNotificationListener.Item> items) {
        mUnread = count;
        mItems = items;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        return mGestures.onTouchEvent(e) || super.onTouchEvent(e);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        restartTick();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(mTick);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        restartTick();
    }

    /** TalkBack reads the time and date (the clock is drawn, there is no text). */
    private void updateDescription() {
        String d = DateUtils.formatDateTime(getContext(), System.currentTimeMillis(),
                DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_SHOW_DATE
                        | DateUtils.FORMAT_SHOW_WEEKDAY);
        if (!d.contentEquals(getContentDescription() != null ? getContentDescription() : "")) {
            setContentDescription(d);
        }
    }

    private void restartTick() {
        removeCallbacks(mTick);
        invalidate();
        updateDescription();
        if (isAttachedToWindow() && isShown()) {
            scheduleTick();
        }
    }

    /** The next tick exactly at a second or minute boundary. */
    private void scheduleTick() {
        long now = System.currentTimeMillis();
        long period = mRenderer.needsSeconds() ? 1000 : 60000;
        long delay = period - now % period + 5;
        postDelayed(mTick, delay);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        mRenderer.tick(getResources().getConfiguration().getLocales().get(0));
        boolean titles = mItems != null;
        float limit = titles ? h * 0.5f : 0f;
        float y;
        if (mOldFace != null && mFade < 1f) {
            int a1 = (int) (255 * (1f - mFade));
            c.saveLayerAlpha(0, 0, w, h, a1);
            mRenderer.draw(c, mOldFace, w, h, mDp, limit, mBattery, mCharging, mUnread);
            c.restore();
            c.saveLayerAlpha(0, 0, w, h, (int) (255 * mFade));
            y = mRenderer.draw(c, mRenderer.face, w, h, mDp, limit, mBattery, mCharging,
                    mUnread);
            c.restore();
        } else {
            y = mRenderer.draw(c, mRenderer.face, w, h, mDp, limit, mBattery, mCharging,
                    mUnread);
        }
        if (titles) {
            drawItems(c, y - 18 * mDp * 0.6f, w, h, 12 * mDp);
        }
    }

    private void drawItems(Canvas c, float top, int w, int h, float pad) {
        float size = 15 * mDp * mRenderer.state.theme.textScale;
        float line = size * 1.6f;
        mItemPaint.setTypeface(mRenderer.state.font);
        mItemPaint.setTextSize(size);
        mItemPaint.setTextAlign(Paint.Align.LEFT);
        mItemPaint.setColor(mRenderer.state.color("clock.titles",
                mRenderer.state.theme.onSurface));
        float y = top;
        for (RearNotificationListener.Item it : mItems) {
            if (y + line > h - pad) {
                break;
            }
            int icon = (int) (size * 1.2f);
            if (it.icon != null) {
                Drawable d = it.icon;
                d.setBounds((int) pad, (int) y, (int) pad + icon, (int) y + icon);
                d.draw(c);
            }
            CharSequence text = TextUtils.ellipsize(it.text, mItemPaint,
                    w - 2 * pad - icon - 6 * mDp, TextUtils.TruncateAt.END);
            c.drawText(text, 0, text.length(), pad + icon + 6 * mDp, y + icon * 0.85f,
                    mItemPaint);
            y += line;
        }
    }
}
