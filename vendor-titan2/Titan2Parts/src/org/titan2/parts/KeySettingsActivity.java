package org.titan2.parts;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;
import android.provider.Settings;
import android.view.inputmethod.InputMethodManager;

import java.util.List;

/**
 * The "Key setting" list: Func1, Func2,
 * Sym, Fn, Shift, Alt, Back, Recent, space and letter keys; a key opens
 * KeyRemapActivity. Modes (EXTRA_MODE): "Keyboard" and "Side keys" of the Titan 2 section,
 * an app profile (all keys) and the full list for the external KEY_SETTINGS action.
 */
public class KeySettingsActivity extends CollapsingToolbarBaseActivity {
    /** Extra: the key prefix (ours is the name from KeyConfig.KEYS). */
    static final String EXTRA_KEY_TYPE = "key_type";
    static final String EXTRA_MODE = "mode";
    static final String MODE_KEYBOARD = "keyboard";
    static final String MODE_SIDE = "side";

    static Intent keyboardIntent(Context ctx) {
        return new Intent(ctx, KeySettingsActivity.class).putExtra(EXTRA_MODE, MODE_KEYBOARD);
    }

    static Intent sideKeysIntent(Context ctx) {
        return new Intent(ctx, KeySettingsActivity.class).putExtra(EXTRA_MODE, MODE_SIDE);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // the title on every create: after a recreate the fragment is restored, the title is not
        String profile = getIntent().getStringExtra(KeyRemapActivity.EXTRA_PROFILE);
        String mode = getIntent().getStringExtra(EXTRA_MODE);
        Bundle args = new Bundle();
        if (profile != null) {
            args.putString(KeyRemapActivity.EXTRA_PROFILE, profile);
            setTitle(KeyPrefs.appLabel(this, profile));
        } else if (MODE_KEYBOARD.equals(mode)) {
            args.putBoolean(EntryFragment.ARG_KEYBOARD_ONLY, true);
            setTitle(R.string.keyboard_section_title);
        } else if (MODE_SIDE.equals(mode)) {
            args.putBoolean(EntryFragment.ARG_SIDE_ONLY, true);
            setTitle(R.string.side_keys_title);
        }
        if (savedInstanceState == null) {
            EntryFragment f = new EntryFragment();
            f.setArguments(args);
            getSupportFragmentManager().beginTransaction()
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame, f)
                    .commit();
        }
    }

    /** Key list: keyboardOnly for the "Keyboard" section, sideOnly for "Side keys". */
    public static class EntryFragment extends SettingsBasePreferenceFragment {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        }

        static final String ARG_KEYBOARD_ONLY = "keyboard_only";
        static final String ARG_SIDE_ONLY = "side_only";

        @Override
        public void onResume() {
            super.onResume();
            // modes change on the key screen: rebuild the summaries on return
            build();
        }

        private void build() {
            final Context ctx = getContext();
            final ContentResolver cr = ctx.getContentResolver();
            final boolean keyboardOnly = getArguments() != null
                    && getArguments().getBoolean(ARG_KEYBOARD_ONLY);
            final boolean sideOnly = getArguments() != null
                    && getArguments().getBoolean(ARG_SIDE_ONLY);
            final String profile = getArguments() == null ? null
                    : getArguments().getString(KeyRemapActivity.EXTRA_PROFILE);
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(ctx);
            if (keyboardOnly && profile == null) {
                addInput(screen);
            }
            PreferenceCategory cat = new PreferenceCategory(ctx);
            cat.setTitle(R.string.key_setting_title);
            screen.addPreference(cat);
            for (int slot = 0; slot < KeyConfig.KEYS.length; slot++) {
                if (keyboardOnly && !KeyConfig.isKeyboardKey(slot)) continue;
                if (sideOnly && !KeyConfig.isSideKey(slot)) continue;
                Preference p = new Preference(ctx);
                p.setPersistent(false);
                p.setTitle(KeyPrefs.keyTitle(ctx, slot));
                if (profile == null || KeyConfig.isOverridden(cr, slot, profile)) {
                    p.setSummary(modeSummary(ctx, cr, slot, profile));
                } else {
                    p.setSummary(R.string.profile_inherit);
                }
                Intent intent = new Intent(ctx, KeyRemapActivity.class);
                intent.putExtra(EXTRA_KEY_TYPE, KeyConfig.KEYS[slot]);
                intent.putExtra(KeyRemapActivity.EXTRA_PROFILE, profile);
                p.setIntent(intent);
                cat.addPreference(p);
            }
            if (profile != null) {
                Preference remove = new Preference(ctx);
                remove.setTitle(R.string.profile_remove);
                remove.setOnPreferenceClickListener(p -> {
                    for (int slot = 0; slot < KeyConfig.KEYS.length; slot++) {
                        if (KeyConfig.isOverridden(cr, slot, profile)) {
                            KeyConfig.setOverridden(ctx, slot, profile, false);
                        }
                    }
                    List<String> apps = KeyConfig.profileApps(cr);
                    apps.remove(profile);
                    KeyConfig.setProfileApps(cr, apps);
                    getActivity().finish();
                    return true;
                });
                screen.addPreference(remove);
            } else if (!sideOnly) {
                Preference space = new Preference(ctx);
                space.setTitle(R.string.space_key_title);
                space.setIntent(new Intent(ctx, SpaceBarActivity.class));
                cat.addPreference(space);
                Preference letters = new Preference(ctx);
                letters.setTitle(R.string.letter_key_title);
                letters.setSummary(KeyConfig.lettersEnabled(cr) ? null
                        : ctx.getString(R.string.state_off));
                letters.setIntent(new Intent(ctx, LetterShortcutActivity.class));
                cat.addPreference(letters);

                // Titan2Parts extensions
                PreferenceCategory extra = new PreferenceCategory(ctx);
                extra.setTitle(R.string.keys_extra_category);
                screen.addPreference(extra);
                Preference chords = new Preference(ctx);
                chords.setTitle(R.string.chords_title);
                chords.setSummary(R.string.chords_entry_summary);
                chords.setIntent(new Intent(ctx, LetterShortcutActivity.class)
                        .putExtra(LetterShortcutActivity.EXTRA_CHORDS, true));
                extra.addPreference(chords);
                Preference profiles = new Preference(ctx);
                profiles.setTitle(R.string.profiles_title);
                profiles.setSummary(R.string.profiles_entry_summary);
                profiles.setIntent(new Intent(ctx, AppProfilesActivity.class));
                extra.addPreference(profiles);
                if (AltWake.isAvailable()) {
                    SwitchPreferenceCompat alt = new SwitchPreferenceCompat(ctx);
                    alt.setPersistent(false);
                    alt.setTitle(R.string.alt_wake_title);
                    alt.setSummary(R.string.alt_wake_summary);
                    alt.setChecked(AltWake.isEnabled());
                    // not written to the driver: the toggle stays as it was
                    alt.setOnPreferenceChangeListener((preference, newValue) ->
                            AltWake.setEnabled(ctx, (Boolean) newValue));
                    extra.addPreference(alt);
                }
                if (keyboardOnly) {
                    // the first-run wizard repeats settings of this and other screens,
                    // only its own entry is here
                    extra.addPreference(KeyboardSetupActivity.presetPreference(ctx,
                            () -> KeyPrefs.rebuildKeepingScroll(this, this::build)));
                }
            }
            setPreferenceScreen(screen);
        }

        /** Input method and layouts of the physical keyboard. */
        private void addInput(PreferenceScreen screen) {
            final Context ctx = getContext();
            PreferenceCategory ime = new PreferenceCategory(ctx);
            ime.setTitle(R.string.kbd_setup_ime_cat);
            screen.addPreference(ime);
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
        }

        static CharSequence modeSummary(Context ctx, ContentResolver cr, int slot, String pkg) {
            String mode = KeyConfig.getMode(cr, slot, pkg);
            if (KeyConfig.MODE_PROGRAMMABLE.equals(mode)) {
                return ctx.getString(R.string.category_fmt, ctx.getString(R.string.mode_programmable),
                        KeyPrefs.functionTitle(ctx, KeyConfig.getFunction(cr, slot, pkg)));
            }
            if (KeyConfig.MODE_PTT.equals(mode)) {
                return ctx.getString(R.string.mode_ptt);
            }
            return ctx.getString(R.string.mode_shortcut);
        }
    }
}
