package org.titan2.rear;

import android.app.NotificationManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import org.titan2.rear.common.RearKeys;
import org.titan2.rear.common.ThemeSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * Quick panel (swipe down): tiles from the settings (order and set are in Titan2Parts) and the rear
 * display brightness. Follows the finger; closed with a swipe up, a tap on the background or "Back".
 * On the lock screen only the torch (if allowed) and brightness: nothing that changes the phone
 * state or opens data.
 */
public class QuickPanel extends LinearLayout {
    private static final String TAG = "RearShell";

    public interface Host {
        void onPanelDragStart();

        /** dy < 0: up (towards closing) */
        void onPanelDrag(float dy);

        void onPanelDragEnd(float vy);

        void openSettings();

        void sleepRear();
    }

    private final Host mHost;
    private final WifiManager mWifi;
    private final NotificationManager mNm;
    private final AudioManager mAudio;
    private final Torch mTorch;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final float mDp;
    private final int mSlop;
    private BrightnessControl mBrightness;
    private ThemeSpec mTheme;
    private Typeface mFont = Typeface.DEFAULT;
    private boolean mLocked;
    private final int mPad;

    private final LinearLayout mTiles;
    private final LinearLayout mBrightRow;
    private final SeekBar mBright;
    private final ImageView mBrightIcon;
    private final View mHandle;
    private final List<String> mTileIds = new ArrayList<>();
    private final List<LinearLayout> mTileViews = new ArrayList<>();

    private float mDownY, mDownX;
    private boolean mDragging;
    private VelocityTracker mVelocity;

    private final Runnable mRefresh = this::refresh;

    public QuickPanel(Context c, Host host) {
        super(c);
        mHost = host;
        mWifi = c.getSystemService(WifiManager.class);
        mNm = c.getSystemService(NotificationManager.class);
        mAudio = c.getSystemService(AudioManager.class);
        mTorch = Torch.get(c);
        mDp = c.getResources().getDisplayMetrics().density;
        mSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        setOrientation(VERTICAL);
        setClickable(true);
        mPad = (int) (12 * mDp);
        setPadding(mPad, mPad, mPad, (int) (6 * mDp));

        mTiles = new LinearLayout(c);
        mTiles.setOrientation(VERTICAL);
        addView(mTiles, new LayoutParams(-1, -2));

        mBrightRow = new LinearLayout(c);
        mBrightRow.setOrientation(HORIZONTAL);
        mBrightRow.setGravity(Gravity.CENTER_VERTICAL);
        mBrightIcon = new ImageView(c);
        mBrightIcon.setImageResource(R.drawable.ic_qs_brightness);
        mBrightRow.addView(mBrightIcon, new LayoutParams((int) (22 * mDp), (int) (22 * mDp)));
        mBright = new SeekBar(c);
        mBright.setMax(1000);
        mBright.setContentDescription(c.getString(R.string.qs_brightness));
        mBright.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser && mBrightness != null) mBrightness.set(p / 1000f);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {}

