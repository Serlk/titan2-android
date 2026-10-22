package org.titan2.rear.common;

import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Theme schema (version 2): key table, defaults, inheritance ("extends"),
 * conversion of old themes (version 1) and validation with clear messages. Docs are in THEMES.md;
 * update them when the schema changes.
 *
 * A theme is stored "as written" (only what it changes); before display it is resolved:
 * DEFAULTS <- parent (recursively) <- the theme itself. File paths (font, image) in each
 * layer are rewritten to "@file:<theme id>/<path>", so a child can use the parent's
 * files.
 */
public final class ThemeJson {
    public static final int SCHEMA = 2;
    public static final int MAX_DEPTH = 8;
    public static final String FILE_PREFIX = "@file:";

    private ThemeJson() {}

    /** The default theme: all schema keys. Every theme implicitly inherits it. */
    public static final String DEFAULTS_JSON = "{"
            + "\"schema\":2,\"name\":\"Default\",\"author\":\"\",\"description\":\"\","
            + "\"colors\":{"
            + "\"background\":\"#000000\",\"onSurface\":\"#FFFFFF\","
            + "\"onSurfaceVariant\":\"@onSurface/0.62\",\"surface\":\"@onSurface/0.12\","
            + "\"primary\":\"#4FC3F7\",\"onPrimary\":\"#000000\",\"secondary\":\"@onSurfaceVariant\","
            + "\"accent\":\"@primary\",\"error\":\"#FF6E6E\",\"scrim\":\"#000000/0.6\","
            + "\"elements\":{}},"
            + "\"typography\":{\"font\":\"sans-serif\",\"weight\":400,\"clockFont\":\"\","
            + "\"clockWeight\":0,\"scale\":1.0,\"labelSize\":12},"
            + "\"clock\":{\"face\":\"digital\",\"seconds\":false,\"24h\":\"system\",\"date\":true,"
            + "\"dateFormat\":\"EEE d MMM\",\"battery\":true,\"notifications\":true,\"size\":1.0,"
            + "\"digits\":\"regular\",\"faces\":{}},"
            + "\"wallpaper\":{\"type\":\"color\",\"color\":\"@background\",\"gradient\":[],"
            + "\"angle\":90,\"image\":\"\",\"dim\":0.0,\"blur\":0},"
            + "\"layout\":{\"columns\":3,\"rows\":3,\"iconSize\":1.0,\"iconShape\":\"none\","
            + "\"iconStyle\":\"color\",\"labels\":true,\"labelLines\":1,\"pageIndicator\":true},"
            + "\"animations\":{\"speed\":1.0,\"style\":\"standard\"}"
            + "}";

