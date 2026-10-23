package org.titan2.rear.widget;

import android.content.Context;
import android.widget.FrameLayout;

import org.titan2.rear.Cfg;
import org.titan2.rear.common.RearKeys;

/**
 * A rear home page with a widget. The shell keeps one instance per widget across rebuilds
 * (theme, settings, lock changes), so state and listeners survive them.
 * Sensors, sessions and timers run only while the page is active: selected, and the shell is
 * started (the rear screen is on).
 */
public abstract class WidgetPage extends FrameLayout {
    protected WidgetStyle mStyle;
    protected Cfg mCfg;
    protected boolean mLocked;
    private boolean mActive;

    protected WidgetPage(Context c) {
        super(c);
    }

    /** The widget id (RearKeys.W_*). */
    public abstract String id();

    /** Theme, settings or lock state changed: rebuild the content. */
    public final void setup(WidgetStyle style, Cfg cfg, boolean locked) {
        mStyle = style;
        mCfg = cfg;
        mLocked = locked;
        removeAllViews();
        build();
        if (mActive) onRefresh();
    }

    protected abstract void build();

    public final void setActive(boolean active) {
        if (active == mActive || mStyle == null) return;
        mActive = active;
        if (active) {
            onActive();
            onRefresh();
        } else {
            onInactive();
        }
    }

    public boolean isActive() {
        return mActive;
    }

    /** Start listening (sensors, media sessions, ticks). */
    protected void onActive() {}

    protected void onInactive() {}

    /** Show the current values. */
    protected void onRefresh() {}

    /** The page is dropped for good (the widget was turned off). */
    public void release() {
        setActive(false);
    }

    protected int px(float dp) {
        return mStyle.px(dp);
    }

    /** Pocket protection is on for this build of the page (locked and enabled). */
    protected boolean guarded() {
        return mLocked && mCfg.bool(RearKeys.LOCK_HOLD);
    }

    /** A widget button: a tap, or a hold while guarded (see Press). */
    protected void press(android.view.View v, Runnable action) {
        Press.bind(v, guarded(), mStyle, action);
    }
}
