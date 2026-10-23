package org.titan2.rear.widget;

import android.content.Context;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.titan2.rear.R;
import org.titan2.rear.common.RearKeys;

import java.util.Locale;

/** Countdown timer: presets (settings), +1 min, start/pause, reset; Stop while it rings. */
public class TimerPage extends WidgetPage implements TimerState.Listener {
    private static final long TICK_MS = 200;
    private static final int MAX_PRESETS = 4;

    private final TimerState mTimer;
    private RingView mRing;
    private TextView mTime, mStart, mReset, mStop;
    private LinearLayout mControls;

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            onRefresh();
            if (isActive() && mTimer.running()) postDelayed(this, TICK_MS);
        }
    };

    public TimerPage(Context c) {
        super(c);
        mTimer = TimerState.get(c);
    }

    @Override
    public String id() {
        return RearKeys.W_TIMER;
    }

    @Override
    protected void build() {
        Context c = getContext();
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(0, 0, 0, px(16));

        FrameLayout ringBox = new FrameLayout(c);
        mRing = new RingView(c);
        mRing.setStyle(mStyle.track, mStyle.accent, px(8));
        ringBox.addView(mRing, new FrameLayout.LayoutParams(-1, -1));
        mTime = new TextView(c);
        mStyle.text(mTime, 30, mStyle.text, true);
        mTime.setGravity(Gravity.CENTER);
        ringBox.addView(mTime, new FrameLayout.LayoutParams(-1, -1));
        col.addView(ringBox, new LinearLayout.LayoutParams(px(132), px(132)));

        // presets: one row that fits (no scrolling: horizontal swipes belong to the pager)
        LinearLayout presets = new LinearLayout(c);
        presets.setPadding(px(10), 0, px(10), 0);
        int shown = 0;
        for (String p : mCfg.list(RearKeys.TIMER_PRESETS)) {
            int sec = RearKeys.parseInt(p, 0);
            if (sec <= 0) continue;
            TextView chip = chip(c, label(sec * 1000L), false);
            press(chip, () -> mTimer.setDuration(sec * 1000L));
            presets.addView(chip, chipLp());
            if (++shown >= MAX_PRESETS) break;
        }
        TextView plus = chip(c, "+1:00", false);
        press(plus, () -> {
            if (mTimer.running()) return;
            mTimer.setDuration(mTimer.remaining() + 60_000);
        });
        presets.addView(plus, chipLp());
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = px(10);
        col.addView(presets, sl);

        mControls = new LinearLayout(c);
        mControls.setGravity(Gravity.CENTER);
        mReset = chip(c, c.getString(R.string.widget_reset), false);
        press(mReset, mTimer::reset);
        mStart = chip(c, "", true);
        press(mStart, () -> {
            if (mTimer.running()) mTimer.pause(); else mTimer.start();
        });
        mControls.addView(mReset, buttonLp());
        mControls.addView(mStart, buttonLp());
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.topMargin = px(10);
        col.addView(mControls, cl);

        mStop = chip(c, c.getString(R.string.widget_timer_stop), true);
        mStop.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, mStyle.sp(18));
        // the ringing alarm stops on a plain tap even when locked
        mStop.setOnClickListener(v -> mTimer.stopRinging());
        col.addView(mStop, new LinearLayout.LayoutParams(px(160), px(52)));

        addView(col, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
    }

    private TextView chip(Context c, String text, boolean main) {
        TextView t = new TextView(c);
        mStyle.text(t, 13, main ? mStyle.theme.onPrimary : mStyle.onCard, main);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(4), px(8), px(4), px(8));
        t.setSingleLine(true);
        t.setBackground(mStyle.pill(main ? mStyle.accent : mStyle.card));
        return t;
    }

    private LinearLayout.LayoutParams chipLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, px(34), 1f);
        lp.leftMargin = lp.rightMargin = px(2);
        return lp;
    }

    private LinearLayout.LayoutParams buttonLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(96), px(42));
        lp.leftMargin = lp.rightMargin = px(6);
        return lp;
    }

    private static String label(long ms) {
        long s = (ms + 999) / 1000;
        if (s >= 3600) {
            return String.format(Locale.getDefault(), "%d:%02d:%02d", s / 3600, s / 60 % 60,
                    s % 60);
        }
        return String.format(Locale.getDefault(), "%d:%02d", s / 60, s % 60);
    }

    @Override
    protected void onActive() {
        mTimer.addListener(this);
    }

    @Override
    protected void onInactive() {
        mTimer.removeListener(this);
        removeCallbacks(mTick);
    }

    @Override
    public void onTimerChanged() {
        post(this::onRefresh);
    }

    @Override
    protected void onRefresh() {
        if (mTime == null) return;
        boolean ringing = mTimer.ringing();
        long left = ringing ? 0 : mTimer.remaining();
        mTime.setText(label(left));
        mTime.setTextColor(ringing ? mStyle.warning : mStyle.text);
        mRing.setProgressColor(ringing ? mStyle.warning : mStyle.accent);
        mRing.setValue(ringing ? 1f : left / (float) Math.max(1, mTimer.duration()));
        mStop.setVisibility(ringing ? VISIBLE : GONE);
        mControls.setVisibility(ringing ? GONE : VISIBLE);
        mStart.setText(mTimer.running() ? R.string.widget_pause
                : mTimer.paused() ? R.string.widget_resume : R.string.widget_start);
        removeCallbacks(mTick);
        if (isActive() && mTimer.running()) postDelayed(mTick, TICK_MS);
    }
}
