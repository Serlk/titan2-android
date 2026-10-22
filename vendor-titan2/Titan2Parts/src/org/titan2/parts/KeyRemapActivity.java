package org.titan2.parts;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;
import android.content.ContentResolver;
import android.content.Context;
import android.os.Bundle;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * One key per page: mode (PTT / Programmable / Shortcut), then the key function or
 * short/long/double actions, with a hint at the bottom.
 *
 * Without the key_type extra (the "Titan 2 keyboard -> Key remapping" entry in Settings): the list
 * of keyboard keys. With the profile_pkg extra: the key profile for that app.
 */
public class KeyRemapActivity extends CollapsingToolbarBaseActivity {
    static final String EXTRA_PROFILE = "profile_pkg";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String key = getIntent().getStringExtra(KeySettingsActivity.EXTRA_KEY_TYPE);
        if (savedInstanceState != null) {
            // the fragment is restored by the FragmentManager, the title is not
            if (key != null && KeyConfig.slotOf(key) >= 0) {
                setTitle(KeyPrefs.keyTitle(this, KeyConfig.slotOf(key)));
            }
            return;
        }
        SettingsBasePreferenceFragment f;
        Bundle args = new Bundle();
        args.putString(EXTRA_PROFILE, getIntent().getStringExtra(EXTRA_PROFILE));
        if (key == null || KeyConfig.slotOf(key) < 0) {
            f = new KeySettingsActivity.EntryFragment();
            args.putBoolean(KeySettingsActivity.EntryFragment.ARG_KEYBOARD_ONLY, true);
        } else {
            f = new KeyFragment();
            args.putString(KeySettingsActivity.EXTRA_KEY_TYPE, key);
            setTitle(KeyPrefs.keyTitle(this, KeyConfig.slotOf(key)));
        }
        f.setArguments(args);
        getSupportFragmentManager().beginTransaction()
                .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame, f)
                .commit();
    }

    public static class KeyFragment extends SettingsBasePreferenceFragment {
        private int mSlot;
        private String mPkg;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            mSlot = KeyConfig.slotOf(getArguments().getString(KeySettingsActivity.EXTRA_KEY_TYPE));
            mPkg = getArguments().getString(EXTRA_PROFILE);
            build();
        }

        private void build() {
            final Context ctx = getContext();
            final ContentResolver cr = ctx.getContentResolver();
            final String keyTitle = KeyPrefs.keyTitle(ctx, mSlot);
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(ctx);

            if (mPkg != null) {
                SwitchPreferenceCompat override = new SwitchPreferenceCompat(ctx);
                override.setPersistent(false);
                override.setTitle(ctx.getString(R.string.profile_override_title,
                        KeyPrefs.appLabel(ctx, mPkg)));
                override.setSummary(R.string.profile_override_summary);
                override.setChecked(KeyConfig.isOverridden(cr, mSlot, mPkg));
                override.setOnPreferenceChangeListener((p, v) -> {
                    KeyConfig.setOverridden(ctx, mSlot, mPkg, (Boolean) v);
                    KeyPrefs.rebuildKeepingScroll(this, this::build);
                    return true;
                });
                screen.addPreference(override);
                if (!override.isChecked()) {
                    setPreferenceScreen(screen);
                    return;
                }
            }

            // Mode
            final String mode = KeyConfig.getMode(cr, mSlot, mPkg);
            PreferenceCategory modes = new PreferenceCategory(ctx);
            modes.setTitle(keyTitle);
            screen.addPreference(modes);
            for (final String m : KeyConfig.modesFor(mSlot)) {
                KeyPrefs.RadioPreference r = new KeyPrefs.RadioPreference(ctx);
                r.setTitle(modeTitle(m));
                r.setChecked(m.equals(mode));
                if (KeyConfig.MODE_PROGRAMMABLE.equals(m)) {
                    r.setSummary(KeyPrefs.functionTitle(ctx, KeyConfig.getFunction(cr, mSlot, mPkg)));
                } else if (KeyConfig.MODE_PTT.equals(m)) {
                    String app = KeyConfig.getPttApp(cr, mSlot, mPkg);
                    r.setSummary(ctx.getString(R.string.ptt_app_name, app == null || app.isEmpty()
                            ? ctx.getString(R.string.ptt_none) : KeyPrefs.appLabel(ctx, app)));
                }
                r.setOnPreferenceChangeListener((p, v) -> {
                    KeyConfig.setMode(ctx, mSlot, m, mPkg);
                    KeyPrefs.rebuildKeepingScroll(this, this::build);
                    return true;
                });
                modes.addPreference(r);
            }

            CharSequence footer = null;
            if (KeyConfig.MODE_PTT.equals(mode)) {
                addPttPicker(screen, ctx, cr);
            } else if (KeyConfig.MODE_PROGRAMMABLE.equals(mode)) {
                final int function = KeyConfig.getFunction(cr, mSlot, mPkg);
                PreferenceCategory funcs = new PreferenceCategory(ctx);
                funcs.setTitle(ctx.getString(R.string.category_fmt, keyTitle,
                        ctx.getString(R.string.mode_programmable)));
                screen.addPreference(funcs);
                for (final int f : KeyConfig.functionsFor(mSlot)) {
                    KeyPrefs.RadioPreference r = new KeyPrefs.RadioPreference(ctx);
                    r.setTitle(KeyPrefs.functionTitle(ctx, f));
                    if (f == KeyConfig.FUNC_MAGIC) r.setSummary(R.string.footer_magic);
                    r.setChecked(f == function);
                    r.setOnPreferenceChangeListener((p, v) -> {
                        KeyConfig.setFunction(ctx, mSlot, f, mPkg);
                        KeyPrefs.rebuildKeepingScroll(this, this::build);
                        return true;
                    });
                    funcs.addPreference(r);
                }
                footer = programmableFooter(ctx, function, keyTitle);
            } else {
                addShortcutPresses(screen, ctx, cr);
                footer = ctx.getString(R.string.footer_shortcut);
            }
            if (footer != null) {
                screen.addPreference(KeyPrefs.footer(ctx, footer));
            }
            if (mSlot == KeyConfig.SLOT_SYM || mSlot == KeyConfig.SLOT_FN) {
                screen.addPreference(KeyPrefs.footer(ctx, ctx.getString(R.string.footer_plektra)));
            }
            setPreferenceScreen(screen);
        }

        private String modeTitle(String mode) {
            if (KeyConfig.MODE_PTT.equals(mode)) return getString(R.string.mode_ptt);
            if (KeyConfig.MODE_PROGRAMMABLE.equals(mode)) return getString(R.string.mode_programmable);
            return getString(R.string.mode_shortcut);
        }

        /** The hint for the Programmable mode. */
        private CharSequence programmableFooter(Context ctx, int function, String keyTitle) {
            switch (function) {
                case KeyConfig.FUNC_NONE:
                    return KeyConfig.isSideKey(mSlot)
                            ? ctx.getString(R.string.footer_none, keyTitle) : null;
                case KeyConfig.FUNC_SYM: return ctx.getString(R.string.footer_sym);
                case KeyConfig.FUNC_MEDIA: return ctx.getString(R.string.footer_media);
                case KeyConfig.FUNC_MAGIC: return ctx.getString(R.string.footer_magic);
                case KeyConfig.FUNC_SHIFT: return ctx.getString(R.string.footer_shift);
                case KeyConfig.FUNC_ALT: return ctx.getString(R.string.footer_alt);
                case KeyConfig.FUNC_POWER: return ctx.getString(R.string.footer_power);
                default: return null; // Ctrl, Tab, Home, Back, Recent: no hint
            }
        }

        private void addPttPicker(PreferenceScreen screen, Context ctx, ContentResolver cr) {
            List<String[]> apps = KeyPrefs.launcherApps(ctx);
            CharSequence[] entries = new CharSequence[apps.size()];
            CharSequence[] values = new CharSequence[apps.size()];
            for (int i = 0; i < apps.size(); i++) {
                entries[i] = apps.get(i)[0];
                values[i] = apps.get(i)[1];
            }
            ListPreference pref = new ListPreference(ctx);
            pref.setPersistent(false);
            // the key is required: the AndroidX dialog looks the entry up by it
            pref.setKey("ptt_app");
            pref.setTitle(R.string.ptt_app_picker_title);
            pref.setDialogTitle(R.string.ptt_app_picker_title);
            pref.setEntries(entries);
            pref.setEntryValues(values);
            String cur = KeyConfig.getPttApp(cr, mSlot, mPkg);
            if (cur != null) pref.setValue(cur);
            pref.setSummary(KeyPrefs.literal(cur == null ? getString(R.string.ptt_none)
                    : KeyPrefs.appLabel(ctx, cur)));
            pref.setOnPreferenceChangeListener((p, v) -> {
                KeyConfig.setPttApp(cr, mSlot, (String) v, mPkg);
                KeyPrefs.rebuildKeepingScroll(this, this::build);
                return true;
            });
            screen.addPreference(pref);
        }

        /** Shortcut mode: short/long/double, "functions" and "all apps" tabs. */
        private void addShortcutPresses(PreferenceScreen screen, Context ctx, ContentResolver cr) {
            final int[] pressTitles =
                    {R.string.press_short, R.string.press_long, R.string.press_double};
            PreferenceCategory cat = new PreferenceCategory(ctx);
            cat.setTitle(ctx.getString(R.string.category_fmt, KeyPrefs.keyTitle(ctx, mSlot),
                    ctx.getString(R.string.mode_shortcut)));
            screen.addPreference(cat);
            List<String[]> apps = KeyPrefs.launcherApps(ctx);
            final String key = KeyConfig.KEYS[mSlot];
            for (int i = 0; i < KeyConfig.PRESSES.length; i++) {
                final String press = KeyConfig.PRESSES[i];
                List<CharSequence> entries = new ArrayList<>();
                List<CharSequence> values = new ArrayList<>();
                for (String a : KeyConfig.builtinActions(mSlot, press)) {
                    entries.add(KeyPrefs.actionTitle(ctx, a));
                    values.add(a);
                }
                for (String[] app : apps) {
                    entries.add(ctx.getString(R.string.action_app, app[0]));
                    values.add(KeyConfig.APP_PREFIX + app[1]);
                }
                ListPreference pref = new ListPreference(ctx);
                pref.setPersistent(false);
                pref.setKey("press_" + i);
                pref.setTitle(pressTitles[i]);
                pref.setDialogTitle(pressTitles[i]);
                pref.setEntries(entries.toArray(new CharSequence[0]));
                pref.setEntryValues(values.toArray(new CharSequence[0]));
                String cur = KeyConfig.getAction(cr, key, press, mPkg);
                pref.setValue(cur);
                pref.setSummary(KeyPrefs.literal(KeyPrefs.actionTitle(ctx, cur)));
                pref.setOnPreferenceChangeListener((p, v) -> {
                    KeyConfig.setAction(cr, key, press, (String) v, mPkg);
                    p.setSummary(KeyPrefs.literal(KeyPrefs.actionTitle(ctx, (String) v)));
                    return true;
                });
                cat.addPreference(pref);
            }
        }
    }
}
