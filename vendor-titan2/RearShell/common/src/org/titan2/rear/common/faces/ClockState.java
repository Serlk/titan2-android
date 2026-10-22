package org.titan2.rear.common.faces;

import android.graphics.Paint;
import android.graphics.Typeface;

import org.json.JSONObject;
import org.titan2.rear.common.ThemeSpec;

import java.util.Calendar;
import java.util.Locale;

/** Everything a face needs to draw. Filled by ClockRenderer. */
public final class ClockState {
    public Calendar now = Calendar.getInstance();
    public Locale locale = Locale.getDefault();
    public boolean h24 = true;
    public boolean seconds;
    public ThemeSpec theme = new ThemeSpec();
    /** digit font (with clock.digits and weights applied) */
    public Typeface font = Typeface.DEFAULT;
    /** clock.digits: regular | bold | thin | outline | tabular */
    public String digits = "regular";
    /** parameters of the current face (clock.faces.<id>) */
    public JSONObject params = new JSONObject();
    /** 0..1: separator blinking etc. */
    public boolean oddSecond;

    public int hour() {
        int h = now.get(Calendar.HOUR_OF_DAY);
        if (h24) {
            return h;
        }
        h %= 12;
        return h == 0 ? 12 : h;
    }

    public int minute() {
        return now.get(Calendar.MINUTE);
    }

    public int second() {
        return now.get(Calendar.SECOND);
    }

    public int color(String element, int fallback) {
        return theme.color(element, fallback);
    }

    /** Digits: font, outline, monospaced digits. */
    public void applyDigits(Paint p, float textSize) {
        p.setTypeface(font);
        p.setTextSize(textSize);
        boolean outline = "outline".equals(digits);
        p.setStyle(outline ? Paint.Style.STROKE : Paint.Style.FILL);
        p.setStrokeWidth(outline ? Math.max(1f, textSize * 0.025f) : 0f);
        p.setFontFeatureSettings("tabular".equals(digits) ? "tnum" : null);
    }
}
