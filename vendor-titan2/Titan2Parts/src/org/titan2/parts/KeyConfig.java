package org.titan2.parts;

import android.content.ContentResolver;
import android.content.Context;
import android.hardware.input.InputManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.Map;

/**
 * Titan 2 programmable keys, letter shortcuts and the space bar: which keys, modes and functions
 * exist and where they are stored.
 *
 * Settings.System keys for modes and functions keep the names the factory firmware uses, so
 * existing settings stay valid: "key prefix" + name
 * (fn_programmable_key_enable, fn_programmable_key_function, func1_shortcut_key_enable, ...).
 * short/long/double actions are our own titan2_key_<key>_<press>.
 */
public final class KeyConfig {
    private KeyConfig() {}

    private static final String TAG = "Titan2KeyConfig";

    // ---- Keys ----
    // Menu order: Func1, Func2, Sym, Fn, Shift, Alt, Back, Recent.
    // The keyboard has one Shift (left) and one Alt (right): setting prefixes shift_l_/alt_r_.
    public static final String[] KEYS =
            {"func1", "func2", "sym", "fn", "shift_l", "alt_r", "back", "recent"};
    public static final int SLOT_FUNC1 = 0, SLOT_FUNC2 = 1, SLOT_SYM = 2, SLOT_FN = 3,
            SLOT_SHIFT = 4, SLOT_ALT = 5, SLOT_BACK = 6, SLOT_RECENT = 7;
    /** Scan codes: side keys from getevent 2026-10-18, keyboard from TitanKey.kl. */
    static final int[] SCANCODES = {249, 250, 253, 251, 42, 100, 158, 580};
    /** Key code after vendor-titan2/keylayout (before KeyRemapper remapping). */
    static final int[] BASE_KEYCODES = {
            KeyEvent.KEYCODE_MACRO_1, KeyEvent.KEYCODE_MACRO_2, KeyEvent.KEYCODE_SYM,
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_APP_SWITCH,
    };
    /** Keyboard device name (aw9523_key). Side keys come from mtk-pmic-keys / gpio_key-func. */
    static final String KEYBOARD_DEVICE = "TitanKey";

    /** A key on the keyboard itself (not a side key): remapped via KeyRemapper in InputReader. */
    public static boolean isKeyboardKey(int slot) {
        return slot >= SLOT_SYM;
    }

    public static boolean isSideKey(int slot) {
        return slot == SLOT_FUNC1 || slot == SLOT_FUNC2;
    }

    public static String prefix(int slot) {
        return KEYS[slot] + "_";
    }

    public static int slotOf(String key) {
        for (int i = 0; i < KEYS.length; i++) {
            if (KEYS[i].equals(key)) return i;
        }
        return -1;
    }

    // ---- Modes ----
    public static final String MODE_PTT = "smartkey_ptt_app_enable";
    public static final String MODE_PROGRAMMABLE = "programmable_key_enable";
    public static final String MODE_SHORTCUT = "shortcut_key_enable";
    /** App for PTT (package), setting <p>smartkey_ptt_app_picker. */
    public static final String PTT_PICKER = "smartkey_ptt_app_picker";
    public static final String PROGRAMMABLE_FUNCTION = "programmable_key_function";

    /** Key modes in menu order (PTT only for side keys). */
    public static String[] modesFor(int slot) {
        return isSideKey(slot)
                ? new String[] {MODE_PTT, MODE_PROGRAMMABLE, MODE_SHORTCUT}
                : new String[] {MODE_PROGRAMMABLE, MODE_SHORTCUT};
    }

    /**
     * Stock: the first mode with <p><mode> == 1, otherwise shortcut. Default programmable for Shift,
     * Alt, Back, Recent; Sym/Fn are programmable too (in the factory firmware their remap always
     * works until the mode is changed explicitly).
     */
    public static String getMode(ContentResolver cr, int slot, String pkg) {
        String sfx = suffix(cr, slot, pkg);
        for (String mode : modesFor(slot)) {
            String v = getString(cr, prefix(slot) + mode + sfx);
            if ("1".equals(v)) return mode;
        }
        return isKeyboardKey(slot) ? MODE_PROGRAMMABLE : MODE_SHORTCUT;
    }

    /** pkg: app profile (null for the common settings). */
    public static void setMode(Context context, int slot, String mode, String pkg) {
        ContentResolver cr = context.getContentResolver();
        String sfx = pkg == null ? "" : "@" + pkg;
        for (String m : modesFor(slot)) {
            putString(cr, prefix(slot) + m + sfx, m.equals(mode) ? "1" : "0");
        }
        applyRemap(context, slot, pkg);
    }

