package org.titan2.rear;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.LauncherApps;
import android.graphics.Insets;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.format.DateFormat;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import org.titan2.rear.common.Effective;
import org.titan2.rear.common.RearKeys;
import org.titan2.rear.common.ThemeSpec;
import org.titan2.rear.common.Wallpapers;
import org.titan2.rear.common.faces.ClockFace;
import org.titan2.rear.common.faces.Faces;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Rear display home screen (SECONDARY_HOME). Pages: clock -> favorites (per settings).
 * On top are sheets that follow the finger: the app list (swipe up), the quick panel
 * (swipe down), PIN/pattern entry (swipe up on the lock screen). All settings and the theme come from
 * the Titan2Parts provider (Cfg, ThemeStore); there is no settings screen of its own.
 *
 * Lock: it is shown over the keyguard too (showWhenLocked), but then only the clock to the extent of
 * lock_mode; no favorites, app list or toggles that change the phone state.
 *
 * The rear display normally has no system decorations (no status bar, navigation bar, wallpaper
 * or keyguard presentation): the shell draws its own background and lock screen. When the user
 * turns them on (titan2_rear_system_decors), the system bars' insets are applied as padding.
 * The activity is exported (SECONDARY_HOME); started on the main display or into another app's
 * task it closes at once, so another app cannot put it (showWhenLocked) over the main keyguard.
 */
