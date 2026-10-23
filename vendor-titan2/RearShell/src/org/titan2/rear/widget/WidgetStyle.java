package org.titan2.rear.widget;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.widget.TextView;

import org.titan2.rear.common.ThemeSpec;

/**
 * Colors and type of the widget pages, taken from the selected theme. Every color is a theme
 * element ("widget.*", see THEMES.md) with a fallback to the theme tokens, so a theme may
 * restyle the widgets without listing them.
 */
public final class WidgetStyle {
    public final ThemeSpec theme;
    public final Typeface font;
    public final float dp;

    public final int text;        // main values (time, temperature, title)
    public final int textDim;     // labels, secondary values
    public final int accent;      // progress, needles, active controls
    public final int track;       // progress track, dial ticks
    public final int card;        // control and block backgrounds
    public final int onCard;
    public final int warning;     // timer finished, out of level

    WidgetStyle(ThemeSpec t, Typeface font, float dp) {
        this.theme = t;
        this.font = font;
        this.dp = dp;
        text = t.color("widget.text", t.onSurface);
        textDim = t.color("widget.textSecondary", t.onSurfaceVariant);
        accent = t.color("widget.accent", t.accent);
        track = t.color("widget.track", (t.onSurfaceVariant & 0xFFFFFF) | 0x33000000);
        card = t.color("widget.card", ThemeSpec.over(t.surface, t.background));
        onCard = t.color("widget.onCard", t.onSurface);
        warning = t.color("widget.warning", t.error);
    }

    public static WidgetStyle of(ThemeSpec t, Typeface font, float dp) {
        return new WidgetStyle(t, font, dp);
    }

    public int px(float v) {
        return Math.round(v * dp);
    }

    /** Text size in sp scaled by the theme text scale. */
    public float sp(float v) {
        return v * theme.textScale;
    }

    public void text(TextView v, float sizeSp, int color, boolean bold) {
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(sizeSp));
        v.setTextColor(color);
        v.setTypeface(Typeface.create(font, bold ? Math.min(1000, theme.weight + 300)
                : theme.weight, false));
        v.setIncludeFontPadding(false);
    }

    public GradientDrawable cardBackground(float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(radiusDp * dp);
        d.setColor(card);
        return d;
    }

    public GradientDrawable pill(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(999 * dp);
        d.setColor(color);
        return d;
    }
}
