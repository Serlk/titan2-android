package org.titan2.rear.common.faces;

import android.graphics.Paint;
import android.graphics.Rect;

import org.titan2.rear.common.ThemeJson;

/** Clock face registry and shared helpers. */
public final class Faces {
    public static final String[] IDS = ThemeJson.FACE_IDS;

    private static final Rect sBounds = new Rect();

    private Faces() {}

    public static ClockFace create(String id) {
        switch (id == null ? "" : id) {
            case "digital_seconds":
                return new DigitalFace(true);
            case "analog":
                return new AnalogFace();
            case "words":
                return new WordFace();
            case "minimal":
                return new MinimalFace();
            case "digital":
            default:
                return new DigitalFace(false);
        }
    }

    public static String next(String id) {
        for (int i = 0; i < IDS.length; i++) {
            if (IDS[i].equals(id)) {
                return IDS[(i + 1) % IDS.length];
            }
        }
        return IDS[0];
    }

    /** Fit the font size so the text fits maxW x maxH (main thread only). */
    static void fit(Paint p, String text, float maxW, float maxH) {
        p.setTextSize(100f);
        p.getTextBounds(text, 0, text.length(), sBounds);
        float w = Math.max(1, p.measureText(text));
        float h = Math.max(1, sBounds.height());
        p.setTextSize(100f * Math.min(maxW / w, maxH / h));
    }

    static String two(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }
}
