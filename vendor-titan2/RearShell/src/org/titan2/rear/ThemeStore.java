package org.titan2.rear;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.json.JSONObject;
import org.titan2.rear.common.RearContract;
import org.titan2.rear.common.RearKeys;
import org.titan2.rear.common.ThemeJson;
import org.titan2.rear.common.ThemeSpec;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Current theme: the resolved JSON from the Titan2Parts provider -> ThemeSpec, fonts and the background image.
 * Theme files (fonts, images) are copied from the provider into the shell cache (Typeface and
 * BitmapFactory need a real file). Loading the same resolved JSON again reuses the last result
 * until invalidate() (the provider reported a theme change: its files may have been replaced under
 * the same names). A malformed theme never throws: it falls back to the defaults.
 */
final class ThemeStore {
    private static final String TAG = "RearShell";
    private static final long MAX_FILE = 16 * 1024 * 1024;

    static final class Loaded {
        ThemeSpec spec;
        Typeface font = Typeface.DEFAULT;
        /** null: same as font */
        Typeface clockFont;
        Bitmap wallpaper;
        String json;
    }

    private static Loaded sLast;

    private ThemeStore() {}

    /** Forget the last theme: the next load re-reads its files. */
    static void invalidate() {
        sLast = null;
    }

    /** Load theme id (falling back to the default theme and the built-in values). */
    static Loaded load(Context c, String id, int w, int h) {
        String json = query(c, id);
        if (json == null && !RearKeys.THEME_DEFAULT.equals(id)) {
            json = query(c, RearKeys.THEME_DEFAULT);
        }
        if (json == null) {
            json = ThemeJson.DEFAULTS_JSON;
        }
        if (sLast != null && json.equals(sLast.json)) {
            return sLast;
        }
        Loaded l = new Loaded();
        l.json = json;
        try {
            l.spec = ThemeSpec.parse(new JSONObject(json));
        } catch (Exception | StackOverflowError e) {
            // StackOverflowError: absurdly nested JSON
            Log.w(TAG, "theme " + id + ": " + e);
            l.spec = ThemeSpec.defaults();
        }
        l.font = typeface(c, l.spec.font);
        l.clockFont = l.spec.clockFont.isEmpty() ? null : typeface(c, l.spec.clockFont);
        if ("image".equals(l.spec.wallpaperType) && !l.spec.wallpaperImage.isEmpty()) {
            l.wallpaper = bitmap(c, l.spec.wallpaperImage, w, h);
        }
        sLast = l;
        return l;
    }

    private static String query(Context c, String id) {
        try (Cursor cur = c.getContentResolver().query(RearContract.theme(id), null, null,
                null, null)) {
            if (cur != null && cur.moveToFirst()) {
                return cur.getString(cur.getColumnIndexOrThrow(RearContract.COL_JSON));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "theme query " + id + ": " + e);
        }
        return null;
    }

    /** Font: a system family name or an "@file:" theme file. */
    static Typeface typeface(Context c, String f) {
        if (f == null || f.isEmpty()) return Typeface.DEFAULT;
        if (!f.startsWith(ThemeJson.FILE_PREFIX)) {
            return Typeface.create(f, Typeface.NORMAL);
        }
        File file = cached(c, f);
        if (file == null) return Typeface.DEFAULT;
        try {
            Typeface t = new Typeface.Builder(file).build();
            return t != null ? t : Typeface.DEFAULT;
        } catch (RuntimeException e) {
            Log.w(TAG, "font " + f + ": " + e);
            return Typeface.DEFAULT;
        }
    }

    /**
     * Background image scaled down to the screen size (inSampleSize). A very elongated picture
     * is sampled further until it has at most 4x the screen's pixels: a huge bitmap would
     * exhaust the heap or exceed the canvas limit when drawn (a crash on every draw).
     */
    static Bitmap bitmap(Context c, String ref, int w, int h) {
        File file = cached(c, ref);
        if (file == null) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) {
            sample *= 2;
        }
        long max = 4L * w * h;
        while ((long) (o.outWidth / sample) * (o.outHeight / sample) > max) {
            sample *= 2;
        }
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        try {
            return BitmapFactory.decodeFile(file.getPath(), o2);
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "wallpaper " + ref + ": " + e);
            return null;
        }
    }

    /** A copy of a theme file in the shell cache (device-protected, like the whole app). */
    private static File cached(Context c, String ref) {
        File dir = new File(c.getCacheDir(), "theme");
        dir.mkdirs();
        File out = new File(dir, ref.replaceAll("[^A-Za-z0-9._-]", "_"));
        try (ParcelFileDescriptor pfd = c.getContentResolver().openFileDescriptor(
                RearContract.file(ref), "r")) {
            if (pfd == null) return null;
            // copy every time: the theme may have been edited with the same file names
            try (InputStream in = new FileInputStream(pfd.getFileDescriptor());
                    FileOutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[16384];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_FILE) throw new IOException("file too large");
                    os.write(buf, 0, n);
                }
            }
            return out;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "theme file " + ref + ": " + e);
            out.delete();
            return null;
        }
    }
}
