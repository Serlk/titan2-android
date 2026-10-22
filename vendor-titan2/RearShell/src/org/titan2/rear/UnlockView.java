package org.titan2.rear;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.titan2.rear.common.ThemeSpec;

import java.util.Arrays;

/**
 * PIN or pattern entry on the rear screen. The check itself happens in SystemUI
 * (Titan2RearUnlockService via UnlockClient): there are the same delays and attempt counter as
 * on the AOSP lock screen; only the input is here. The input is kept in a byte[]/char buffer
 * and wiped right after sending and on close.
 */
final class UnlockView extends FrameLayout {
    interface Listener {
        /** The credential was accepted: SystemUI lifts the lock. */
        void onUnlockAccepted();

        /** Unlocking from here is impossible (the lock type changed, the service is unavailable). */
        void onUnlockUnavailable();
    }

    private static final int MAX_PIN = 16;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Listener mListener;
    private final UnlockClient mClient;
    private final ThemeSpec mTheme;
    private final Typeface mFont;
    private final float mDp;

    private boolean mBusy;
    /** PIN: ASCII digits; length is mPinLen. Wiped with zeros. */
    private final byte[] mPin = new byte[MAX_PIN];
    private int mPinLen;
    private int mAutoLength = -1;
    private long mLockoutUntil;
    private boolean mPattern;

    private final TextView mTitle;
    private TextView mDots;
    private View mInput;

    private final Runnable mCountdown = new Runnable() {
        @Override
        public void run() {
            long left = mLockoutUntil - SystemClock.elapsedRealtime();
            if (left <= 0) {
                mLockoutUntil = 0;
                setPrompt(false);
                setInputEnabled(true);
                return;
            }
            mTitle.setText(getContext().getString(R.string.unlock_lockout,
                    (int) ((left + 999) / 1000)));
            mTitle.setTextColor(color("bouncer.error", mTheme.error));
            mHandler.postDelayed(this, 1000);
        }
    };

    UnlockView(Context context, ThemeSpec theme, Typeface font, Listener listener) {
        super(context);
        mListener = listener;
        mClient = UnlockClient.get(context);
        mTheme = theme;
        mFont = font;
        mDp = getResources().getDisplayMetrics().density;
        mTitle = new TextView(context);
        mTitle.setTypeface(font);
        mTitle.setTextSize(16);
        mTitle.setGravity(Gravity.CENTER);
        LayoutParams tl = new LayoutParams(-1, -2, Gravity.TOP);
        tl.topMargin = (int) (4 * mDp);
        addView(mTitle, tl);
        setTitle(R.string.unlock_wait, false);
    }

    private int color(String element, int fallback) {
        return mTheme.color(element, fallback);
    }

    void showPin(int autoLength, long lockoutMs) {
        mPattern = false;
        mAutoLength = autoLength;
        buildPin();
        setPrompt(false);
        startLockout(lockoutMs);
    }

    void showPattern(int size, long lockoutMs) {
        mPattern = true;
        buildPattern(size);
        setPrompt(false);
        startLockout(lockoutMs);
    }

    /** Close the input: erase what was typed, forget the pending reply. */
    void wipe() {
        mHandler.removeCallbacksAndMessages(null);
        clearPin();
        if (mInput instanceof PatternInput) ((PatternInput) mInput).reset();
        mClient.cancel();
        mBusy = false;
    }

    private void onResult(int what, Message m) {
        mBusy = false;
        if (m == null) {
            mListener.onUnlockUnavailable();
            return;
        }
        switch (m.arg1) {
            case UnlockClient.RESULT_OK:
                performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                setTitle(R.string.unlock_unlocking, false);
                setInputEnabled(false);
                mListener.onUnlockAccepted();
                break;
            case UnlockClient.RESULT_LOCKOUT:
                performHapticFeedback(HapticFeedbackConstants.REJECT);
                shake();
                if (m.arg2 > 0) startLockout(m.arg2);
                else setTitle(R.string.unlock_wrong, true); // never stay on "Checking…"
                break;
            case UnlockClient.RESULT_WRONG:
                performHapticFeedback(HapticFeedbackConstants.REJECT);
                setTitle(R.string.unlock_wrong, true);
                shake();
                break;
            default:
                mListener.onUnlockUnavailable();
                break;
        }
    }

    /** Shake the input field on error (like the AOSP bouncer). */
    private void shake() {
        View v = mInput;
        if (v == null || !Motion.enabled()) return;
        float d = 12 * mDp;
        ObjectAnimator a = ObjectAnimator.ofFloat(v, View.TRANSLATION_X,
                0, d, -d, d * 0.7f, -d * 0.7f, d * 0.35f, -d * 0.35f, 0);
        a.setDuration(Motion.dur(Motion.LONG));
        a.setInterpolator(Motion.STANDARD);
        a.start();
    }

