package org.titan2.parts;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;
import android.content.ContentResolver;
import android.content.Context;
import android.os.Bundle;
import androidx.preference.PreferenceCategory;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

/**
 * "Space": "Double press" (answer, hang up, shutter),
 * "Press" (go home), a hint. Below is our addition: hold -> layout.
 * Executed by KeyHandler.
 */
public class SpaceBarActivity extends CollapsingToolbarBaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.space_key_title);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                            new SpaceFragment())
                    .commit();
        }
    }

    public static class SpaceFragment extends SettingsBasePreferenceFragment {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            final Context ctx = getContext();
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(ctx);
            PreferenceCategory dbl = new PreferenceCategory(ctx);
            dbl.setTitle(R.string.space_double_category);
            screen.addPreference(dbl);
            dbl.addPreference(toggle(ctx, KeyConfig.SPACE_ANSWER, R.string.space_answer_title,
                    R.string.space_answer_summary));
            dbl.addPreference(toggle(ctx, KeyConfig.SPACE_REJECT, R.string.space_reject_title,
                    R.string.space_reject_summary));
            dbl.addPreference(toggle(ctx, KeyConfig.SPACE_CAPTURE, R.string.space_capture_title,
                    R.string.space_capture_summary));
            PreferenceCategory click = new PreferenceCategory(ctx);
            click.setTitle(R.string.space_click_category);
            screen.addPreference(click);
            click.addPreference(toggle(ctx, KeyConfig.SPACE_GOHOME, R.string.space_home_title,
                    R.string.space_home_summary));
            screen.addPreference(KeyPrefs.footer(ctx, ctx.getString(R.string.space_note)));

            PreferenceCategory extra = new PreferenceCategory(ctx);
            extra.setTitle(R.string.keys_extra_category);
            screen.addPreference(extra);
            extra.addPreference(toggle(ctx, KeyConfig.SPACE_LONG_LAYOUT,
                    R.string.space_long_layout_title, R.string.space_long_layout_summary));
            setPreferenceScreen(screen);
        }

        @Override
        public void onResume() {
            super.onResume();
            refreshToggles(getPreferenceScreen());
        }

        /** Settings.System -> the toggle() switches (the key holds the setting name). */
        static void refreshToggles(androidx.preference.PreferenceGroup g) {
            if (g == null) return;
            final ContentResolver cr = g.getContext().getContentResolver();
            for (int i = 0; i < g.getPreferenceCount(); i++) {
                androidx.preference.Preference p = g.getPreference(i);
                if (p instanceof androidx.preference.PreferenceGroup) {
                    refreshToggles((androidx.preference.PreferenceGroup) p);
                } else if (p instanceof SwitchPreferenceCompat && p.getKey() != null
                        && p.getKey().startsWith(TOGGLE_TAG)) {
                    ((SwitchPreferenceCompat) p).setChecked(KeyConfig.getInt(cr,
                            p.getKey().substring(TOGGLE_TAG.length()), 0) == 1);
                }
            }
        }

        /** Key prefix of the toggle() switches: marks them for refreshToggles(). */
        private static final String TOGGLE_TAG = "toggle:";

        static SwitchPreferenceCompat toggle(Context ctx, String setting, int title, int summary) {
            final ContentResolver cr = ctx.getContentResolver();
            SwitchPreferenceCompat p = new SwitchPreferenceCompat(ctx);
            p.setPersistent(false);
            p.setKey(TOGGLE_TAG + setting);
            p.setTitle(title);
            p.setSummary(summary);
            p.setChecked(KeyConfig.getInt(cr, setting, 0) == 1);
            p.setOnPreferenceChangeListener((pref, v) -> {
                KeyConfig.putInt(cr, setting, (Boolean) v ? 1 : 0);
                return true;
            });
            return p;
        }
    }
}
