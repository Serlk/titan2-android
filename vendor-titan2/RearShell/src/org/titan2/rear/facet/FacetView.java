package org.titan2.rear.facet;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlendMode;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LightingColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import java.util.Random;

/**
 * Notification light effects on the rear screen, drawn over everything on black (an AMOLED:
 * black pixels are off, so it looks like light on a dark back). One effect plays "repeat"
 * times, then onFinished runs. Effects: crt (glitch, then a CRT power-off), message (icon,
 * app, title), sides (light bands from the edges), pulse (a breathing edge glow).
 */
public class FacetView extends View {
    public static final String CRT = "crt";
    public static final String MESSAGE = "message";
    public static final String SIDES = "sides";
    public static final String PULSE = "pulse";
    public static final String RANDOM = "random";
    static final String[] ALL = {CRT, MESSAGE, SIDES, PULSE};

    private static final long CRT_MS = 1600;
    private static final long MESSAGE_MS = 2600;
    private static final long SIDES_MS = 1300;
    private static final long PULSE_MS = 1400;

    public interface Listener {
        void onFinished();
    }

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Random mRandom = new Random();
    private final float mDp;
    private Listener mListener;

    private String mEffect;
    private int mColor = Color.WHITE;
    private Drawable mIcon;
    private String mApp, mTitle;
    private Typeface mFont = Typeface.DEFAULT;
    private int mRepeat = 1;
    private long mStart;
    private Bitmap mContent;
    private final int[] mSliceShift = new int[12];
    private long mLastGlitch;

    private final Runnable mFrame = this::invalidate;

    public FacetView(Context c) {
        super(c);
        mDp = c.getResources().getDisplayMetrics().density;
        setBackgroundColor(Color.BLACK);
        setVisibility(GONE);
    }

    public void setListener(Listener l) {
        mListener = l;
    }

    public boolean isPlaying() {
        return mEffect != null;
    }

    /** Starts an effect; text may be null (privacy). */
    public void play(String effect, int color, Drawable icon, String app, String title,
            Typeface font, int repeat) {
        if (RANDOM.equals(effect) || effect == null) effect = ALL[mRandom.nextInt(ALL.length)];
        mEffect = effect;
        mColor = color | 0xFF000000;
        mIcon = icon;
        mApp = app;
        mTitle = title;
        mFont = font != null ? font : Typeface.DEFAULT;
        mRepeat = Math.max(1, repeat);
        mContent = null;
        // the clock starts with the first frame on screen (the rear may still be waking)
        mStart = 0;
        setAlpha(1f);
        setVisibility(VISIBLE);
        invalidate();
        removeCallbacks(mNeverShown);
        postDelayed(mNeverShown, NEVER_SHOWN_MS);
    }

    /** The screen did not come on: drop the effect rather than play it later. */
    private static final long NEVER_SHOWN_MS = 2000;
    private final Runnable mNeverShown = () -> {
        if (mEffect != null && mStart == 0) stop();
    };

    /** Stops at once (a touch, the screen going off). */
    public void stop() {
        // also hides the black overlay kept up after an effect (until the screen is off)
        mEffect = null;
        removeCallbacks(mFrame);
        removeCallbacks(mNeverShown);
        setVisibility(GONE);
        if (mContent != null) {
            mContent.recycle();
            mContent = null;
        }
    }

