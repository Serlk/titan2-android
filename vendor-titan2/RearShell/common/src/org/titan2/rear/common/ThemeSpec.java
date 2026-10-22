package org.titan2.rear.common;

import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * A resolved theme (the result of ThemeJson.resolve) parsed into fields. Parsing is lenient: a wrong
 * value becomes the default (strict validation is ThemeJson.validate on import).
 */
public final class ThemeSpec {
    public String id = RearKeys.THEME_DEFAULT;
    public String name = "Default";
    public String author = "";
    public String description = "";

    // colors (tokens)
    public int background = Color.BLACK;
    public int surface = 0x1FFFFFFF;
    public int onSurface = Color.WHITE;
    public int onSurfaceVariant = 0x9EFFFFFF;
    public int primary = 0xFF4FC3F7;
    public int onPrimary = Color.BLACK;
    public int secondary = 0x9EFFFFFF;
    public int accent = 0xFF4FC3F7;
    public int error = 0xFFFF6E6E;
    public int scrim = 0x99000000;
    /** separate element colors (colors.elements) */
    private final Map<String, Integer> mElements = new HashMap<>();

    // fonts
    public String font = "sans-serif";
    public int weight = 400;
    /** empty: same as font */
    public String clockFont = "";
    /** 0: same as weight */
    public int clockWeight = 0;
    public float textScale = 1f;
    public float labelSize = 12f;

    // clock
    public String face = "digital";
    public boolean seconds;
    /** null: as in the system */
    public Boolean h24;
    public boolean date = true;
    public String dateFormat = "EEE d MMM";
    public boolean battery = true;
    public boolean notifications = true;
    public float clockSize = 1f;
    public String digits = "regular";
    private JSONObject mFaces = new JSONObject();

    // background
    public String wallpaperType = "color";
    public int wallpaperColor = Color.BLACK;
    public int[] gradient = new int[0];
    public int gradientAngle = 90;
    /** "@file:<id>/<path>" or empty */
    public String wallpaperImage = "";
    public float dim;
    public float blur;

    // grid
    public int columns = 3;
    public int rows = 3;
    public float iconSize = 1f;
    public String iconShape = "none";
    public String iconStyle = "color";
    public boolean labels = true;
    public int labelLines = 1;
    public boolean pageIndicator = true;

    // animations
    public float animSpeed = 1f;
    public String animStyle = "standard";

    /** The original resolved JSON (for the editor). */
    public JSONObject json;

    /** Element color: its own from colors.elements or the fallback. */
    public int color(String element, int fallback) {
        Integer c = mElements.get(element);
        return c != null ? c : fallback;
    }

    public boolean hasElement(String element) {
        return mElements.containsKey(element);
    }

    /** Face parameters (clock.faces.<id>); an empty object if not set. */
    public JSONObject faceParams(String faceId) {
        JSONObject o = mFaces.optJSONObject(faceId);
        return o != null ? o : EMPTY;
    }

    private static final JSONObject EMPTY = new JSONObject();

    public static ThemeSpec defaults() {
        return parse(ThemeJson.defaults());
    }

