package org.titan2.rear;

import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/**
 * The list of all apps over the home screen. Opened with a swipe up (follows the
 * finger), closed with a swipe down when the list is scrolled to the top, or with "Back".
 */
public class DrawerView extends FrameLayout {
    interface Host {
        void onDrawerDragStart();

        /** dy > 0: down (towards closing) */
        void onDrawerDrag(float dy);

        void onDrawerDragEnd(float vy);
    }

    final AppGridView grid;
    private final Host mHost;
    private final int mSlop;
    private float mDownX, mDownY;
    private boolean mDragging;
    private VelocityTracker mVelocity;

    public DrawerView(Context c, Host host) {
        super(c);
        mHost = host;
        mSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        grid = new AppGridView(c);
        int pad = (int) (8 * c.getResources().getDisplayMetrics().density);
        grid.setPadding(pad, pad * 2, pad, pad);
        addView(grid, new LayoutParams(-1, -1));
        setClickable(true);
    }

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
        // screen coordinates: the drawer itself moves under the finger while dragged
        float x = ev.getRawX(), y = ev.getRawY();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = x;
                mDownY = y;
                mDragging = false;
                return false;
            case MotionEvent.ACTION_MOVE: {
                float dy = y - mDownY, dx = x - mDownX;
                if (!mDragging && dy > mSlop && dy > Math.abs(dx) && grid.atTop()) {
                    mDragging = true;
                    mDownY = y;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    mHost.onDrawerDragStart();
                }
                if (mDragging) mHost.onDrawerDrag(y - mDownY);
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
                if (was) mHost.onDrawerDragEnd(ev.getActionMasked() == MotionEvent.ACTION_UP
                        ? vy : 0f);
                return was;
            }
            default:
                return mDragging;
        }
    }
}
