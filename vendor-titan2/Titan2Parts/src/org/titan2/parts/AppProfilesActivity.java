package org.titan2.parts;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;
import androidx.preference.PreferenceScreen;

import java.util.List;

/**
 * Per-app key profiles (Titan2Parts extension): for the focused package (sys.titan2.focus,
 * frameworks_base 0003) any programmable key may have its own mode, function or
 * actions. App list -> keys screen of that app (KeySettingsActivity with profile_pkg).
 */
public class AppProfilesActivity extends CollapsingToolbarBaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                            new ProfilesFragment())
                    .commit();
        }
    }

    public static class ProfilesFragment extends SettingsBasePreferenceFragment {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        }

        @Override
        public void onResume() {
            super.onResume();
            build();
        }

        private void build() {
            final Context ctx = getContext();
            final ContentResolver cr = ctx.getContentResolver();
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(ctx);
            Preference add = new Preference(ctx);
            add.setTitle(R.string.profiles_add);
            add.setOnPreferenceClickListener(p -> {
                pickApp(ctx, cr);
                return true;
            });
            screen.addPreference(add);
            PreferenceCategory cat = new PreferenceCategory(ctx);
            cat.setTitle(R.string.profiles_title);
            screen.addPreference(cat);
            for (String pkg : KeyConfig.profileApps(cr)) {
                Preference p = new Preference(ctx);
                p.setTitle(KeyPrefs.appLabel(ctx, pkg));
                p.setSummary(pkg);
                p.setIntent(open(ctx, pkg));
                cat.addPreference(p);
            }
            screen.addPreference(KeyPrefs.footer(ctx, ctx.getString(R.string.profiles_info)));
            setPreferenceScreen(screen);
        }

        private static Intent open(Context ctx, String pkg) {
            return new Intent(ctx, KeySettingsActivity.class)
                    .putExtra(KeyRemapActivity.EXTRA_PROFILE, pkg);
        }

        private void pickApp(Context ctx, ContentResolver cr) {
            final List<String[]> apps = KeyPrefs.launcherApps(ctx);
            CharSequence[] labels = new CharSequence[apps.size()];
            for (int i = 0; i < apps.size(); i++) labels[i] = apps.get(i)[0];
            new AlertDialog.Builder(ctx)
                    .setTitle(R.string.profiles_add)
                    .setItems(labels, (d, which) -> {
                        String pkg = apps.get(which)[1];
                        List<String> list = KeyConfig.profileApps(cr);
                        if (!list.contains(pkg)) {
                            list.add(pkg);
                            KeyConfig.setProfileApps(cr, list);
                        }
                        startActivity(open(ctx, pkg));
                    })
                    .show();
        }
    }
}