    private void startLockout(long ms) {
        if (ms <= 0) {
            return;
        }
        mLockoutUntil = SystemClock.elapsedRealtime() + ms;
        setInputEnabled(false);
        mHandler.removeCallbacks(mCountdown);
        mCountdown.run();
    }

    private void setPrompt(boolean error) {
        setTitle(mPattern ? R.string.unlock_draw : R.string.unlock_enter, error);
    }

    private void setTitle(int res, boolean error) {
        mTitle.setText(res);
        mTitle.setTextColor(error ? color("bouncer.error", mTheme.error)
                : color("bouncer.title", mTheme.onSurface));
    }

    private boolean titleIsError() {
        return mTitle.getCurrentTextColor() == color("bouncer.error", mTheme.error)
                && mLockoutUntil == 0;
    }

    private void setInputEnabled(boolean on) {
        if (mInput != null) {
            mInput.setEnabled(on);
            mInput.animate().alpha(on ? 1f : 0.4f).setDuration(Motion.dur(Motion.SHORT)).start();
        }
    }

    private boolean canInput() {
        return !mBusy && mLockoutUntil == 0;
    }

    private void submit(byte[] credential) {
        if (!canInput()) {
            Arrays.fill(credential, (byte) 0);
            return;
        }
        mBusy = true;
        setTitle(R.string.unlock_checking, false);
        // UnlockClient erases the credential after sending
        mClient.unlock(credential, this::onResult);
    }

    // PIN

    private void clearPin() {
        Arrays.fill(mPin, (byte) 0);
        mPinLen = 0;
        updateDots();
    }

