package org.titan2.parts;

import android.app.AlertDialog;
import android.content.Context;
import android.database.Cursor;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;

import org.json.JSONArray;
import org.json.JSONObject;
import org.titan2.rear.common.RearKeys;
import org.titan2.rear.common.ThemeJson;
import org.titan2.rear.common.ThemeSpec;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * A simple theme editor: colors (palette), fonts, clock, background, grid, animations, with a live
 * preview. Writes only what was changed, over the parent ("extends"): a built-in theme
 * is never modified; "Edit" on it creates a new user theme that inherits it.
 * Resetting a value ("same as parent") removes the key from the theme.
 */
public class RearThemeEditorFragment extends RearPrefs {
    static final String EXTRA_NEW = "new_theme";

    private static final int K_STR = 0, K_INT = 1, K_FLOAT = 2, K_BOOL = 3, K_H24 = 4;

    private String mTargetId;
    private String mBase = "";
    private JSONObject mRaw;
    private JSONObject mResolved = ThemeJson.defaults();
    private ThemeSpec mSpec = new ThemeSpec();
    private String mError;
    private boolean mNew;
    private boolean mSaved;

    private final ActivityResultLauncher<String[]> mPickFont = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), u -> onFile(u, "typography.font"));
    private final ActivityResultLauncher<String[]> mPickClockFont = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), u -> onFile(u, "typography.clockFont"));
    private final ActivityResultLauncher<String[]> mPickImage = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), u -> onFile(u, "wallpaper.image"));

    @Override
    public void onCreate(Bundle state) {
        Context c = requireContext();
        String source = getArguments() != null
                ? getArguments().getString(RearScreenActivity.EXTRA_THEME) : null;
        if (source == null) source = RearConfig.get(c, RearKeys.THEME);
        mNew = requireActivity().getIntent().getBooleanExtra(EXTRA_NEW, false)
                || RearThemes.isBuiltin(source);
        try {
            if (state != null && state.getString("raw") != null) {
                mRaw = new JSONObject(state.getString("raw"));
                mTargetId = state.getString("target");
                mBase = state.getString("base", "");
                mNew = state.getBoolean("new");
            } else if (mNew) {
                String name = getString(R.string.rear_theme_custom_name,
                        RearThemes.info(c, source).name);
                mRaw = new JSONObject().put("schema", ThemeJson.SCHEMA).put("name", name);
                mBase = source;
                mTargetId = RearThemes.newId(c, name);
            } else {
                mRaw = ThemeJson.upgrade(RearThemes.raw(c, source));
                mTargetId = source;
                mBase = mRaw.optString("extends", "");
            }
        } catch (Exception e) {
            mRaw = new JSONObject();
            mTargetId = RearThemes.newId(c, "theme");
        }
        mRaw.remove("extends");
        resolveNow();
        super.onCreate(state);
    }

    @Override
    public void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("raw", mRaw.toString());
        out.putString("target", mTargetId);
        out.putString("base", mBase);
        out.putBoolean("new", mNew);
    }

    @Override
    public void onDestroy() {
        // the new theme was never saved: remove the files chosen for it (not on a configuration
        // change: the recreated editor restores mRaw, which still points at those files)
        boolean recreating = getActivity() != null && getActivity().isChangingConfigurations();
        if (mNew && !mSaved && !recreating && getContext() != null
                && !new File(RearThemes.userDir(getContext(), mTargetId),
                        RearThemes.THEME_FILE).exists()) {
            File dir = RearThemes.userDir(getContext(), mTargetId);
            File[] fs = dir.listFiles();
            if (fs != null) for (File f : fs) f.delete();
            dir.delete();
        }
        super.onDestroy();
    }

    /** This theme's layer with "extends" (a copy: resolve rewrites the file paths). */
    private JSONObject layer() throws Exception {
        JSONObject l = new JSONObject(mRaw.toString());
        if (!mBase.isEmpty()) l.put("extends", mBase);
        return l;
    }

    private void resolveNow() {
        Context c = requireContext();
        try {
            final JSONObject layer = layer();
            mResolved = ThemeJson.resolve(mTargetId, id -> id.equals(mTargetId)
                    ? new JSONObject(layer.toString()) : RearThemes.raw(c, id));
            mSpec = ThemeSpec.parse(mResolved);
            mError = null;
        } catch (Exception e) {
            mError = e.getMessage();
        }
    }

    @Override
    protected void updatePreview() {
        if (mPreview != null) mPreview.set(mSpec, null);
    }

    @Override
    protected void build(PreferenceScreen screen) {
        render(screen);
    }

    private void refresh() {
        resolveNow();
        KeyPrefs.rebuildKeepingScroll(this, () -> {
            PreferenceScreen s = getPreferenceManager().createPreferenceScreen(getContext());
            render(s);
            setPreferenceScreen(s);
            updatePreview();
        });
    }

    // ------------------------------------------------------------ JSON by path "a.b.c"

    private Object own(String path) {
        Object o = mRaw;
        for (String k : path.split("\\.")) {
            if (!(o instanceof JSONObject)) return null;
            o = ((JSONObject) o).opt(k);
        }
        return o;
    }

    private Object resolved(String path) {
        Object o = mResolved;
        for (String k : path.split("\\.")) {
            if (!(o instanceof JSONObject)) return null;
            o = ((JSONObject) o).opt(k);
        }
        return o;
    }

    /** Keys with a dot in the name (colors.elements."clock.hours") are separate paths. */
    private void setElement(String element, Object v) {
        try {
            JSONObject colors = child(mRaw, "colors");
            JSONObject el = child(colors, "elements");
            if (v == null) el.remove(element);
            else el.put(element, v);
            if (el.length() == 0) colors.remove("elements");
            if (colors.length() == 0) mRaw.remove("colors");
        } catch (Exception ignored) {
        }
        refresh();
    }

    private String ownElement(String element) {
        JSONObject c = mRaw.optJSONObject("colors");
        JSONObject e = c != null ? c.optJSONObject("elements") : null;
        return e != null ? e.optString(element, null) : null;
    }

    private static JSONObject child(JSONObject o, String k) throws Exception {
        JSONObject c = o.optJSONObject(k);
        if (c == null) {
            c = new JSONObject();
            o.put(k, c);
        }
        return c;
    }

    private void set(String path, Object v) {
        try {
            String[] ks = path.split("\\.");
            JSONObject o = mRaw;
            List<JSONObject> chain = new ArrayList<>();
            for (int i = 0; i < ks.length - 1; i++) {
                chain.add(o);
                o = child(o, ks[i]);
            }
            if (v == null) o.remove(ks[ks.length - 1]);
            else o.put(ks[ks.length - 1], v);
            // remove empty sections
            for (int i = ks.length - 2; i >= 0; i--) {
                JSONObject parent = chain.get(i);
                JSONObject sec = parent.optJSONObject(ks[i]);
                if (sec != null && sec.length() == 0) parent.remove(ks[i]);
            }
        } catch (Exception ignored) {
        }
        refresh();
    }

    // ------------------------------------------------------------ screen

    private void render(PreferenceScreen screen) {
        Context c = getContext();
        addPreview(screen);
        if (mError != null) {
            screen.addPreference(KeyPrefs.footer(c, getString(R.string.rear_theme_broken,
                    mError)));
        }

        PreferenceCategory meta = cat(screen, R.string.rear_ed_cat_theme);
        text(meta, "name", R.string.rear_ed_name);
        text(meta, "author", R.string.rear_ed_author);
        text(meta, "description", R.string.rear_ed_description);
        Preference base = new Preference(c);
        base.setKey("e:extends");
        base.setTitle(R.string.rear_ed_base);
        base.setSummary(mBase.isEmpty() ? getString(R.string.rear_ed_base_none)
                : RearThemes.info(c, mBase).name);
        base.setOnPreferenceClickListener(p -> {
            chooseBase();
            return true;
        });
        meta.addPreference(base);

        PreferenceCategory colors = cat(screen, R.string.rear_ed_cat_colors);
        String[] tokenNames = getResources().getStringArray(R.array.rear_ed_token_entries);
        for (int i = 0; i < ThemeJson.COLOR_TOKENS.length; i++) {
            final String t = ThemeJson.COLOR_TOKENS[i];
            color(colors, "colors." + t, tokenNames[i], tokenColor(t), own("colors." + t),
                    v -> set("colors." + t, v));
        }
        PreferenceCategory elements = cat(screen, R.string.rear_ed_cat_elements);
        elements.setInitialExpandedChildrenCount(0);
        for (String e : ThemeJson.ELEMENTS) {
            final String el = e;
            color(elements, "el:" + el, el, elementColor(el), ownElement(el),
                    v -> setElement(el, v));
        }

        PreferenceCategory font = cat(screen, R.string.rear_ed_cat_font);
        fontList(font, "typography.font", R.string.rear_ed_font, false);
        CharSequence[] weights = {"100", "200", "300", "400", "500", "600", "700", "800", "900"};
        listE(font, "typography.weight", R.string.rear_ed_weight, weights, weights, K_INT);
        fontList(font, "typography.clockFont", R.string.rear_ed_clock_font, true);
        CharSequence[] cw = {"0", "100", "200", "300", "400", "500", "600", "700", "800", "900"};
        CharSequence[] cwn = cw.clone();
        cwn[0] = getString(R.string.rear_ed_same);
        listE(font, "typography.clockWeight", R.string.rear_ed_clock_weight, cwn, cw, K_INT);
        CharSequence[] scales = {"0.8", "0.9", "1.0", "1.1", "1.2", "1.3", "1.4"};
        listE(font, "typography.scale", R.string.rear_ed_scale, percent(scales), scales, K_FLOAT);
        CharSequence[] lsz = {"10", "11", "12", "13", "14", "16"};
        listE(font, "typography.labelSize", R.string.rear_ed_label_size, lsz, lsz, K_FLOAT);

        PreferenceCategory clock = cat(screen, R.string.rear_ed_cat_clock);
        listE(clock, "clock.face", R.string.rear_face_title,
                getResources().getTextArray(R.array.rear_face_entries), ThemeJson.FACE_IDS,
                K_STR);
        listE(clock, "clock.digits", R.string.rear_ed_digits,
                getResources().getTextArray(R.array.rear_ed_digits_entries), ThemeJson.DIGITS,
                K_STR);
        CharSequence[] sizes = {"0.5", "0.6", "0.7", "0.8", "0.9", "1.0", "1.1", "1.2"};
        listE(clock, "clock.size", R.string.rear_ed_clock_size, percent(sizes), sizes, K_FLOAT);
        boolSwitch(clock, "clock.seconds", R.string.rear_seconds_title);
        h24(clock);
        boolSwitch(clock, "clock.date", R.string.rear_date_title);
        listE(clock, "clock.dateFormat", R.string.rear_date_format_title,
                skip1(getResources().getTextArray(R.array.rear_date_entries)),
                skip1(RearScreenActivity.ClockFragment.DATE_FORMATS), K_STR);
        boolSwitch(clock, "clock.battery", R.string.rear_battery_title);
        boolSwitch(clock, "clock.notifications", R.string.rear_notif_count_title);
        faceParams(clock);

        PreferenceCategory wall = cat(screen, R.string.rear_ed_cat_wallpaper);
        listE(wall, "wallpaper.type", R.string.rear_ed_wall_type,
                getResources().getTextArray(R.array.rear_ed_wall_entries),
                ThemeJson.WALLPAPER_TYPES, K_STR);
        String type = mSpec.wallpaperType;
        if ("color".equals(type)) {
            color(wall, "wallpaper.color", getString(R.string.rear_ed_wall_color),
                    mSpec.wallpaperColor, own("wallpaper.color"), v -> set("wallpaper.color", v));
        } else if ("gradient".equals(type)) {
            for (int i = 0; i < 3; i++) {
                final int idx = i;
                int col = i < mSpec.gradient.length ? mSpec.gradient[i]
                        : (i == 0 ? mSpec.background : mSpec.surface);
                String label = getString(R.string.rear_ed_gradient_color, i + 1);
                color(wall, "wallpaper.gradient." + i, label, col,
                        i < mSpec.gradient.length ? ThemeJson.toHex(col) : null,
                        v -> setGradient(idx, v));
            }
            CharSequence[] angles = {"0", "45", "90", "135", "180", "225", "270", "315"};
            CharSequence[] an = new CharSequence[angles.length];
            for (int i = 0; i < angles.length; i++) an[i] = angles[i] + "°";
            listE(wall, "wallpaper.angle", R.string.rear_ed_angle, an, angles, K_INT);
        } else {
            Preference img = new Preference(c);
            img.setKey("e:wallpaper.image");
            img.setTitle(R.string.rear_ed_image);
            Object v = resolved("wallpaper.image");
            img.setSummary(v == null || "".equals(v) ? getString(R.string.rear_ed_image_none)
                    : fileLabel(String.valueOf(v)));
            img.setOnPreferenceClickListener(p -> {
                mPickImage.launch(new String[] {"image/*"});
                return true;
            });
            wall.addPreference(img);
            CharSequence[] blur = {"0", "2", "4", "8", "12", "16", "25"};
            listE(wall, "wallpaper.blur", R.string.rear_ed_blur, blur, blur, K_FLOAT);
        }
        CharSequence[] dims = {"0", "0.1", "0.2", "0.3", "0.4", "0.5", "0.6", "0.7"};
        listE(wall, "wallpaper.dim", R.string.rear_ed_dim, percent(dims), dims, K_FLOAT);

        PreferenceCategory grid = cat(screen, R.string.rear_ed_cat_grid);
        CharSequence[] nums = {"1", "2", "3", "4", "5", "6"};
        listE(grid, "layout.columns", R.string.rear_columns_title, nums, nums, K_INT);
        listE(grid, "layout.rows", R.string.rear_rows_title, nums, nums, K_INT);
        CharSequence[] isz = {"0.6", "0.7", "0.8", "0.9", "1.0", "1.1", "1.2", "1.3", "1.4"};
        listE(grid, "layout.iconSize", R.string.rear_icon_size_title, percent(isz), isz, K_FLOAT);
        listE(grid, "layout.iconShape", R.string.rear_icon_shape_title,
                getResources().getTextArray(R.array.rear_icon_shape_entries),
                ThemeJson.ICON_SHAPES, K_STR);
        listE(grid, "layout.iconStyle", R.string.rear_icon_style_title,
                getResources().getTextArray(R.array.rear_icon_style_entries),
                ThemeJson.ICON_STYLES, K_STR);
        boolSwitch(grid, "layout.labels", R.string.rear_labels_title);
        CharSequence[] lines = {"1", "2"};
        listE(grid, "layout.labelLines", R.string.rear_ed_label_lines, lines, lines, K_INT);
        boolSwitch(grid, "layout.pageIndicator", R.string.rear_ed_indicator);

        PreferenceCategory anim = cat(screen, R.string.rear_cat_anim);
        CharSequence[] speeds = {"0.5", "0.75", "1.0", "1.25", "1.5", "2.0"};
        CharSequence[] sn = new CharSequence[speeds.length];
        for (int i = 0; i < speeds.length; i++) sn[i] = "×" + speeds[i];
        listE(anim, "animations.speed", R.string.rear_anim_speed_title, sn, speeds, K_FLOAT);
        listE(anim, "animations.style", R.string.rear_anim_style_title,
                getResources().getTextArray(R.array.rear_anim_style_entries),
                ThemeJson.ANIM_STYLES, K_STR);

        PreferenceCategory save = cat(screen, R.string.rear_ed_cat_save);
        Preference s1 = new Preference(c);
        s1.setKey("e:save");
        s1.setTitle(R.string.rear_ed_save);
        s1.setSummary(getString(R.string.rear_ed_save_summary,
                mTargetId.substring(mTargetId.indexOf(':') + 1)));
        s1.setOnPreferenceClickListener(p -> {
            save(false);
            return true;
        });
        save.addPreference(s1);
        Preference s2 = new Preference(c);
        s2.setKey("e:save_apply");
        s2.setTitle(R.string.rear_ed_save_apply);
        s2.setOnPreferenceClickListener(p -> {
            save(true);
            return true;
        });
        save.addPreference(s2);
        screen.addPreference(KeyPrefs.footer(c, getString(R.string.rear_ed_footer)));
    }

    private static CharSequence[] skip1(CharSequence[] a) {
        CharSequence[] o = new CharSequence[a.length - 1];
        System.arraycopy(a, 1, o, 0, o.length);
        return o;
    }

    private static CharSequence[] percent(CharSequence[] v) {
        CharSequence[] o = new CharSequence[v.length];
        for (int i = 0; i < v.length; i++) {
            o[i] = Math.round(Float.parseFloat(v[i].toString()) * 100) + "%";
        }
        return o;
    }

    private int tokenColor(String t) {
        switch (t) {
            case "background": return mSpec.background;
            case "surface": return mSpec.surface;
            case "onSurface": return mSpec.onSurface;
            case "onSurfaceVariant": return mSpec.onSurfaceVariant;
            case "primary": return mSpec.primary;
            case "onPrimary": return mSpec.onPrimary;
            case "secondary": return mSpec.secondary;
            case "accent": return mSpec.accent;
            case "error": return mSpec.error;
            default: return mSpec.scrim;
        }
    }

    /** Element color: its own or the one the shell takes by default (approximately). */
    private int elementColor(String e) {
        if (mSpec.hasElement(e)) return mSpec.color(e, 0);
        if (e.startsWith("clock.handSecond") || e.equals("clock.seconds")
                || e.equals("clock.center") || e.equals("bouncer.pattern")) return mSpec.accent;
        if (e.equals("qs.tileActive") || e.equals("qs.slider")) return mSpec.primary;
        if (e.equals("qs.onTileActive")) return mSpec.onPrimary;
        if (e.equals("bouncer.error")) return mSpec.error;
        if (e.equals("clock.status") || e.equals("clock.ticks") || e.equals("pager.indicator")) {
            return mSpec.onSurfaceVariant;
        }
        if (e.endsWith("background") || e.equals("qs.tile") || e.equals("bouncer.key")) {
            return ThemeSpec.over(mSpec.surface, mSpec.background);
        }
        return mSpec.onSurface;
    }

    private int[] palette() {
        return new int[] {mSpec.background, mSpec.surface, mSpec.onSurface,
                mSpec.onSurfaceVariant, mSpec.primary, mSpec.secondary, mSpec.accent};
    }

    interface Setter {
        void set(Object v);
    }

    private void color(PreferenceGroup g, String key, CharSequence title, int color,
            Object own, Setter setter) {
        Context c = getContext();
        Preference p = new Preference(c);
        p.setKey("e:" + key);
        p.setTitle(title);
        p.setSummary(own != null ? String.valueOf(own)
                : getString(R.string.rear_ed_inherited, ThemeJson.toHex(color)));
        GradientDrawable sw = new GradientDrawable();
        sw.setShape(GradientDrawable.OVAL);
        sw.setColor(color);
        float dp = c.getResources().getDisplayMetrics().density;
        sw.setStroke((int) dp, 0x40808080);
        sw.setSize((int) (28 * dp), (int) (28 * dp));
        p.setIcon(sw);
        p.setOnPreferenceClickListener(x -> {
            ColorPickerDialog.show(c, title, color, palette(), own != null,
                    new ColorPickerDialog.Callback() {
                        @Override
                        public void onColor(int col) {
                            setter.set(ThemeJson.toHex(col));
                        }

                        @Override
                        public void onReset() {
                            setter.set(null);
                        }
                    });
            return true;
        });
        g.addPreference(p);
    }

    private void setGradient(int idx, Object v) {
        try {
            JSONArray cur = new JSONArray();
            for (int i = 0; i < mSpec.gradient.length; i++) {
                cur.put(ThemeJson.toHex(mSpec.gradient[i]));
            }
            if (cur.length() < 2) {
                cur = new JSONArray().put(ThemeJson.toHex(mSpec.background))
                        .put(ThemeJson.toHex(mSpec.surface));
            }
            if (v == null) {
                if (idx >= 2 && idx < cur.length()) cur.remove(idx);
            } else if (idx < cur.length()) {
                cur.put(idx, v);
            } else {
                cur.put(v);
            }
            set("wallpaper.gradient", cur);
        } catch (Exception ignored) {
        }
    }

    private void text(PreferenceGroup g, String path, int title) {
        EditTextPreference p = new EditTextPreference(getContext());
        p.setKey("e:" + path);
        p.setPersistent(false);
        p.setTitle(title);
        p.setDialogTitle(title);
        String v = mRaw.optString(path, "");
        p.setText(v);
        p.setSummary(v.isEmpty() ? getString(R.string.rear_ed_empty) : v);
        p.setOnPreferenceChangeListener((x, nv) -> {
            String s = ((String) nv).trim();
            set(path, s.isEmpty() && !"name".equals(path) ? null : s);
            return false;
        });
        g.addPreference(p);
    }

    private static final String INHERIT = "@inherit";

    /**
     * List of a theme value. The first item is "same as parent" (the key is removed from the theme), the
     * summary shows whether the value is own or inherited.
     */
    private void listE(PreferenceGroup g, String path, int title, CharSequence[] entries,
            CharSequence[] values, int kind) {
        ListPreference p = new ListPreference(getContext());
        p.setKey("e:" + path);
        p.setPersistent(false);
        p.setTitle(title);
        p.setDialogTitle(title);
        Object cur = resolved(path);
        int idx = closest(values, cur, kind);
        CharSequence label = idx >= 0 ? entries[idx] : String.valueOf(cur);
        boolean mine = own(path) != null;
        CharSequence[] e2 = new CharSequence[entries.length + 1];
        CharSequence[] v2 = new CharSequence[values.length + 1];
        e2[0] = getString(R.string.rear_ed_inherit_item, label);
        v2[0] = INHERIT;
        System.arraycopy(entries, 0, e2, 1, entries.length);
        System.arraycopy(values, 0, v2, 1, values.length);
        p.setEntries(e2);
        p.setEntryValues(v2);
        p.setValueIndex(mine && idx >= 0 ? idx + 1 : 0);
        p.setSummary(mine ? KeyPrefs.literal(label)
                : KeyPrefs.literal(getString(R.string.rear_ed_inherited, label)));
        p.setOnPreferenceChangeListener((x, nv) -> {
            String s = (String) nv;
            Object v;
            if (INHERIT.equals(s)) v = null;
            else if (kind == K_INT) v = Integer.parseInt(s);
            else if (kind == K_FLOAT) v = Double.parseDouble(s);
            else if (kind == K_BOOL) v = Boolean.valueOf(s);
            else if (kind == K_H24) v = "system".equals(s) ? "system" : (Object) Boolean.valueOf(s);
            else v = s;
            set(path, v);
            return false;
        });
        g.addPreference(p);
    }

    private static int closest(CharSequence[] values, Object cur, int kind) {
        if (cur == null) return -1;
        if (kind == K_STR || kind == K_BOOL || kind == K_H24) {
            for (int i = 0; i < values.length; i++) {
                if (values[i].toString().equals(String.valueOf(cur))) return i;
            }
            return -1;
        }
        double d;
        try {
            d = cur instanceof Number ? ((Number) cur).doubleValue()
                    : Double.parseDouble(String.valueOf(cur));
        } catch (NumberFormatException e) {
            return -1;
        }
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < values.length; i++) {
            double x = Double.parseDouble(values[i].toString());
            if (Math.abs(x - d) < bestD) {
                bestD = Math.abs(x - d);
                best = i;
            }
        }
        return best;
    }

    private void boolSwitch(PreferenceGroup g, String path, int title) {
        listE(g, path, title, new CharSequence[] {getString(R.string.rear_ed_on),
                getString(R.string.rear_ed_off)}, new CharSequence[] {"true", "false"}, K_BOOL);
    }

    private void h24(PreferenceGroup g) {
        listE(g, "clock.24h", R.string.rear_24h_title,
                skip1(getResources().getTextArray(R.array.rear_24h_entries)),
                new CharSequence[] {"system", "true", "false"}, K_H24);
    }

    /** Parameters of the chosen clock face (clock.faces.<id>.*). */
    private void faceParams(PreferenceGroup g) {
        String face = mSpec.face;
        String base = "clock.faces." + face + ".";
        switch (face) {
            case "analog": {
                CharSequence[] ticks = {"all", "hours", "none"};
                listE(g, base + "ticks", R.string.rear_ed_ticks,
                        getResources().getTextArray(R.array.rear_ed_ticks_entries), ticks, K_STR);
                boolSwitch(g, base + "numbers", R.string.rear_ed_numbers);
                CharSequence[] hw = {"0.5", "0.75", "1.0", "1.5", "2.0"};
                listE(g, base + "handWidth", R.string.rear_ed_hand_width, percent(hw), hw,
                        K_FLOAT);
                break;
            }
            case "words": {
                CharSequence[] lang = {"system", "ru", "en"};
                listE(g, base + "language", R.string.rear_ed_language,
                        getResources().getTextArray(R.array.rear_ed_lang_entries), lang, K_STR);
                CharSequence[] cs = {"lower", "upper", "title"};
                listE(g, base + "case", R.string.rear_ed_case,
                        getResources().getTextArray(R.array.rear_ed_case_entries), cs, K_STR);
                break;
            }
            case "minimal":
                boolSwitch(g, base + "leadingZero", R.string.rear_ed_leading_zero);
                break;
            default: {
                boolSwitch(g, base + "blink", R.string.rear_ed_blink);
                boolSwitch(g, base + "leadingZero", R.string.rear_ed_leading_zero);
                CharSequence[] sep = {":", ".", " ", "·"};
                CharSequence[] sepn = {":", ".", getString(R.string.rear_ed_space), "·"};
                listE(g, base + "separator", R.string.rear_ed_separator, sepn, sep, K_STR);
                break;
            }
        }
    }

    private void fontList(PreferenceGroup g, String path, int title, boolean clock) {
        List<CharSequence> names = new ArrayList<>();
        List<CharSequence> vals = new ArrayList<>();
        names.add(getString(R.string.rear_ed_inherit_item, ""));
        vals.add(INHERIT);
        if (clock) {
            names.add(getString(R.string.rear_ed_same));
            vals.add("");
        }
        for (String f : ThemeJson.SYSTEM_FONTS) {
            names.add(f);
            vals.add(f);
        }
        Object cur = resolved(path);
        String curS = cur == null ? "" : String.valueOf(cur);
        if (curS.startsWith(ThemeJson.FILE_PREFIX)) {
            names.add(fileLabel(curS));
            vals.add(curS);
        }
        names.add(getString(R.string.rear_ed_font_file));
        vals.add("@pick");
        ListPreference p = new ListPreference(getContext());
        p.setKey("e:" + path);
        p.setPersistent(false);
        p.setTitle(title);
        p.setDialogTitle(title);
        p.setEntries(names.toArray(new CharSequence[0]));
        p.setEntryValues(vals.toArray(new CharSequence[0]));
        int idx = vals.indexOf(curS);
        CharSequence label = idx >= 0 ? names.get(idx) : curS;
        p.setValueIndex(own(path) != null && idx >= 0 ? idx : 0);
        p.setSummary(KeyPrefs.literal(own(path) != null ? label
                : getString(R.string.rear_ed_inherited, label)));
        p.setOnPreferenceChangeListener((x, nv) -> {
            String s = (String) nv;
            if ("@pick".equals(s)) {
                String[] types = {"font/ttf", "font/otf", "font/*", "application/x-font-ttf",
                        "application/x-font-otf", "application/octet-stream"};
                if (clock) mPickClockFont.launch(types);
                else mPickFont.launch(types);
            } else if (INHERIT.equals(s)) {
                set(path, null);
            } else if (s.startsWith(ThemeJson.FILE_PREFIX)) {
                // the file is already chosen: no change
            } else {
                set(path, s.isEmpty() ? null : s);
            }
            return false;
        });
        g.addPreference(p);
    }

    private String fileLabel(String ref) {
        int slash = ref.lastIndexOf('/');
        return getString(R.string.rear_ed_file, slash >= 0 ? ref.substring(slash + 1) : ref);
    }

    private void onFile(Uri uri, String path) {
        if (uri == null) return;
        Context c = getContext();
        String name = "file";
        try (Cursor cur = c.getContentResolver().query(uri,
                new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) name = cur.getString(0);
        } catch (RuntimeException ignored) {
        }
        try {
            String rel = RearThemes.addFile(c, mTargetId, uri, name);
            if ("wallpaper.image".equals(path)) {
                JSONObject w = child(mRaw, "wallpaper");
                w.put("type", "image");
            }
            set(path, rel);
        } catch (Exception e) {
            Toast.makeText(c, getString(R.string.rear_theme_failed, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void chooseBase() {
        Context c = getContext();
        List<RearThemes.Info> all = RearThemes.list(c);
        List<CharSequence> names = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        names.add(getString(R.string.rear_ed_base_none));
        ids.add("");
        for (RearThemes.Info i : all) {
            if (i.id.equals(mTargetId)) continue;
            names.add(i.name);
            ids.add(i.id);
        }
        new AlertDialog.Builder(c)
                .setTitle(R.string.rear_ed_base)
                .setSingleChoiceItems(names.toArray(new CharSequence[0]), ids.indexOf(mBase),
                        (d, w) -> {
                            mBase = ids.get(w);
                            d.dismiss();
                            refresh();
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void save(boolean apply) {
        Context c = getContext();
        try {
            JSONObject out = layer();
            out.put("schema", ThemeJson.SCHEMA);
            ThemeJson.Report r = ThemeJson.validate(out.toString(),
                    RearThemes.isBuiltin(mTargetId) ? null : RearThemes.files(c, mTargetId));
            if (!r.ok() || mError != null) {
                new AlertDialog.Builder(c)
                        .setTitle(R.string.rear_ed_save_failed)
                        .setMessage(mError != null ? mError : r.toString())
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                return;
            }
            RearThemes.saveRaw(c, mTargetId, out);
            mSaved = true;
            mNew = false;
            if (apply) RearConfig.put(c, RearKeys.THEME, mTargetId);
            Toast.makeText(c, apply ? R.string.rear_ed_saved_applied : R.string.rear_ed_saved,
                    Toast.LENGTH_SHORT).show();
            if (apply) requireActivity().finish();
        } catch (Exception e) {
            Toast.makeText(c, getString(R.string.rear_theme_failed, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }
}
