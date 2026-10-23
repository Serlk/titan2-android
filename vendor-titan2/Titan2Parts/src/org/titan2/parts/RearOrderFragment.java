package org.titan2.parts;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.titan2.rear.common.RearKeys;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Ordered rear screen lists with dragging (by the "handle" or a long press):
 *   MODE_FAVORITES: favorites (pages between the clock and the app list);
 *   MODE_TILES    : quick panel tiles (check the needed ones and arrange them);
 *   MODE_PAGES    : home pages after the clock (widgets and the favorites), the same way.
 */
public class RearOrderFragment extends Fragment {
    static final String ARG_MODE = "mode";
    static final int MODE_FAVORITES = 0;
    static final int MODE_TILES = 1;
    static final int MODE_PAGES = 2;

    private int mMode;
    private final List<Item> mItems = new ArrayList<>();
    private Adapter mAdapter;
    private ItemTouchHelper mTouch;
    private TextView mEmpty;
    private float mDp;

    static final class Item {
        String key;
        CharSequence label;
        Drawable icon;
        boolean checked = true;
    }

    static RearOrderFragment create(int mode) {
        RearOrderFragment f = new RearOrderFragment();
        Bundle b = new Bundle();
        b.putInt(ARG_MODE, mode);
        f.setArguments(b);
        return f;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
            Bundle state) {
        Context c = requireContext();
        mMode = getArguments() != null ? getArguments().getInt(ARG_MODE) : MODE_FAVORITES;
        mDp = c.getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * mDp);

        TextView info = new TextView(c);
        info.setText(mMode == MODE_TILES ? R.string.rear_tiles_info
                : mMode == MODE_PAGES ? R.string.rear_pages_info : R.string.rear_favorites_info);
        info.setPadding(pad, pad / 2, pad, pad / 2);
        info.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
        info.setTextColor(themeColor(c, android.R.attr.textColorSecondary));
        root.addView(info);

        if (mMode == MODE_FAVORITES) {
            Button add = new Button(c, null, android.R.attr.borderlessButtonStyle);
            add.setText(R.string.rear_favorites_add);
            add.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            add.setPadding(pad, 0, pad, 0);
            add.setOnClickListener(v -> addFavorites());
            root.addView(add, new LinearLayout.LayoutParams(-1, -2));
            mEmpty = new TextView(c);
            mEmpty.setText(R.string.rear_favorites_empty);
            mEmpty.setPadding(pad, pad, pad, pad);
            mEmpty.setGravity(Gravity.CENTER);
            root.addView(mEmpty, new LinearLayout.LayoutParams(-1, -2));
        }

