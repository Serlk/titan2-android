package org.titan2.rear;

import android.animation.ValueAnimator;
import android.graphics.Path;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/**
 * Material 3 / AOSP style motion (like SystemUI and Launcher3 in Android 16): emphasized/standard
 * curves, durations, spring parameters. The speed multiplier comes from the theme and
 * settings (Effective.animScale); animators honor the system animation scale themselves, springs
 * (androidx.dynamicanimation) do not, so enabled() checks it too.
 */
final class Motion {
    /** M3 emphasized (the main transition curve) */
    static final Interpolator EMPHASIZED;
    /** M3 emphasized decelerate: elements entering */
    static final Interpolator EMPHASIZED_DECELERATE = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    /** M3 emphasized accelerate: elements leaving */
    static final Interpolator EMPHASIZED_ACCELERATE = new PathInterpolator(0.3f, 0f, 0.8f, 0.15f);
    /** M3 standard */
    static final Interpolator STANDARD = new PathInterpolator(0.2f, 0f, 0f, 1f);
    static final Interpolator STANDARD_DECELERATE = new PathInterpolator(0f, 0f, 0f, 1f);
    static final Interpolator STANDARD_ACCELERATE = new PathInterpolator(0.3f, 0f, 1f, 1f);

    static {
        Path p = new Path();
        p.moveTo(0, 0);
        p.cubicTo(0.05f, 0f, 0.133333f, 0.06f, 0.166666f, 0.4f);
        p.cubicTo(0.208333f, 0.82f, 0.25f, 1f, 1f, 1f);
        EMPHASIZED = new PathInterpolator(p);
    }

    // M3 durations (ms)
    static final long SHORT = 150;
    static final long MEDIUM = 300;
    static final long LONG = 400;
    static final long EXTRA_LONG = 500;

    /** 1: as designed, 2: twice as slow, 0: no animations */
    private static float sScale = 1f;
    private static String sStyle = "standard";

    private Motion() {}

    static void configure(float scale, String style) {
        sScale = scale;
        sStyle = style == null ? "standard" : style;
    }

    static boolean enabled() {
        return sScale > 0f && ValueAnimator.areAnimatorsEnabled();
    }

    static long dur(long ms) {
        return enabled() ? (long) (ms * sScale) : 0;
    }

    static String style() {
        return sStyle;
    }

    static boolean expressive() {
        return "expressive".equals(sStyle);
    }

    static boolean subtle() {
        return "subtle".equals(sStyle);
    }

    /** How much to shift/scale the background on transitions (subtle: half as much). */
    static float amount() {
        return subtle() ? 0.5f : expressive() ? 1.3f : 1f;
    }

    /** Spring stiffness: lower is slower; honors the speed multiplier. */
    static float stiffness() {
        float base = expressive() ? 450f : subtle() ? 900f : 650f;
        float s = Math.max(0.25f, sScale);
        return base / (s * s);
    }

    static float damping() {
        return expressive() ? 0.72f : subtle() ? 1f : 0.88f;
    }
}