    public static ThemeSpec parse(JSONObject o) {
        ThemeSpec t = new ThemeSpec();
        t.json = o;
        t.id = o.optString("id", t.id);
        t.name = o.optString("name", t.name);
        t.author = o.optString("author", "");
        t.description = o.optString("description", "");

        JSONObject c = o.optJSONObject("colors");
        Map<String, Integer> tokens = new HashMap<>();
        if (c != null) {
            for (String k : ThemeJson.COLOR_TOKENS) {
                resolveToken(k, c, tokens, 0);
            }
        }
        t.background = tok(tokens, "background", t.background) | 0xFF000000;
        t.surface = tok(tokens, "surface", t.surface);
        t.onSurface = tok(tokens, "onSurface", t.onSurface);
        t.onSurfaceVariant = tok(tokens, "onSurfaceVariant", t.onSurfaceVariant);
        t.primary = tok(tokens, "primary", t.primary);
        t.onPrimary = tok(tokens, "onPrimary", t.onPrimary);
        t.secondary = tok(tokens, "secondary", t.secondary);
        t.accent = tok(tokens, "accent", t.accent);
        t.error = tok(tokens, "error", t.error);
        t.scrim = tok(tokens, "scrim", t.scrim);
        tokens.put("background", t.background);
        JSONObject el = c != null ? c.optJSONObject("elements") : null;
        if (el != null) {
            Iterator<String> it = el.keys();
            while (it.hasNext()) {
                String k = it.next();
                try {
                    t.mElements.put(k, ThemeJson.parseColor(el.optString(k), tokens));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        JSONObject ty = o.optJSONObject("typography");
        if (ty != null) {
            t.font = ty.optString("font", t.font);
            t.weight = clamp(ty.optInt("weight", t.weight), 100, 900);
            t.clockFont = ty.optString("clockFont", "");
            t.clockWeight = clamp(ty.optInt("clockWeight", 0), 0, 900);
            t.textScale = clampF((float) ty.optDouble("scale", 1), 0.6f, 1.6f);
            t.labelSize = clampF((float) ty.optDouble("labelSize", 12), 8, 20);
        }

        JSONObject cl = o.optJSONObject("clock");
        if (cl != null) {
            t.face = cl.optString("face", t.face);
            t.seconds = cl.optBoolean("seconds", t.seconds);
            Object h = cl.opt("24h");
            t.h24 = h instanceof Boolean ? (Boolean) h : null;
            t.date = cl.optBoolean("date", t.date);
            t.dateFormat = cl.optString("dateFormat", t.dateFormat);
            t.battery = cl.optBoolean("battery", t.battery);
            t.notifications = cl.optBoolean("notifications", t.notifications);
            t.clockSize = clampF((float) cl.optDouble("size", 1), 0.4f, 1.2f);
            t.digits = cl.optString("digits", t.digits);
            JSONObject f = cl.optJSONObject("faces");
            if (f != null) t.mFaces = f;
        }

        JSONObject w = o.optJSONObject("wallpaper");
        t.wallpaperColor = t.background;
        if (w != null) {
            t.wallpaperType = w.optString("type", t.wallpaperType);
            try {
                t.wallpaperColor = ThemeJson.parseColor(w.optString("color", "@background"),
                        tokens) | 0xFF000000;
            } catch (IllegalArgumentException ignored) {
            }
            JSONArray g = w.optJSONArray("gradient");
            if (g != null && g.length() >= 2) {
                int[] cols = new int[Math.min(4, g.length())];
                try {
                    for (int i = 0; i < cols.length; i++) {
                        cols[i] = ThemeJson.parseColor(g.optString(i), tokens);
                    }
                    t.gradient = cols;
                } catch (IllegalArgumentException ignored) {
                }
            }
            t.gradientAngle = w.optInt("angle", 90);
            t.wallpaperImage = w.optString("image", "");
            t.dim = clampF((float) w.optDouble("dim", 0), 0, 1);
            t.blur = clampF((float) w.optDouble("blur", 0), 0, 25);
        }

        JSONObject l = o.optJSONObject("layout");
        if (l != null) {
            t.columns = clamp(l.optInt("columns", 3), 1, 6);
            t.rows = clamp(l.optInt("rows", 3), 1, 6);
            t.iconSize = clampF((float) l.optDouble("iconSize", 1), 0.6f, 1.4f);
            t.iconShape = l.optString("iconShape", t.iconShape);
            t.iconStyle = l.optString("iconStyle", t.iconStyle);
            t.labels = l.optBoolean("labels", true);
            t.labelLines = clamp(l.optInt("labelLines", 1), 1, 2);
            t.pageIndicator = l.optBoolean("pageIndicator", true);
        }

        JSONObject a = o.optJSONObject("animations");
        if (a != null) {
            t.animSpeed = clampF((float) a.optDouble("speed", 1), 0.25f, 4f);
            t.animStyle = a.optString("style", t.animStyle);
        }
        return t;
    }

    /** Resolve a token that references other tokens (depth limited against cycles). */
    private static Integer resolveToken(String k, JSONObject c, Map<String, Integer> out,
            int depth) {
        if (out.containsKey(k)) return out.get(k);
        String expr = c.optString(k, null);
        if (expr == null || depth > 10) return null;
        String base = expr.trim();
        int slash = base.lastIndexOf('/');
        if (slash > 0) base = base.substring(0, slash).trim();
        if (base.startsWith("@")) {
            if (resolveToken(base.substring(1), c, out, depth + 1) == null) return null;
        }
        try {
            int v = ThemeJson.parseColor(expr, out);
            out.put(k, v);
            return v;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static int tok(Map<String, Integer> m, String k, int def) {
        Integer v = m.get(k);
        return v != null ? v : def;
    }

    public static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public static float clampF(float v, float lo, float hi) {
        return Float.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v));
    }

    /** An opaque color over the background (for translucent tokens in tiles etc.). */
    public static int over(int top, int bottom) {
        float a = Color.alpha(top) / 255f;
        int r = Math.round(Color.red(top) * a + Color.red(bottom) * (1 - a));
        int g = Math.round(Color.green(top) * a + Color.green(bottom) * (1 - a));
        int b = Math.round(Color.blue(top) * a + Color.blue(bottom) * (1 - a));
        return Color.rgb(r, g, b);
    }
}
