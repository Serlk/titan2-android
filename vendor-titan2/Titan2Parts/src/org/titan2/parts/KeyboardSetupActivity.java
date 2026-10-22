package org.titan2.parts;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemProperties;
import android.os.UserHandle;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;
import android.provider.Settings;
import android.view.inputmethod.InputMethodManager;


/**
 * "Titan 2 keyboard setup", a continuation of the first setup: opened once right after the
 * wizard (Google SetupWizard does not allow inserting own pages). Presets and manual tuning
 * of the essentials: input keyboard, Fn/Sym roles, touchpad, backlight, layouts. All of it is also in
 * "Settings -> System -> Extra keys / Keyboard gestures" and "Display -> Keyboard backlight".
 */
public class KeyboardSetupActivity extends CollapsingToolbarBaseActivity {
    static final String SHOWN = "titan2_kbd_setup_shown";

    /** From Titan2App: the wizard is finished and the screen has not been shown yet, open it. */
    static void maybeShow(Context context) {
        ContentResolver cr = context.getContentResolver();
        if (Settings.Secure.getIntForUser(cr, Settings.Secure.USER_SETUP_COMPLETE, 0,
                UserHandle.USER_SYSTEM) != 1
                || "1".equals(Settings.Secure.getStringForUser(cr, SHOWN, UserHandle.USER_SYSTEM))) {
            return;
        }
        Settings.Secure.putStringForUser(cr, SHOWN, "1", UserHandle.USER_SYSTEM);
        context.startActivityAsUser(new Intent(context, KeyboardSetupActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), UserHandle.SYSTEM);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                            new SetupFragment())
                    .commit();
        }
    }

    /** The last chosen "Preset", so the mark in the list stays. */
    static final String SETTING_PRESET = "titan2_kbd_preset";
    private static final String PRESET_STOCK = "stock", PRESET_TYPING = "typing",
            PRESET_MINIMAL = "minimal";

    /** "Preset": here (first run) and in Titan 2 -> Keyboard. */
    static ListPreference presetPreference(Context ctx, Runnable onApplied) {
        ContentResolver cr = ctx.getContentResolver();
        ListPreference preset = new ListPreference(ctx);
        preset.setPersistent(false);
        // the key is required: the AndroidX dialog looks the entry up by it
        preset.setKey(SETTING_PRESET);
        preset.setTitle(R.string.kbd_setup_preset);
        preset.setDialogTitle(R.string.kbd_setup_preset);
        preset.setEntries(R.array.kbd_setup_preset_entries);
        preset.setEntryValues(new CharSequence[] {PRESET_TYPING, PRESET_STOCK, PRESET_MINIMAL});
        String chosen = Settings.System.getStringForUser(cr, SETTING_PRESET,
                UserHandle.USER_SYSTEM);
        if (chosen != null) {
            preset.setValue(chosen);
            preset.setSummary("%s");
        } else {
            preset.setSummary(R.string.kbd_setup_preset_summary);
        }
        preset.setOnPreferenceChangeListener((p, v) -> {
            applyPreset(ctx, (String) v);
            putSys(cr, SETTING_PRESET, (String) v);
            onApplied.run();
            return true;
        });
        return preset;
    }

    public static class SetupFragment extends SettingsBasePreferenceFragment {

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            build();
        }

        private void build() {
            final Context ctx = getContext();
            final ContentResolver cr = ctx.getContentResolver();
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(ctx);
            setPreferenceScreen(screen);

            Preference intro = new Preference(ctx);
            intro.setSummary(R.string.kbd_setup_intro);
            intro.setSelectable(false);
            screen.addPreference(intro);

            // show the applied values (after the dialog closes, without jumping to the top)
            screen.addPreference(presetPreference(ctx,
                    () -> KeyPrefs.rebuildKeepingScroll(this, this::build)));

            // input keyboard
            PreferenceCategory ime = category(screen, R.string.kbd_setup_ime_cat);
            Preference picker = new Preference(ctx);
            picker.setTitle(R.string.kbd_setup_ime);
            picker.setSummary(R.string.kbd_setup_ime_summary);
            picker.setOnPreferenceClickListener(p -> {
                ctx.getSystemService(InputMethodManager.class).showInputMethodPicker();
                return true;
            });
            ime.addPreference(picker);
            Preference layouts = new Preference(ctx);
            layouts.setTitle(R.string.kbd_setup_layouts);
            layouts.setSummary(R.string.kbd_setup_layouts_summary);
            layouts.setIntent(new Intent(Settings.ACTION_HARD_KEYBOARD_SETTINGS));
            ime.addPreference(layouts);

            // keys
            PreferenceCategory keys = category(screen, R.string.kbd_setup_keys_cat);
            keys.addPreference(roleList(ctx, KeyConfig.SLOT_FN, R.string.kbd_setup_fn,
                    KeyConfig.functionsFor(KeyConfig.SLOT_FN)));
            keys.addPreference(roleList(ctx, KeyConfig.SLOT_SYM, R.string.kbd_setup_sym,
                    KeyConfig.functionsFor(KeyConfig.SLOT_SYM)));
            Preference more = new Preference(ctx);
            more.setTitle(R.string.kbd_setup_more_keys);
            more.setIntent(new Intent(ctx, KeySettingsActivity.class));
            keys.addPreference(more);

            // touchpad
            PreferenceCategory pad = category(screen, R.string.kbd_setup_touchpad_cat);
            pad.addPreference(propSwitch(ctx, TouchpadController.PROP_SCROLL,
                    R.string.scroll_assistant_title, R.string.kbd_setup_scroll_summary));
            pad.addPreference(propSwitch(ctx, TouchpadController.PROP_CURSOR,
                    R.string.cursor_assistant_title, R.string.kbd_setup_cursor_summary));
            ListPreference all = new ListPreference(ctx);
            all.setPersistent(false);
            all.setKey("touchpad_mode_all");
            all.setTitle(R.string.kbd_setup_touchpad_mode);
            all.setDialogTitle(R.string.kbd_setup_touchpad_mode);
            all.setEntries(R.array.touchpad_event_type);
            all.setEntryValues(new CharSequence[] {"-1", "0", "1", "2", "3", "4"});
            String common = TouchpadApps.commonMode(ctx);
            if (common != null) {
                all.setValue(common);
                all.setSummary("%s");
            } else {
                all.setSummary(R.string.kbd_setup_touchpad_mode_summary);
            }
            all.setOnPreferenceChangeListener((p, v) -> {
                TouchpadApps.setAll(ctx, Integer.parseInt((String) v));
                p.setSummary("%s");
                return true;
            });
            pad.addPreference(all);

            // backlight
            PreferenceCategory led = category(screen, R.string.kbd_led_title);
            ListPreference timeout = new ListPreference(ctx);
            timeout.setPersistent(false);
            timeout.setKey(KeyboardLight.SETTING_TIMEOUT);
            timeout.setTitle(R.string.kbd_led_timeout);
            timeout.setDialogTitle(R.string.kbd_led_timeout);
            timeout.setEntries(R.array.kbd_led_timeout_entries);
            timeout.setEntryValues(KeyboardLedSettingsActivity.LedFragment.TIMEOUTS);
            timeout.setValue(sys(cr, KeyboardLight.SETTING_TIMEOUT, KeyboardLight.DEFAULT_TIMEOUT));
            timeout.setSummary("%s");
            timeout.setOnPreferenceChangeListener((p, v) -> {
                putSys(cr, KeyboardLight.SETTING_TIMEOUT, (String) v);
                putSys(cr, KeyboardLight.SETTING_TIMEOUT_BACKUP, (String) v);
                return true;
            });
            led.addPreference(timeout);
            SwitchPreferenceCompat auto = new SwitchPreferenceCompat(ctx);
            auto.setPersistent(false);
            auto.setTitle(R.string.kbd_led_auto);
            auto.setChecked("1".equals(sys(cr, KeyboardLight.SETTING_AUTO, "0")));
            auto.setOnPreferenceChangeListener((p, v) -> {
                putSys(cr, KeyboardLight.SETTING_AUTO, (Boolean) v ? "1" : "0");
                return true;
            });
            led.addPreference(auto);

            Preference done = new Preference(ctx);
            done.setTitle(R.string.kbd_setup_done);
            done.setSummary(R.string.kbd_setup_done_summary);
            done.setOnPreferenceClickListener(p -> {
                getActivity().finish();
                return true;
            });
            screen.addPreference(done);
        }

        private PreferenceCategory category(PreferenceScreen screen, int title) {
            PreferenceCategory c = new PreferenceCategory(getContext());
            c.setTitle(title);
            screen.addPreference(c);
            return c;
        }

        private ListPreference roleList(Context ctx, int slot, int title, int[] functions) {
            ListPreference p = new ListPreference(ctx);
            p.setPersistent(false);
            p.setKey("role_" + slot);
            p.setTitle(title);
            p.setDialogTitle(title);
            CharSequence[] entries = new CharSequence[functions.length];
            CharSequence[] values = new CharSequence[functions.length];
            for (int i = 0; i < functions.length; i++) {
                entries[i] = KeyPrefs.functionTitle(ctx, functions[i]);
                values[i] = String.valueOf(functions[i]);
            }
            p.setEntries(entries);
            p.setEntryValues(values);
            ContentResolver cr = ctx.getContentResolver();
            if (KeyConfig.MODE_PROGRAMMABLE.equals(KeyConfig.getMode(cr, slot, null))) {
                p.setValue(String.valueOf(KeyConfig.getFunction(cr, slot, null)));
                p.setSummary("%s");
            } else {
                // key in shortcut/PTT mode: show that instead of an empty summary
                p.setSummary(KeyPrefs.literal(
                        KeySettingsActivity.EntryFragment.modeSummary(ctx, cr, slot, null)));
            }
            p.setOnPreferenceChangeListener((pref, v) -> {
                setRole(ctx, slot, Integer.parseInt((String) v));
                pref.setSummary("%s");
                return true;
            });
            return p;
        }

        private SwitchPreferenceCompat propSwitch(Context ctx, String prop, int title, int summary) {
            SwitchPreferenceCompat s = new SwitchPreferenceCompat(ctx);
            s.setPersistent(false);
            s.setTitle(title);
            s.setSummary(summary);
            s.setChecked(SystemProperties.get(prop, "1").equals("1"));
            s.setOnPreferenceChangeListener((p, v) -> {
                SystemProperties.set(prop, (Boolean) v ? "1" : "0");
                return true;
            });
            return s;
        }
    }

    static void setRole(Context ctx, int slot, int function) {
        KeyConfig.setMode(ctx, slot, KeyConfig.MODE_PROGRAMMABLE, null);
        KeyConfig.setFunction(ctx, slot, function, null);
    }

    static void applyPreset(Context ctx, String preset) {
        ContentResolver cr = ctx.getContentResolver();
        switch (preset) {
            case "stock":         // firmware defaults: Sym = Sym, Fn = Home
                setRole(ctx, KeyConfig.SLOT_SYM, KeyConfig.FUNC_SYM);
                setRole(ctx, KeyConfig.SLOT_FN, KeyConfig.FUNC_HOME);
                SystemProperties.set(TouchpadController.PROP_SCROLL, "1");
                SystemProperties.set(TouchpadController.PROP_CURSOR, "0");
                putSys(cr, KeyboardLight.SETTING_TIMEOUT, "5000");
                break;
            case "minimal":
                setRole(ctx, KeyConfig.SLOT_SYM, KeyConfig.FUNC_SYM);
                setRole(ctx, KeyConfig.SLOT_FN, KeyConfig.FUNC_CTRL);
                SystemProperties.set(TouchpadController.PROP_SCROLL, "0");
                SystemProperties.set(TouchpadController.PROP_CURSOR, "0");
                putSys(cr, KeyboardLight.SETTING_TIMEOUT, "5000");
                break;
            default:              // for typing (recommended)
                setRole(ctx, KeyConfig.SLOT_SYM, KeyConfig.FUNC_SYM);
                setRole(ctx, KeyConfig.SLOT_FN, KeyConfig.FUNC_CTRL);
                SystemProperties.set(TouchpadController.PROP_SCROLL, "1");
                SystemProperties.set(TouchpadController.PROP_CURSOR, "1");
                putSys(cr, KeyboardLight.SETTING_TIMEOUT, "10000");
                break;
        }
        putSys(cr, KeyboardLight.SETTING_TIMEOUT_BACKUP,
                sys(cr, KeyboardLight.SETTING_TIMEOUT, KeyboardLight.DEFAULT_TIMEOUT));
    }

    static String sys(ContentResolver cr, String key, String def) {
        String v = Settings.System.getStringForUser(cr, key, UserHandle.USER_SYSTEM);
        return v != null ? v : def;
    }

    static void putSys(ContentResolver cr, String key, String value) {
        Settings.System.putStringForUser(cr, key, value, UserHandle.USER_SYSTEM);
    }
}
