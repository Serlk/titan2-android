package org.titan2.parts;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;
import androidx.preference.TwoStatePreference;

import com.android.settingslib.widget.LayoutPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import org.titan2.rear.common.RearKeys;

import java.util.Arrays;

/**
 * Shared by the rear screen settings screens: everything is stored in rear_config (RearConfig),
 * the provider notifies the shell at once; defaults come from RearKeys (the same as in the shell).
 * Every ListPreference has a key: the AndroidX dialog looks the entry up by it.
 */
abstract class RearPrefs extends SettingsBasePreferenceFragment
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    protected ThemePreview.PreviewView mPreview;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        getPreferenceManager().setSharedPreferencesName(RearConfig.FILE);
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(getContext());
        setPreferenceScreen(screen);
        build(screen);
        updatePreview();
    }

    protected abstract void build(PreferenceScreen screen);

    @Override
    public void onResume() {
        super.onResume();
        RearConfig.prefs(getContext()).registerOnSharedPreferenceChangeListener(this);
        // the shell writes some keys itself (RearConfigProvider): no stale switches after a return
        syncAll(getPreferenceScreen());
        updatePreview();
    }

    @Override
    public void onPause() {
        RearConfig.prefs(getContext()).unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        if (key != null) sync(findPreference(key));
        updatePreview();
    }

    private void syncAll(PreferenceGroup g) {
        if (g == null) return;
        for (int i = 0; i < g.getPreferenceCount(); i++) {
            Preference p = g.getPreference(i);
            if (p instanceof PreferenceGroup) syncAll((PreferenceGroup) p);
            else sync(p);
        }
    }

    /** Stored value -> a persistent switch or list (changed outside this screen). */
    private void sync(Preference p) {
        if (p == null || !p.isPersistent() || p.getKey() == null || getContext() == null) return;
        Context c = getContext();
        // an unset key shows its default already; do not write the default into the file
        if (!RearConfig.prefs(c).contains(p.getKey())) return;
        if (p instanceof TwoStatePreference) {
            ((TwoStatePreference) p).setChecked(RearConfig.bool(c, p.getKey()));
        } else if (p instanceof ListPreference) {
            ListPreference l = (ListPreference) p;
            String v = RearConfig.get(c, p.getKey());
            if (v != null && !v.equals(l.getValue())) l.setValue(v);
        }
    }

    /** Preview of the current theme with the current settings (at the top of the screen). */
    protected void addPreview(PreferenceGroup g) {
        mPreview = new ThemePreview.PreviewView(getContext());
        LayoutPreference lp = new LayoutPreference(getContext(), mPreview);
        lp.setKey("rear_preview");
        lp.setSelectable(false);
        g.addPreference(lp);
    }

    protected void updatePreview() {
        if (mPreview == null || getContext() == null) return;
        Context c = getContext();
        mPreview.set(RearThemes.spec(c, RearConfig.get(c, RearKeys.THEME)),
                RearConfig.values(c));
    }

    protected PreferenceCategory cat(PreferenceGroup s, int title) {
        PreferenceCategory pc = new PreferenceCategory(getContext());
        pc.setTitle(title);
        s.addPreference(pc);
        return pc;
    }

    protected ListPreference list(PreferenceGroup g, String key, int title, CharSequence[] entries,
            CharSequence[] values) {
        ListPreference p = new ListPreference(getContext());
        p.setKey(key);
        p.setTitle(title);
        p.setDialogTitle(title);
        p.setEntries(entries);
        p.setEntryValues(values);
        String def = RearKeys.def(key);
        if (Arrays.asList(values).contains(def)) p.setDefaultValue(def);
        p.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        g.addPreference(p);
        return p;
    }

    protected ListPreference list(PreferenceGroup g, String key, int title, int entries,
            CharSequence[] values) {
        return list(g, key, title, getResources().getTextArray(entries), values);
    }

    protected SwitchPreferenceCompat sw(PreferenceGroup g, String key, int title, int summary) {
        SwitchPreferenceCompat p = new SwitchPreferenceCompat(getContext());
        p.setKey(key);
        p.setTitle(title);
        if (summary != 0) p.setSummary(summary);
        p.setDefaultValue(RearKeys.parseBool(RearKeys.def(key), false));
        g.addPreference(p);
        return p;
    }

    protected Preference link(PreferenceGroup g, int title, int summary, Intent intent) {
        Preference p = new Preference(getContext());
        p.setTitle(title);
        if (summary != 0) p.setSummary(summary);
        p.setIntent(intent);
        g.addPreference(p);
        return p;
    }

    /** "As in the theme" + options (the labels are the values themselves). */
    protected CharSequence[] withTheme(CharSequence[] labels) {
        CharSequence[] out = new CharSequence[labels.length + 1];
        out[0] = getString(R.string.rear_from_theme);
        System.arraycopy(labels, 0, out, 1, labels.length);
        return out;
    }

    protected static CharSequence[] withThemeValue(CharSequence... values) {
        CharSequence[] out = new CharSequence[values.length + 1];
        out[0] = RearKeys.FROM_THEME;
        System.arraycopy(values, 0, out, 1, values.length);
        return out;
    }

    static final CharSequence[] TRI = {RearKeys.FROM_THEME, RearKeys.ON, RearKeys.OFF};
}
