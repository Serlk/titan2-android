package org.titan2.parts;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.net.Uri;
import android.util.Log;

import org.json.JSONObject;
import org.titan2.rear.common.RearContract;
import org.titan2.rear.common.RearKeys;
import org.titan2.rear.common.ThemeJson;
import org.titan2.rear.common.ThemeSpec;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Rear screen themes. Each theme is a folder with theme.json and (optionally) files: fonts,
 * images.
 *   builtin:<name>: assets/rear_themes/<name>/ in the Titan2Parts APK (read-only);
 *   user:<name>   : files/rear_themes/<name>/ (device-protected): import, copies, editor.
 * Inheritance and validation are in ThemeJson (library shared with the shell), reference in THEMES.md.
 */
final class RearThemes {
    private static final String TAG = "Titan2Rear";
    static final String BUILTIN = "builtin:";
    static final String USER = "user:";
    static final String ASSET_DIR = "rear_themes";
    static final String THEME_FILE = "theme.json";
    private static final long MAX_JSON = 256 * 1024;
    private static final long MAX_FILE = 16 * 1024 * 1024;
    private static final long MAX_ZIP = 32 * 1024 * 1024;
    private static final int MAX_ENTRIES = 128;
    static final Set<String> FILE_EXT = new HashSet<>(Arrays.asList(
            "ttf", "otf", "ttc", "png", "jpg", "jpeg", "webp"));

    private RearThemes() {}

    /** A row of the theme list. */
    static final class Info {
        String id;
        String name;
        String author = "";
        String description = "";
        String extendsId = "";
        boolean builtin;
        /** non-null: the theme does not resolve (no parent, broken JSON) */
        String error;
    }

    static File userRoot(Context c) {
        File d = new File(c.getFilesDir(), ASSET_DIR);
        d.mkdirs();
        return d;
    }

    static boolean isBuiltin(String id) {
        return id != null && id.startsWith(BUILTIN);
    }

    private static String name(String id) {
        int colon = id.indexOf(':');
        String n = id.substring(colon + 1);
        if (n.isEmpty() || n.contains("/") || n.contains("..")) {
            throw new IllegalArgumentException("bad theme id " + id);
        }
        return n;
    }

    static File userDir(Context c, String id) {
        return new File(userRoot(c), name(id));
    }

    // ------------------------------------------------------------ reading

    static List<Info> list(Context c) {
        List<Info> out = new ArrayList<>();
        try {
            String[] names = c.getAssets().list(ASSET_DIR);
            if (names != null) {
                Arrays.sort(names);
                // the default theme first
                List<String> ordered = new ArrayList<>(Arrays.asList(names));
                String def = RearKeys.THEME_DEFAULT.substring(BUILTIN.length());
                if (ordered.remove(def)) ordered.add(0, def);
                for (String n : ordered) out.add(info(c, BUILTIN + n));
            }
        } catch (IOException e) {
            Log.w(TAG, "assets", e);
        }
        File[] dirs = userRoot(c).listFiles();
        if (dirs != null) {
            Arrays.sort(dirs);
            for (File d : dirs) {
                if (new File(d, THEME_FILE).isFile()) out.add(info(c, USER + d.getName()));
            }
        }
        return out;
    }

