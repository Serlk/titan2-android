package org.titan2.parts;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import org.titan2.rear.common.ThemeJson;

/**
 * Color picker: swatch, HEX, hue/saturation/brightness/alpha sliders and a palette
 * (theme colors + a Material set). "Same as parent" drops the own color (inherit).
 */
final class ColorPickerDialog {
    interface Callback {
        void onColor(int color);

        /** "Same as parent": drop the own color */
        void onReset();
    }

    private static final int[] MATERIAL = {
            0xFF000000, 0xFF121212, 0xFF424242, 0xFF9E9E9E, 0xFFFFFFFF, 0xFFF44336, 0xFFE91E63,
            0xFF9C27B0, 0xFF673AB7, 0xFF3F51B5, 0xFF2196F3, 0xFF03A9F4, 0xFF00BCD4, 0xFF009688,
            0xFF4CAF50, 0xFF8BC34A, 0xFFCDDC39, 0xFFFFEB3B, 0xFFFFC107, 0xFFFF9800, 0xFFFF5722,
            0xFF795548, 0xFF607D8B,
    };

    private final Context mContext;
    private final float mDp;
    private int mColor;
    private final float[] mHsv = new float[3];
    private View mSwatch;
    private EditText mHex;
    private SeekBar mH, mS, mV, mA;
    private boolean mUpdating;

    private ColorPickerDialog(Context c, int color) {
        mContext = c;
        mDp = c.getResources().getDisplayMetrics().density;
        mColor = color;
    }

    static void show(Context c, CharSequence title, int color, int[] themeColors,
            boolean canReset, Callback cb) {
        ColorPickerDialog d = new ColorPickerDialog(c, color);
        AlertDialog.Builder b = new AlertDialog.Builder(c)
                .setTitle(title)
                .setView(d.build(themeColors))
                .setPositiveButton(android.R.string.ok, (x, w) -> cb.onColor(d.mColor))
                .setNegativeButton(android.R.string.cancel, null);
        if (canReset) b.setNeutralButton(R.string.rear_color_inherit, (x, w) -> cb.onReset());
        b.show();
    }

    private View build(int[] themeColors) {
        LinearLayout root = new LinearLayout(mContext);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * mDp);
        root.setPadding(pad, (int) (8 * mDp), pad, 0);

        LinearLayout top = new LinearLayout(mContext);
        top.setGravity(Gravity.CENTER_VERTICAL);
        mSwatch = new View(mContext);
        top.addView(mSwatch, new LinearLayout.LayoutParams((int) (56 * mDp), (int) (40 * mDp)));
        mHex = new EditText(mContext);
        mHex.setSingleLine(true);
        mHex.setFilters(new InputFilter[] {new InputFilter.LengthFilter(9)});
        mHex.setTypeface(android.graphics.Typeface.MONOSPACE);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(0, -2, 1);
        hl.leftMargin = (int) (12 * mDp);
        top.addView(mHex, hl);
        root.addView(top);

        mH = slider(root, R.string.rear_color_hue, 360);
        mS = slider(root, R.string.rear_color_saturation, 100);
        mV = slider(root, R.string.rear_color_value, 100);
        mA = slider(root, R.string.rear_color_alpha, 255);

        HorizontalScrollView hs = new HorizontalScrollView(mContext);
        LinearLayout pal = new LinearLayout(mContext);
        if (themeColors != null) for (int col : themeColors) chip(pal, col);
        for (int col : MATERIAL) chip(pal, col);
        hs.addView(pal);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, -2);
        pl.topMargin = (int) (8 * mDp);
        root.addView(hs, pl);

        mHex.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (mUpdating) return;
                try {
                    set(ThemeJson.parseColor(s.toString(), null), false);
                } catch (IllegalArgumentException ignored) {
                }
            }
        });
        set(mColor, true);
        return root;
    }

    private SeekBar slider(LinearLayout root, int label, int max) {
        TextView t = new TextView(mContext);
        t.setText(label);
        t.setTextSize(12);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.topMargin = (int) (6 * mDp);
        root.addView(t, tl);
        SeekBar s = new SeekBar(mContext);
        s.setMax(max);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar b, int p, boolean fromUser) {
                if (!fromUser || mUpdating) return;
                mHsv[0] = mH.getProgress();
                mHsv[1] = mS.getProgress() / 100f;
                mHsv[2] = mV.getProgress() / 100f;
                set(Color.HSVToColor(mA.getProgress(), mHsv), true);
            }

            @Override
            public void onStartTrackingTouch(SeekBar b) {}

            @Override
            public void onStopTrackingTouch(SeekBar b) {}
        });
        root.addView(s);
        return s;
    }

    private void chip(LinearLayout pal, int color) {
        View v = new View(mContext);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        d.setStroke((int) mDp, 0x40808080);
        v.setBackground(d);
        v.setOnClickListener(x -> set(color, true));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (32 * mDp),
                (int) (32 * mDp));
        lp.setMargins((int) (3 * mDp), (int) (3 * mDp), (int) (3 * mDp), (int) (3 * mDp));
        pal.addView(v, lp);
    }

    /** Update everything for the color; text: rewrite the HEX field too. */
    private void set(int color, boolean text) {
        mColor = color;
        mUpdating = true;
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(8 * mDp);
        d.setColor(color);
        d.setStroke((int) mDp, 0x40808080);
        mSwatch.setBackground(d);
        Color.colorToHSV(color, mHsv);
        mH.setProgress(Math.round(mHsv[0]));
        mS.setProgress(Math.round(mHsv[1] * 100));
        mV.setProgress(Math.round(mHsv[2] * 100));
        mA.setProgress(Color.alpha(color));
        if (text) mHex.setText(ThemeJson.toHex(color));
        mUpdating = false;
    }
}
