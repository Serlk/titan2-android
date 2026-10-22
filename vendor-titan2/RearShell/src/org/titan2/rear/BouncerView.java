package org.titan2.rear;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Message;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.titan2.rear.common.ThemeSpec;

/**
 * Rear screen "bouncer": slides up following the finger on a swipe up from the clock on the lock screen
 * (like the AOSP lock screen). Once open, it asks SystemUI what is needed:
 *   can_skip (no PIN, trusted device, fingerprint already recognized): unlock at once;
 *   available (PIN or pattern): entry (UnlockView);
 *   otherwise (password, admin policy): "unlock the main screen".
 * Closed with a swipe down (by the top part) or "Back"; the input is erased.
 */
final class BouncerView extends FrameLayout implements UnlockView.Listener {
    interface Host {
        void onBouncerDragStart();

        /** dy > 0: down (towards closing) */
        void onBouncerDrag(float dy);

        void onBouncerDragEnd(float vy);

        /** Not possible from here: show a hint and request unlocking on the main screen. */
        void onBouncerMainScreen();
    }

    private final Host mHost;
    private final UnlockClient mClient;
    private final float mDp;
    private final int mSlop;
    private final View mHandle;
    private final TextView mMessage;
    private ThemeSpec mTheme = new ThemeSpec();
    private Typeface mFont = Typeface.DEFAULT;
    private UnlockView mInput;
    private boolean mStarted;

    private float mDownX, mDownY;
    private boolean mDragging, mCanDrag;
    private VelocityTracker mVelocity;

    BouncerView(Context c, Host host) {
        super(c);
        mHost = host;
        mClient = UnlockClient.get(c);
        mDp = c.getResources().getDisplayMetrics().density;
        mSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        setClickable(true);
        mHandle = new View(c);
        LayoutParams hl = new LayoutParams((int) (32 * mDp), (int) (4 * mDp),
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hl.topMargin = (int) (8 * mDp);
        addView(mHandle, hl);
        mMessage = new TextView(c);
        mMessage.setGravity(Gravity.CENTER);
        mMessage.setTextSize(17);
        int pad = (int) (24 * mDp);
        mMessage.setPadding(pad, pad, pad, pad);
        addView(mMessage, new LayoutParams(-1, -1));
    }

    void setup(ThemeSpec t, Typeface font) {
        mTheme = t;
        mFont = font;
        float r = 28 * mDp;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadii(new float[] {r, r, r, r, 0, 0, 0, 0});
        bg.setColor(t.color("bouncer.background", ThemeSpec.over(
                (t.surface & 0xFFFFFF) | 0x14000000, t.background)));
        setBackground(bg);
        GradientDrawable hb = new GradientDrawable();
        hb.setCornerRadius(2 * mDp);
        hb.setColor(t.onSurfaceVariant);
        mHandle.setBackground(hb);
        mMessage.setTypeface(font);
        mMessage.setTextColor(t.color("bouncer.title", t.onSurface));
    }

    /** The sheet opened: ask SystemUI which input is needed. */
    void start() {
        if (mStarted) return;
        mStarted = true;
        removeInput();
        message(R.string.unlock_wait);
        mClient.query(this::onQuery);
    }

    /** The sheet closed: erase the input. */
    void stop() {
        mStarted = false;
        mClient.cancel();
        removeInput();
    }

    private void message(int res) {
        mMessage.setText(res);
        mMessage.setVisibility(VISIBLE);
    }

    private void removeInput() {
        if (mInput != null) {
            mInput.wipe();
            removeView(mInput);
            mInput = null;
        }
    }

    private void onQuery(int what, Message m) {
        if (!mStarted) return;
        if (m == null) {
            mainScreen();
            return;
        }
        Bundle b = m.getData();
        final int type = m.arg1;
        final int grid = m.arg2;
        final boolean available = b.getBoolean("available");
        final long lockout = b.getLong("lockout_ms");
        final int pinLength = b.getInt("pin_length", -1);
        if (b.getBoolean("can_skip")) {
            message(R.string.unlock_unlocking);
            mClient.dismiss((w, r) -> {
                if (!mStarted) return;
                if (r != null && r.arg1 == UnlockClient.RESULT_OK) return; // wait for keyguard
                // input is already needed (e.g. trust expired)
                showInput(available, type, grid, pinLength, lockout);
            });
            return;
        }
        showInput(available, type, grid, pinLength, lockout);
    }

    private void showInput(boolean available, int type, int grid, int pinLength, long lockout) {
        if (!available || (type != UnlockClient.TYPE_PIN && type != UnlockClient.TYPE_PATTERN)) {
            mainScreen();
            return;
        }
        mMessage.setVisibility(GONE);
        mInput = new UnlockView(getContext(), mTheme, mFont, this);
        LayoutParams lp = new LayoutParams(-1, -1);
        lp.topMargin = (int) (16 * mDp);
        addView(mInput, lp);
        if (type == UnlockClient.TYPE_PIN) mInput.showPin(pinLength, lockout);
        else mInput.showPattern(grid, lockout);
        // the input fades in instead of appearing abruptly
        if (Motion.enabled()) {
            mInput.setAlpha(0f);
            mInput.setTranslationY(16 * mDp);
            mInput.animate().alpha(1f).translationY(0f).setDuration(Motion.dur(Motion.MEDIUM))
                    .setInterpolator(Motion.EMPHASIZED_DECELERATE).start();
        }
    }

    private void mainScreen() {
        removeInput();
        message(R.string.unlock_main_screen);
        mHost.onBouncerMainScreen();
    }

    @Override
    public void onUnlockAccepted() {
        // the lock is lifted in SystemUI; the shell learns about it from KeyguardManager
    }

    @Override
    public void onUnlockUnavailable() {
        mainScreen();
    }

    // swipe down closes (by the top part, or anywhere if there is no input)

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return track(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        track(ev);
        return true;
    }

    private boolean track(MotionEvent ev) {
        if (mVelocity == null) mVelocity = VelocityTracker.obtain();
        Sheet.addRawMovement(mVelocity, ev);
        // screen coordinates: the bouncer itself moves under the finger while dragged
        float x = ev.getRawX(), y = ev.getRawY();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = x;
                mDownY = y;
                mDragging = false;
                mCanDrag = mInput == null || ev.getY() < getPaddingTop() + 56 * mDp;
                return false;
            case MotionEvent.ACTION_MOVE: {
                float dy = y - mDownY, dx = x - mDownX;
                if (!mDragging && mCanDrag && dy > mSlop && dy > Math.abs(dx)) {
                    mDragging = true;
                    mDownY = y;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    mHost.onBouncerDragStart();
                }
                if (mDragging) mHost.onBouncerDrag(y - mDownY);
                return mDragging;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean was = mDragging;
                mDragging = false;
                mVelocity.computeCurrentVelocity(1000);
                float vy = mVelocity.getYVelocity();
                mVelocity.recycle();
                mVelocity = null;
                if (was) mHost.onBouncerDragEnd(ev.getActionMasked() == MotionEvent.ACTION_UP
                        ? vy : 0f);
                return was;
            }
            default:
                return mDragging;
        }
    }
}