    private void updateDots() {
        if (mDots == null) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mPinLen; i++) sb.append(i == 0 ? "●" : " ●");
        mDots.setText(sb);
    }

    private void buildPin() {
        removeInput();
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(LinearLayout.VERTICAL);
        mDots = new TextView(getContext());
        mDots.setTextColor(color("bouncer.dot", mTheme.onSurface));
        mDots.setTextSize(18);
        mDots.setGravity(Gravity.CENTER);
        col.addView(mDots, new LinearLayout.LayoutParams(-1, (int) (30 * mDp)));
        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "<", "0", ">"};
        int keyBg = color("bouncer.key", ThemeSpec.over(mTheme.surface, mTheme.background));
        int onKey = color("bouncer.onKey", mTheme.onSurface);
        for (int r = 0; r < 4; r++) {
            LinearLayout row = new LinearLayout(getContext());
            for (int c = 0; c < 3; c++) {
                final String k = keys[r * 3 + c];
                View b;
                if (k.equals("<") || k.equals(">")) {
                    ImageView iv = new ImageView(getContext());
                    iv.setImageResource(k.equals("<") ? R.drawable.ic_backspace
                            : R.drawable.ic_enter);
                    iv.setScaleType(ImageView.ScaleType.CENTER);
                    iv.setImageTintList(ColorStateList.valueOf(onKey));
                    iv.setContentDescription(getContext().getString(k.equals("<")
                            ? R.string.unlock_delete : R.string.unlock_ok));
                    b = iv;
                } else {
                    TextView t = new TextView(getContext());
                    t.setText(k);
                    t.setTextSize(24);
                    t.setTypeface(mFont);
                    t.setTextColor(onKey);
                    t.setGravity(Gravity.CENTER);
                    b = t;
                }
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(100 * mDp);
                bg.setColor(k.equals("<") || k.equals(">") ? 0 : keyBg);
                GradientDrawable mask = new GradientDrawable();
                mask.setCornerRadius(100 * mDp);
                mask.setColor(0xFFFFFFFF);
                b.setBackground(new RippleDrawable(
                        ColorStateList.valueOf((onKey & 0xFFFFFF) | 0x40000000), bg, mask));
                b.setOnClickListener(v -> onPinKey(k));
                if (k.equals("<")) {
                    b.setOnLongClickListener(v -> {
                        clearPin();
                        return true;
                    });
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
                int m = (int) (5 * mDp);
                lp.setMargins(m * 2, m, m * 2, m);
                row.addView(b, lp);
            }
            col.addView(row, new LinearLayout.LayoutParams(-1, 0, 1));
        }
        LayoutParams lp = new LayoutParams(-1, -1);
        lp.topMargin = (int) (30 * mDp);
        lp.leftMargin = lp.rightMargin = (int) (16 * mDp);
        lp.bottomMargin = (int) (8 * mDp);
        addView(col, lp);
        mInput = col;
    }

    private void onPinKey(String k) {
        if (!canInput()) {
            return;
        }
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        switch (k) {
            case "<":
                if (mPinLen > 0) mPin[--mPinLen] = 0;
                break;
            case ">":
                if (mPinLen > 0) submitPin();
                return;
            default:
                if (mPinLen < MAX_PIN) mPin[mPinLen++] = (byte) k.charAt(0);
                break;
        }
        updateDots();
        if (titleIsError()) {
            setPrompt(false);
        }
        // auto-confirm only if enabled in the system (then the length is known)
        if (mAutoLength > 0 && mPinLen == mAutoLength) {
            submitPin();
        }
    }

    private void submitPin() {
        byte[] b = Arrays.copyOf(mPin, mPinLen);
        clearPin();
        submit(b);
    }

    // pattern

    private void buildPattern(int size) {
        removeInput();
        PatternInput p = new PatternInput(getContext(), Math.max(3, size));
        LayoutParams lp = new LayoutParams(-1, -1);
        lp.topMargin = (int) (34 * mDp);
        lp.leftMargin = lp.rightMargin = lp.bottomMargin = (int) (12 * mDp);
        addView(p, lp);
        mInput = p;
    }

    private void removeInput() {
        if (mInput != null) removeView(mInput);
        mInput = null;
        mDots = null;
    }

    /** A size x size grid; the result is the dot numbers '1'... in the order traversed (like LockPatternUtils). */
    private final class PatternInput extends View {
        private final int mSize;
        private final Paint mDot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mLine = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean[] mUsed;
        private final byte[] mSeq;
        private int mLen;
        private int mLast = -1;
        private float mX, mY;

        PatternInput(Context c, int size) {
            super(c);
            mSize = size;
            mUsed = new boolean[size * size];
            mSeq = new byte[size * size];
            mLine.setStrokeWidth(5 * mDp);
            mLine.setStrokeCap(Paint.Cap.ROUND);
        }

        private float cell() {
            return Math.min(getWidth(), getHeight()) / (float) mSize;
        }

        private float cx(int i) {
            return (getWidth() - cell() * mSize) / 2 + cell() * (i % mSize + 0.5f);
        }

        private float cy(int i) {
            return (getHeight() - cell() * mSize) / 2 + cell() * (i / mSize + 0.5f);
        }

        @Override
        protected void onDraw(Canvas c) {
            float r = cell() * 0.07f;
            int dot = color("bouncer.dot", mTheme.onSurface);
            int line = color("bouncer.pattern", mTheme.accent);
            mLine.setColor((line & 0xFFFFFF) | 0xC0000000);
            for (int i = 1; i < mLen; i++) {
                int a = mSeq[i - 1] - '1', b = mSeq[i] - '1';
                c.drawLine(cx(a), cy(a), cx(b), cy(b), mLine);
            }
            if (mLast >= 0 && isEnabled()) {
                c.drawLine(cx(mLast), cy(mLast), mX, mY, mLine);
            }
            for (int i = 0; i < mUsed.length; i++) {
                mDot.setColor(mUsed[i] ? line : dot);
                c.drawCircle(cx(i), cy(i), mUsed[i] ? r * 1.7f : r, mDot);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (!isEnabled() || !canInput()) {
                return true;
            }
            mX = e.getX();
            mY = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    reset();
                    if (titleIsError()) {
                        setPrompt(false);
                    }
                    hit();
                    break;
                case MotionEvent.ACTION_MOVE:
                    hit();
                    break;
                case MotionEvent.ACTION_UP:
                    mLast = -1;
                    byte[] s = Arrays.copyOf(mSeq, mLen);
                    reset();
                    if (s.length > 0) submit(s);
                    break;
                case MotionEvent.ACTION_CANCEL:
                    reset();
                    break;
            }
            invalidate();
            return true;
        }

        private void hit() {
            float rad = cell() * 0.35f;
            for (int i = 0; i < mUsed.length; i++) {
                if (!mUsed[i] && Math.hypot(mX - cx(i), mY - cy(i)) < rad) {
                    // a skipped dot is filled in exactly as LockPatternView does it (only a jump
                    // of two rows/columns; larger grids must not get extra dots)
                    if (mLast >= 0) {
                        int r0 = mLast / mSize, c0 = mLast % mSize;
                        int dr = i / mSize - r0, dc = i % mSize - c0;
                        int fr = r0, fc = c0;
                        if (Math.abs(dr) == 2 && Math.abs(dc) != 1) fr = r0 + (dr > 0 ? 1 : -1);
                        if (Math.abs(dc) == 2 && Math.abs(dr) != 1) fc = c0 + (dc > 0 ? 1 : -1);
                        int mid = fr * mSize + fc;
                        if (!mUsed[mid]) add(mid);
                    }
                    add(i);
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                }
            }
        }

        private void add(int i) {
            mUsed[i] = true;
            mSeq[mLen++] = (byte) ('1' + i);
            mLast = i;
        }

        void reset() {
            Arrays.fill(mUsed, false);
            Arrays.fill(mSeq, (byte) 0);
            mLen = 0;
            mLast = -1;
            invalidate();
        }
    }
}