    static boolean exists(Context c, String id) {
        try {
            rawText(c, id);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static Info info(Context c, String id) {
        Info i = new Info();
        i.id = id;
        i.builtin = isBuiltin(id);
        i.name = id.substring(id.indexOf(':') + 1);
        try {
            JSONObject raw = ThemeJson.upgrade(raw(c, id));
            i.name = raw.optString("name", i.name);
            i.author = raw.optString("author", "");
            i.description = raw.optString("description", "");
            i.extendsId = raw.optString("extends", "");
            resolve(c, id);
        } catch (Exception e) {
            i.error = e.getMessage();
        }
        return i;
    }

    /** theme.json text as in the file. */
    static String rawText(Context c, String id) throws IOException {
        if (isBuiltin(id)) {
            return read(c.getAssets().open(ASSET_DIR + "/" + name(id) + "/" + THEME_FILE),
                    MAX_JSON);
        }
        return read(new FileInputStream(new File(userDir(c, id), THEME_FILE)), MAX_JSON);
    }

    static JSONObject raw(Context c, String id) throws Exception {
        return new JSONObject(rawText(c, id));
    }

    /** Resolve (DEFAULTS <- parents <- theme); files as "@file:<id>/<path>". */
    static JSONObject resolve(Context c, String id) throws Exception {
        return ThemeJson.resolve(id, x -> raw(c, x));
    }

    /** The parsed theme; on error, the default theme, then the built-in values. */
    static ThemeSpec spec(Context c, String id) {
        try {
            return ThemeSpec.parse(resolve(c, id));
        } catch (Exception e) {
            try {
                return ThemeSpec.parse(resolve(c, RearKeys.THEME_DEFAULT));
            } catch (Exception e2) {
                return ThemeSpec.defaults();
            }
        }
    }

    /** File names in the theme folder (except theme.json), relative paths. */
    static Set<String> files(Context c, String id) {
        Set<String> out = new HashSet<>();
        if (isBuiltin(id)) {
            listAssets(c, ASSET_DIR + "/" + name(id), "", out);
        } else {
            listDir(userDir(c, id), "", out);
        }
        out.remove(THEME_FILE);
        return out;
    }

    private static void listAssets(Context c, String dir, String prefix, Set<String> out) {
        try {
            String[] names = c.getAssets().list(dir);
            if (names == null) return;
            for (String n : names) {
                String[] sub = c.getAssets().list(dir + "/" + n);
                if (sub != null && sub.length > 0) listAssets(c, dir + "/" + n, prefix + n + "/", out);
                else out.add(prefix + n);
            }
        } catch (IOException ignored) {
        }
    }

    private static void listDir(File dir, String prefix, Set<String> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) listDir(f, prefix + f.getName() + "/", out);
            else out.add(prefix + f.getName());
        }
    }

    /**
     * A theme file by its "@file:<id>/<path>" reference for reading: a user theme file or a cached
     * copy of a built-in one from assets. null: no file or the path is outside the theme folder.
     */
    static File fileFor(Context c, String themeId, String path) {
        if (!ThemeJson.isSafePath(path)) return null;
        try {
            if (isBuiltin(themeId)) {
                File out = new File(new File(c.getCacheDir(), ASSET_DIR + "/" + name(themeId)),
                        path);
                if (!out.isFile()) {
                    out.getParentFile().mkdirs();
                    // via a temporary file: provider calls run on several binder threads, and a
                    // reader must never see a half-written font
                    File tmp = File.createTempFile(out.getName(), ".tmp", out.getParentFile());
                    try (InputStream in = c.getAssets().open(ASSET_DIR + "/" + name(themeId)
                            + "/" + path); OutputStream os = new FileOutputStream(tmp)) {
                        copy(in, os, MAX_FILE);
                    } catch (IOException e) {
                        tmp.delete();
                        throw e;
                    }
                    if (!tmp.renameTo(out)) {
                        tmp.delete();
                        if (!out.isFile()) throw new IOException("cannot cache " + out);
                    }
                }
                return out;
            }
            File dir = userDir(c, themeId).getCanonicalFile();
            File f = new File(dir, path).getCanonicalFile();
            if (!f.getPath().startsWith(dir.getPath() + File.separator) || !f.isFile()) {
                return null;
            }
            return f;
        } catch (IOException | IllegalArgumentException e) {
            Log.w(TAG, "theme file " + themeId + "/" + path + ": " + e);
            return null;
        }
    }

    static File fileForRef(Context c, String ref) {
        if (ref == null || !ref.startsWith(ThemeJson.FILE_PREFIX)) return null;
        String s = ref.substring(ThemeJson.FILE_PREFIX.length());
        int slash = s.indexOf('/');
        if (slash <= 0) return null;
        return fileFor(c, s.substring(0, slash), s.substring(slash + 1));
    }

    static Typeface typeface(Context c, String font) {
        if (font == null || font.isEmpty()) return Typeface.DEFAULT;
        if (!font.startsWith(ThemeJson.FILE_PREFIX)) return Typeface.create(font, Typeface.NORMAL);
        File f = fileForRef(c, font);
        if (f == null) return Typeface.DEFAULT;
        try {
            Typeface t = new Typeface.Builder(f).build();
            return t != null ? t : Typeface.DEFAULT;
        } catch (RuntimeException e) {
            return Typeface.DEFAULT;
        }
    }