    // ---- Programmable mode functions ----
    // Index = value of <p>programmable_key_function; value = the keycode the key produces.
    public static final int FUNC_NONE = 0, FUNC_CTRL = 1, FUNC_SYM = 2, FUNC_MAGIC = 3,
            FUNC_MEDIA = 4, FUNC_TAB = 5, FUNC_HOME = 6, FUNC_SHIFT = 7, FUNC_ALT = 8,
            FUNC_BACK = 9, FUNC_POWER = 10, FUNC_RECENT = 11;
    static final int[] FUNC_KEYCODES = {
            KeyEvent.KEYCODE_UNKNOWN, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_SYM,
            KeyEvent.KEYCODE_UNKNOWN /* Magic: key code 403, handled by KeyHandler */,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_APP_SWITCH,
    };

    /**
     * Functions in menu order for Titan 2
     * (config_support_keyboard=true, config_key_0_period_comma=false -> no "0", ".", ",").
     */
    public static int[] functionsFor(int slot) {
        if (isSideKey(slot)) {
            return new int[] {FUNC_NONE, FUNC_MEDIA, FUNC_POWER, FUNC_SYM, FUNC_CTRL, FUNC_TAB,
                    FUNC_SHIFT, FUNC_ALT, FUNC_HOME, FUNC_BACK, FUNC_RECENT, FUNC_MAGIC};
        }
        if (slot == SLOT_SYM || slot == SLOT_FN) {
            return new int[] {FUNC_NONE, FUNC_SYM, FUNC_CTRL, FUNC_TAB, FUNC_SHIFT, FUNC_ALT,
                    FUNC_HOME, FUNC_BACK, FUNC_RECENT, FUNC_MAGIC};
        }
        return new int[] {FUNC_SYM, FUNC_CTRL, FUNC_TAB, FUNC_SHIFT, FUNC_ALT, FUNC_HOME,
                FUNC_BACK, FUNC_RECENT, FUNC_MAGIC};
    }

    /**
     * Default: Shift, Alt, Back and Recent map "to themselves" (Shift->Shift, Alt->Alt, Back->Back,
     * Recent->Recent), side keys to None. Sym->Sym, Fn->Ctrl as on the factory firmware
     * (persist.sys.agui_programmable_sym_key=63, _fn_key=113) and as in our TitanKey.kl;
     * the factory firmware with ro.agui.symkey_fnkey_default=2-6 sets Fn->Home.
     */
    static int defaultFunction(int slot) {
        switch (slot) {
            case SLOT_SYM: return FUNC_SYM;
            case SLOT_FN: return FUNC_CTRL;
            case SLOT_SHIFT: return FUNC_SHIFT;
            case SLOT_ALT: return FUNC_ALT;
            case SLOT_BACK: return FUNC_BACK;
            case SLOT_RECENT: return FUNC_RECENT;
            default: return FUNC_NONE;
        }
    }

    public static int getFunction(ContentResolver cr, int slot, String pkg) {
        String v = getString(cr, prefix(slot) + PROGRAMMABLE_FUNCTION + suffix(cr, slot, pkg));
        if (v != null) {
            try {
                int f = Integer.parseInt(v.trim());
                if (f >= 0 && f < FUNC_KEYCODES.length) return f;
            } catch (NumberFormatException e) {
                // the default follows
            }
        }
        return defaultFunction(slot);
    }

    public static void setFunction(Context context, int slot, int function, String pkg) {
        putString(context.getContentResolver(), prefix(slot) + PROGRAMMABLE_FUNCTION
                + (pkg == null ? "" : "@" + pkg), String.valueOf(function));
        applyRemap(context, slot, pkg);
    }

    // ---- App profiles (Titan2Parts extension) ----
    // A key may have its own set for package pkg: flag titan2_profile_<key>@pkg = 1, then
    // mode, function, actions and PTT are read from the same keys with the "@pkg" suffix.
    /** Comma-separated list of packages with profiles, only for the settings screen. */
    public static final String PROFILE_APPS = "titan2_key_profile_apps";

    public static String profileFlag(int slot, String pkg) {
        return "titan2_profile_" + KEYS[slot] + "@" + pkg;
    }

    public static boolean isOverridden(ContentResolver cr, int slot, String pkg) {
        return pkg != null && !pkg.isEmpty() && "1".equals(getString(cr, profileFlag(slot, pkg)));
    }