public class RearHomeActivity extends Activity implements PagerView.Listener,
        QuickPanel.Host, DrawerView.Host, BouncerView.Host, ClockView.Actions, Cfg.Listener,
        RearNotificationListener.Callback {

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Cfg mCfg;
    private KeyguardManager mKm;
    private LauncherApps mLauncherApps;
    private float mDp;

    private FrameLayout mRoot;
    private ImageView mWallImage;
    private View mDim;
    private FrameLayout mContent;
    private PagerView mPager;
    private PageIndicator mIndicator;
    private ClockView mClock;
    private View mBlack;
    private DrawerView mDrawer;
    private View mScrim;
    private QuickPanel mPanel;
    private BouncerView mBouncer;
    private TextView mHint;

    private Sheet mDrawerSheet, mPanelSheet, mBouncerSheet;

    private ThemeStore.Loaded mTheme;
    private boolean mLocked;
    private String mLockMode = RearKeys.LOCK_CLOCK_COUNT;
    private List<Apps.Entry> mApps = new ArrayList<>();
    private int mBattery = -1;
    private boolean mCharging;
    private boolean mFirstBuild = true;
    /** started on the main display / in a foreign task: closed in onCreate, nothing registered */
    private boolean mAbandoned;
    private DisplayManager mDm;
    /** all apps (unfiltered by the lock): reloaded only when packages or the filter/sort change */
    private List<Apps.Entry> mAllApps;
    private String mAppsKey;
    private Insets mInsets = Insets.NONE;

    // the current vertical gesture from the pager
    private static final int V_NONE = 0, V_DRAWER = 1, V_PANEL = 2, V_BOUNCER = 3, V_ACTION = 4;
    private int mVTarget = V_NONE;
    private String mVAction;
    private float mVStart;

    private final Runnable mRebuild = () -> rebuild(false);
    private final Runnable mHideHint = this::hideHint;

    private final OnBackInvokedCallback mBack = () -> {
        if (mBouncerSheet.isActive()) {
            mBouncerSheet.animateTo(false);
        } else if (mPanelSheet.isActive()) {
            mPanelSheet.animateTo(false);
        } else if (mDrawerSheet.isActive()) {
            mDrawerSheet.animateTo(false);
        } else if (mPager.getPage() != 0) {
            mPager.setPage(0, true);
        }
        // "Back" does nothing on the clock: this is the home screen
    };

    private final BroadcastReceiver mBatteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, 0);
            mBattery = level < 0 || scale <= 0 ? -1 : level * 100 / scale;
            mCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
            if (mClock != null) mClock.setBattery(mBattery, mCharging);
        }
    };

    /** USER_PRESENT / SCREEN_OFF / SCREEN_ON: a fallback path for the lock listener */
    private final BroadcastReceiver mLockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (Intent.ACTION_USER_UNLOCKED.equals(a)
                    || Intent.ACTION_MANAGED_PROFILE_AVAILABLE.equals(a)
                    || Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE.equals(a)
                    || Intent.ACTION_MANAGED_PROFILE_UNLOCKED.equals(a)) {
                // the app list differs once credential storage / the work profile opens
                appsChanged();
                return;
            }
            if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                // the screen went off: close and erase the PIN entry, remove the sheets
                closeAll(false);
            }
            checkLock();
            // the lock screen engages with a delay after SCREEN_OFF
            mHandler.postDelayed(RearHomeActivity.this::checkLock, 1000);
        }
    };

    private final KeyguardManager.KeyguardLockedStateListener mKeyguardListener =
            locked -> checkLock();

    /**
     * The rear display has its own power group: it can go off alone, with no SCREEN_OFF
     * broadcast (and the activity is not necessarily stopped at once). Erase a typed PIN then.
     */
    private final DisplayManager.DisplayListener mDisplayListener =
            new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int id) {}

                @Override
                public void onDisplayRemoved(int id) {}

                @Override
                public void onDisplayChanged(int id) {
                    Display d = getDisplay();
                    if (d != null && d.getDisplayId() == id && d.getState() != Display.STATE_ON) {
                        closeAll(false);
                    }
                }
            };

    private final LauncherApps.Callback mAppsCallback = new LauncherApps.Callback() {
        @Override
        public void onPackageRemoved(String pkg, UserHandle user) {
            appsChanged();
        }

        @Override
        public void onPackageAdded(String pkg, UserHandle user) {
            appsChanged();
        }

        @Override
        public void onPackageChanged(String pkg, UserHandle user) {
            appsChanged();
        }

        @Override
        public void onPackagesAvailable(String[] pkgs, UserHandle user, boolean replacing) {
            appsChanged();
        }

        @Override
        public void onPackagesUnavailable(String[] pkgs, UserHandle user, boolean replacing) {
            appsChanged();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (misplaced()) {
            // only the system starts the rear home, and never on the main display
            mAbandoned = true;
            finish();
            return;
        }
        setShowWhenLocked(true);
        mCfg = Cfg.get(this);
        mKm = getSystemService(KeyguardManager.class);
        mLauncherApps = getSystemService(LauncherApps.class);
        mDp = getResources().getDisplayMetrics().density;
        buildViews();
        setContentView(mRoot);

        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, mBack);
        mCfg.addListener(this);
        registerReceiver(mBatteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        IntentFilter lf = new IntentFilter(Intent.ACTION_USER_PRESENT);
        lf.addAction(Intent.ACTION_SCREEN_OFF);
        lf.addAction(Intent.ACTION_SCREEN_ON);
        lf.addAction(Intent.ACTION_USER_UNLOCKED);
        lf.addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE);
        lf.addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE);
        lf.addAction(Intent.ACTION_MANAGED_PROFILE_UNLOCKED);
        registerReceiver(mLockReceiver, lf);
        try {
            mKm.addKeyguardLockedStateListener(getMainExecutor(), mKeyguardListener);
        } catch (SecurityException e) {
            // without SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE the broadcasts remain
        }
        mLauncherApps.registerCallback(mAppsCallback, mHandler);
        mDm = getSystemService(DisplayManager.class);
        mDm.registerDisplayListener(mDisplayListener, mHandler);
        RearNotificationListener.addCallback(this);
        // the binding to the SystemUI unlock service lasts while the process lives (see UnlockClient)
        UnlockClient.get(this).bind();

        startService(new Intent(this, RearGestureService.class));
        mLocked = mKm.isKeyguardLocked();
        mCfg.reload();
        rebuild(true);
    }

    /**
     * On the main display, or not the root of its own (home) task: started by another app into
     * its task, which would give a second home over the keyguard.
     */
    private boolean misplaced() {
        Display d = getDisplay();
        return d == null || d.getDisplayId() == Display.DEFAULT_DISPLAY || !isTaskRoot();
    }

    private void buildViews() {
        mRoot = new FrameLayout(this);
        // system bars exist only with titan2_rear_system_decors; without them the insets are 0
        mRoot.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets i = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout());
            if (!i.equals(mInsets)) {
                mInsets = i;
                applyInsets();
            }
            return insets;
        });
        mWallImage = new ImageView(this);
        mWallImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mRoot.addView(mWallImage, new FrameLayout.LayoutParams(-1, -1));
        mDim = new View(this);
        mRoot.addView(mDim, new FrameLayout.LayoutParams(-1, -1));

        mContent = new FrameLayout(this);
        mPager = new PagerView(this);
        mPager.setListener(this);
        mContent.addView(mPager, new FrameLayout.LayoutParams(-1, -1));
        mIndicator = new PageIndicator(this);
        FrameLayout.LayoutParams il = new FrameLayout.LayoutParams(-1, (int) (14 * mDp),
                Gravity.BOTTOM);
        il.bottomMargin = (int) (4 * mDp);
        mContent.addView(mIndicator, il);
        mRoot.addView(mContent, new FrameLayout.LayoutParams(-1, -1));
        mClock = new ClockView(this);
        mClock.setActions(this);

        // "screen off" on the lock screen (lock_mode=off): touches pass to the pager
        mBlack = new View(this);
        mBlack.setBackgroundColor(0xFF000000);
        mBlack.setVisibility(View.GONE);
        mRoot.addView(mBlack, new FrameLayout.LayoutParams(-1, -1));

        mDrawer = new DrawerView(this, this);
        mDrawer.setVisibility(View.GONE);
        mDrawer.grid.setOnItemClickListener((p, v, pos, id) -> launch(mDrawer.grid.entry(pos), v));
        mDrawer.grid.setOnItemLongClickListener((p, v, pos, id) -> {
            toggleFavorite(mDrawer.grid.entry(pos));
            return true;
        });
        mRoot.addView(mDrawer, new FrameLayout.LayoutParams(-1, -1));

        mScrim = new View(this);
        mScrim.setVisibility(View.GONE);
        mScrim.setOnClickListener(v -> mPanelSheet.animateTo(false));
        mRoot.addView(mScrim, new FrameLayout.LayoutParams(-1, -1));
        mPanel = new QuickPanel(this, this);
        mPanel.setVisibility(View.GONE);
        mRoot.addView(mPanel, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        mBouncer = new BouncerView(this, this);
        mBouncer.setVisibility(View.GONE);
        mRoot.addView(mBouncer, new FrameLayout.LayoutParams(-1, -1));

        mHint = new TextView(this);
        mHint.setVisibility(View.GONE);
        mHint.setTextSize(14);
        mHint.setGravity(Gravity.CENTER);
        int pad = (int) (14 * mDp);
        mHint.setPadding(pad, pad / 2, pad, pad / 2);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(-2, -2,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hl.bottomMargin = (int) (28 * mDp);
        hl.leftMargin = hl.rightMargin = (int) (16 * mDp);
        mRoot.addView(mHint, hl);

        mDrawerSheet = new Sheet(new Sheet.Target() {
            @Override
            public void onProgress(float p) {
                applyDrawer(p);
            }

            @Override
            public void onSettled(boolean open) {
                if (!open) {
                    mDrawer.setVisibility(View.GONE);
                    mDrawer.grid.setSelection(0);
                }
            }
        });
        mPanelSheet = new Sheet(new Sheet.Target() {
            @Override
            public void onProgress(float p) {
                applyPanel(p);
            }

            @Override
            public void onSettled(boolean open) {
                if (!open) {
                    mPanel.setVisibility(View.GONE);
                    mScrim.setVisibility(View.GONE);
                }
            }
        });
        mBouncerSheet = new Sheet(new Sheet.Target() {
            @Override
            public void onProgress(float p) {
                applyBouncer(p);
            }

            @Override
            public void onSettled(boolean open) {
                if (open) {
                    mBouncer.start();
                } else {
                    mBouncer.stop();
                    mBouncer.setVisibility(View.GONE);
                }
            }
        });
        // finger travel for the sheets from the screen size
        mRoot.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            mDrawerSheet.setDistance(h * 0.55f);
            mBouncerSheet.setDistance(h * 0.5f);
            mPanelSheet.setDistance(panelDistance());
            float fling = 600 * mDp;
            mDrawerSheet.setFlingVelocity(fling);
            mBouncerSheet.setFlingVelocity(fling);
            mPanelSheet.setFlingVelocity(fling);
        });
    }

    /** The wallpaper stays full-bleed; content, sheets and the hint keep clear of the bars. */
    private void applyInsets() {
        Insets i = mInsets;
        mContent.setPadding(i.left, i.top, i.right, i.bottom);
        mDrawer.setPadding(i.left, i.top, i.right, i.bottom);
        mBouncer.setPadding(i.left, i.top, i.right, i.bottom);
        mPanel.setInsetTop(i.top);
        FrameLayout.LayoutParams hl = (FrameLayout.LayoutParams) mHint.getLayoutParams();
        hl.bottomMargin = (int) (28 * mDp) + i.bottom;
        mHint.setLayoutParams(hl);
    }

    // ------------------------------------------------------------ sheet progress -> View properties

    private float drawerDistance() {
        return mRoot.getHeight() * 0.55f;
    }

    private void applyDrawer(float p) {
        if (p > 0f && mDrawer.getVisibility() != View.VISIBLE) mDrawer.setVisibility(View.VISIBLE);
        float d = drawerDistance();
        mDrawer.setTranslationY((1f - p) * d);
        mDrawer.setAlpha(Math.min(1f, p * 1.6f));
        float a = Motion.amount();
        mContent.setAlpha(1f - p);
        float s = 1f - 0.06f * a * p;
        mContent.setScaleX(s);
        mContent.setScaleY(s);
        mContent.setTranslationY(-p * d * 0.12f * a);
    }

    /** Finger travel for the panel: its height (40% of the screen until measured). */
    private float panelDistance() {
        return mPanel.getHeight() > 0 ? mPanel.getHeight() : mRoot.getHeight() * 0.4f;
    }

    private void applyPanel(float p) {
        if (p > 0f && mPanel.getVisibility() != View.VISIBLE) {
            mPanel.setVisibility(View.VISIBLE);
            mScrim.setVisibility(View.VISIBLE);
        }
        float h = mPanel.getHeight() > 0 ? mPanel.getHeight() : mRoot.getHeight() * 0.5f;
        mPanel.setTranslationY(-(1f - p) * h);
        mPanel.setAlpha(Math.min(1f, p * 2.5f));
        mScrim.setAlpha(p);
    }

    private void applyBouncer(float p) {
        if (p > 0f && mBouncer.getVisibility() != View.VISIBLE) {
            mBouncer.setVisibility(View.VISIBLE);
        }
        float d = mRoot.getHeight() * 0.5f;
        mBouncer.setTranslationY((1f - p) * mRoot.getHeight() * 0.6f);
        mBouncer.setAlpha(Math.min(1f, p * 2f));
        float a = Motion.amount();
        // the clock moves up, shrinks and fades, like on the AOSP lock screen
        mContent.setAlpha(1f - Math.min(1f, p * 1.3f));
        float s = 1f - 0.1f * a * p;
        mContent.setScaleX(s);
        mContent.setScaleY(s);
        mContent.setTranslationY(-p * d * 0.3f * a);
        if (mBlack.getVisibility() == View.VISIBLE) mBlack.setAlpha(1f - p);
    }

    /** Close all sheets (animate=false: at once, e.g. the screen went off). */
    private void closeAll(boolean animate) {
        for (Sheet s : new Sheet[] {mBouncerSheet, mPanelSheet, mDrawerSheet}) {
            if (!s.isActive()) continue;
            if (animate) s.animateTo(false);
            else s.jumpTo(false);
        }
    }

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onDestroy() {
        if (mAbandoned) {
            super.onDestroy();
            return;
        }
        mHandler.removeCallbacksAndMessages(null);
        mBouncer.stop();
        mDm.unregisterDisplayListener(mDisplayListener);
        mCfg.removeListener(this);
        unregisterReceiver(mBatteryReceiver);
        unregisterReceiver(mLockReceiver);
        try {
            mKm.removeKeyguardLockedStateListener(mKeyguardListener);
        } catch (SecurityException ignored) {
        }
        mLauncherApps.unregisterCallback(mAppsCallback);
        RearNotificationListener.removeCallback(this);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (misplaced()) {
            // the rear display went away and the task was moved to the main display
            finish();
            return;
        }
        checkLock();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // left the screen: a typed PIN must not remain
        if (mBouncerSheet.isActive()) mBouncerSheet.jumpTo(false);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // the "Home" gesture: close the sheets, back to the clock
        closeAll(true);
        mPager.setPage(0, true);
    }

    private void checkLock() {
        boolean locked = mKm.isKeyguardLocked();
        if (locked == mLocked) return;
        mLocked = locked;
        if (!locked) {
            // unlocked: the content fades in, the PIN entry slides down
            boolean fromBouncer = mBouncerSheet.isActive();
            rebuild(false);
            if (fromBouncer) {
                mBouncerSheet.animateTo(false);
            } else {
                enterContent();
            }
        } else {
            closeAll(false);
            rebuild(false);
            enterContent();
        }
    }

    /** Smooth appearance of the content on a state change (not abruptly). */
    private void enterContent() {
        if (!Motion.enabled()) return;
        mContent.setAlpha(0f);
        mContent.setScaleX(0.94f);
        mContent.setScaleY(0.94f);
        mContent.setTranslationY(0f);
        mContent.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(Motion.dur(Motion.LONG))
                .setInterpolator(Motion.EMPHASIZED_DECELERATE).start();
    }

    private void appsChanged() {
        mAllApps = null;
        // an updated app may have a new icon under the same key
        Icons.clear();
        scheduleRebuild();
    }

    private void scheduleRebuild() {
        mHandler.removeCallbacks(mRebuild);
        mHandler.postDelayed(mRebuild, 150);
    }

    @Override
    public void onConfigChanged(boolean themeChanged) {
        if (themeChanged) {
            // the theme content changed: files (font, picture) may differ under the same JSON
            ThemeStore.invalidate();
            Icons.clear();
            mHandler.removeCallbacks(mRebuild);
            rebuild(true);
            if (!mFirstBuild) {
                // a new theme: a soft change (cross-fade of the whole screen)
                crossFadeTheme();
            }
        } else {
            scheduleRebuild();
        }
    }

    private void crossFadeTheme() {
        if (!Motion.enabled()) return;
        mRoot.setAlpha(0.2f);
        mRoot.animate().alpha(1f).setDuration(Motion.dur(Motion.LONG))
                .setInterpolator(Motion.STANDARD).start();
    }

    @Override
    public void onNotificationsChanged() {
        updateNotifications();
    }

    private int rearId() {
        Display d = getDisplay();
        if (d != null && d.getDisplayId() != Display.DEFAULT_DISPLAY) return d.getDisplayId();
        int id = RearDisplays.findId(this);
        return id != Display.INVALID_DISPLAY ? id : (d != null ? d.getDisplayId() : 0);
    }

    // ------------------------------------------------------------ building from the theme and settings

    private void rebuild(boolean themeChanged) {
        mHandler.removeCallbacks(mRebuild);
        mTheme = ThemeStore.load(this, mCfg.get(RearKeys.THEME), RearDisplays.WIDTH,
                RearDisplays.HEIGHT);
        ThemeSpec t = mTheme.spec;
        Motion.configure(Effective.animScale(t, mCfg), Effective.animStyle(t, mCfg));
        mLockMode = mCfg.get(RearKeys.LOCK_MODE);

        // background: color/gradient, image (blur via RenderEffect on the GPU), dimming
        mRoot.setBackground(Wallpapers.background(t));
        mWallImage.setImageBitmap(mTheme.wallpaper);
        mWallImage.setVisibility(mTheme.wallpaper != null ? View.VISIBLE : View.GONE);
        mWallImage.setRenderEffect(mTheme.wallpaper != null && t.blur > 0
                ? RenderEffect.createBlurEffect(t.blur * mDp, t.blur * mDp, Shader.TileMode.CLAMP)
                : null);
        mDim.setBackgroundColor(((int) (t.dim * 255) << 24));
        mDim.setVisibility(t.dim > 0 ? View.VISIBLE : View.GONE);

        // clock
        ClockFace prev = mClock.renderer().face;
        mClock.renderer().configure(t, mCfg, mTheme.font, mTheme.clockFont,
                DateFormat.is24HourFormat(this));
        if (mLocked && RearKeys.LOCK_CLOCK.equals(mLockMode)) {
            mClock.renderer().showBattery = false;
            mClock.renderer().showCount = false;
        }
        mClock.refresh(prev, !mFirstBuild);
        mClock.setBattery(mBattery, mCharging);
        boolean black = mLocked && RearKeys.LOCK_OFF.equals(mLockMode);
        mBlack.setVisibility(black ? View.VISIBLE : View.GONE);
        mBlack.setAlpha(1f);

        // pages: clock + favorites (only when unlocked)
        List<View> pages = new ArrayList<>();
        pages.add(mClock);
        AppGridView.Style style = AppGridView.style(t, mTheme.font,
                Effective.columns(t, mCfg), Effective.rows(t, mCfg), Effective.labels(t, mCfg),
                Effective.iconShape(t, mCfg), Effective.iconStyle(t, mCfg),
                Effective.iconSize(t, mCfg));
        if (!mLocked) {
            // loading every app label is slow: not on every lock/unlock or settings change
            Set<String> allowed = allowedPackages();
            String sort = mCfg.get(RearKeys.DRAWER_SORT);
            String key = (allowed == null ? "*" : new TreeSet<>(allowed).toString()) + "|" + sort;
            if (mAllApps == null || !key.equals(mAppsKey)) {
                mApps = Apps.load(this, allowed, sort);
                // before the first unlock only direct-boot apps are visible: do not keep that
                boolean unlocked = getSystemService(UserManager.class).isUserUnlocked();
                mAllApps = unlocked ? mApps : null;
                mAppsKey = key;
            } else {
                mApps = mAllApps;
            }
            if (mCfg.bool(RearKeys.HOME_FAVORITES)) {
                List<Apps.Entry> favs = Apps.favorites(mApps, mCfg.list(RearKeys.FAVORITES));
                int perPage = style.columns * style.rows;
                for (int start = 0; start < favs.size(); start += perPage) {
                    pages.add(grid(favs.subList(start, Math.min(favs.size(), start + perPage)),
                            style));
                }
            }
            mDrawer.grid.setup(new ArrayList<>(mApps), style);
        } else {
            mApps = new ArrayList<>();
            mDrawer.grid.setup(mApps, style);
        }
        mDrawer.setBackgroundColor(t.color("drawer.background",
                (t.background & 0xFFFFFF) | 0xF2000000));
        // ClockView moves between rebuilds: detach it from the old parent
        if (mClock.getParent() != null) ((ViewGroup) mClock.getParent()).removeView(mClock);
        mPager.setPages(pages.toArray(new View[0]));
        mPager.setPageTransforms(Motion.enabled());
        mIndicator.setup(t.pageIndicator ? pages.size() : 0,
                t.color("pager.indicator", (t.onSurfaceVariant & 0xFFFFFF) | 0x66000000),
                t.color("pager.indicatorActive", t.onSurface));
        mIndicator.setPosition(mPager.getPage());

        mPanel.setup(t, mTheme.font, BrightnessControl.forDisplay(this, rearId()), mLocked,
                mCfg.list(RearKeys.QS_TILES), mCfg.bool(RearKeys.LOCK_TORCH),
                mCfg.bool(RearKeys.QS_BRIGHTNESS));
        mScrim.setBackgroundColor(t.scrim);
        mBouncer.setup(t, mTheme.font);

        GradientDrawable hb = new GradientDrawable();
        hb.setCornerRadius(18 * mDp);
        hb.setColor(t.color("hint.background", ThemeSpec.over(t.surface, t.background)));
        mHint.setBackground(hb);
        mHint.setTextColor(t.color("hint.text", t.onSurface));
        mHint.setTypeface(mTheme.font);
        updateNotifications();
        mFirstBuild = false;
    }

    /** Packages allowed on the rear screen; null means all. */
    private Set<String> allowedPackages() {
        if (!RearKeys.APPS_SELECTED.equals(mCfg.get(RearKeys.APPS_FILTER))) return null;
        return new HashSet<>(mCfg.list(RearKeys.APPS_ALLOWED));
    }

    private AppGridView grid(List<Apps.Entry> items, AppGridView.Style style) {
        AppGridView g = new AppGridView(this);
        int pad = (int) (6 * mDp);
        g.setPadding(pad, pad, pad, (int) (18 * mDp));
        g.setup(new ArrayList<>(items), style);
        g.setOnItemClickListener((p, v, pos, id) -> launch(g.entry(pos), v));
        g.setOnItemLongClickListener((p, v, pos, id) -> {
            toggleFavorite(g.entry(pos));
            return true;
        });
        return g;
    }

    private void launch(Apps.Entry e, View source) {
        if (mLocked) {
            requestUnlock();
            return;
        }
        Apps.launch(this, e, rearId(), source);
    }

    /** Long press on an icon: add to favorites (at the end) / remove. */
    private void toggleFavorite(Apps.Entry e) {
        List<String> favs = mCfg.list(RearKeys.FAVORITES);
        boolean added = !favs.remove(e.key());
        if (added) favs.add(e.key());
        mClock.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        mCfg.putList(RearKeys.FAVORITES, favs);
        hint(getString(added ? R.string.fav_added : R.string.fav_removed, e.label));
    }

    private void updateNotifications() {
        int count = RearNotificationListener.count();
        List<RearNotificationListener.Item> items = null;
        if (mLocked && RearKeys.LOCK_CLOCK_TITLES.equals(mLockMode)) {
            items = RearNotificationListener.items(this, 6);
        }
        mClock.setUnread(count, items);
    }

    private void cycleFace() {
        String next = Faces.next(mClock.renderer().face.id());
        mCfg.put(RearKeys.CLOCK_FACE, next);
        ClockFace prev = mClock.renderer().face;
        mClock.renderer().setFace(Faces.create(next));
        mClock.refresh(prev, true);
    }

    // ------------------------------------------------------------ hint at the bottom

    private void hint(CharSequence text) {
        mHint.setText(text);
        mHandler.removeCallbacks(mHideHint);
        mHint.animate().cancel();
        if (mHint.getVisibility() != View.VISIBLE) {
            mHint.setVisibility(View.VISIBLE);
            mHint.setAlpha(0f);
            mHint.setTranslationY(10 * mDp);
        }
        mHint.animate().alpha(1f).translationY(0f).setDuration(Motion.dur(Motion.MEDIUM))
                .setInterpolator(Motion.EMPHASIZED_DECELERATE).start();
        mHandler.postDelayed(mHideHint, 2200);
    }

    private void hideHint() {
        mHint.animate().alpha(0f).translationY(6 * mDp).setDuration(Motion.dur(Motion.SHORT + 50))
                .setInterpolator(Motion.EMPHASIZED_ACCELERATE)
                .withEndAction(() -> mHint.setVisibility(View.GONE)).start();
    }

    /** On the lock screen: entry on the rear screen (if allowed) or a hint. */
    private void requestUnlock() {
        if (mCfg.bool(RearKeys.UNLOCK_FROM_REAR)) {
            mBouncerSheet.animateTo(true);
        } else {
            hint(getString(R.string.unlock_hint));
        }
    }

    // ------------------------------------------------------------ actions

    private void runHomeAction(String act) {
        switch (act) {
            case RearKeys.ACT_DRAWER:
                if (mLocked) requestUnlock();
                else mDrawerSheet.animateTo(true);
                break;
            case RearKeys.ACT_QUICK_PANEL:
                openPanel();
                break;
            case RearKeys.ACT_RECENTS:
                if (mLocked) requestUnlock();
                else RearActions.run(this, RearKeys.ACT_RECENTS, rearId());
                break;
            case RearKeys.ACT_NEXT_FACE:
                cycleFace();
                break;
            case RearKeys.ACT_TORCH:
                if (!mLocked || mCfg.bool(RearKeys.LOCK_TORCH)) Torch.get(this).toggle();
                else requestUnlock();
                break;
            case RearKeys.ACT_SLEEP:
                RearActions.sleep(this, rearId());
                break;
            case RearKeys.ACT_SETTINGS:
                openSettings();
                break;
            default:
                break;
        }
    }

    private void openPanel() {
        if (mLocked && RearKeys.LOCK_OFF.equals(mLockMode)) return;
        mPanel.refresh();
        mPanelSheet.animateTo(true);
    }

    private boolean lockedBlack() {
        return mLocked && RearKeys.LOCK_OFF.equals(mLockMode);
    }

    // ClockView.Actions

    @Override
    public void onClockTap(boolean doubleTap) {
        if (lockedBlack()) return;
        runHomeAction(mCfg.get(doubleTap ? RearKeys.CLOCK_DOUBLE_TAP : RearKeys.CLOCK_TAP));
    }

    @Override
    public void onClockLongPress() {
        if (lockedBlack()) return;
        runHomeAction(mCfg.get(RearKeys.CLOCK_LONG_PRESS));
    }

    @Override
    public boolean hasDoubleTap() {
        return !RearKeys.ACT_NONE.equals(mCfg.get(RearKeys.CLOCK_DOUBLE_TAP));
    }

    // ------------------------------------------------------------ PagerView.Listener

    @Override
    public void onPageSelected(int page) {
    }

    @Override
    public void onPageScrolled(float position) {
        mIndicator.setPosition(position);
    }

    @Override
    public boolean onVerticalStart(int page, boolean up, boolean fromTop) {
        if (mDrawerSheet.isActive() || mPanelSheet.isActive() || mBouncerSheet.isActive()) {
            return false;
        }
        boolean down = !up && (fromTop || mCfg.bool(RearKeys.HOME_SWIPE_DOWN_ANYWHERE));
        if (mLocked) {
            if (up) {
                if (mCfg.bool(RearKeys.UNLOCK_FROM_REAR)) {
                    mVTarget = V_BOUNCER;
                    mBouncerSheet.startDrag();
                    mVStart = mBouncerSheet.progress();
                    mBouncer.setup(mTheme.spec, mTheme.font);
                } else {
                    mVTarget = V_ACTION;
                    mVAction = null; // hint only
                }
                return true;
            }
            if (down && !RearKeys.LOCK_OFF.equals(mLockMode)) {
                startPanelDrag();
                return true;
            }
            return false;
        }
        String act = up ? mCfg.get(RearKeys.HOME_SWIPE_UP)
                : down ? mCfg.get(RearKeys.HOME_SWIPE_DOWN) : RearKeys.ACT_NONE;
        if (RearKeys.ACT_NONE.equals(act)) return false;
        if (up && RearKeys.ACT_DRAWER.equals(act)) {
            mVTarget = V_DRAWER;
            mDrawerSheet.startDrag();
            mVStart = mDrawerSheet.progress();
        } else if (!up && RearKeys.ACT_QUICK_PANEL.equals(act)) {
            startPanelDrag();
        } else {
            mVTarget = V_ACTION;
            mVAction = act;
        }
        return true;
    }

    private void startPanelDrag() {
        mVTarget = V_PANEL;
        mPanel.refresh();
        // the panel must be measured before the first drag frame
        mPanel.setVisibility(View.VISIBLE);
        mScrim.setVisibility(View.VISIBLE);
        applyPanel(mPanelSheet.progress());
        mPanelSheet.startDrag();
        mVStart = mPanelSheet.progress();
    }

    @Override
    public void onVerticalMove(float dy) {
        switch (mVTarget) {
            case V_DRAWER:
                mDrawerSheet.drag(mVStart, -dy);
                break;
            case V_BOUNCER:
                mBouncerSheet.drag(mVStart, -dy);
                break;
            case V_PANEL:
                mPanelSheet.setDistance(panelDistance());
                mPanelSheet.drag(mVStart, dy);
                break;
            default:
                break;
        }
    }

    @Override
    public void onVerticalEnd(float dy, float vy, boolean cancelled) {
        int target = mVTarget;
        mVTarget = V_NONE;
        float cancel = -100000f;
        switch (target) {
            case V_DRAWER:
                mDrawerSheet.release(cancelled ? cancel : -vy);
                break;
            case V_BOUNCER:
                mBouncerSheet.release(cancelled ? cancel : -vy);
                break;
            case V_PANEL:
                mPanelSheet.release(cancelled ? cancel : vy);
                break;
            case V_ACTION:
                if (cancelled || Math.abs(dy) < 40 * mDp) break;
                if (mVAction == null) {
                    hint(getString(R.string.unlock_hint));
                } else {
                    runHomeAction(mVAction);
                }
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------ DrawerView.Host

    @Override
    public void onDrawerDragStart() {
        mDrawerSheet.startDrag();
        mVStart = mDrawerSheet.progress();
    }

    @Override
    public void onDrawerDrag(float dy) {
        mDrawerSheet.drag(mVStart, -dy);
    }

    @Override
    public void onDrawerDragEnd(float vy) {
        mDrawerSheet.release(-vy);
    }

    // ------------------------------------------------------------ QuickPanel.Host

    @Override
    public void onPanelDragStart() {
        mPanelSheet.setDistance(panelDistance());
        mPanelSheet.startDrag();
        mVStart = mPanelSheet.progress();
    }

    @Override
    public void onPanelDrag(float dy) {
        mPanelSheet.drag(mVStart, dy);
    }

    @Override
    public void onPanelDragEnd(float vy) {
        mPanelSheet.release(vy);
    }

    /** Rear screen settings: Titan2Parts on the main screen. */
    @Override
    public void openSettings() {
        if (mLocked) {
            requestUnlock();
            return;
        }
        closeAll(true);
        if (RearActions.openSettings(this)) {
            hint(getString(R.string.settings_on_main));
        }
    }

    @Override
    public void sleepRear() {
        mPanelSheet.jumpTo(false);
        RearActions.sleep(this, rearId());
    }

    // ------------------------------------------------------------ BouncerView.Host

    @Override
    public void onBouncerDragStart() {
        mBouncerSheet.startDrag();
        mVStart = mBouncerSheet.progress();
    }

    @Override
    public void onBouncerDrag(float dy) {
        mBouncerSheet.drag(mVStart, -dy);
    }

    @Override
    public void onBouncerDragEnd(float vy) {
        mBouncerSheet.release(-vy);
    }

    @Override
    public void onBouncerMainScreen() {
        try {
            mKm.requestDismissKeyguard(this, null);
        } catch (RuntimeException ignored) {
        }
    }
}
