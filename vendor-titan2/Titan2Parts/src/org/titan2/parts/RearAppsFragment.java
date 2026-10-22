package org.titan2.parts;

import android.content.Context;
import android.content.pm.PackageManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import com.android.settingslib.widget.LayoutPreference;

import org.titan2.rear.common.RearKeys;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which apps are visible on the rear screen: all or only the checked ones (search by name and
 * package). Icons as in the launcher.
 */
public class RearAppsFragment extends RearPrefs {
    private final List<SwitchPreferenceCompat> mApps = new ArrayList<>();
    private final Set<String> mAllowed = new LinkedHashSet<>();
    private SwitchPreferenceCompat mOnly;

    @Override
    protected void build(PreferenceScreen screen) {
        Context c = getContext();
        mAllowed.addAll(RearConfig.list(c, RearKeys.APPS_ALLOWED));

        mOnly = new SwitchPreferenceCompat(c);
        mOnly.setKey("rear_apps_only");
        mOnly.setPersistent(false);
        mOnly.setTitle(R.string.rear_apps_only_title);
        mOnly.setSummary(R.string.rear_apps_only_summary);
        mOnly.setChecked(RearKeys.APPS_SELECTED.equals(RearConfig.get(c, RearKeys.APPS_FILTER)));
        mOnly.setOnPreferenceChangeListener((p, v) -> {
            boolean on = (Boolean) v;
            RearConfig.put(c, RearKeys.APPS_FILTER, on ? RearKeys.APPS_SELECTED
                    : RearKeys.APPS_ALL);
            for (SwitchPreferenceCompat a : mApps) a.setEnabled(on);
            return true;
        });
        screen.addPreference(mOnly);

        EditText search = new EditText(c);
        search.setHint(R.string.rear_apps_search);
        search.setSingleLine(true);
        FrameLayout box = new FrameLayout(c);
        int pad = (int) (16 * c.getResources().getDisplayMetrics().density);
        box.setPadding(pad, 0, pad, 0);
        box.addView(search, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LayoutPreference sp = new LayoutPreference(c, box);
        sp.setKey("rear_apps_search");
        sp.setSelectable(false);
        screen.addPreference(sp);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int d) {}

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int d) {}

            @Override
            public void afterTextChanged(Editable s) {
                filter(s.toString());
            }
        });

        PreferenceCategory cat = cat(screen, R.string.rear_apps_list);
        Preference all = new Preference(c);
        all.setKey("rear_apps_all");
        all.setTitle(R.string.rear_apps_select_all);
        all.setOnPreferenceClickListener(p -> {
            setAll(true);
            return true;
        });
        cat.addPreference(all);
        Preference none = new Preference(c);
        none.setKey("rear_apps_none");
        none.setTitle(R.string.rear_apps_select_none);
        none.setOnPreferenceClickListener(p -> {
            setAll(false);
            return true;
        });
        cat.addPreference(none);

        PackageManager pm = c.getPackageManager();
        boolean on = mOnly.isChecked();
        for (String[] app : KeyPrefs.launcherApps(c)) {
            final String pkg = app[1];
            SwitchPreferenceCompat p = new SwitchPreferenceCompat(c);
            p.setKey("app:" + pkg);
            p.setPersistent(false);
            p.setTitle(app[0]);
            p.setSummary(pkg);
            try {
                p.setIcon(pm.getApplicationIcon(pkg));
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            p.setChecked(mAllowed.contains(pkg));
            p.setEnabled(on);
            p.setOnPreferenceChangeListener((x, v) -> {
                if ((Boolean) v) mAllowed.add(pkg);
                else mAllowed.remove(pkg);
                save();
                return true;
            });
            cat.addPreference(p);
            mApps.add(p);
        }
        all.setEnabled(on);
        none.setEnabled(on);
        mOnly.setOnPreferenceClickListener(p -> {
            all.setEnabled(mOnly.isChecked());
            none.setEnabled(mOnly.isChecked());
            return false;
        });
    }

    private void setAll(boolean on) {
        for (SwitchPreferenceCompat p : mApps) {
            if (!p.isVisible()) continue; // only the ones found by search
            String pkg = p.getKey().substring(4);
            p.setChecked(on);
            if (on) mAllowed.add(pkg);
            else mAllowed.remove(pkg);
        }
        save();
    }

    private void save() {
        RearConfig.putList(getContext(), RearKeys.APPS_ALLOWED, new ArrayList<>(mAllowed));
    }

    private void filter(String q) {
        String s = q.trim().toLowerCase(Locale.ROOT);
        for (SwitchPreferenceCompat p : mApps) {
            boolean match = s.isEmpty()
                    || String.valueOf(p.getTitle()).toLowerCase(Locale.ROOT).contains(s)
                    || p.getKey().toLowerCase(Locale.ROOT).contains(s);
            p.setVisible(match);
        }
    }
}