    /** Key suffix: "@pkg" if the key has a profile for pkg, otherwise "". */
    static String suffix(ContentResolver cr, int slot, String pkg) {
        return isOverridden(cr, slot, pkg) ? "@" + pkg : "";
    }

    /** Enable/disable a key profile; when enabled it starts as a copy of the common settings. */
    public static void setOverridden(Context context, int slot, String pkg, boolean on) {
        ContentResolver cr = context.getContentResolver();
        if (on && getString(cr, prefix(slot) + PROGRAMMABLE_FUNCTION + "@" + pkg) == null) {
            String mode = getMode(cr, slot, null);
            for (String m : modesFor(slot)) {
                putString(cr, prefix(slot) + m + "@" + pkg, m.equals(mode) ? "1" : "0");
            }
            putString(cr, prefix(slot) + PROGRAMMABLE_FUNCTION + "@" + pkg,
                    String.valueOf(getFunction(cr, slot, null)));
            for (String press : PRESSES) {
                putString(cr, settingName(KEYS[slot], press) + "@" + pkg,
                        getAction(cr, KEYS[slot], press, null));
            }
            String ptt = getPttApp(cr, slot, null);
            if (ptt != null) putString(cr, prefix(slot) + PTT_PICKER + "@" + pkg, ptt);
        }
        putString(cr, profileFlag(slot, pkg), on ? "1" : "0");
    }

    public static java.util.List<String> profileApps(ContentResolver cr) {
        java.util.List<String> out = new ArrayList<>();
        String v = getString(cr, PROFILE_APPS);
        if (v != null) {
            for (String p : v.split(",")) {
                if (!p.isEmpty() && !out.contains(p)) out.add(p);
            }
        }
        return out;
    }

    public static void setProfileApps(ContentResolver cr, java.util.List<String> apps) {
        putString(cr, PROFILE_APPS, String.join(",", apps));
    }

    /** The key currently works as the Magic key (Programmable -> Magic). */
    static boolean isMagic(ContentResolver cr, int slot, String pkg) {
        return MODE_PROGRAMMABLE.equals(getMode(cr, slot, pkg))
                && getFunction(cr, slot, pkg) == FUNC_MAGIC;
    }

    // ---- Keyboard key remapping ----

    /**
     * What InputReader should remap a keyboard key to. KEYCODE_UNKNOWN: the key is
     * handled by KeyHandler by scan code (Shortcut, None, Magic, Media); this also drops its
     * modifier role (key codes 403/405/406..412).
     */
    static int remapTarget(ContentResolver cr, int slot, String pkg) {
        if (!MODE_PROGRAMMABLE.equals(getMode(cr, slot, pkg))) {
            return KeyEvent.KEYCODE_UNKNOWN;
        }
        int f = getFunction(cr, slot, pkg);
        if (f == FUNC_NONE || f == FUNC_MAGIC || f == FUNC_MEDIA) {
            return KeyEvent.KEYCODE_UNKNOWN;
        }
        return FUNC_KEYCODES[f];
    }

    /**
     * Apply the key remap via the framework KeyRemapper (InputManager.remapModifierKey):
     * it stores it itself and applies it in InputReader to all "full" physical keyboards before
     * PhoneWindowManager and the IME. The key is the code from TitanKey.kl, each
     * Titan 2 key has its own. Side keys are not alphabetic devices and KeyRemapper ignores them:
     * KeyHandler remaps them by injecting.
     */
    public static void applyRemap(Context context, int slot, String pkg) {
        if (!isKeyboardKey(slot)) {
            return;
        }
        if (pkg != null && !pkg.equals(sActivePackage)) {
            // profile of an app other than the focused one: applied when switching to it
            return;
        }
        pkg = sActivePackage;
        try {
            InputManager im = context.getSystemService(InputManager.class);
            Map<Integer, Integer> current = im.getModifierKeyRemapping();
            int from = BASE_KEYCODES[slot];
            int to = remapTarget(context.getContentResolver(), slot, pkg);
            Integer cur = current.get(from);
            if (to == from) {
                if (cur != null) im.remapModifierKey(from, from); // from == to clears the remap
            } else if (cur == null || cur != to) {
                im.remapModifierKey(from, to);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "remap " + KEYS[slot] + " failed", e);
        }
    }

