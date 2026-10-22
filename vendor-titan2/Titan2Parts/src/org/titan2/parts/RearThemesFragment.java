package org.titan2.parts;

import android.app.AlertDialog;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;

import org.titan2.rear.common.RearKeys;
import org.titan2.rear.common.ThemeJson;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Rear screen themes: a list with previews, apply, edit (a built-in one via a new
 * theme inheriting it), copy, export/import (JSON or ZIP with fonts and
 * images), delete. On import the theme is validated: errors come with a clear text.
 */
public class RearThemesFragment extends RearPrefs {
    private String mExportId;

    private final ActivityResultLauncher<String[]> mImport = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::onImport);
    private final ActivityResultLauncher<String> mExportJson = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/json"), this::onExport);
    private final ActivityResultLauncher<String> mExportZip = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/zip"), this::onExport);

    @Override
    protected void build(PreferenceScreen screen) {
        // rebuilt entirely in onResume (the list may have changed in the editor)
    }

    @Override
    public void onResume() {
        super.onResume();
        rebuild();
    }

    private void rebuild() {
        Context c = getContext();
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(c);
        addPreview(screen);
        String current = RearConfig.get(c, RearKeys.THEME);

        PreferenceCategory actions = cat(screen, R.string.rear_themes_actions);
        Preference create = new Preference(c);
        create.setKey("rear_theme_create");
        create.setTitle(R.string.rear_theme_create);
        create.setSummary(R.string.rear_theme_create_summary);
        create.setOnPreferenceClickListener(p -> {
            startActivity(RearScreenActivity.page(c, RearScreenActivity.P_EDITOR)
                    .putExtra(RearScreenActivity.EXTRA_THEME, current)
                    .putExtra(RearThemeEditorFragment.EXTRA_NEW, true));
            return true;
        });
        actions.addPreference(create);
        Preference imp = new Preference(c);
        imp.setKey("rear_theme_import");
        imp.setTitle(R.string.rear_theme_import);
        imp.setSummary(R.string.rear_theme_import_summary);
        imp.setOnPreferenceClickListener(p -> {
            mImport.launch(new String[] {"application/json", "application/zip",
                    "application/x-zip-compressed", "text/plain", "application/octet-stream"});
            return true;
        });
        actions.addPreference(imp);

        PreferenceCategory list = cat(screen, R.string.rear_themes_list);
        for (RearThemes.Info i : RearThemes.list(c)) {
            KeyPrefs.RadioPreference p = new KeyPrefs.RadioPreference(c);
            p.setKey("theme:" + i.id);
            p.setTitle(i.name);
            p.setSummary(summary(c, i));
            p.setChecked(i.id.equals(current));
            p.setIcon(new ThemePreview.PreviewDrawable(c, RearThemes.spec(c, i.id)));
            p.setOnPreferenceChangeListener((x, v) -> {
                // a broken theme is not applied (it can be opened as JSON or deleted)
                if (i.error != null) {
                    toast(getString(R.string.rear_theme_broken, i.error));
                    return false;
                }
                apply(i.id);
                return true;
            });
            p.setExtraWidgetOnClickListener(v -> actions(i));
            list.addPreference(p);
        }
        screen.addPreference(KeyPrefs.footer(c, getString(R.string.rear_themes_footer)));
        setPreferenceScreen(screen);
        updatePreview();
    }

    private String summary(Context c, RearThemes.Info i) {
        if (i.error != null) return getString(R.string.rear_theme_broken, i.error);
        List<String> parts = new ArrayList<>();
        parts.add(getString(i.builtin ? R.string.rear_theme_builtin : R.string.rear_theme_user));
        if (!i.author.isEmpty()) parts.add(i.author);
        if (!i.extendsId.isEmpty()) {
            parts.add(getString(R.string.rear_theme_extends,
                    RearThemes.info(c, i.extendsId).name));
        }
        String s = String.join(" · ", parts);
        return i.description.isEmpty() ? s : s + "\n" + i.description;
    }

    private void apply(String id) {
        RearConfig.put(getContext(), RearKeys.THEME, id);
        rebuild();
    }

    private void actions(RearThemes.Info i) {
        Context c = getContext();
        List<CharSequence> labels = new ArrayList<>();
        List<Runnable> run = new ArrayList<>();
        labels.add(getString(R.string.rear_theme_apply));
        run.add(() -> apply(i.id));
        labels.add(getString(i.builtin ? R.string.rear_theme_customize : R.string.rear_theme_edit));
        run.add(() -> startActivity(RearScreenActivity.page(c, RearScreenActivity.P_EDITOR)
                .putExtra(RearScreenActivity.EXTRA_THEME, i.id)
                .putExtra(RearThemeEditorFragment.EXTRA_NEW, i.builtin)));
        labels.add(getString(R.string.rear_theme_duplicate));
        run.add(() -> duplicate(i));
        labels.add(getString(R.string.rear_theme_export));
        run.add(() -> {
            mExportId = i.id;
            String file = ThemeJson.slug(i.name);
            if (RearThemes.exportsZip(c, i.id)) mExportZip.launch(file + ".zip");
            else mExportJson.launch(file + ".json");
        });
        labels.add(getString(R.string.rear_theme_show_json));
        run.add(() -> showJson(i));
        if (!i.builtin) {
            labels.add(getString(R.string.rear_theme_delete));
            run.add(() -> confirmDelete(i));
        }
        new AlertDialog.Builder(c)
                .setTitle(i.name)
                .setItems(labels.toArray(new CharSequence[0]), (d, w) -> run.get(w).run())
                .show();
    }

    private void duplicate(RearThemes.Info i) {
        Context c = getContext();
        EditText name = new EditText(c);
        name.setInputType(InputType.TYPE_CLASS_TEXT);
        name.setText(getString(R.string.rear_theme_copy_name, i.name));
        name.setSelectAllOnFocus(true);
        new AlertDialog.Builder(c)
                .setTitle(R.string.rear_theme_duplicate)
                .setView(name)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    try {
                        RearThemes.duplicate(c, i.id, name.getText().toString().trim());
                        rebuild();
                    } catch (Exception e) {
                        toast(getString(R.string.rear_theme_failed, e.getMessage()));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmDelete(RearThemes.Info i) {
        Context c = getContext();
        List<String> kids = RearThemes.children(c, i.id);
        String msg = kids.isEmpty() ? getString(R.string.rear_theme_delete_confirm, i.name)
                : getString(R.string.rear_theme_delete_parent, i.name, String.join(", ", kids));
        new AlertDialog.Builder(c)
                .setMessage(msg)
                .setPositiveButton(R.string.rear_theme_delete, (d, w) -> {
                    if (i.id.equals(RearConfig.get(c, RearKeys.THEME))) {
                        RearConfig.put(c, RearKeys.THEME, RearKeys.THEME_DEFAULT);
                    }
                    RearThemes.delete(c, i.id);
                    rebuild();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showJson(RearThemes.Info i) {
        Context c = getContext();
        String text;
        try {
            text = RearThemes.rawText(c, i.id);
            ThemeJson.Report r = ThemeJson.validate(text, RearThemes.files(c, i.id));
            if (!r.errors.isEmpty() || !r.warnings.isEmpty()) text = r + "\n\n" + text;
        } catch (Exception e) {
            text = e.getMessage();
        }
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextSize(12);
        tv.setTextIsSelectable(true);
        int pad = (int) (20 * c.getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad / 2, pad, 0);
        ScrollView sv = new ScrollView(c);
        sv.addView(tv);
        new AlertDialog.Builder(c)
                .setTitle(i.name)
                .setView(sv)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void onImport(Uri uri) {
        if (uri == null) return;
        Context c = getContext();
        RearThemes.ImportResult r = RearThemes.importFrom(c, uri);
        if (r.id == null) {
            new AlertDialog.Builder(c)
                    .setTitle(R.string.rear_theme_import_failed)
                    .setMessage(r.report.toString())
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        String name = RearThemes.info(c, r.id).name;
        rebuild();
        AlertDialog.Builder b = new AlertDialog.Builder(c)
                .setTitle(getString(R.string.rear_theme_imported, name))
                .setPositiveButton(R.string.rear_theme_apply, (d, w) -> apply(r.id))
                .setNegativeButton(android.R.string.cancel, null);
        if (!r.report.warnings.isEmpty()) b.setMessage(r.report.toString());
        b.show();
    }

    private void onExport(Uri uri) {
        if (uri == null || mExportId == null) return;
        Context c = getContext();
        try (OutputStream out = c.getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new java.io.IOException("cannot write " + uri);
            RearThemes.export(c, mExportId, out);
            toast(getString(R.string.rear_theme_exported));
        } catch (Exception e) {
            toast(getString(R.string.rear_theme_failed, e.getMessage()));
        }
    }

    private void toast(String s) {
        Toast.makeText(getContext(), s, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) mExportId = savedInstanceState.getString("export_id");
    }

    @Override
    public void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("export_id", mExportId);
    }
}
