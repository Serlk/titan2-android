package org.titan2.rear;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.UserHandle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.view.WindowInsets;
import android.widget.TextView;
import android.window.OnBackInvokedDispatcher;

import org.titan2.rear.common.RearKeys;
import org.titan2.rear.common.ThemeSpec;
import org.titan2.rear.common.Wallpapers;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple "Recents" of the rear display: tasks currently on it (TaskInfo.displayId).
 * Tap returns to a task, long press closes it. Main screen tasks are not shown.
 */
public class RearRecentsActivity extends Activity {
    private static final String TAG = "RearShell";

    private final List<ActivityManager.RecentTaskInfo> mTasks = new ArrayList<>();
    private ListView mList;
    private TextView mEmpty;
    private ThemeSpec mTheme;
    private android.graphics.Typeface mFont;
    private float mDp;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        mDp = getResources().getDisplayMetrics().density;
        ThemeStore.Loaded l = ThemeStore.load(this, Cfg.get(this).get(RearKeys.THEME),
                RearDisplays.WIDTH, RearDisplays.HEIGHT);
        mTheme = l.spec;
        mFont = l.font;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Wallpapers.background(mTheme));
        // system bars only with titan2_rear_system_decors; without them the insets are 0
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets i = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout());
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return insets;
        });
        TextView title = new TextView(this);
        title.setText(R.string.recents_title);
        title.setTypeface(mFont);
        title.setTextColor(mTheme.primary);
        title.setTextSize(18);
        int pad = (int) (12 * mDp);
        title.setPadding(pad, pad, pad, pad);
        root.addView(title);
        mEmpty = new TextView(this);
        mEmpty.setText(R.string.recents_empty);
        mEmpty.setTextColor(mTheme.onSurfaceVariant);
        mEmpty.setGravity(Gravity.CENTER);
        root.addView(mEmpty, new LinearLayout.LayoutParams(-1, -2));
        mList = new ListView(this);
        mList.setDivider(null);
        mList.setAdapter(mAdapter);
        mList.setOnItemClickListener((p, v, pos, id) -> open(mTasks.get(pos)));
        mList.setOnItemLongClickListener((p, v, pos, id) -> {
            remove(mTasks.get(pos));
            return true;
        });
        root.addView(mList, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        // enter: the list slides up from the bottom and fades in (M3 emphasized decelerate)
        if (Motion.enabled()) {
            root.setAlpha(0f);
            root.setTranslationY(24 * mDp);
            root.animate().alpha(1f).translationY(0f).setDuration(Motion.dur(Motion.MEDIUM))
                    .setInterpolator(Motion.EMPHASIZED_DECELERATE).start();
        }
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::finish);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    @Override
    protected void onPause() {
        super.onPause();
        finish(); // "Recents" does not stay in the stack
    }

    private int displayId() {
        return getDisplay() != null ? getDisplay().getDisplayId() : RearDisplays.findId(this);
    }

    private void reload() {
        mTasks.clear();
        int display = displayId();
        try {
            List<ActivityManager.RecentTaskInfo> all = ActivityTaskManager.getInstance()
                    .getRecentTasks(30, ActivityManager.RECENT_IGNORE_UNAVAILABLE,
                            UserHandle.myUserId());
            for (ActivityManager.RecentTaskInfo t : all) {
                ComponentName cn = t.baseActivity != null ? t.baseActivity
                        : t.baseIntent.getComponent();
                if (t.displayId != display || cn == null
                        || cn.getPackageName().equals(getPackageName())) {
                    continue;
                }
                mTasks.add(t);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "getRecentTasks: " + e);
        }
        mEmpty.setVisibility(mTasks.isEmpty() ? View.VISIBLE : View.GONE);
        mAdapter.notifyDataSetChanged();
    }

    private void open(ActivityManager.RecentTaskInfo t) {
        ActivityOptions o = ActivityOptions.makeBasic().setLaunchDisplayId(displayId());
        try {
            ActivityTaskManager.getService().startActivityFromRecents(t.taskId, o.toBundle());
        } catch (Exception e) {
            Log.w(TAG, "startActivityFromRecents: " + e);
        }
        finish();
    }

    private void remove(ActivityManager.RecentTaskInfo t) {
        try {
            ActivityTaskManager.getService().removeTask(t.taskId);
        } catch (Exception e) {
            Log.w(TAG, "removeTask: " + e);
        }
        reload();
    }

    private final BaseAdapter mAdapter = new BaseAdapter() {
        @Override
        public int getCount() {
            return mTasks.size();
        }

        @Override
        public Object getItem(int p) {
            return mTasks.get(p);
        }

        @Override
        public long getItemId(int p) {
            return mTasks.get(p).taskId;
        }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout row = (LinearLayout) convert;
            if (row == null) {
                row = new LinearLayout(RearRecentsActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                int pad = (int) (10 * mDp);
                row.setPadding(pad, pad, pad, pad);
                row.addView(new ImageView(RearRecentsActivity.this),
                        new LinearLayout.LayoutParams((int) (40 * mDp), (int) (40 * mDp)));
                TextView tv = new TextView(RearRecentsActivity.this);
                tv.setTextSize(16);
                tv.setPadding(pad, 0, 0, 0);
                row.addView(tv);
            }
            ActivityManager.RecentTaskInfo t = mTasks.get(pos);
            ComponentName cn = t.baseActivity != null ? t.baseActivity
                    : t.baseIntent.getComponent();
            PackageManager pm = getPackageManager();
            CharSequence label = cn.getPackageName();
            Drawable icon = null;
            try {
                label = pm.getActivityInfo(cn, 0).loadLabel(pm);
                icon = pm.getActivityIcon(cn);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            if (t.taskDescription != null && t.taskDescription.getLabel() != null) {
                label = t.taskDescription.getLabel();
            }
            ((ImageView) row.getChildAt(0)).setImageDrawable(icon);
            TextView tv = (TextView) row.getChildAt(1);
            tv.setText(label);
            tv.setTextColor(mTheme.color("label", mTheme.onSurface));
            tv.setTypeface(mFont);
            return row;
        }
    };
}