    /**
     * Reconcile at startup (from KeyHandler): only keys configured on this screen, so as
     * not to overwrite remaps made earlier in "Key remapping" or in the Android Settings.
     */
    static void applyConfiguredRemaps(Context context, String focusPkg, String oldPkg) {
        ContentResolver cr = context.getContentResolver();
        sActivePackage = focusPkg;
        for (int slot = SLOT_SYM; slot < KEYS.length; slot++) {
            boolean configured = getString(cr, prefix(slot) + PROGRAMMABLE_FUNCTION) != null;
            for (String m : modesFor(slot)) {
                configured |= getString(cr, prefix(slot) + m) != null;
            }
            configured |= isOverridden(cr, slot, focusPkg) || isOverridden(cr, slot, oldPkg);
            if (configured) applyRemap(context, slot, null);
        }
    }

    /**
     * The focused package the keys are currently laid out for in KeyRemapper. Lives in system_server
     * (KeyHandler); always null in the Titan2Parts process: there the remap is applied for the common
     * settings, and KeyHandler applies the profile on the next focus change.
     */
    static volatile String sActivePackage;

    // ---- Shortcut mode actions ----
    public static final String[] PRESSES = {"short", "long", "double"};

    // Setting value. "app:<package>" launches an app.
    public static final String NONE = "none";
    public static final String TORCH = "torch";
    public static final String CAMERA = "camera";
    public static final String NOTIFICATIONS = "notifications";
    public static final String RINGER = "ringer";
    // rear screen (RearService)
    public static final String REAR_SELFIE = "rear_selfie";
    public static final String REAR_QR = "rear_qr";
    public static final String REAR_LIGHT = "rear_light";
    /** Camera and microphone off for all apps (Android system toggles). */
    public static final String PRIVACY = "privacy";
    // Built-in functions
    /** Shortcut_function2: voice recorder. */
    public static final String RECORD = "record";
    /** Shortcut_function7: screenshot after 800 ms. */
    public static final String SCREENSHOT = "screenshot";
    /** Shortcut_function8: screen recording (crDroid built-in recorder, on/off). */
    public static final String SCREEN_RECORD = "screen_record";
    /** Shortcut_function12: silent mode (on/off). */
    public static final String SILENT = "silent";
    /** shortcut_function_audio_vibration: vibrate mode (on/off). */
    public static final String VIBRATE = "vibrate";
    /** Shortcut_function30: turn the screen off. */
    public static final String SCREEN_OFF = "screen_off";
    /** shortcut_function_back / _home / _recent: key press. */
    public static final String KEY_BACK = "key_back";
    public static final String KEY_HOME = "key_home";
    public static final String KEY_RECENT = "key_recent";
    public static final String APP_PREFIX = "app:";
    /** App shortcut (LauncherApps): "shortcut:<package>/<id>". */
    public static final String SHORTCUT_PREFIX = "shortcut:";
    /** Intent URI (old ACTION_CREATE_SHORTCUT shortcuts: direct call, SMS). */
    public static final String INTENT_PREFIX = "intent:";

    /**
     * Built-in functions available for a key and press type:
     * torch, vibrate and "screen off" only for Func1/Func2; no torch, silent mode or vibrate
     * on a short press. Then come our own functions.
     */
    public static String[] builtinActions(int slot, String press) {
        boolean side = isSideKey(slot);
        boolean isShort = "short".equals(press);
        ArrayList<String> out = new ArrayList<>();
        out.add(NONE);
        if (side && !isShort) out.add(TORCH);
        out.add(RECORD);
        out.add(SCREENSHOT);
        out.add(SCREEN_RECORD);
        if (!isShort) out.add(SILENT);
        if (side) out.add(SCREEN_OFF);
        if (side && !isShort) out.add(VIBRATE);
        out.add(KEY_BACK);
        out.add(KEY_HOME);
        out.add(KEY_RECENT);
        // Titan2Parts: torch on a short press of the side keys existed before the port, keep it
        if (side && isShort) out.add(TORCH);
        out.add(CAMERA);
        out.add(NOTIFICATIONS);
        out.add(RINGER);
        out.add(PRIVACY);
        if (side && RearService.SUPPORTED) {
            out.add(REAR_SELFIE);
            out.add(REAR_QR);
            out.add(REAR_LIGHT);
        }
        return out.toArray(new String[0]);
    }

    /** Defaults: Func1 long is the torch, Func2 short is the camera (factory defaults). */
    static String defaultAction(String key, String press) {
        if ("func1".equals(key) && "long".equals(press)) return TORCH;
        if ("func2".equals(key) && "short".equals(press)) return CAMERA;
        return NONE;
    }

    public static String settingName(String key, String press) {
        return "titan2_key_" + key + "_" + press;
    }

    public static String getAction(ContentResolver cr, String key, String press, String pkg) {
        String v = getString(cr, settingName(key, press) + suffix(cr, slotOf(key), pkg));
        return v != null ? v : defaultAction(key, press);
    }