        RecyclerView list = new RecyclerView(c);
        list.setLayoutManager(new LinearLayoutManager(c));
        mAdapter = new Adapter();
        list.setAdapter(mAdapter);
        mTouch = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder a,
                    @NonNull RecyclerView.ViewHolder b) {
                int from = a.getBindingAdapterPosition(), to = b.getBindingAdapterPosition();
                if (from < 0 || to < 0) return false;
                Collections.swap(mItems, from, to);
                mAdapter.notifyItemMoved(from, to);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder h, int dir) {}

            @Override
            public void clearView(@NonNull RecyclerView rv,
                    @NonNull RecyclerView.ViewHolder h) {
                super.clearView(rv, h);
                h.itemView.setActivated(false);
                save();
            }

            @Override
            public void onSelectedChanged(RecyclerView.ViewHolder h, int state) {
                super.onSelectedChanged(h, state);
                if (h != null && state == ItemTouchHelper.ACTION_STATE_DRAG) {
                    h.itemView.setActivated(true);
                    h.itemView.performHapticFeedback(
                            android.view.HapticFeedbackConstants.DRAG_START);
                }
            }
        });
        mTouch.attachToRecyclerView(list);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        load();
        return root;
    }

    private static int themeColor(Context c, int attr) {
        TypedValue tv = new TypedValue();
        c.getTheme().resolveAttribute(attr, tv, true);
        return tv.resourceId != 0 ? c.getColor(tv.resourceId) : tv.data;
    }

    private void load() {
        Context c = requireContext();
        mItems.clear();
        if (checklist()) {
            String[] all = mMode == MODE_PAGES ? RearKeys.ALL_PAGES : RearKeys.ALL_TILES;
            CharSequence[] names = getResources().getTextArray(mMode == MODE_PAGES
                    ? R.array.rear_page_entries : R.array.rear_tile_entries);
            List<String> on = RearConfig.list(c, listKey());
            for (String k : on) {
                int idx = indexOf(all, k);
                if (idx < 0) continue;
                Item i = new Item();
                i.key = k;
                i.label = names[idx];
                mItems.add(i);
            }
            for (int idx = 0; idx < all.length; idx++) {
                if (on.contains(all[idx])) continue;
                Item i = new Item();
                i.key = all[idx];
                i.label = names[idx];
                i.checked = false;
                mItems.add(i);
            }
        } else {
            for (String k : RearConfig.list(c, RearKeys.FAVORITES)) {
                Item i = favorite(c, k);
                if (i != null) mItems.add(i);
            }
        }
        mAdapter.notifyDataSetChanged();
        updateEmpty();
    }

    private void updateEmpty() {
        if (mEmpty != null) mEmpty.setVisibility(mItems.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private static int indexOf(String[] a, String s) {
        for (int i = 0; i < a.length; i++) if (a[i].equals(s)) return i;
        return -1;
    }

    /** Favorites item by the "component[#profile]" key. */
    private static Item favorite(Context c, String key) {
        String flat = key.contains("#") ? key.substring(0, key.indexOf('#')) : key;
        ComponentName cn = ComponentName.unflattenFromString(flat);
        if (cn == null) return null;
        PackageManager pm = c.getPackageManager();
        Item i = new Item();
        i.key = key;
        try {
            i.label = pm.getActivityInfo(cn, 0).loadLabel(pm);
            i.icon = pm.getActivityIcon(cn);
        } catch (PackageManager.NameNotFoundException e) {
            i.label = cn.getPackageName();
        }
        if (key.contains("#")) i.label = i.label + " " + c.getString(R.string.rear_work_badge);
        return i;
    }

    private void save() {
        List<String> keys = new ArrayList<>();
        for (Item i : mItems) if (i.checked) keys.add(i.key);
        RearConfig.putList(requireContext(), listKey(), keys);
    }

    /** Tiles and pages: a fixed set to check and arrange; favorites: added apps. */
    private boolean checklist() {
        return mMode == MODE_TILES || mMode == MODE_PAGES;
    }

    private String listKey() {
        return mMode == MODE_TILES ? RearKeys.QS_TILES
                : mMode == MODE_PAGES ? RearKeys.PAGES : RearKeys.FAVORITES;
    }

    /** Add to favorites: all apps with a launcher icon (respecting the allow list). */
    private void addFavorites() {
        Context c = requireContext();
        PackageManager pm = c.getPackageManager();
        Set<String> have = new HashSet<>();
        for (Item i : mItems) have.add(i.key);
        boolean only = RearKeys.APPS_SELECTED.equals(RearConfig.get(c, RearKeys.APPS_FILTER));
        Set<String> allowed = new HashSet<>(RearConfig.list(c, RearKeys.APPS_ALLOWED));
        List<String[]> rows = new ArrayList<>();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals("org.titan2.rear") || (only && !allowed.contains(pkg))) continue;
            String key = new ComponentName(pkg, ri.activityInfo.name).flattenToString();
            if (have.contains(key)) continue;
            rows.add(new String[] {ri.loadLabel(pm).toString(), key});
        }
        Collator col = Collator.getInstance();
        rows.sort((a, b) -> col.compare(a[0], b[0]));
        CharSequence[] labels = new CharSequence[rows.size()];
        boolean[] checked = new boolean[rows.size()];
        for (int i = 0; i < rows.size(); i++) labels[i] = rows.get(i)[0];
        new AlertDialog.Builder(c)
                .setTitle(R.string.rear_favorites_add)
                .setMultiChoiceItems(labels, checked, (d, w, on) -> checked[w] = on)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    for (int i = 0; i < rows.size(); i++) {
                        if (!checked[i]) continue;
                        Item it = favorite(c, rows.get(i)[1]);
                        if (it != null) mItems.add(it);
                    }
                    mAdapter.notifyDataSetChanged();
                    updateEmpty();
                    save();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private final class Holder extends RecyclerView.ViewHolder {
        final ImageView handle, icon, remove;
        final TextView label;
        final CheckBox check;

        Holder(LinearLayout row) {
            super(row);
            handle = (ImageView) row.getChildAt(0);
            icon = (ImageView) row.getChildAt(1);
            label = (TextView) row.getChildAt(2);
            check = (CheckBox) row.getChildAt(3);
            remove = (ImageView) row.getChildAt(4);
        }
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            Context c = parent.getContext();
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int pad = (int) (12 * mDp);
            row.setPadding(pad, 0, pad, 0);
            row.setMinimumHeight((int) (56 * mDp));
            TypedValue tv = new TypedValue();
            c.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            row.setBackgroundResource(tv.resourceId);
            row.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));

            ImageView handle = new ImageView(c);
            handle.setImageResource(R.drawable.ic_rear_drag);
            handle.setImageTintList(android.content.res.ColorStateList.valueOf(
                    themeColor(c, android.R.attr.textColorSecondary)));
            handle.setContentDescription(c.getString(R.string.rear_drag));
            row.addView(handle, new LinearLayout.LayoutParams((int) (40 * mDp), (int) (40 * mDp)));
            handle.setScaleType(ImageView.ScaleType.CENTER);

            ImageView icon = new ImageView(c);
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams((int) (36 * mDp),
                    (int) (36 * mDp));
            il.rightMargin = (int) (12 * mDp);
            row.addView(icon, il);

            TextView label = new TextView(c);
            label.setTextAppearance(android.R.style.TextAppearance_Material_Subhead);
            label.setTextColor(themeColor(c, android.R.attr.textColorPrimary));
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));

            CheckBox check = new CheckBox(c);
            row.addView(check);

            ImageView remove = new ImageView(c);
            remove.setImageResource(R.drawable.ic_rear_remove);
            remove.setImageTintList(android.content.res.ColorStateList.valueOf(
                    themeColor(c, android.R.attr.textColorSecondary)));
            remove.setScaleType(ImageView.ScaleType.CENTER);
            remove.setContentDescription(c.getString(R.string.rear_remove));
            remove.setBackgroundResource(tv.resourceId);
            row.addView(remove, new LinearLayout.LayoutParams((int) (40 * mDp), (int) (40 * mDp)));
            return new Holder(row);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int pos) {
            Item it = mItems.get(pos);
            h.label.setText(it.label);
            h.icon.setImageDrawable(it.icon);
            h.icon.setVisibility(it.icon != null ? View.VISIBLE : View.GONE);
            boolean tiles = checklist();
            h.check.setVisibility(tiles ? View.VISIBLE : View.GONE);
            h.remove.setVisibility(tiles ? View.GONE : View.VISIBLE);
            h.check.setOnCheckedChangeListener(null);
            h.check.setChecked(it.checked);
            h.check.setOnCheckedChangeListener((b, on) -> {
                it.checked = on;
                save();
            });
            h.remove.setOnClickListener(v -> {
                int p = h.getBindingAdapterPosition();
                if (p < 0) return;
                mItems.remove(p);
                notifyItemRemoved(p);
                updateEmpty();
                save();
            });
            h.handle.setOnTouchListener((v, e) -> {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) mTouch.startDrag(h);
                return false;
            });
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }
    }
}
