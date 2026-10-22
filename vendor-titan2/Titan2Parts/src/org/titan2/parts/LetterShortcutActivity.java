package org.titan2.parts;

import android.app.Activity;
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.os.Bundle;
import android.os.Process;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;
import android.util.Log;
import android.widget.EditText;

import java.util.ArrayList;
import java.util.List;

/**
 * "Letter keys": a switch, "short" / "long" tabs,
 * each letter's target is an app ("All apps") or a shortcut ("All shortcuts": LauncherApps
 * app shortcuts and old ACTION_CREATE_SHORTCUT ones such as a direct call).
 *
 * With the chords extra: our Sym+letter (or Fn+Sym+letter) chords, same targets plus a text snippet.
 */
public class LetterShortcutActivity extends CollapsingToolbarBaseActivity {
    static final String EXTRA_CHORDS = "chords";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean chords = getIntent().getBooleanExtra(EXTRA_CHORDS, false);
        setTitle(chords ? R.string.chords_title : R.string.letters_title);
        if (savedInstanceState == null) {
            LettersFragment f = new LettersFragment();
            Bundle args = new Bundle();
            args.putBoolean(EXTRA_CHORDS, chords);
            f.setArguments(args);
            getSupportFragmentManager().beginTransaction()
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame, f)
                    .commit();
        }
    }

    public static class LettersFragment extends SettingsBasePreferenceFragment {
        private static final String TAG = "Titan2Letters";
        private static final int REQ_CREATE_SHORTCUT = 1;
        private static final String STATE_LETTER = "pending_letter";
        private static final String STATE_PRESS = "pending_press";
        private boolean mChords;
        private char mPendingLetter;
        private String mPendingPress;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            mChords = getArguments().getBoolean(EXTRA_CHORDS);
            if (savedInstanceState != null) {
                // the shortcut creator may outlive this fragment (rotation, process death)
                mPendingLetter = savedInstanceState.getChar(STATE_LETTER);
                mPendingPress = savedInstanceState.getString(STATE_PRESS);
            }
            build();
        }

        @Override
        public void onSaveInstanceState(Bundle out) {
            super.onSaveInstanceState(out);
            out.putChar(STATE_LETTER, mPendingLetter);
            out.putString(STATE_PRESS, mPendingPress);
        }

        private void build() {
            final Context ctx = getContext();
            final ContentResolver cr = ctx.getContentResolver();
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(ctx);
            final String enableKey = mChords ? KeyConfig.CHORD_ENABLE : KeyConfig.LETTER_ENABLE;
            SwitchPreferenceCompat enable = new SwitchPreferenceCompat(ctx);
            enable.setPersistent(false);
            enable.setTitle(mChords ? R.string.chords_title : R.string.letters_title);
            enable.setSummary(mChords ? R.string.chords_info : R.string.letters_info);
            final boolean on = KeyConfig.getInt(cr, enableKey, 0) == 1;
            enable.setChecked(on);
            enable.setOnPreferenceChangeListener((p, v) -> {
                KeyConfig.putInt(cr, enableKey, (Boolean) v ? 1 : 0);
                KeyPrefs.rebuildKeepingScroll(this, this::build);
                return true;
            });
            screen.addPreference(enable);

            if (on) {
                if (mChords) {
                    addChordOptions(screen, ctx, cr);
                    addLetters(screen, ctx, cr, null, getString(R.string.chords_letters));
                } else {
                    addLetters(screen, ctx, cr, "short", getString(R.string.press_short));
                    addLetters(screen, ctx, cr, "long", getString(R.string.press_long));
                }
            }
            if (!mChords) {
                screen.addPreference(KeyPrefs.footer(ctx, getString(R.string.letters_magic_info)));
            }
            setPreferenceScreen(screen);
        }

        private void addChordOptions(PreferenceScreen screen, Context ctx, ContentResolver cr) {
            ListPreference mod = new ListPreference(ctx);
            mod.setPersistent(false);
            // the key is required: the AndroidX dialog looks the entry up by it
            mod.setKey("chord_modifier");
            mod.setTitle(R.string.chord_modifier_title);
            mod.setDialogTitle(R.string.chord_modifier_title);
            mod.setEntries(new CharSequence[] {getString(R.string.chord_mod_sym),
                    getString(R.string.chord_mod_fnsym)});
            mod.setEntryValues(new CharSequence[] {"0", "1"});
            mod.setValue(String.valueOf(KeyConfig.getInt(cr, KeyConfig.CHORD_MODIFIER, 0)));
            mod.setSummary("%s");
            mod.setOnPreferenceChangeListener((p, v) -> {
                KeyConfig.putInt(cr, KeyConfig.CHORD_MODIFIER, Integer.parseInt((String) v));
                return true;
            });
            screen.addPreference(mod);
            screen.addPreference(SpaceBarActivity.SpaceFragment.toggle(ctx,
                    KeyConfig.CHORD_IN_FIELDS, R.string.chord_in_fields_title,
                    R.string.chord_in_fields_summary));
        }

        /** press == null means chords. */
        private void addLetters(PreferenceScreen screen, Context ctx, ContentResolver cr,
                final String press, String title) {
            PreferenceCategory cat = new PreferenceCategory(ctx);
            cat.setTitle(title);
            screen.addPreference(cat);
            for (char c = 'A'; c <= 'Z'; c++) {
                final char letter = c;
                Preference p = new Preference(ctx);
                p.setTitle(String.valueOf(letter));
                String value = press == null ? KeyConfig.getChord(cr, letter)
                        : KeyConfig.getLetter(cr, letter, press);
                String label = press == null ? KeyConfig.getChordTitle(cr, letter)
                        : KeyConfig.getLetterTitle(cr, letter, press);
                p.setSummary(value == null ? getString(R.string.letter_not_set)
                        : (label != null ? label : value));
                p.setOnPreferenceClickListener(pref -> {
                    choose(letter, press);
                    return true;
                });
                cat.addPreference(p);
            }
        }

        private void save(char letter, String press, String value, String title) {
            ContentResolver cr = getContext().getContentResolver();
            if (press == null) {
                KeyConfig.setChord(cr, letter, value, title);
            } else {
                KeyConfig.setLetter(cr, letter, press, value, title);
            }
            build();
        }

        /** Target chooser for one letter and press. */
        private void choose(final char letter, final String press) {
            final Context ctx = getContext();
            List<CharSequence> items = new ArrayList<>();
            items.add(getString(R.string.letter_delete));
            items.add(getString(R.string.letter_all_apps));
            items.add(getString(R.string.letter_all_shortcuts));
            if (press == null) items.add(getString(R.string.chord_text));
            new AlertDialog.Builder(ctx)
                    .setTitle(getString(R.string.letter_set_title, String.valueOf(letter)))
                    .setItems(items.toArray(new CharSequence[0]), (d, which) -> {
                        switch (which) {
                            case 0: save(letter, press, KeyConfig.NONE, null); break;
                            case 1: chooseApp(letter, press); break;
                            case 2: chooseShortcut(letter, press); break;
                            default: chooseText(letter); break;
                        }
                    })
                    .show();
        }

        private void chooseApp(final char letter, final String press) {
            final List<String[]> apps = KeyPrefs.launcherApps(getContext());
            CharSequence[] labels = new CharSequence[apps.size()];
            for (int i = 0; i < apps.size(); i++) labels[i] = apps.get(i)[0];
            new AlertDialog.Builder(getContext())
                    .setTitle(R.string.letter_all_apps)
                    .setItems(labels, (d, which) -> save(letter, press,
                            KeyConfig.APP_PREFIX + apps.get(which)[1], apps.get(which)[0]))
                    .show();
        }

        /** LauncherApps shortcuts (static and dynamic) + ACTION_CREATE_SHORTCUT activities. */
        private void chooseShortcut(final char letter, final String press) {
            final Context ctx = getContext();
            final PackageManager pm = ctx.getPackageManager();
            final List<CharSequence> labels = new ArrayList<>();
            final List<Object> targets = new ArrayList<>();
            try {
                LauncherApps la = ctx.getSystemService(LauncherApps.class);
                LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery().setQueryFlags(
                        LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                                | LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                                | LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED);
                List<ShortcutInfo> list = la.getShortcuts(q, Process.myUserHandle());
                if (list != null) {
                    for (ShortcutInfo si : list) {
                        CharSequence l = si.getShortLabel();
                        labels.add(KeyPrefs.appLabel(ctx, si.getPackage()) + ": "
                                + (l != null ? l : si.getId()));
                        targets.add(si);
                    }
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "shortcuts unavailable", e);
            }
            for (ResolveInfo ri : pm.queryIntentActivities(
                    new Intent(Intent.ACTION_CREATE_SHORTCUT), 0)) {
                labels.add(ri.loadLabel(pm));
                targets.add(ri);
            }
            new AlertDialog.Builder(ctx)
                    .setTitle(R.string.letter_all_shortcuts)
                    .setItems(labels.toArray(new CharSequence[0]), (d, which) -> {
                        Object t = targets.get(which);
                        if (t instanceof ShortcutInfo) {
                            ShortcutInfo si = (ShortcutInfo) t;
                            save(letter, press, KeyConfig.SHORTCUT_PREFIX + si.getPackage() + "/"
                                    + si.getId(), labels.get(which).toString());
                        } else {
                            ResolveInfo ri = (ResolveInfo) t;
                            mPendingLetter = letter;
                            mPendingPress = press;
                            Intent i = new Intent(Intent.ACTION_CREATE_SHORTCUT).setClassName(
                                    ri.activityInfo.packageName, ri.activityInfo.name);
                            try {
                                startActivityForResult(i, REQ_CREATE_SHORTCUT);
                            } catch (RuntimeException e) {
                                Log.w(TAG, "create shortcut failed", e);
                            }
                        }
                    })
                    .show();
        }

        @Override
        public void onActivityResult(int requestCode, int resultCode, Intent data) {
            if (requestCode != REQ_CREATE_SHORTCUT || resultCode != Activity.RESULT_OK
                    || data == null || mPendingLetter == 0) {
                return;
            }
            Intent target = data.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT, Intent.class);
            if (target == null) {
                Log.w(TAG, "no legacy shortcut intent in result");
                return;
            }
            String name = data.getStringExtra(Intent.EXTRA_SHORTCUT_NAME);
            save(mPendingLetter, mPendingPress,
                    KeyConfig.INTENT_PREFIX + target.toUri(Intent.URI_INTENT_SCHEME), name);
        }

        private void chooseText(final char letter) {
            final EditText edit = new EditText(getContext());
            String cur = KeyConfig.getChord(getContext().getContentResolver(), letter);
            if (cur != null && cur.startsWith(KeyConfig.TEXT_PREFIX)) {
                edit.setText(cur.substring(KeyConfig.TEXT_PREFIX.length()));
            }
            edit.setHint(R.string.chord_text_hint);
            new AlertDialog.Builder(getContext())
                    .setTitle(R.string.chord_text)
                    .setMessage(R.string.chord_text_help)
                    .setView(edit)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        String text = edit.getText().toString();
                        if (text.isEmpty()) {
                            save(letter, null, KeyConfig.NONE, null);
                        } else {
                            save(letter, null, KeyConfig.TEXT_PREFIX + text, "«" + text + "»");
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }
    }
}
