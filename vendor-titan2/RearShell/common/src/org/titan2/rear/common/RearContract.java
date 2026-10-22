package org.titan2.rear.common;

import android.net.Uri;

/**
 * Contract of the rear screen settings provider. Source of truth: Titan2Parts
 * (org.titan2.parts.rear, device-protected, directBootAware); the RearShell shell only reads
 * (and writes a couple of its own keys: favorites, clock face). Access by the REAR_CONFIG signature permission.
 *
 *   content://org.titan2.parts.rear/settings          all keys: key, value
 *   content://org.titan2.parts.rear/settings/<key>    one key
 *   content://org.titan2.parts.rear/themes            theme list: id, name, author, builtin, extends
 *   content://org.titan2.parts.rear/themes/<id>       theme: id, name, json (inheritance already
 *                                                     resolved), raw (as in the file)
 *   content://org.titan2.parts.rear/file/<id>/<path>  theme file (font, image), openFile "r"
 *
 * call(METHOD_PUT, key, {value}): write a key (the shell may write only SHELL_WRITABLE).
 * Changes: notifyChange on settings and themes (observe with notifyForDescendants).
 */
public final class RearContract {
    public static final String AUTHORITY = "org.titan2.parts.rear";
    public static final String PERMISSION = "org.titan2.permission.REAR_CONFIG";

    public static final String PARTS_PACKAGE = "org.titan2.parts";
    public static final String SHELL_PACKAGE = "org.titan2.rear";
    /** rear screen settings in Titan2Parts (main display) */
    public static final String ACTION_REAR_SETTINGS = "org.titan2.parts.REAR_SETTINGS";
    /** the old shell action, now redirects to Titan2Parts */
    public static final String ACTION_SHELL_SETTINGS = "org.titan2.rear.SETTINGS";

    public static final Uri BASE = Uri.parse("content://" + AUTHORITY);
    public static final Uri SETTINGS = Uri.withAppendedPath(BASE, "settings");
    public static final Uri THEMES = Uri.withAppendedPath(BASE, "themes");
    public static final Uri FILES = Uri.withAppendedPath(BASE, "file");

    public static final String COL_KEY = "key";
    public static final String COL_VALUE = "value";
    public static final String COL_ID = "id";
    public static final String COL_NAME = "name";
    public static final String COL_AUTHOR = "author";
    public static final String COL_BUILTIN = "builtin";
    public static final String COL_EXTENDS = "extends";
    public static final String COL_JSON = "json";
    public static final String COL_RAW = "raw";

    public static final String METHOD_PUT = "put";
    public static final String EXTRA_VALUE = "value";

    /** Keys the shell itself may write (long press on an icon, clock face change). */
    public static final String[] SHELL_WRITABLE = {RearKeys.FAVORITES, RearKeys.CLOCK_FACE};

    private RearContract() {}

    public static Uri setting(String key) {
        return SETTINGS.buildUpon().appendPath(key).build();
    }

    public static Uri theme(String id) {
        return THEMES.buildUpon().appendPath(id).build();
    }

    /**
     * Theme file: a ref like "@file:<themeId>/<path>" (see ThemeJson.FILE_PREFIX).
     * A malformed ref (theme JSON is user data) throws IllegalArgumentException.
     */
    public static Uri file(String ref) {
        if (ref == null || !ref.startsWith(ThemeJson.FILE_PREFIX)) {
            throw new IllegalArgumentException("not a theme file ref: " + ref);
        }
        String s = ref.substring(ThemeJson.FILE_PREFIX.length());
        int slash = s.indexOf('/');
        if (slash <= 0 || !ThemeJson.isSafePath(s.substring(slash + 1))) {
            throw new IllegalArgumentException("bad theme file ref: " + ref);
        }
        Uri.Builder b = FILES.buildUpon().appendPath(s.substring(0, slash));
        for (String seg : s.substring(slash + 1).split("/")) {
            b.appendPath(seg);
        }
        return b.build();
    }
}
