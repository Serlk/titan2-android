package org.titan2.rear.widget;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.titan2.rear.R;
import org.titan2.rear.common.RearKeys;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Stopwatch: start/pause, lap (running) / reset (paused), the last laps. The state is kept in
 * preferences (elapsedRealtime based), so it keeps counting with the screen off.
 */
public class StopwatchPage extends WidgetPage {
    private static final String PREFS = "widget_stopwatch";
    private static final long TICK_MS = 50;
    private static final int LAPS_SHOWN = 3;

    private final SharedPreferences mPrefs;
    private TextView mTime, mStart, mSecond;
    private LinearLayout mLaps;

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            mTime.setText(format(elapsed()));
            if (isActive() && running()) postDelayed(this, TICK_MS);
        }
    };

    public StopwatchPage(Context c) {
        super(c);
        mPrefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override
    public String id() {
        return RearKeys.W_STOPWATCH;
    }

    private boolean running() {
        return mPrefs.getLong("start", 0) > 0;
    }

    private long elapsed() {
        long acc = mPrefs.getLong("acc", 0);
        long start = mPrefs.getLong("start", 0);
        return start > 0 ? acc + SystemClock.elapsedRealtime() - start : acc;
    }

    private List<Long> laps() {
        List<Long> out = new ArrayList<>();
        for (String s : TextUtils.split(mPrefs.getString("laps", ""), ",")) {
            if (!s.isEmpty()) out.add(Long.parseLong(s));
        }
        return out;
    }

    private void toggle() {
        if (running()) {
            mPrefs.edit().putLong("acc", elapsed()).putLong("start", 0).apply();
        } else {
            mPrefs.edit().putLong("start", SystemClock.elapsedRealtime()).apply();
        }
        onRefresh();
    }

    private void lapOrReset() {
        if (running()) {
            List<Long> laps = laps();
            laps.add(elapsed());
            mPrefs.edit().putString("laps", TextUtils.join(",", laps)).apply();
        } else {
            mPrefs.edit().putLong("acc", 0).putLong("start", 0).putString("laps", "").apply();
        }
        onRefresh();
    }

    @Override
    protected void build() {
        Context c = getContext();
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(px(12), 0, px(12), px(16));

        mTime = new TextView(c);
        mStyle.text(mTime, 38, mStyle.text, true);
        mTime.setGravity(Gravity.CENTER);
        mTime.setFontFeatureSettings("tnum");
        col.addView(mTime, new LinearLayout.LayoutParams(-1, -2));

        mLaps = new LinearLayout(c);
        mLaps.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2);
        ll.topMargin = px(6);
        col.addView(mLaps, ll);
        mLaps.setVisibility(mCfg.bool(RearKeys.STOPWATCH_LAPS) ? VISIBLE : GONE);

        LinearLayout buttons = new LinearLayout(c);
        buttons.setGravity(Gravity.CENTER);
        mSecond = button(c, false);
        press(mSecond, this::lapOrReset);
        mStart = button(c, true);
        press(mStart, this::toggle);
        buttons.addView(mSecond, buttonLp());
        buttons.addView(mStart, buttonLp());
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
        bl.topMargin = px(12);
        col.addView(buttons, bl);
        addView(col, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
    }

    private TextView button(Context c, boolean main) {
        TextView t = new TextView(c);
        mStyle.text(t, 14, main ? mStyle.theme.onPrimary : mStyle.onCard, main);
        t.setGravity(Gravity.CENTER);
        t.setBackground(mStyle.pill(main ? mStyle.accent : mStyle.card));
        return t;
    }

    private LinearLayout.LayoutParams buttonLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(96), px(44));
        lp.leftMargin = lp.rightMargin = px(6);
        return lp;
    }

    private static String format(long ms) {
        long cs = ms / 10;
        long s = cs / 100;
        if (s >= 3600) {
            return String.format(Locale.getDefault(), "%d:%02d:%02d", s / 3600, s / 60 % 60,
                    s % 60);
        }
        return String.format(Locale.getDefault(), "%02d:%02d.%02d", s / 60, s % 60, cs % 100);
    }

    @Override
    protected void onInactive() {
        removeCallbacks(mTick);
    }

    @Override
    protected void onRefresh() {
        if (mTime == null) return;
        boolean run = running();
        mTime.setText(format(elapsed()));
        mStart.setText(run ? R.string.widget_pause
                : elapsed() > 0 ? R.string.widget_resume : R.string.widget_start);
        mSecond.setText(run ? R.string.widget_stopwatch_lap : R.string.widget_reset);
        mSecond.setEnabled(run || elapsed() > 0);
        mSecond.setAlpha(run || elapsed() > 0 ? 1f : 0.5f);

        mLaps.removeAllViews();
        List<Long> laps = laps();
        for (int i = laps.size() - 1, n = 0; i >= 0 && n < LAPS_SHOWN; i--, n++) {
            long split = laps.get(i) - (i > 0 ? laps.get(i - 1) : 0);
            TextView t = new TextView(getContext());
            mStyle.text(t, 12, mStyle.textDim, false);
            t.setGravity(Gravity.CENTER);
            t.setFontFeatureSettings("tnum");
            t.setText(getContext().getString(R.string.widget_stopwatch_lap_row, i + 1,
                    format(split), format(laps.get(i))));
            mLaps.addView(t);
        }
        removeCallbacks(mTick);
        if (isActive() && run) postDelayed(mTick, TICK_MS);
    }
}
