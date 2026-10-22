package org.titan2.rear;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

/**
 * Horizontal home screen pager (no androidx): the page follows the finger, settling uses
 * the M3 standard curve with fling; neighboring pages fade and shrink slightly (as
 * in Launcher3). Vertical gestures on a page are not handled here but passed to the listener
 * continuously (start/move/end); the home screen drags its sheets with the finger from them.
 */
public class PagerView extends ViewGroup {
    public interface Listener {
        void onPageSelected(int page);

        /** Scroll position in pages (for the indicator). */
        void onPageScrolled(float position);

        /**
         * A vertical gesture started: up means upwards, fromTop means from the top edge.
         * true: the home screen takes the gesture.
         */
        boolean onVerticalStart(int page, boolean up, boolean fromTop);

        /** dy: offset from the start (px, negative is up). */
        void onVerticalMove(float dy);

        /** vy: velocity (px/s, negative is up); cancelled: the touch was cancelled. */
        void onVerticalEnd(float dy, float vy, boolean cancelled);
    }

    private static final int NONE = 0, HORIZONTAL = 1, VERTICAL = 2, REJECTED = 3;

    private final int mSlop;
    private final float mTopEdge;
    private final int mMinFling;
    private Listener mListener;
    private int mPage;
    private int mMode;
    private float mDownX, mDownY;
    private int mDownScroll;
    private boolean mFromTop;
    private VelocityTracker mVelocity;
    private ValueAnimator mAnim;
    private int mAnimFrom, mAnimTo;
    private boolean mTransforms = true;

    public PagerView(Context c) {
        super(c);
        ViewConfiguration vc = ViewConfiguration.get(c);
        mSlop = vc.getScaledTouchSlop();
        mMinFling = vc.getScaledMinimumFlingVelocity() * 4;
        mTopEdge = 36 * c.getResources().getDisplayMetrics().density;
        setMotionEventSplittingEnabled(false);
    }

    public void setListener(Listener l) {
        mListener = l;
    }

    /** Neighboring page effect (fade/shrink while paging). */
    public void setPageTransforms(boolean on) {
        mTransforms = on;
        applyTransforms();
    }

    public int getPage() {
        return mPage;
    }

    public int getPageCount() {
        return getChildCount();
    }

    public View getPageView(int i) {
        return getChildAt(i);
    }

    public void setPage(int page, boolean animate) {
        setPage(page, animate, 0f);
    }

    private void setPage(int page, boolean animate, float velocity) {
        page = Math.max(0, Math.min(getChildCount() - 1, page));
        boolean changed = page != mPage;
        mPage = page;
        int target = page * getWidth();
        if (mAnim != null) mAnim.cancel();
        long d = Motion.dur(Motion.MEDIUM + 50);
        if (animate && getWidth() > 0 && d > 0 && getScrollX() != target) {
            // a fling is shorter with a "brake" curve, a calm release uses standard
            float dist = Math.abs(target - getScrollX()) / (float) getWidth();
            boolean fling = Math.abs(velocity) > mMinFling;
            mAnimFrom = getScrollX();
            mAnimTo = target;
            mAnim = ValueAnimator.ofFloat(0f, 1f);
            mAnim.setDuration((long) (d * Math.max(0.45f, Math.min(1f, dist * 1.4f))
                    * (fling ? 0.8f : 1f)));
            mAnim.setInterpolator(fling ? Motion.EMPHASIZED_DECELERATE : Motion.STANDARD);
            mAnim.addUpdateListener(a -> scrollTo(
                    (int) (mAnimFrom + (mAnimTo - mAnimFrom) * a.getAnimatedFraction()), 0));
            mAnim.start();
        } else {
            scrollTo(target, 0);
        }
        if (changed && mListener != null) mListener.onPageSelected(page);
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        applyTransforms();
        if (mListener != null && getWidth() > 0) {
            mListener.onPageScrolled(getScrollX() / (float) getWidth());
        }
    }

