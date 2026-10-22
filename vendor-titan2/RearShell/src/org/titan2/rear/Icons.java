package org.titan2.rear;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.LruCache;

/**
 * Icon styling: shape (none: as the system, circle, squircle, rounded) and style (color,
 * mono: the monochrome layer of an adaptive icon in the label color, otherwise grayscale).
 * Finished bitmaps are cached per process.
 */
final class Icons {
    private static final LruCache<String, Drawable> sCache = new LruCache<>(300);

    private Icons() {}

    static void clear() {
        sCache.evictAll();
    }

    static Drawable get(Context c, Apps.Entry e, String shape, String style, int tint,
            int plate) {
        String key = shape + "|" + style + "|" + tint + "|" + plate + "|" + e.key();
        Drawable d = sCache.get(key);
        if (d == null) {
            d = make(c, e.info.getIcon(0), shape, style, tint, plate);
            sCache.put(key, d);
        }
        return d;
    }

    private static Drawable make(Context c, Drawable icon, String shape, String style, int tint,
            int plate) {
        int size = (int) (64 * c.getResources().getDisplayMetrics().density);
        boolean mono = "mono".equals(style);
        boolean own = !"none".equals(shape);
        if (!own && !mono) {
            return icon;
        }
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Path clip = shapePath(own ? shape : "circle", size);
        cv.clipPath(clip);
        AdaptiveIconDrawable adaptive = icon instanceof AdaptiveIconDrawable
                ? (AdaptiveIconDrawable) icon : null;
        if (mono) {
            // a backing of our own shape in the surface color, the monochrome layer on top
            cv.drawColor(plate);
            Drawable m = adaptive != null ? adaptive.getMonochrome() : null;
            if (m != null) {
                m = m.mutate();
                m.setTint(tint);
                m.setTintMode(PorterDuff.Mode.SRC_IN);
                drawLayer(cv, m, size, 1.5f);
            } else {
                Drawable g = icon.getConstantState() != null
                        ? icon.getConstantState().newDrawable().mutate() : icon.mutate();
                ColorMatrix cm = new ColorMatrix();
                cm.setSaturation(0);
                g.setColorFilter(new ColorMatrixColorFilter(cm));
                drawLayer(cv, g, size, adaptive != null ? 1.5f : 0.72f);
            }
        } else if (adaptive != null) {
            // adaptive icon layers without the system mask, for our own shape
            if (adaptive.getBackground() != null) drawLayer(cv, adaptive.getBackground(), size, 1.5f);
            if (adaptive.getForeground() != null) drawLayer(cv, adaptive.getForeground(), size, 1.5f);
        } else {
            cv.drawColor(plate);
            drawLayer(cv, icon, size, 0.72f);
        }
        return new BitmapDrawable(c.getResources(), b);
    }

    /** scale > 1: the layer is larger than the icon (108dp adaptive layers with 72dp visible). */
    private static void drawLayer(Canvas cv, Drawable d, int size, float scale) {
        int s = (int) (size * scale);
        int off = (size - s) / 2;
        d.setBounds(off, off, off + s, off + s);
        d.draw(cv);
    }

    static Path shapePath(String shape, int size) {
        Path p = new Path();
        switch (shape) {
            case "rounded":
                p.addRoundRect(new RectF(0, 0, size, size), size * 0.24f, size * 0.24f,
                        Path.Direction.CW);
                break;
            case "squircle": {
                // superellipse, like Pixel icons
                float s = size / 100f;
                p.moveTo(50 * s, 0);
                p.cubicTo(10 * s, 0, 0, 10 * s, 0, 50 * s);
                p.cubicTo(0, 90 * s, 10 * s, 100 * s, 50 * s, 100 * s);
                p.cubicTo(90 * s, 100 * s, 100 * s, 90 * s, 100 * s, 50 * s);
                p.cubicTo(100 * s, 10 * s, 90 * s, 0, 50 * s, 0);
                p.close();
                break;
            }
            case "circle":
            default:
                p.addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW);
                break;
        }
        return p;
    }
}
