package org.titan2.rear;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.titan2.rear.common.ThemeSpec;

import java.util.Collections;
import java.util.List;

/**
 * Icon grid (favorites and the list of all apps). Cell height = grid height / rows,
 * so a favorites page fills the screen exactly, and the list scrolls.
 */
public class AppGridView extends GridView {
    /** Styling parameters (effective: theme + settings). */
    static final class Style {
        int columns = 3;
        int rows = 3;
        boolean labels = true;
        int labelLines = 1;
        float labelSize = 12;
        float iconSize = 1f;
        String shape = "none";
        String style = "color";
        int labelColor;
        int monoTint;
        int plate;
        Typeface font = Typeface.DEFAULT;
    }

    private final Adapter mAdapter = new Adapter();
    private List<Apps.Entry> mItems = Collections.emptyList();
    private Style mStyle = new Style();
    private int mCellH;
    private final float mDp;

    public AppGridView(Context c) {
        super(c);
        mDp = c.getResources().getDisplayMetrics().density;
        setAdapter(mAdapter);
        setSelector(android.R.color.transparent);
        setVerticalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setStretchMode(STRETCH_COLUMN_WIDTH);
        setClipToPadding(false);
    }

    static Style style(ThemeSpec t, Typeface font, int columns, int rows, boolean labels,
            String shape, String iconStyle, float iconSize) {
        Style s = new Style();
        s.columns = columns;
        s.rows = rows;
        s.labels = labels;
        s.labelLines = t.labelLines;
        s.labelSize = t.labelSize * t.textScale;
        s.iconSize = iconSize;
        s.shape = shape;
        s.style = iconStyle;
        s.labelColor = t.color("label", t.onSurface);
        s.monoTint = t.color("label", t.onSurface);
        s.plate = ThemeSpec.over(t.surface, t.background);
        s.font = font;
        return s;
    }

    public void setup(List<Apps.Entry> items, Style style) {
        mItems = items;
        mStyle = style;
        setNumColumns(Math.max(1, style.columns));
        updateCell(getHeight());
        mAdapter.notifyDataSetChanged();
    }

    public Apps.Entry entry(int pos) {
        return mItems.get(pos);
    }

    /** At the top of the list (the sheet can be pulled down)? */
    public boolean atTop() {
        return !canScrollVertically(-1);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        updateCell(h);
        post(mAdapter::notifyDataSetChanged);
    }

    private void updateCell(int h) {
        int avail = h - getPaddingTop() - getPaddingBottom();
        mCellH = avail > 0 ? avail / Math.max(1, mStyle.rows) : (int) (96 * mDp);
    }

    private class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return mItems.size();
        }

        @Override
        public Object getItem(int p) {
            return mItems.get(p);
        }

        @Override
        public long getItemId(int p) {
            return p;
        }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout cell = (LinearLayout) convert;
            if (cell == null) {
                cell = new LinearLayout(getContext());
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                ImageView iv = new ImageView(getContext());
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                cell.addView(iv);
                TextView tv = new TextView(getContext());
                tv.setGravity(Gravity.CENTER_HORIZONTAL);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                cell.addView(tv);
            }
            cell.setLayoutParams(new AbsListView.LayoutParams(
                    AbsListView.LayoutParams.MATCH_PARENT, mCellH));
            Apps.Entry e = mItems.get(pos);
            ImageView iv = (ImageView) cell.getChildAt(0);
            TextView tv = (TextView) cell.getChildAt(1);
            int label = mStyle.labels
                    ? (int) (mStyle.labelSize * 1.5f * mDp * mStyle.labelLines) : 0;
            int icon = (int) Math.min(56 * mDp * mStyle.iconSize,
                    Math.max(16 * mDp, mCellH - label - 10 * mDp));
            iv.setLayoutParams(new LinearLayout.LayoutParams(icon, icon));
            iv.setImageDrawable(Icons.get(getContext(), e, mStyle.shape, mStyle.style,
                    mStyle.monoTint, mStyle.plate));
            tv.setVisibility(mStyle.labels ? VISIBLE : GONE);
            tv.setText(e.label);
            // without labels the cell would be read as nothing
            cell.setContentDescription(mStyle.labels ? null : e.label);
            tv.setTypeface(mStyle.font);
            tv.setTextSize(mStyle.labelSize);
            tv.setSingleLine(mStyle.labelLines == 1);
            tv.setMaxLines(mStyle.labelLines);
            tv.setTextColor(mStyle.labelColor);
            return cell;
        }
    }
}