    public static void setAction(ContentResolver cr, String key, String press, String action,
            String pkg) {
        putString(cr, settingName(key, press) + (pkg == null ? "" : "@" + pkg), action);
    }

    public static String getPttApp(ContentResolver cr, int slot, String pkg) {
        return getString(cr, prefix(slot) + PTT_PICKER + suffix(cr, slot, pkg));
    }

    public static void setPttApp(ContentResolver cr, int slot, String app, String pkg) {
        putString(cr, prefix(slot) + PTT_PICKER + (pkg == null ? "" : "@" + pkg), app);
    }

    // ---- Letter shortcuts ----
    /** keyboard_shortcut_enable: 1/0, default 0 (the factory firmware stores "true"/"false"). */
    public static final String LETTER_ENABLE = "titan2_keyboard_shortcut_enable";
    public static final String[] LETTER_PRESSES = {"short", "long"};

    public static String letterSetting(char letter, String press) {
        return "titan2_letter_" + letter + "_" + press;
    }

    public static String letterTitleSetting(char letter, String press) {
        return letterSetting(letter, press) + "_title";
    }

    public static boolean lettersEnabled(ContentResolver cr) {
        return getInt(cr, LETTER_ENABLE, 0) == 1;
    }

    public static String getLetter(ContentResolver cr, char letter, String press) {
        String v = getString(cr, letterSetting(letter, press));
        return v != null && !v.isEmpty() && !NONE.equals(v) ? v : null;
    }

    public static void setLetter(ContentResolver cr, char letter, String press, String value,
            String title) {
        putString(cr, letterSetting(letter, press), value);
        putString(cr, letterTitleSetting(letter, press), title);
    }

    public static String getLetterTitle(ContentResolver cr, char letter, String press) {
        return getString(cr, letterTitleSetting(letter, press));
    }

    /** Whether a short or long shortcut is assigned to the letter. */
    static boolean letterAssigned(ContentResolver cr, char letter) {
        return getLetter(cr, letter, "short") != null || getLetter(cr, letter, "long") != null;
    }

    // ---- Space bar: 1/0, default 0 ----
    public static final String SPACE_GOHOME = "titan2_space_key_gohome";
    public static final String SPACE_ANSWER = "titan2_space_key_call_answer";
    public static final String SPACE_REJECT = "titan2_space_key_reject_call";
    public static final String SPACE_CAPTURE = "titan2_space_key_capture";

    /** Holding space >= 500 ms switches to the next layout (like Ctrl+Space). 1/0, default 0. */
    public static final String SPACE_LONG_LAYOUT = "titan2_space_long_layout";

    // ---- Chords (Titan2Parts extension): Sym (or Fn+Sym) + letter ----
    public static final String CHORD_ENABLE = "titan2_chord_enable";
    /** 0: Sym+letter, 1: Fn+Sym+letter. */
    public static final String CHORD_MODIFIER = "titan2_chord_modifier";
    /** 0: only outside text fields (Plektra is left alone), 1: in text fields too. */
    public static final String CHORD_IN_FIELDS = "titan2_chord_in_fields";
    /** Text snippet: "text:<text>", the text may contain {date}, {time}, {date:pattern}. */
    public static final String TEXT_PREFIX = "text:";

    public static String chordSetting(char letter) {
        return "titan2_chord_" + letter;
    }

    public static String getChord(ContentResolver cr, char letter) {
        String v = getString(cr, chordSetting(letter));
        return v != null && !v.isEmpty() && !NONE.equals(v) ? v : null;
    }

    public static String getChordTitle(ContentResolver cr, char letter) {
        return getString(cr, chordSetting(letter) + "_title");
    }

    public static void setChord(ContentResolver cr, char letter, String value, String title) {
        putString(cr, chordSetting(letter), value);
        putString(cr, chordSetting(letter) + "_title", title);
    }

    // ---- Settings.System of the current user ----
    static String getString(ContentResolver cr, String name) {
        return Settings.System.getStringForUser(cr, name, UserHandle.USER_CURRENT);
    }

    static void putString(ContentResolver cr, String name, String value) {
        Settings.System.putStringForUser(cr, name, value, UserHandle.USER_CURRENT);
    }

    public static int getInt(ContentResolver cr, String name, int def) {
        return Settings.System.getIntForUser(cr, name, def, UserHandle.USER_CURRENT);
    }

    public static void putInt(ContentResolver cr, String name, int value) {
        Settings.System.putIntForUser(cr, name, value, UserHandle.USER_CURRENT);
    }
}