    private long length() {
        switch (mEffect) {
            case CRT:
                return CRT_MS;
            case MESSAGE:
                return MESSAGE_MS;
            case SIDES:
                return SIDES_MS;
            default:
                return PULSE_MS;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        if (mEffect == null) return;
        long now = SystemClock.uptimeMillis();
        if (mStart == 0) mStart = now;
        long len = length();
        // the message is shown once (its text is the point); the others repeat
        int repeat = MESSAGE.equals(mEffect) ? 1 : mRepeat;
        long t = now - mStart;
        if (t >= len * repeat) {
            String done = mEffect;
            stop();
            if (mListener != null && done != null) mListener.onFinished();
            return;
        }
        float p = (t % len) / (float) len;
        switch (mEffect) {
            case CRT:
                drawCrt(c, p, now);
                break;
            case MESSAGE:
                drawMessage(c, p);
                break;
            case SIDES:
                drawSides(c, p);
                break;
            default:
                drawPulse(c, p);
                break;
        }
        postOnAnimation(mFrame);
    }

    // ------------------------------------------------------------ content (icon + app name)

    private Bitmap content() {
        if (mContent != null) return mContent;
        int w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
        mContent = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(mContent);
        float cy = h * 0.42f;
        int icon = (int) (72 * mDp);
        if (mIcon != null) {
            mIcon.setBounds(w / 2 - icon / 2, (int) cy - icon / 2, w / 2 + icon / 2,
                    (int) cy + icon / 2);
            mIcon.draw(c);
        }
        mText.setTypeface(Typeface.create(mFont, Typeface.BOLD));
        mText.setTextSize(18 * mDp);
        mText.setColor(mColor);
        mText.setTextAlign(Paint.Align.CENTER);
        if (!TextUtils.isEmpty(mApp)) {
            String s = TextUtils.ellipsize(mApp, mText, w - 32 * mDp,
                    TextUtils.TruncateAt.END).toString();
            c.drawText(s, w / 2f, cy + icon / 2f + 34 * mDp, mText);
        }
        return mContent;
    }

    // ------------------------------------------------------------ crt

    private void drawCrt(Canvas c, float p, long now) {
        int w = getWidth(), h = getHeight();
        Bitmap b = content();
        if (p < 0.72f) {
            // glitch: horizontal slices shifted, RGB split, flicker; new shifts every ~70 ms
            if (now - mLastGlitch > 70) {
                mLastGlitch = now;
                for (int i = 0; i < mSliceShift.length; i++) {
                    mSliceShift[i] = mRandom.nextInt(5) == 0
                            ? (int) ((mRandom.nextFloat() - 0.5f) * 40 * mDp) : 0;
                }
            }
            float split = (2 + mRandom.nextFloat() * 4) * mDp;
            int alpha = mRandom.nextInt(8) == 0 ? 120 : 255;
            int slice = Math.max(1, h / mSliceShift.length);
            Rect src = new Rect();
            RectF dst = new RectF();
            mPaint.setAlpha(alpha);
            for (int i = 0; i < mSliceShift.length; i++) {
                src.set(0, i * slice, w, Math.min(h, (i + 1) * slice));
                for (int ch = 0; ch < 3; ch++) {
                    int mul = ch == 0 ? 0xFF0000 : ch == 1 ? 0x00FF00 : 0x0000FF;
                    float dx = mSliceShift[i] + (ch == 0 ? -split : ch == 2 ? split : 0);
                    dst.set(src.left + dx, src.top, src.right + dx, src.bottom);
                    mPaint.setColorFilter(new LightingColorFilter(mul, 0));
                    mPaint.setBlendMode(ch == 0 ? null : BlendMode.PLUS);
                    c.drawBitmap(b, src, dst, mPaint);
                }
            }
            mPaint.setColorFilter(null);
            mPaint.setBlendMode(null);
            mPaint.setAlpha(255);
            scanlines(c, w, h);
        } else {
            // CRT power-off: the picture collapses into a line, then into a dot
            float q = (p - 0.72f) / 0.28f;
            float cy = h / 2f, cx = w / 2f;
            mPaint.setColor(mColor);
            if (q < 0.5f) {
                float lh = Math.max(2 * mDp, h * (1f - q * 2f) * 0.25f);
                c.save();
                c.scale(1f, lh / h, cx, cy);
                c.drawBitmap(b, 0, 0, null);
                c.restore();
                mPaint.setAlpha(200);
                c.drawRect(0, cy - mDp, w, cy + mDp, mPaint);
            } else {
                float r = (1f - (q - 0.5f) * 2f);
                mPaint.setAlpha((int) (255 * r));
                c.drawRect(cx - w / 2f * r, cy - mDp, cx + w / 2f * r, cy + mDp, mPaint);
                c.drawCircle(cx, cy, 3 * mDp * r + mDp, mPaint);
            }
            mPaint.setAlpha(255);
        }
    }

    private void scanlines(Canvas c, int w, int h) {
        mPaint.setColor(0x55000000);
        float step = 3 * mDp;
        for (float y = 0; y < h; y += step) c.drawRect(0, y, w, y + mDp, mPaint);
    }

    // ------------------------------------------------------------ message

    private void drawMessage(Canvas c, float p) {
        int w = getWidth(), h = getHeight();
        float in = Math.min(1f, p / 0.18f), out = p > 0.8f ? (p - 0.8f) / 0.2f : 0f;
        float a = ease(in) * (1f - out);
        float dy = (1f - ease(in)) * 30 * mDp;
        // the frame glow
        glowRect(c, w, h, a * 0.6f, 10);
        int alpha = (int) (255 * a);
        float cy = h * 0.38f + dy;
        int icon = (int) (64 * mDp);
        if (mIcon != null) {
            mIcon.setBounds(w / 2 - icon / 2, (int) cy - icon / 2, w / 2 + icon / 2,
                    (int) cy + icon / 2);
            mIcon.setAlpha(alpha);
            mIcon.draw(c);
            mIcon.setAlpha(255);
        }
        mText.setTypeface(Typeface.create(mFont, Typeface.BOLD));
        mText.setTextAlign(Paint.Align.CENTER);
        mText.setTextSize(16 * mDp);
        mText.setColor(mColor);
        mText.setAlpha(alpha);
        float y = cy + icon / 2f + 30 * mDp;
        if (!TextUtils.isEmpty(mApp)) {
            c.drawText(TextUtils.ellipsize(mApp, mText, w - 32 * mDp,
                    TextUtils.TruncateAt.END).toString(), w / 2f, y, mText);
        }
        if (!TextUtils.isEmpty(mTitle)) {
            mText.setTypeface(Typeface.create(mFont, Typeface.NORMAL));
            mText.setTextSize(14 * mDp);
            mText.setColor(Color.WHITE);
            mText.setAlpha(alpha);
            c.drawText(TextUtils.ellipsize(mTitle, mText, w - 32 * mDp,
                    TextUtils.TruncateAt.END).toString(), w / 2f, y + 26 * mDp, mText);
        }
    }

    // ------------------------------------------------------------ sides

    private void drawSides(Canvas c, float p) {
        int w = getWidth(), h = getHeight();
        // bands run in from both edges to the middle and fade there
        float reach = ease(Math.min(1f, p / 0.6f)) * w / 2f;
        float fade = p > 0.55f ? 1f - (p - 0.55f) / 0.45f : 1f;
        int a = (int) (255 * fade);
        int solid = (mColor & 0xFFFFFF) | (a << 24);
        float band = 46 * mDp;
        mPaint.setShader(new LinearGradient(reach - band, 0, reach, 0, 0x00000000, solid,
                Shader.TileMode.CLAMP));
        c.drawRect(Math.max(0, reach - band), 0, reach, h, mPaint);
        mPaint.setShader(new LinearGradient(w - reach, 0, w - reach + band, 0, solid,
                0x00000000, Shader.TileMode.CLAMP));
        c.drawRect(w - reach, 0, Math.min(w, w - reach + band), h, mPaint);
        // the edges stay lit a little
        mPaint.setShader(new LinearGradient(0, 0, 18 * mDp, 0, solid, 0x00000000,
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 18 * mDp, h, mPaint);
        mPaint.setShader(new LinearGradient(w, 0, w - 18 * mDp, 0, solid, 0x00000000,
                Shader.TileMode.CLAMP));
        c.drawRect(w - 18 * mDp, 0, w, h, mPaint);
        mPaint.setShader(null);
    }

    // ------------------------------------------------------------ pulse

    private void drawPulse(Canvas c, float p) {
        int w = getWidth(), h = getHeight();
        // breathe in and out: sin^2
        float a = (float) Math.pow(Math.sin(Math.PI * p), 2);
        glowRect(c, w, h, a, 16);
        if (mIcon != null) {
            int icon = (int) (56 * mDp);
            mIcon.setBounds(w / 2 - icon / 2, h / 2 - icon / 2, w / 2 + icon / 2,
                    h / 2 + icon / 2);
            mIcon.setAlpha((int) (255 * (0.25f + 0.75f * a)));
            mIcon.draw(c);
            mIcon.setAlpha(255);
        }
    }

    /** A soft glowing frame along the screen edges. */
    private void glowRect(Canvas c, int w, int h, float a, float widthDp) {
        if (a <= 0f) return;
        float sw = widthDp * mDp;
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(sw);
        mPaint.setColor(mColor);
        mPaint.setAlpha((int) (255 * a));
        mPaint.setMaskFilter(new BlurMaskFilter(sw, BlurMaskFilter.Blur.NORMAL));
        float r = 28 * mDp;
        c.drawRoundRect(sw / 2, sw / 2, w - sw / 2, h - sw / 2, r, r, mPaint);
        mPaint.setMaskFilter(null);
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setAlpha(255);
    }

    private static float ease(float x) {
        // cubic ease-out
        float y = 1f - x;
        return 1f - y * y * y;
    }
}