    public static JSONObject defaults() {
        try {
            return new JSONObject(DEFAULTS_JSON);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- schema

    static final int T_STRING = 0, T_BOOL = 1, T_INT = 2, T_FLOAT = 3, T_COLOR = 4, T_ENUM = 5,
            T_FONT = 6, T_FILE = 7, T_COLORS = 8, T_BOOL_SYSTEM = 9, T_OBJECT = 10,
            T_ELEMENTS = 11, T_FACES = 12;

    static final class Spec {
        final int type;
        final double min, max;
        final String[] values;

        Spec(int type, double min, double max, String... values) {
            this.type = type;
            this.min = min;
            this.max = max;
            this.values = values;
        }
    }

    /** Schema keys: path -> type. Order = order in THEMES.md. */
    static final Map<String, Spec> SPECS = new LinkedHashMap<>();

    public static final String[] COLOR_TOKENS = {
            "background", "surface", "onSurface", "onSurfaceVariant", "primary", "onPrimary",
            "secondary", "accent", "error", "scrim",
    };

    /** Elements whose color can be set separately (colors.elements). */
    public static final String[] ELEMENTS = {
            "clock.hours", "clock.minutes", "clock.seconds", "clock.separator", "clock.date",
            "clock.status", "clock.titles", "clock.handHour", "clock.handMinute",
            "clock.handSecond", "clock.ticks", "clock.ticksMajor", "clock.numbers",
            "clock.center",
            "label", "drawer.background", "pager.indicator", "pager.indicatorActive",
            "qs.background", "qs.tile", "qs.tileActive", "qs.onTile", "qs.onTileActive",
            "qs.slider",
            "bouncer.background", "bouncer.title", "bouncer.key", "bouncer.onKey", "bouncer.dot",
            "bouncer.pattern", "bouncer.error",
            "hint.background", "hint.text",
    };

    public static final String[] FACE_IDS = {"digital", "digital_seconds", "analog", "words",
            "minimal"};
    public static final String[] DIGITS = {"regular", "bold", "thin", "outline", "tabular"};
    public static final String[] WALLPAPER_TYPES = {"color", "gradient", "image"};
    public static final String[] ICON_SHAPES = {"none", "circle", "squircle", "rounded"};
    public static final String[] ICON_STYLES = {"color", "mono"};
    public static final String[] ANIM_STYLES = {"standard", "expressive", "subtle", "none"};
    public static final String[] SYSTEM_FONTS = {
            "sans-serif", "sans-serif-light", "sans-serif-thin", "sans-serif-medium",
            "sans-serif-black", "sans-serif-condensed", "sans-serif-condensed-light",
            "serif", "monospace", "serif-monospace", "casual", "cursive", "sans-serif-smallcaps",
    };

    /** Clock face parameters: clock.faces.<face>.<param>. */
    static final Map<String, Map<String, Spec>> FACE_PARAMS = new LinkedHashMap<>();

    static {
        SPECS.put("schema", new Spec(T_INT, 1, SCHEMA));
        SPECS.put("name", new Spec(T_STRING, 0, 0));
        SPECS.put("author", new Spec(T_STRING, 0, 0));
        SPECS.put("description", new Spec(T_STRING, 0, 0));
        SPECS.put("version", new Spec(T_STRING, 0, 0));
        SPECS.put("extends", new Spec(T_STRING, 0, 0));
        SPECS.put("colors", new Spec(T_OBJECT, 0, 0));
        for (String t : COLOR_TOKENS) {
            SPECS.put("colors." + t, new Spec(T_COLOR, 0, 0));
        }
        SPECS.put("colors.elements", new Spec(T_ELEMENTS, 0, 0));
        SPECS.put("typography", new Spec(T_OBJECT, 0, 0));
        SPECS.put("typography.font", new Spec(T_FONT, 0, 0));
        SPECS.put("typography.weight", new Spec(T_INT, 100, 900));
        SPECS.put("typography.clockFont", new Spec(T_FONT, 0, 0));
        SPECS.put("typography.clockWeight", new Spec(T_INT, 0, 900));
        SPECS.put("typography.scale", new Spec(T_FLOAT, 0.6, 1.6));
        SPECS.put("typography.labelSize", new Spec(T_FLOAT, 8, 20));
        SPECS.put("clock", new Spec(T_OBJECT, 0, 0));
        SPECS.put("clock.face", new Spec(T_ENUM, 0, 0, FACE_IDS));
        SPECS.put("clock.seconds", new Spec(T_BOOL, 0, 0));
        SPECS.put("clock.24h", new Spec(T_BOOL_SYSTEM, 0, 0));
        SPECS.put("clock.date", new Spec(T_BOOL, 0, 0));
        SPECS.put("clock.dateFormat", new Spec(T_STRING, 0, 0));
        SPECS.put("clock.battery", new Spec(T_BOOL, 0, 0));
        SPECS.put("clock.notifications", new Spec(T_BOOL, 0, 0));
        SPECS.put("clock.size", new Spec(T_FLOAT, 0.4, 1.2));
        SPECS.put("clock.digits", new Spec(T_ENUM, 0, 0, DIGITS));
        SPECS.put("clock.faces", new Spec(T_FACES, 0, 0));
        SPECS.put("wallpaper", new Spec(T_OBJECT, 0, 0));
        SPECS.put("wallpaper.type", new Spec(T_ENUM, 0, 0, WALLPAPER_TYPES));
        SPECS.put("wallpaper.color", new Spec(T_COLOR, 0, 0));
        SPECS.put("wallpaper.gradient", new Spec(T_COLORS, 0, 4));
        SPECS.put("wallpaper.angle", new Spec(T_INT, 0, 359));
        SPECS.put("wallpaper.image", new Spec(T_FILE, 0, 0));
        SPECS.put("wallpaper.dim", new Spec(T_FLOAT, 0, 1));
        SPECS.put("wallpaper.blur", new Spec(T_FLOAT, 0, 25));
        SPECS.put("layout", new Spec(T_OBJECT, 0, 0));
        SPECS.put("layout.columns", new Spec(T_INT, 1, 6));
        SPECS.put("layout.rows", new Spec(T_INT, 1, 6));
        SPECS.put("layout.iconSize", new Spec(T_FLOAT, 0.6, 1.4));
        SPECS.put("layout.iconShape", new Spec(T_ENUM, 0, 0, ICON_SHAPES));
        SPECS.put("layout.iconStyle", new Spec(T_ENUM, 0, 0, ICON_STYLES));
        SPECS.put("layout.labels", new Spec(T_BOOL, 0, 0));
        SPECS.put("layout.labelLines", new Spec(T_INT, 1, 2));
        SPECS.put("layout.pageIndicator", new Spec(T_BOOL, 0, 0));
        SPECS.put("animations", new Spec(T_OBJECT, 0, 0));
        SPECS.put("animations.speed", new Spec(T_FLOAT, 0.25, 4));
        SPECS.put("animations.style", new Spec(T_ENUM, 0, 0, ANIM_STYLES));

        Map<String, Spec> digital = new LinkedHashMap<>();
        digital.put("separator", new Spec(T_STRING, 0, 0));
        digital.put("blink", new Spec(T_BOOL, 0, 0));
        digital.put("leadingZero", new Spec(T_BOOL, 0, 0));
        FACE_PARAMS.put("digital", digital);
        FACE_PARAMS.put("digital_seconds", digital);
        Map<String, Spec> analog = new LinkedHashMap<>();
        analog.put("ticks", new Spec(T_ENUM, 0, 0, "all", "hours", "none"));
        analog.put("numbers", new Spec(T_BOOL, 0, 0));
        analog.put("handWidth", new Spec(T_FLOAT, 0.5, 2));
        FACE_PARAMS.put("analog", analog);
        Map<String, Spec> words = new LinkedHashMap<>();
        words.put("language", new Spec(T_ENUM, 0, 0, "system", "ru", "en"));
        words.put("case", new Spec(T_ENUM, 0, 0, "lower", "upper", "title"));
        FACE_PARAMS.put("words", words);
        Map<String, Spec> minimal = new LinkedHashMap<>();
        minimal.put("leadingZero", new Spec(T_BOOL, 0, 0));
        FACE_PARAMS.put("minimal", minimal);
    }

    // ---------------------------------------------------------------- version 1

    /** An old flat theme (without "schema") -> version 2. New themes are returned as is. */
    public static JSONObject upgrade(JSONObject o) throws JSONException {
        if (o.has("schema") || !(o.has("background") || o.has("foreground")
                || o.has("icons") || o.has("grid") || o.has("font") || o.has("accent"))) {
            return o;
        }
        JSONObject n = new JSONObject();
        n.put("schema", SCHEMA);
        copy(o, "name", n, "name");
        copy(o, "author", n, "author");
        copy(o, "extends", n, "extends");
        JSONObject colors = new JSONObject();
        copy(o, "background", colors, "background");
        copy(o, "foreground", colors, "onSurface");
        copy(o, "accent", colors, "primary");
        copy(o, "accent", colors, "accent");
        copy(o, "secondary", colors, "secondary");
        copy(o, "secondary", colors, "onSurfaceVariant");
        copy(o, "background", colors, "onPrimary");
        if (colors.length() > 0) n.put("colors", colors);
        if (o.has("font")) {
            n.put("typography", new JSONObject().put("font", o.get("font")));
        }
        String img = o.optString("backgroundImage", "");
        if (!img.isEmpty()) {
            n.put("wallpaper", new JSONObject().put("type", "image").put("image", img));
        }
        JSONObject c = o.optJSONObject("clock");
        if (c != null) n.put("clock", new JSONObject(c.toString()));
        JSONObject layout = new JSONObject();
        JSONObject g = o.optJSONObject("grid");
        if (g != null) {
            copy(g, "columns", layout, "columns");
            copy(g, "rows", layout, "rows");
        }
        JSONObject i = o.optJSONObject("icons");
        if (i != null) {
            copy(i, "labels", layout, "labels");
            String st = i.optString("style", "");
            if ("circle".equals(st)) layout.put("iconShape", "circle").put("iconStyle", "color");
            else if ("mono".equals(st)) layout.put("iconShape", "none").put("iconStyle", "mono");
            else if ("plain".equals(st)) layout.put("iconShape", "none").put("iconStyle", "color");
        }
        if (layout.length() > 0) n.put("layout", layout);
        return n;
    }

    private static void copy(JSONObject from, String k, JSONObject to, String k2)
            throws JSONException {
        if (from.has(k)) to.put(k2, from.get(k));
    }

    // ---------------------------------------------------------------- inheritance

    /** Source of "raw" themes by id (Titan2Parts: assets and files). */
    public interface Loader {
        /** Theme JSON as in the file; throws if there is no theme or the file is unreadable. */
        JSONObject load(String id) throws Exception;
    }

    /**
     * Resolve a theme: DEFAULTS <- chain of parents <- theme. Files as "@file:<id>/<path>".
     * Errors (no parent, a cycle, depth) throw with a clear text.
     */
    public static JSONObject resolve(String id, Loader loader) throws Exception {
        List<String> chain = new ArrayList<>();
        List<JSONObject> layers = new ArrayList<>();
        String cur = id;
        while (cur != null && !cur.isEmpty()) {
            if (chain.contains(cur)) {
                throw new JSONException("\"extends\" loop: " + String.join(" → ", chain)
                        + " → " + cur);
            }
            if (chain.size() >= MAX_DEPTH) {
                throw new JSONException("\"extends\" chain longer than " + MAX_DEPTH);
            }
            JSONObject raw;
            try {
                raw = loader.load(cur);
            } catch (Exception e) {
                if (chain.isEmpty()) throw e;
                throw new JSONException("parent theme \"" + cur + "\" not found (extends in "
                        + chain.get(chain.size() - 1) + ")");
            }
            JSONObject layer = upgrade(raw);
            rewriteFiles(layer, cur);
            chain.add(cur);
            layers.add(layer);
            String parent = layer.optString("extends", "");
            cur = parent.isEmpty() ? null : parent;
        }
        JSONObject out = defaults();
        for (int i = layers.size() - 1; i >= 0; i--) {
            merge(out, layers.get(i));
        }
        out.remove("extends");
        out.put("id", id);
        out.put("schema", SCHEMA);
        if (layers.size() > 1) {
            out.put("parents", new JSONArray(chain.subList(1, chain.size())));
        }
        return out;
    }

    /** Deep merge: objects by key, everything else (arrays included) is replaced. */
    public static void merge(JSONObject into, JSONObject over) throws JSONException {
        Iterator<String> it = over.keys();
        while (it.hasNext()) {
            String k = it.next();
            Object v = over.get(k);
            Object old = into.opt(k);
            if (v instanceof JSONObject && old instanceof JSONObject) {
                merge((JSONObject) old, (JSONObject) v);
            } else if (v instanceof JSONObject) {
                JSONObject copy = new JSONObject();
                merge(copy, (JSONObject) v);
                into.put(k, copy);
            } else {
                into.put(k, v);
            }
        }
    }

    /** Is the font a file rather than a system family name? */
    public static boolean isFontFile(String f) {
        if (f == null) return false;
        String l = f.toLowerCase();
        return f.contains("/") || l.endsWith(".ttf") || l.endsWith(".otf") || l.endsWith(".ttc");
    }

    /** A relative path inside the theme folder (no "..", no absolute paths)? */
    public static boolean isSafePath(String p) {
        if (p == null || p.isEmpty() || p.startsWith("/") || p.contains("\\")) return false;
        for (String seg : p.split("/")) {
            if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) return false;
        }
        return true;
    }

    private static void rewriteFiles(JSONObject layer, String id) throws JSONException {
        JSONObject t = layer.optJSONObject("typography");
        if (t != null) {
            for (String k : new String[] {"font", "clockFont"}) {
                String f = t.optString(k, "");
                if (isFontFile(f) && !f.startsWith(FILE_PREFIX)) {
                    if (isSafePath(f)) t.put(k, FILE_PREFIX + id + "/" + f);
                    else t.remove(k);
                }
            }
        }
        JSONObject w = layer.optJSONObject("wallpaper");
        if (w != null) {
            String img = w.optString("image", "");
            if (!img.isEmpty() && !img.startsWith(FILE_PREFIX)) {
                if (isSafePath(img)) w.put("image", FILE_PREFIX + id + "/" + img);
                else w.remove("image");
            }
        }
    }

    /** Files referenced by the resolved theme ("@file:..."). */
    public static List<String> fileRefs(JSONObject resolved) {
        List<String> out = new ArrayList<>();
        JSONObject t = resolved.optJSONObject("typography");
        if (t != null) {
            addRef(out, t.optString("font", ""));
            addRef(out, t.optString("clockFont", ""));
        }
        JSONObject w = resolved.optJSONObject("wallpaper");
        if (w != null) addRef(out, w.optString("image", ""));
        return out;
    }

    private static void addRef(List<String> out, String v) {
        if (v.startsWith(FILE_PREFIX) && !out.contains(v)) out.add(v);
    }

    // ---------------------------------------------------------------- colors

    /**
     * Color: "#RGB", "#RRGGBB", "#AARRGGBB", a name ("black"), a token reference ("@primary"),
     * and an optional alpha after a slash: "@onSurface/0.12", "#000000/0.6".
     * tokens: already resolved tokens (may be null, then references are not allowed).
     */
    public static int parseColor(String expr, Map<String, Integer> tokens) {
        if (expr == null) throw new IllegalArgumentException("no color");
        String s = expr.trim();
        // Color.parseColor("") throws StringIndexOutOfBoundsException, not IllegalArgumentException
        if (s.isEmpty()) throw new IllegalArgumentException("empty color");
        float alpha = 1f;
        int slash = s.lastIndexOf('/');
        if (slash > 0) {
            try {
                alpha = Float.parseFloat(s.substring(slash + 1).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("bad alpha in \"" + expr + "\"");
            }
            if (!(alpha >= 0 && alpha <= 1)) { // also NaN
                throw new IllegalArgumentException("alpha must be 0..1 in \"" + expr + "\"");
            }
            s = s.substring(0, slash).trim();
            if (s.isEmpty()) throw new IllegalArgumentException("empty color in \"" + expr + "\"");
        }
        int c;
        if (s.startsWith("@")) {
            Integer v = tokens != null ? tokens.get(s.substring(1)) : null;
            if (v == null) throw new IllegalArgumentException("unknown color token " + s);
            c = v;
        } else if (s.startsWith("#") && (s.length() == 4 || s.length() == 5)) {
            StringBuilder sb = new StringBuilder("#");
            for (int i = 1; i < s.length(); i++) sb.append(s.charAt(i)).append(s.charAt(i));
            c = Color.parseColor(sb.toString());
        } else {
            c = Color.parseColor(s);
        }
        if (alpha < 1f) {
            int a = Math.round(Color.alpha(c) * alpha);
            c = (c & 0x00FFFFFF) | (a << 24);
        }
        return c;
    }

    public static String toHex(int c) {
        if (Color.alpha(c) == 0xFF) return String.format("#%06X", c & 0xFFFFFF);
        return String.format("#%08X", c);
    }

    // ---------------------------------------------------------------- validation

    /** Validation result: errors: the theme is unusable, warnings: usable but something is missing. */
    public static final class Report {
        public final List<String> errors = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();

        public boolean ok() {
            return errors.isEmpty();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            for (String e : errors) sb.append("✗ ").append(e).append('\n');
            for (String w : warnings) sb.append("⚠ ").append(w).append('\n');
            return sb.toString().trim();
        }
    }

    /** Validate theme text (before inheritance). files: file names in the theme folder (or null). */
    public static Report validate(String text, Set<String> files) {
        Report r = new Report();
        JSONObject o;
        try {
            o = new JSONObject(text);
        } catch (JSONException e) {
            r.errors.add("not valid JSON: " + e.getMessage());
            return r;
        }
        try {
            if (!o.has("schema")) {
                JSONObject up = upgrade(o);
                if (up != o) {
                    r.warnings.add("old theme format (schema 1) converted to schema 2");
                    o = up;
                }
            }
        } catch (JSONException e) {
            r.errors.add("cannot convert old theme: " + e.getMessage());
            return r;
        }
        if (o.optInt("schema", SCHEMA) > SCHEMA) {
            r.errors.add("\"schema\": " + o.opt("schema") + " is newer than supported ("
                    + SCHEMA + ")");
        }
        if (o.optString("name", "").trim().isEmpty()) {
            r.warnings.add("\"name\" is missing; the file name will be used");
        }
        walk(o, "", r, files);
        return r;
    }

    private static void walk(JSONObject o, String prefix, Report r, Set<String> files) {
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            String path = prefix.isEmpty() ? k : prefix + "." + k;
            Spec s = SPECS.get(path);
            Object v = o.opt(k);
            if (s == null) {
                if (prefix.isEmpty() && (k.equals("id") || k.equals("parents"))) continue;
                r.warnings.add("unknown key \"" + path + "\" (ignored)");
                continue;
            }
            check(path, s, v, r, files);
            if (s.type == T_OBJECT && v instanceof JSONObject) {
                walk((JSONObject) v, path, r, files);
            }
        }
    }

    private static Set<String> tokenNames() {
        return new HashSet<>(Arrays.asList(COLOR_TOKENS));
    }

    private static void checkColor(String path, Object v, Report r) {
        if (!(v instanceof String)) {
            r.errors.add(path + ": color must be a string like \"#RRGGBB\"");
            return;
        }
        String s = ((String) v).trim();
        String base = s;
        int slash = s.lastIndexOf('/');
        if (slash > 0) base = s.substring(0, slash).trim();
        if (base.startsWith("@")) {
            if (!tokenNames().contains(base.substring(1))) {
                r.errors.add(path + ": unknown color token \"" + base + "\" (use one of "
                        + String.join(", ", COLOR_TOKENS) + ")");
                return;
            }
            Map<String, Integer> fake = new java.util.HashMap<>();
            fake.put(base.substring(1), Color.BLACK);
            try {
                parseColor(s, fake);
            } catch (IllegalArgumentException e) {
                r.errors.add(path + ": " + e.getMessage());
            }
            return;
        }
        try {
            parseColor(s, null);
        } catch (IllegalArgumentException e) {
            r.errors.add(path + ": bad color \"" + s + "\" (" + e.getMessage() + ")");
        }
    }

    private static void check(String path, Spec s, Object v, Report r, Set<String> files) {
        switch (s.type) {
            case T_STRING:
                if (!(v instanceof String)) r.errors.add(path + ": must be a string");
                break;
            case T_BOOL:
                if (!(v instanceof Boolean)) r.errors.add(path + ": must be true or false");
                break;
            case T_BOOL_SYSTEM:
                if (!(v instanceof Boolean) && !"system".equals(v)) {
                    r.errors.add(path + ": must be true, false or \"system\"");
                }
                break;
            case T_INT:
            case T_FLOAT: {
                if (!(v instanceof Number)) {
                    r.errors.add(path + ": must be a number");
                    break;
                }
                double d = ((Number) v).doubleValue();
                if (s.type == T_INT && d != Math.rint(d)) {
                    r.errors.add(path + ": must be a whole number");
                } else if (d < s.min || d > s.max) {
                    r.errors.add(path + ": " + v + " is out of range " + fmt(s.min) + "…"
                            + fmt(s.max));
                }
                break;
            }
            case T_COLOR:
                checkColor(path, v, r);
                break;
            case T_COLORS: {
                if (!(v instanceof JSONArray)) {
                    r.errors.add(path + ": must be a list of colors, e.g. [\"#000000\", \"#203040\"]");
                    break;
                }
                JSONArray a = (JSONArray) v;
                if (a.length() != 0 && (a.length() < 2 || a.length() > (int) s.max)) {
                    r.errors.add(path + ": needs 2…" + (int) s.max + " colors");
                }
                for (int i = 0; i < a.length(); i++) checkColor(path + "[" + i + "]", a.opt(i), r);
                break;
            }
            case T_ENUM:
                if (!(v instanceof String) || !Arrays.asList(s.values).contains(v)) {
                    r.errors.add(path + ": \"" + v + "\" is not one of "
                            + String.join(", ", s.values));
                }
                break;
            case T_FONT:
                if (!(v instanceof String)) {
                    r.errors.add(path + ": must be a font family name or a .ttf/.otf file");
                } else if (isFontFile((String) v)) {
                    checkFile(path, (String) v, r, files);
                } else if (!((String) v).isEmpty()
                        && !Arrays.asList(SYSTEM_FONTS).contains(v)) {
                    r.warnings.add(path + ": \"" + v + "\" is not a known system font family;"
                            + " Android will fall back to sans-serif if it does not exist");
                }
                break;
            case T_FILE:
                if (!(v instanceof String)) {
                    r.errors.add(path + ": must be a file name");
                } else if (!((String) v).isEmpty()) {
                    checkFile(path, (String) v, r, files);
                }
                break;
            case T_OBJECT:
                if (!(v instanceof JSONObject)) r.errors.add(path + ": must be an object { … }");
                break;
            case T_ELEMENTS: {
                if (!(v instanceof JSONObject)) {
                    r.errors.add(path + ": must be an object { \"element\": \"#color\" }");
                    break;
                }
                JSONObject e = (JSONObject) v;
                List<String> known = Arrays.asList(ELEMENTS);
                Iterator<String> it = e.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    if (!known.contains(k)) {
                        r.warnings.add(path + ": unknown element \"" + k + "\" (ignored)");
                    }
                    checkColor(path + "." + k, e.opt(k), r);
                }
                break;
            }
            case T_FACES: {
                if (!(v instanceof JSONObject)) {
                    r.errors.add(path + ": must be an object { \"analog\": { … } }");
                    break;
                }
                JSONObject f = (JSONObject) v;
                Iterator<String> it = f.keys();
                while (it.hasNext()) {
                    String face = it.next();
                    Map<String, Spec> params = FACE_PARAMS.get(face);
                    JSONObject fo = f.optJSONObject(face);
                    if (params == null) {
                        r.warnings.add(path + ": unknown face \"" + face + "\" (ignored)");
                        continue;
                    }
                    if (fo == null) {
                        r.errors.add(path + "." + face + ": must be an object");
                        continue;
                    }
                    Iterator<String> pi = fo.keys();
                    while (pi.hasNext()) {
                        String p = pi.next();
                        Spec ps = params.get(p);
                        if (ps == null) {
                            r.warnings.add(path + "." + face + ": unknown parameter \"" + p
                                    + "\" (ignored)");
                        } else {
                            check(path + "." + face + "." + p, ps, fo.opt(p), r, files);
                        }
                    }
                }
                break;
            }
            default:
                break;
        }
    }

    private static void checkFile(String path, String f, Report r, Set<String> files) {
        if (f.startsWith(FILE_PREFIX)) return;
        if (!isSafePath(f)) {
            r.errors.add(path + ": \"" + f + "\" must be a path inside the theme folder"
                    + " (no leading /, no ..)");
        } else if (files != null && !files.contains(f)) {
            r.warnings.add(path + ": file \"" + f + "\" is not in the theme;"
                    + " it is only found if a parent theme has it");
        }
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    /** Make a safe theme file id from a name. */
    public static String slug(String name) {
        String s = name == null ? "" : name.trim().toLowerCase()
                .replaceAll("[^a-z0-9а-яё_-]+", "_").replaceAll("^_+|_+$", "");
        if (s.isEmpty()) s = "theme";
        return s.length() > 40 ? s.substring(0, 40) : s;
    }
}