            @Override
            public void onStopTrackingTouch(SeekBar s) {}
        });
        mBrightRow.addView(mBright, new LayoutParams(0, (int) (40 * mDp), 1));
        LayoutParams bl = new LayoutParams(-1, -2);
        bl.topMargin = (int) (8 * mDp);
        addView(mBrightRow, bl);

        // a "handle" at the bottom: a hint that the panel slides up
        mHandle = new View(c);
        LayoutParams hl = new LayoutParams((int) (32 * mDp), (int) (4 * mDp));
        hl.gravity = Gravity.CENTER_HORIZONTAL;
        hl.topMargin = (int) (8 * mDp);
        addView(mHandle, hl);
    }

    /** Room for the status bar when the system decorations are on the rear display. */
    public void setInsetTop(int top) {
        setPadding(mPad, mPad + top, mPad, (int) (6 * mDp));
    }

    /** Apply the theme, the tile set and the lock state. */
    public void setup(ThemeSpec t, Typeface font, BrightnessControl b, boolean locked,
            List<String> tiles, boolean torchWhenLocked, boolean brightness) {
        mTheme = t;
        mFont = font;
        mBrightness = b;
        mLocked = locked;
        GradientDrawable bg = new GradientDrawable();
        float r = 24 * mDp;
        bg.setCornerRadii(new float[] {0, 0, 0, 0, r, r, r, r});
        bg.setColor(t.color("qs.background", ThemeSpec.over(t.surface, t.background)));
        setBackground(bg);
        GradientDrawable hb = new GradientDrawable();
        hb.setCornerRadius(2 * mDp);
        hb.setColor(t.onSurfaceVariant);
        mHandle.setBackground(hb);

        mTileIds.clear();
        for (String id : tiles) {
            if (locked && !(RearKeys.TILE_TORCH.equals(id) && torchWhenLocked)) continue;
            if (RearKeys.TILE_TORCH.equals(id) && !mTorch.available()) continue;
            mTileIds.add(id);
        }
        buildTiles();
        mBrightRow.setVisibility(brightness ? VISIBLE : GONE);
        int slider = t.color("qs.slider", t.primary);
        mBright.setProgressTintList(ColorStateList.valueOf(slider));
        mBright.setThumbTintList(ColorStateList.valueOf(slider));
        mBright.setProgressBackgroundTintList(ColorStateList.valueOf(t.onSurfaceVariant));
        mBrightIcon.setImageTintList(ColorStateList.valueOf(t.onSurfaceVariant));
        refresh();
    }

    private void buildTiles() {
        mTiles.removeAllViews();
        mTileViews.clear();
        LinearLayout row = null;
        for (int i = 0; i < mTileIds.size(); i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(getContext());
                row.setOrientation(HORIZONTAL);
                mTiles.addView(row, new LayoutParams(-1, (int) (64 * mDp)));
            }
            final String id = mTileIds.get(i);
            LinearLayout tile = new LinearLayout(getContext());
            tile.setOrientation(VERTICAL);
            tile.setGravity(Gravity.CENTER);
            ImageView icon = new ImageView(getContext());
            icon.setImageResource(iconFor(id, false));
            tile.addView(icon, new LayoutParams((int) (22 * mDp), (int) (22 * mDp)));
            TextView label = new TextView(getContext());
            label.setText(labelFor(id));
            label.setTextSize(11);
            label.setSingleLine(true);
            label.setTypeface(mFont);
            label.setGravity(Gravity.CENTER);
            LayoutParams ll = new LayoutParams(-2, -2);
            ll.topMargin = (int) (3 * mDp);
            tile.addView(label, ll);
            tile.setOnClickListener(v -> onTile(id));
            LayoutParams lp = new LayoutParams(0, -1, 1);
            int m = (int) (4 * mDp);
            lp.setMargins(m, m, m, m);
            row.addView(tile, lp);
            mTileViews.add(tile);
        }
        // an incomplete last row: empty slots so the tiles do not stretch
        if (row != null) {
            for (int i = mTileIds.size() % 3; i != 0 && i < 3; i++) {
                row.addView(new View(getContext()), new LayoutParams(0, -1, 1));
            }
        }
    }

    private static int labelFor(String id) {
        switch (id) {
            case RearKeys.TILE_TORCH:
                return R.string.qs_torch;
            case RearKeys.TILE_WIFI:
                return R.string.qs_wifi;
            case RearKeys.TILE_DND:
                return R.string.qs_dnd;
            case RearKeys.TILE_RINGER:
                return R.string.qs_ringer;
            case RearKeys.TILE_SLEEP:
                return R.string.qs_sleep;
            default:
                return R.string.qs_settings;
        }
    }

    private int iconFor(String id, boolean on) {
        switch (id) {
            case RearKeys.TILE_TORCH:
                return R.drawable.ic_qs_torch;
            case RearKeys.TILE_WIFI:
                return R.drawable.ic_qs_wifi;
            case RearKeys.TILE_DND:
                return R.drawable.ic_qs_dnd;
            case RearKeys.TILE_RINGER: {
                int m = mAudio.getRingerMode();
                return m == AudioManager.RINGER_MODE_VIBRATE ? R.drawable.ic_qs_vibrate
                        : m == AudioManager.RINGER_MODE_SILENT ? R.drawable.ic_qs_silent
                        : R.drawable.ic_qs_ringer;
            }
            case RearKeys.TILE_SLEEP:
                return R.drawable.ic_qs_sleep;
            default:
                return R.drawable.ic_qs_settings;
        }
    }

    private boolean isOn(String id) {
        switch (id) {
            case RearKeys.TILE_TORCH:
                return mTorch.isOn();
            case RearKeys.TILE_WIFI:
                return mWifi.isWifiEnabled();
            case RearKeys.TILE_DND:
                return mNm.getCurrentInterruptionFilter()
                        > NotificationManager.INTERRUPTION_FILTER_ALL;
            case RearKeys.TILE_RINGER:
                return mAudio.getRingerMode() == AudioManager.RINGER_MODE_NORMAL;
            default:
                return false;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mTorch.addListener(mRefresh);
    }

    @Override
    protected void onDetachedFromWindow() {
        mTorch.removeListener(mRefresh);
        super.onDetachedFromWindow();
    }

    /** Update the tile state and brightness. */
    public void refresh() {
        if (mTheme == null) return;
        int tile = mTheme.color("qs.tile", ThemeSpec.over(mTheme.surface, mTheme.background));
        int active = mTheme.color("qs.tileActive", mTheme.primary);
        int onTile = mTheme.color("qs.onTile", mTheme.onSurface);
        int onActive = mTheme.color("qs.onTileActive", mTheme.onPrimary);
        for (int i = 0; i < mTileViews.size(); i++) {
            String id = mTileIds.get(i);
            boolean on = isOn(id);
            LinearLayout v = mTileViews.get(i);
            GradientDrawable d = new GradientDrawable();
            d.setCornerRadius(18 * mDp);
            d.setColor(on ? active : tile);
            int fg = on ? onActive : onTile;
            v.setBackground(new RippleDrawable(ColorStateList.valueOf((fg & 0xFFFFFF) | 0x33000000),
                    d, null));
            // TalkBack: "Wi-Fi, On" (the sleep/settings tiles are buttons without a state)
            if (!RearKeys.TILE_SLEEP.equals(id) && !RearKeys.TILE_SETTINGS.equals(id)) {
                v.setStateDescription(getContext().getString(
                        on ? R.string.qs_state_on : R.string.qs_state_off));
            }
            ImageView icon = (ImageView) v.getChildAt(0);
            icon.setImageResource(iconFor(id, on));
            icon.setImageTintList(ColorStateList.valueOf(fg));
            ((TextView) v.getChildAt(1)).setTextColor(fg);
        }
        float b = mBrightness != null ? mBrightness.get() : Float.NaN;
        mBright.setEnabled(!Float.isNaN(b));
        if (!Float.isNaN(b)) mBright.setProgress(Math.round(b * 1000));
    }

    private void onTile(String id) {
        performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
        try {
            switch (id) {
                case RearKeys.TILE_TORCH:
                    mTorch.toggle();
                    break;
                case RearKeys.TILE_WIFI:
                    if (!mLocked) mWifi.setWifiEnabled(!mWifi.isWifiEnabled());
                    break;
                case RearKeys.TILE_DND:
                    if (!mLocked) {
                        boolean on = isOn(id);
                        mNm.setInterruptionFilter(on ? NotificationManager.INTERRUPTION_FILTER_ALL
                                : NotificationManager.INTERRUPTION_FILTER_PRIORITY);
                    }
                    break;
                case RearKeys.TILE_RINGER:
                    if (!mLocked) {
                        int m = mAudio.getRingerMode();
                        mAudio.setRingerMode(m == AudioManager.RINGER_MODE_NORMAL
                                ? AudioManager.RINGER_MODE_VIBRATE
                                : m == AudioManager.RINGER_MODE_VIBRATE
                                        ? AudioManager.RINGER_MODE_SILENT
                                        : AudioManager.RINGER_MODE_NORMAL);
                    }
                    break;
                case RearKeys.TILE_SLEEP:
                    mHost.sleepRear();
                    break;
                default:
                    mHost.openSettings();
                    break;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "tile " + id + ": " + e);
        }
        mMain.postDelayed(mRefresh, 400);
    }

    // dragging up closes

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return track(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        track(ev);
        return true;
    }

    private boolean track(MotionEvent ev) {
        if (mVelocity == null) mVelocity = VelocityTracker.obtain();
        Sheet.addRawMovement(mVelocity, ev);
        // screen coordinates: the panel itself moves under the finger while dragged
        float x = ev.getRawX(), y = ev.getRawY();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = x;
                mDownY = y;
                mDragging = false;
                return false;
            case MotionEvent.ACTION_MOVE: {
                float dy = y - mDownY, dx = x - mDownX;
                // the brightness slider is dragged sideways: leave it alone
                if (!mDragging && -dy > mSlop && -dy > Math.abs(dx) * 1.5f) {
                    mDragging = true;
                    mDownY = y;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    mHost.onPanelDragStart();
                }
                if (mDragging) mHost.onPanelDrag(y - mDownY);
                return mDragging;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean was = mDragging;
                mDragging = false;
                mVelocity.computeCurrentVelocity(1000);
                float vy = mVelocity.getYVelocity();
                mVelocity.recycle();
                mVelocity = null;
                if (was) mHost.onPanelDragEnd(ev.getActionMasked() == MotionEvent.ACTION_UP
                        ? vy : 0f);
                return was;
            }
            default:
                return mDragging;
        }
    }
}