    /** Neighboring pages: alpha and scale by distance to the center (View properties only). */
    private void applyTransforms() {
        int w = getWidth();
        if (w == 0) return;
        float amount = mTransforms ? Motion.amount() : 0f;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            float off = Math.min(1f, Math.abs((i * w - getScrollX()) / (float) w));
            v.setAlpha(1f - off * 0.6f * Math.min(1f, amount));
            float s = 1f - off * 0.08f * amount;
            v.setScaleX(s);
            v.setScaleY(s);
        }
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec), h = MeasureSpec.getSize(hSpec);
        int cw = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY);
        int ch = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(cw, ch);
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t;
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).layout(i * w, 0, (i + 1) * w, h);
        }
        if (mPage >= getChildCount()) mPage = Math.max(0, getChildCount() - 1);
        if (mAnim == null || !mAnim.isRunning()) scrollTo(mPage * w, 0);
        applyTransforms();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return track(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        track(ev);
        return true;
    }

    /** Touch handling for interception and our own processing; true: the gesture is ours. */
    private boolean track(MotionEvent ev) {
        if (mVelocity == null) mVelocity = VelocityTracker.obtain();
        Sheet.addRawMovement(mVelocity, ev);
        // screen coordinates: the pager's parent is moved/scaled while a sheet is dragged
        float x = ev.getRawX(), y = ev.getRawY();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (mAnim != null && mAnim.isRunning()) {
                    // caught the page mid-flight: keep paging from here
                    mAnim.cancel();
                    mMode = HORIZONTAL;
                } else {
                    mMode = NONE;
                }
                mDownX = x;
                mDownY = y;
                mDownScroll = getScrollX();
                mFromTop = ev.getY() < mTopEdge;
                return mMode != NONE;
            case MotionEvent.ACTION_MOVE: {
                float dx = x - mDownX, dy = y - mDownY;
                if (mMode == NONE) {
                    if (Math.abs(dx) > mSlop && Math.abs(dx) > Math.abs(dy)) {
                        mMode = getChildCount() > 1 ? HORIZONTAL : REJECTED;
                    } else if (Math.abs(dy) > mSlop && Math.abs(dy) > Math.abs(dx)) {
                        boolean take = mListener != null
                                && mListener.onVerticalStart(mPage, dy < 0, mFromTop);
                        mMode = take ? VERTICAL : REJECTED;
                        // start from the current finger position, no jump by the slop
                        mDownY = y;
                    }
                    if ((mMode == HORIZONTAL || mMode == VERTICAL) && getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                }
                if (mMode == HORIZONTAL) {
                    int max = (getChildCount() - 1) * getWidth();
                    float target = mDownScroll - dx;
                    // past the edges: with resistance
                    if (target < 0) target = target * 0.3f;
                    else if (target > max) target = max + (target - max) * 0.3f;
                    scrollTo((int) target, 0);
                } else if (mMode == VERTICAL) {
                    mListener.onVerticalMove(y - mDownY);
                }
                return mMode == HORIZONTAL || mMode == VERTICAL;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean up = ev.getActionMasked() == MotionEvent.ACTION_UP;
                int mode = mMode;
                float dx = x - mDownX, dy = y - mDownY;
                mMode = NONE;
                mVelocity.computeCurrentVelocity(1000);
                float vx = mVelocity.getXVelocity(), vy = mVelocity.getYVelocity();
                mVelocity.recycle();
                mVelocity = null;
                if (mode == HORIZONTAL) {
                    int page = Math.round(getScrollX() / (float) Math.max(1, getWidth()));
                    if (up && Math.abs(vx) > mMinFling) {
                        // a fling goes to the neighboring page in its direction
                        page = vx < 0 ? mPage + 1 : mPage - 1;
                    }
                    setPage(page, true, vx);
                } else if (mode == VERTICAL) {
                    mListener.onVerticalEnd(dy, vy, !up);
                }
                return mode == HORIZONTAL || mode == VERTICAL;
            }
            default:
                return mMode == HORIZONTAL || mMode == VERTICAL;
        }
    }

    /** Remove all pages and add new ones. */
    public void setPages(View... pages) {
        removeAllViews();
        for (View v : pages) {
            addView(v);
        }
        setPage(Math.min(mPage, pages.length - 1), false);
        applyTransforms();
    }
}
