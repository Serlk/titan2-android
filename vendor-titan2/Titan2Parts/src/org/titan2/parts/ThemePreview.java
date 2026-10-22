package org.titan2.parts;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.format.DateFormat;
import android.view.View;

import org.titan2.rear.common.Effective;
import org.titan2.rear.common.ThemeSpec;
import org.titan2.rear.common.Wallpapers;
import org.titan2.rear.common.faces.ClockRenderer;

/**
 * Theme preview "as on the rear screen" (410x502): background, the clock with the same faces as
 * the shell (shared library), a row of icons. A View for the theme screens and the editor,
 * a Drawable for the icon in the theme list.
 */
final class ThemePreview {
    static final float ASPECT = 410f / 502f;

    private ThemePreview() {}

    /** Painter: everything theme-dependent is prepared in set(), drawing does no allocations. */
    static final class Renderer {
        private final Context mContext;
        private final ClockRenderer mClock = new ClockRenderer();
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mBmp = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final RectF mRect = new RectF();
        private final Rect mSrc = new Rect();
        private ThemeSpec mSpec = new ThemeSpec();
        private Drawable mBackground;
        private Bitmap mImage;
        private int mColumns = 3;
        private String mShape = "none";
        private boolean mLabels = true;

        Renderer(Context c) {
            mContext = c;
        }

        /** Theme + user settings (values may be null: theme only). */
        void set(ThemeSpec t, Effective.Values values, boolean loadImage) {
            mSpec = t;
            Effective.Values v = values != null ? values : key -> null;
            Typeface font = RearThemes.typeface(mContext, t.font);
            Typeface clockFont = t.clockFont.isEmpty() ? null
                    : RearThemes.typeface(mContext, t.clockFont);
            mClock.configure(t, v, font, clockFont, DateFormat.is24HourFormat(mContext));
            mBackground = Wallpapers.background(t);
            mImage = loadImage && "image".equals(t.wallpaperType) && !t.wallpaperImage.isEmpty()
                    ? RearThemes.bitmap(mContext, t.wallpaperImage, 410, 502) : null;
            mColumns = Math.min(4, Effective.columns(t, v));
            mShape = Effective.iconShape(t, v);
            mLabels = Effective.labels(t, v);
        }

        void draw(Canvas c, Rect b) {
            float w = b.width(), h = b.height();
            // 1 preview dp = a fraction of the "real" 410 px screen at ~1.25 density
            float dp = w / 328f;
            c.save();
            c.translate(b.left, b.top);
            mRect.set(0, 0, w, h);
            Path clip = new Path();
            clip.addRoundRect(mRect, 10 * dp, 10 * dp, Path.Direction.CW);
            c.clipPath(clip);
            mBackground.setBounds(0, 0, (int) w, (int) h);
            mBackground.draw(c);
            if (mImage != null) {
                // center crop
                float s = Math.max(w / mImage.getWidth(), h / mImage.getHeight());
                float sw = w / s, sh = h / s;
                int l = (int) ((mImage.getWidth() - sw) / 2), t = (int) ((mImage.getHeight() - sh) / 2);
                mSrc.set(l, t, (int) (l + sw), (int) (t + sh));
                c.drawBitmap(mImage, mSrc, mRect, mBmp);
            }
            if (mSpec.dim > 0) c.drawColor(((int) (mSpec.dim * 255)) << 24);
            // clock: the top 70%
            mClock.tick(mContext.getResources().getConfiguration().getLocales().get(0));
            c.save();
            c.clipRect(0, 0, w, h * 0.72f);
            mClock.draw(c, mClock.face, w, h * 0.72f, dp, 0, 85, false, 3);
            c.restore();
            // row of icons
            float cell = w / mColumns;
            float r = Math.min(cell * 0.28f, 22 * dp);
            float cy = h * 0.82f;
            int[] cols = {mSpec.primary, mSpec.secondary, mSpec.accent, mSpec.onSurfaceVariant};
            for (int i = 0; i < mColumns; i++) {
                float cx = cell * (i + 0.5f);
                mPaint.setColor(cols[i % cols.length]);
                switch (mShape) {
                    case "rounded":
                    case "squircle":
                        float rr = "rounded".equals(mShape) ? r * 0.5f : r * 0.8f;
                        c.drawRoundRect(cx - r, cy - r, cx + r, cy + r, rr, rr, mPaint);
                        break;
                    case "circle":
                    case "none":
                    default:
                        c.drawCircle(cx, cy, r, mPaint);
                        break;
                }
                if (mLabels) {
                    mPaint.setColor(mSpec.color("label", mSpec.onSurface));
                    mPaint.setAlpha(150);
                    c.drawRoundRect(cx - r * 0.9f, cy + r * 1.35f, cx + r * 0.9f,
                            cy + r * 1.35f + 3 * dp, 2 * dp, 2 * dp, mPaint);
                    mPaint.setAlpha(255);
                }
            }
            c.restore();
        }
    }

    /** Large preview (editor, theme list). */
    static final class PreviewView extends View {
        final Renderer renderer;
        private final Rect mBounds = new Rect();

        PreviewView(Context c) {
            super(c);
            renderer = new Renderer(c);
        }

        void set(ThemeSpec t, Effective.Values values) {
            renderer.set(t, values, true);
            invalidate();
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int h = (int) (220 * getResources().getDisplayMetrics().density);
            setMeasuredDimension(MeasureSpec.getSize(wSpec), h);
        }

        @Override
        protected void onDraw(Canvas c) {
            float pad = 12 * getResources().getDisplayMetrics().density;
            float h = getHeight() - 2 * pad;
            float w = h * ASPECT;
            int l = (int) ((getWidth() - w) / 2);
            mBounds.set(l, (int) pad, (int) (l + w), (int) (pad + h));
            renderer.draw(c, mBounds);
        }
    }

    /** Small preview for the icon of a theme list item. */
    static final class PreviewDrawable extends Drawable {
        private final Renderer mRenderer;
        private final int mW, mH;

        PreviewDrawable(Context c, ThemeSpec t) {
            mRenderer = new Renderer(c);
            mRenderer.set(t, null, false);
            float dp = c.getResources().getDisplayMetrics().density;
            mH = (int) (52 * dp);
            mW = (int) (mH * ASPECT);
        }

        @Override
        public void draw(Canvas c) {
            mRenderer.draw(c, getBounds());
        }

        @Override
        public int getIntrinsicWidth() {
            return mW;
        }

        @Override
        public int getIntrinsicHeight() {
            return mH;
        }

        @Override
        public void setAlpha(int alpha) {}

        @Override
        public void setColorFilter(ColorFilter cf) {}

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
