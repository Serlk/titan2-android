package org.titan2.rear.common.faces;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.format.DateFormat;

import org.titan2.rear.common.Effective;
import org.titan2.rear.common.ThemeSpec;

import java.util.Calendar;
import java.util.Locale;

/**
 * The clock page without a View: face + date + status line (charge, number of
 * notifications). Used by the shell ClockView and the theme preview in Titan2Parts.
 */
public final class ClockRenderer {
    public final ClockState state = new ClockState();
    public ClockFace face = Faces.create("digital");
    public boolean showDate = true;
    public boolean showBattery = true;
    public boolean showCount = true;
    public String dateFormat = "EEE d MMM";
    public float clockSize = 1f;

    private final Paint mInfo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mArea = new RectF();
    private Typeface mInfoFont = Typeface.DEFAULT;
    private float mTextScale = 1f;
    private String mPattern;
    private String mPatternSkeleton;
    private Locale mPatternLocale;
    private final StringBuilder mStatus = new StringBuilder();

    public ClockRenderer() {
        mInfo.setTextAlign(Paint.Align.CENTER);
    }

    /** Font with a weight (API 28+); weight <= 0: unchanged. */
    public static Typeface weighted(Typeface base, int weight) {
        if (base == null) base = Typeface.DEFAULT;
        if (weight <= 0) return base;
        return Typeface.create(base, Math.max(1, Math.min(1000, weight)), false);
    }

    /**
     * Set up from the theme and settings. textFont/clockFont: already loaded families (a name or
     * a theme file), system24: the system 24-hour format.
     */
    public void configure(ThemeSpec t, Effective.Values s, Typeface textFont,
            Typeface clockFont, boolean system24) {
        state.theme = t;
        String id = Effective.face(t, s);
        if (face == null || !face.id().equals(id)) face = Faces.create(id);
        state.params = t.faceParams(face.id());
        state.seconds = Effective.seconds(t, s);
        Boolean h = Effective.h24(t, s);
        state.h24 = h != null ? h : system24;
        state.digits = t.digits;
        int w = "bold".equals(t.digits) ? 700 : "thin".equals(t.digits) ? 200
                : t.clockWeight > 0 ? t.clockWeight : t.weight;
        state.font = weighted(clockFont != null ? clockFont : textFont, w);
        mInfoFont = weighted(textFont, t.weight);
        mTextScale = t.textScale;
        showDate = Effective.date(t, s);
        dateFormat = Effective.dateFormat(t, s);
        showBattery = Effective.battery(t, s);
        showCount = Effective.notifications(t, s);
        clockSize = t.clockSize;
    }

    public void setFace(ClockFace f) {
        face = f;
        state.params = state.theme.faceParams(f.id());
    }

    public boolean needsSeconds() {
        return face.needsSeconds(state);
    }

    /** Update the time (once per drawn frame). */
    public void tick(Locale locale) {
        state.now = Calendar.getInstance();
        state.locale = locale;
        state.oddSecond = state.second() % 2 == 1;
    }

    /** The "⚡85%   ✉ 3" line (empty if there is nothing to show). */
    public CharSequence status(int battery, boolean charging, int unread) {
        mStatus.setLength(0);
        if (showBattery && battery >= 0) {
            if (charging) mStatus.append('⚡');
            mStatus.append(battery).append('%');
        }
        if (showCount && unread > 0) {
            if (mStatus.length() > 0) mStatus.append("   ");
            mStatus.append("✉ ").append(unread);
        }
        return mStatus;
    }

    /**
     * Draw the clock page in a w x h rectangle. bottomLimit: the bottom of the clock
     * area (for the "notification titles" mode; 0: the whole screen). Returns y below the status
     * line.
     */
    public float draw(Canvas c, ClockFace f, float w, float h, float dp, float bottomLimit,
            int battery, boolean charging, int unread) {
        float pad = 12 * dp;
        float infoSize = 18 * dp * mTextScale;
        CharSequence status = status(battery, charging, unread);
        float infoLines = (showDate ? 1 : 0) + (status.length() == 0 ? 0 : 1);
        float bottom = h - pad - infoLines * infoSize * 1.4f;
        if (bottomLimit > 0) bottom = Math.min(bottom, bottomLimit);
        float full = bottom - pad;
        float used = full * clockSize;
        float top = pad + (full - used) / 2f;
        mArea.set(pad, top, w - pad, top + used);
        f.draw(c, mArea, state);

        mInfo.setTypeface(mInfoFont);
        mInfo.setTextSize(infoSize);
        mInfo.setStyle(Paint.Style.FILL);
        float y = bottom + infoSize * 1.2f;
        if (showDate) {
            mInfo.setColor(state.color("clock.date", state.theme.onSurface));
            c.drawText(dateText(), w / 2f, y, mInfo);
            y += infoSize * 1.4f;
        }
        if (status.length() > 0) {
            mInfo.setColor(state.color("clock.status", state.theme.onSurfaceVariant));
            c.drawText(status, 0, status.length(), w / 2f, y, mInfo);
            y += infoSize * 1.4f;
        }
        return y;
    }

    private String dateText() {
        Locale l = state.locale;
        if (mPattern == null || !dateFormat.equals(mPatternSkeleton) || !l.equals(mPatternLocale)) {
            try {
                mPattern = DateFormat.getBestDateTimePattern(l, dateFormat);
            } catch (RuntimeException e) {
                mPattern = DateFormat.getBestDateTimePattern(l, "EEE d MMM");
            }
            mPatternSkeleton = dateFormat;
            mPatternLocale = l;
        }
        return DateFormat.format(mPattern, state.now).toString();
    }
}