    static Bitmap bitmap(Context c, String ref, int w, int h) {
        File f = fileForRef(c, ref);
        if (f == null) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getPath(), o2);
    }

    // ------------------------------------------------------------ writing

    static void notifyChanged(Context c) {
        c.getContentResolver().notifyChange(RearContract.THEMES, null);
    }

    /** A free id user:<slug>, user:<slug>_2, ... */
    static String newId(Context c, String name) {
        String base = ThemeJson.slug(name);
        String id = USER + base;
        for (int i = 2; new File(userRoot(c), id.substring(USER.length())).exists(); i++) {
            id = USER + base + "_" + i;
        }
        return id;
    }

    /** Write theme.json of a user theme (the folder is created). */
    static void saveRaw(Context c, String id, JSONObject raw) throws Exception {
        if (isBuiltin(id)) throw new IOException("built-in themes are read-only");
        File dir = userDir(c, id);
        dir.mkdirs();
        File tmp = new File(dir, THEME_FILE + ".tmp");
        try (OutputStream os = new FileOutputStream(tmp)) {
            os.write(raw.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(new File(dir, THEME_FILE))) throw new IOException("cannot save theme");
        notifyChanged(c);
    }

    /** Copy a file (font/image) into the theme folder; returns the relative path. */
    static String addFile(Context c, String id, Uri src, String fileName) throws IOException {
        String ext = ext(fileName);
        if (!FILE_EXT.contains(ext)) throw new IOException("unsupported file type: ." + ext);
        String safe = fileName.replaceAll("[^A-Za-z0-9._-]", "_");
        File dir = userDir(c, id);
        dir.mkdirs();
        try (InputStream in = c.getContentResolver().openInputStream(src);
                OutputStream os = new FileOutputStream(new File(dir, safe))) {
            if (in == null) throw new IOException("cannot open " + src);
            copy(in, os, MAX_FILE);
        }
        return safe;
    }

    /** A full copy of a theme (with files) under a new name. */
    static String duplicate(Context c, String id, String newName) throws Exception {
        JSONObject raw = raw(c, id);
        String nid = newId(c, newName);
        File dir = userDir(c, nid);
        dir.mkdirs();
        for (String f : files(c, id)) {
            File src = fileFor(c, id, f);
            if (src == null) continue;
            File dst = new File(dir, f);
            dst.getParentFile().mkdirs();
            try (InputStream in = new FileInputStream(src);
                    OutputStream os = new FileOutputStream(dst)) {
                copy(in, os, MAX_FILE);
            }
        }
        raw.put("name", newName);
        saveRaw(c, nid, raw);
        return nid;
    }

    static boolean delete(Context c, String id) {
        if (isBuiltin(id)) return false;
        boolean ok = deleteTree(userDir(c, id));
        notifyChanged(c);
        return ok;
    }

    private static boolean deleteTree(File f) {
        File[] fs = f.listFiles();
        if (fs != null) for (File x : fs) deleteTree(x);
        return f.delete();
    }

    /** User themes that inherit id (warn before deleting the parent). */
    static List<String> children(Context c, String id) {
        List<String> out = new ArrayList<>();
        for (Info i : list(c)) {
            if (id.equals(i.extendsId)) out.add(i.name);
        }
        return out;
    }

    // ------------------------------------------------------------ import

    static final class ImportResult {
        String id;
        ThemeJson.Report report = new ThemeJson.Report();
    }

    /** Import a .json or .zip (theme.json + files). On validation errors the theme is not saved. */
    static ImportResult importFrom(Context c, Uri uri) {
        ImportResult r = new ImportResult();
        byte[] data;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("cannot open file");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            copy(in, bos, MAX_ZIP);
            data = bos.toByteArray();
        } catch (IOException e) {
            r.report.errors.add(e.getMessage());
            return r;
        }
        boolean zip = data.length > 4 && data[0] == 'P' && data[1] == 'K';
        Map<String, byte[]> files = new LinkedHashMap<>();
        String json;
        if (zip) {
            json = unzip(data, files, r.report);
            if (json == null) return r;
        } else {
            if (data.length > MAX_JSON) {
                r.report.errors.add("theme file is larger than 256 KiB");
                return r;
            }
            json = new String(data, StandardCharsets.UTF_8);
        }
        ThemeJson.Report v = ThemeJson.validate(json, files.keySet());
        r.report.errors.addAll(v.errors);
        r.report.warnings.addAll(v.warnings);
        if (!r.report.ok()) return r;
        try {
            JSONObject raw = ThemeJson.upgrade(new JSONObject(json));
            String ext = raw.optString("extends", "");
            if (!ext.isEmpty() && !exists(c, ext)) {
                r.report.errors.add("\"extends\": theme \"" + ext + "\" is not installed"
                        + " (built-in ids: " + builtinIds(c) + ")");
                return r;
            }
            String name = raw.optString("name", "").trim();
            if (name.isEmpty()) {
                name = uri.getLastPathSegment() != null ? uri.getLastPathSegment() : "Theme";
                raw.put("name", name);
            }
            raw.put("schema", ThemeJson.SCHEMA);
            String id = newId(c, name);
            File dir = userDir(c, id);
            dir.mkdirs();
            for (Map.Entry<String, byte[]> f : files.entrySet()) {
                File dst = new File(dir, f.getKey());
                dst.getParentFile().mkdirs();
                try (OutputStream os = new FileOutputStream(dst)) {
                    os.write(f.getValue());
                }
            }
            saveRaw(c, id, raw);
            // check that the theme resolves (parents, files)
            resolve(c, id);
            r.id = id;
        } catch (Exception e) {
            r.report.errors.add(e.getMessage());
        }
        return r;
    }

    private static String builtinIds(Context c) {
        List<String> ids = new ArrayList<>();
        for (Info i : list(c)) if (i.builtin) ids.add(i.id);
        return String.join(", ", ids);
    }

    /** Unpack a zip: theme.json (at the root or in a single folder) + allowed files. */
    private static String unzip(byte[] data, Map<String, byte[]> files, ThemeJson.Report rep) {
        Map<String, byte[]> all = new LinkedHashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new java.io.ByteArrayInputStream(data))) {
            ZipEntry e;
            int n = 0;
            long total = 0;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                if (++n > MAX_ENTRIES) {
                    rep.errors.add("zip has more than " + MAX_ENTRIES + " files");
                    return null;
                }
                String path = e.getName().replace('\\', '/');
                if (!ThemeJson.isSafePath(path)) {
                    rep.errors.add("unsafe path in zip: " + e.getName());
                    return null;
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                copy(zin, bos, MAX_FILE);
                // the unpacked total is bounded too: 128 entries x 16 MiB would not fit in memory
                total += bos.size();
                if (total > MAX_ZIP) {
                    rep.errors.add("zip unpacks to more than " + (MAX_ZIP >> 20) + " MiB");
                    return null;
                }
                all.put(path, bos.toByteArray());
            }
        } catch (IOException e) {
            rep.errors.add("bad zip: " + e.getMessage());
            return null;
        }
        // a common top directory (an archive of the theme folder): strip it
        String prefix = "";
        if (!all.containsKey(THEME_FILE)) {
            for (String p : all.keySet()) {
                if (p.endsWith("/" + THEME_FILE) && p.indexOf('/') == p.length()
                        - THEME_FILE.length() - 1) {
                    prefix = p.substring(0, p.length() - THEME_FILE.length());
                    break;
                }
            }
        }
        byte[] json = all.get(prefix + THEME_FILE);
        if (json == null) {
            rep.errors.add("zip must contain " + THEME_FILE + " (at the top or in one folder)");
            return null;
        }
        for (Map.Entry<String, byte[]> f : all.entrySet()) {
            String p = f.getKey();
            if (!p.startsWith(prefix) || p.equals(prefix + THEME_FILE)) continue;
            String rel = p.substring(prefix.length());
            if (FILE_EXT.contains(ext(rel))) files.put(rel, f.getValue());
            else rep.warnings.add("file \"" + rel + "\" skipped (allowed: " + FILE_EXT + ")");
        }
        return new String(json, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------ export

    /**
     * Export: a user theme with its chain of user parents is merged into one,
     * "extends" stays only for a built-in theme (it exists on every Titan 2). Files go alongside.
     * A zip if there are files, otherwise JSON. Returns true if a zip was written.
     */
    static boolean export(Context c, String id, OutputStream out) throws Exception {
        List<String> chain = new ArrayList<>();
        List<JSONObject> layers = new ArrayList<>();
        String cur = id;
        String base = "";
        while (cur != null && !cur.isEmpty() && chain.size() < ThemeJson.MAX_DEPTH) {
            if (chain.contains(cur)) break;
            if (isBuiltin(cur) && !cur.equals(id)) {
                base = cur;
                break;
            }
            JSONObject layer = ThemeJson.upgrade(raw(c, cur));
            relocateFiles(layer, cur, cur.equals(id) ? "" : ThemeJson.slug(name(cur)) + "/");
            chain.add(cur);
            layers.add(layer);
            String p = layer.optString("extends", "");
            cur = p.isEmpty() ? null : p;
        }
        JSONObject merged = new JSONObject();
        for (int i = layers.size() - 1; i >= 0; i--) ThemeJson.merge(merged, layers.get(i));
        merged.put("schema", ThemeJson.SCHEMA);
        merged.put("name", layers.get(0).optString("name", name(id)));
        if (!base.isEmpty()) merged.put("extends", base);
        else merged.remove("extends");

        // files: path in the archive -> file
        Map<String, File> files = new LinkedHashMap<>();
        for (int i = 0; i < chain.size(); i++) {
            String tid = chain.get(i);
            String prefix = tid.equals(id) ? "" : ThemeJson.slug(name(tid)) + "/";
            for (String f : files(c, tid)) {
                File src = fileFor(c, tid, f);
                if (src != null) files.put(prefix + f, src);
            }
        }
        byte[] json = merged.toString(2).getBytes(StandardCharsets.UTF_8);
        if (files.isEmpty()) {
            out.write(json);
            return false;
        }
        try (ZipOutputStream zout = new ZipOutputStream(out)) {
            zout.putNextEntry(new ZipEntry(THEME_FILE));
            zout.write(json);
            zout.closeEntry();
            for (Map.Entry<String, File> f : files.entrySet()) {
                zout.putNextEntry(new ZipEntry(f.getKey()));
                try (InputStream in = new FileInputStream(f.getValue())) {
                    copy(in, zout, MAX_FILE);
                }
                zout.closeEntry();
            }
        }
        return true;
    }

    /** Relative file paths of a layer -> with the folder prefix (for merging on export). */
    private static void relocateFiles(JSONObject layer, String id, String prefix)
            throws Exception {
        if (prefix.isEmpty()) return;
        JSONObject t = layer.optJSONObject("typography");
        if (t != null) {
            for (String k : new String[] {"font", "clockFont"}) {
                String f = t.optString(k, "");
                if (ThemeJson.isFontFile(f) && ThemeJson.isSafePath(f)) t.put(k, prefix + f);
            }
        }
        JSONObject w = layer.optJSONObject("wallpaper");
        if (w != null) {
            String img = w.optString("image", "");
            if (!img.isEmpty() && ThemeJson.isSafePath(img)) w.put("image", prefix + img);
        }
    }

    /** Whether the theme (with its user parents) has files -> the export will be a zip. */
    static boolean exportsZip(Context c, String id) {
        String cur = id;
        for (int i = 0; i < ThemeJson.MAX_DEPTH && cur != null && !cur.isEmpty(); i++) {
            if (isBuiltin(cur) && !cur.equals(id)) break;
            if (!files(c, cur).isEmpty()) return true;
            try {
                cur = ThemeJson.upgrade(raw(c, cur)).optString("extends", "");
            } catch (Exception e) {
                break;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ helpers

    static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    static void copy(InputStream in, OutputStream out, long max) throws IOException {
        byte[] buf = new byte[16384];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > max) throw new IOException("file is too large");
            out.write(buf, 0, n);
        }
    }

    static String read(InputStream in, long max) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            copy(is, bos, max);
            return bos.toString(StandardCharsets.UTF_8);
        }
    }
}
