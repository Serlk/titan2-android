package org.titan2.rear;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.MotionEvent;
import android.view.VelocityTracker;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/**
 * State of a "sheet" (app list, quick panel, PIN entry): progress 0 (closed) ... 1
 * (open). While the finger is on the screen the progress follows it; on release a spring to the nearest
 * state, taking velocity into account (as in Launcher3/SystemUI). Programmatic open/close uses
 * the M3 emphasized curves. The Target only applies the progress to View properties,
 * with no allocation per frame.
 */
final class Sheet {
    interface Target {
        void onProgress(float p);

        void onSettled(boolean open);
    }

    private static final float SCALE = 1000f;

    private final Target mTarget;
    private final SpringAnimation mSpring;
    private ValueAnimator mAnim;
    private float mProgress;
    private boolean mOpen;
    /** finger travel (px) for progress 0 -> 1 */
    private float mDistance = 1f;
    /** velocity (px/s) above which a release counts as a fling */
    private float mFling = 800f;

    Sheet(Target target) {
        mTarget = target;
        mSpring = new SpringAnimation(new FloatValueHolder());
        mSpring.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_PIXELS);
        mSpring.addUpdateListener((a, value, velocity) -> apply(value / SCALE));
        mSpring.addEndListener((a, canceled, value, velocity) -> {
            if (!canceled) settle();
        });
    }

    /**
     * Feed a velocity tracker in screen coordinates. The views that drag a sheet move (translate,
     * scale) under the finger, so their local coordinates would feed the motion back into itself;
     * the drag deltas are taken from getRawX/getRawY for the same reason.
     */
    static void addRawMovement(VelocityTracker v, MotionEvent ev) {
        float dx = ev.getRawX() - ev.getX(), dy = ev.getRawY() - ev.getY();
        ev.offsetLocation(dx, dy);
        v.addMovement(ev);
        ev.offsetLocation(-dx, -dy);
    }

    void setDistance(float px) {
        mDistance = Math.max(1f, px);
    }

    void setFlingVelocity(float px) {
        mFling = px;
    }

    float progress() {
        return mProgress;
    }

    boolean isOpen() {
        return mOpen;
    }

    /** Open or opening/being dragged. */
    boolean isActive() {
        return mOpen || mProgress > 0f;
    }

    private void apply(float p) {
        mProgress = Math.max(0f, Math.min(1f, p));
        mTarget.onProgress(mProgress);
    }

    private void settle() {
        mOpen = mProgress >= 0.5f;
        apply(mOpen ? 1f : 0f);
        mTarget.onSettled(mOpen);
    }

    void cancel() {
        if (mSpring.isRunning()) mSpring.cancel();
        if (mAnim != null) {
            ValueAnimator a = mAnim;
            mAnim = null;
            a.cancel();
        }
    }

    /** Drag start: stop animations, progress from the current one. */
    void startDrag() {
        cancel();
    }

    /** Dragging: start + delta/distance (delta > 0 is towards opening). */
    void drag(float start, float deltaPx) {
        apply(start + deltaPx / mDistance);
    }

    /** Released: velocity > 0 is towards opening (px/s). */
    void release(float velocityPx) {
        boolean open;
        if (Math.abs(velocityPx) > mFling) {
            open = velocityPx > 0;
        } else {
            open = mProgress > 0.5f;
        }
        springTo(open, velocityPx / mDistance);
    }

    private void springTo(boolean open, float velocity) {
        cancel();
        if (!Motion.enabled()) {
            jumpTo(open);
            return;
        }
        SpringForce f = new SpringForce(open ? SCALE : 0f)
                .setStiffness(Motion.stiffness())
                .setDampingRatio(Motion.damping());
        mSpring.setSpring(f);
        mSpring.setStartValue(mProgress * SCALE);
        mSpring.setStartVelocity(velocity * SCALE);
        mSpring.start();
    }

    /** Open/close with the M3 curve (no finger). */
    void animateTo(boolean open) {
        if (open == mOpen && mProgress == (open ? 1f : 0f) && mAnim == null
                && !mSpring.isRunning()) {
            return;
        }
        cancel();
        long d = Motion.dur(open ? Motion.LONG : Motion.MEDIUM);
        if (d <= 0) {
            jumpTo(open);
            return;
        }
        final float from = mProgress, to = open ? 1f : 0f;
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration((long) (d * Math.max(0.35f, Math.abs((open ? 1f : 0f) - mProgress))));
        a.setInterpolator(open ? Motion.EMPHASIZED_DECELERATE : Motion.EMPHASIZED_ACCELERATE);
        // getAnimatedFraction: no Float boxing on every frame
        a.addUpdateListener(x -> apply(from + (to - from) * x.getAnimatedFraction()));
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator x) {
                if (mAnim == x) {
                    mAnim = null;
                    settle();
                }
            }
        });
        mAnim = a;
        a.start();
    }

    void jumpTo(boolean open) {
        cancel();
        apply(open ? 1f : 0f);
        settle();
    }
}
