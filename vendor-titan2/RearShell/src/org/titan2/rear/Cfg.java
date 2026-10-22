package org.titan2.rear;

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import org.titan2.rear.common.Effective;
import org.titan2.rear.common.RearContract;
import org.titan2.rear.common.RearKeys;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shell settings from the Titan2Parts provider (org.titan2.parts.rear). The shell has no
 * settings of its own: only a value cache and a change observer here. Without the provider (Titan2Parts
 * not installed, no permission): the defaults from RearKeys.
 */
public final class Cfg implements Effective.Values {
    private static final String TAG = "RearShell";
    private static Cfg sInstance;

    public interface Listener {
        /** themeChanged: the theme or its content changed */
        void onConfigChanged(boolean themeChanged);
    }

    private final Context mContext;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final List<Listener> mListeners = new ArrayList<>();
    private Map<String, String> mValues = Collections.emptyMap();
    private boolean mPendingTheme;

    private final Runnable mNotify = () -> {
        boolean theme = mPendingTheme;
        mPendingTheme = false;
        String oldTheme = get(RearKeys.THEME);
        reload();
        theme |= !oldTheme.equals(get(RearKeys.THEME));
        for (Listener l : new ArrayList<>(mListeners)) {
            l.onConfigChanged(theme);
        }
    };

    private final ContentObserver mSettingsObserver = new ContentObserver(mMain) {
        @Override
        public void onChange(boolean selfChange) {
            schedule(false);
        }
    };

    private final ContentObserver mThemesObserver = new ContentObserver(mMain) {
        @Override
        public void onChange(boolean selfChange) {
            schedule(true);
        }
    };

    private Cfg(Context c) {
        mContext = c.getApplicationContext();
        try {
            mContext.getContentResolver().registerContentObserver(RearContract.SETTINGS, true,
                    mSettingsObserver);
            mContext.getContentResolver().registerContentObserver(RearContract.THEMES, true,
                    mThemesObserver);
        } catch (RuntimeException e) {
            Log.w(TAG, "observer: " + e);
        }
        reload();
    }

    public static synchronized Cfg get(Context c) {
        if (sInstance == null) sInstance = new Cfg(c);
        return sInstance;
    }

    public void addListener(Listener l) {
        mListeners.add(l);
    }

    public void removeListener(Listener l) {
        mListeners.remove(l);
    }

    private void schedule(boolean theme) {
        mPendingTheme |= theme;
        mMain.removeCallbacks(mNotify);
        mMain.postDelayed(mNotify, 80);
    }

    /** Re-read all keys (one provider query). */
    public void reload() {
        Map<String, String> m = new HashMap<>();
        try (Cursor c = mContext.getContentResolver().query(RearContract.SETTINGS, null, null,
                null, null)) {
            if (c != null) {
                int k = c.getColumnIndex(RearContract.COL_KEY);
                int v = c.getColumnIndex(RearContract.COL_VALUE);
                while (c.moveToNext()) {
                    m.put(c.getString(k), c.getString(v));
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "settings provider: " + e);
        }
        mValues = m;
    }

    @Override
    public String get(String key) {
        String v = mValues.get(key);
        return v != null ? v : RearKeys.def(key);
    }

    public boolean bool(String key) {
        return RearKeys.parseBool(get(key), RearKeys.parseBool(RearKeys.def(key), false));
    }

    public List<String> list(String key) {
        String v = get(key);
        List<String> out = new ArrayList<>();
        if (TextUtils.isEmpty(v)) return out;
        for (String s : v.split(",")) {
            if (!s.trim().isEmpty()) out.add(s.trim());
        }
        return out;
    }

    /** Write (only keys allowed for the shell, see RearContract.SHELL_WRITABLE). */
    public void put(String key, String value) {
        Map<String, String> m = new HashMap<>(mValues);
        m.put(key, value);
        mValues = m;
        Bundle b = new Bundle();
        b.putString(RearContract.EXTRA_VALUE, value);
        try {
            mContext.getContentResolver().call(RearContract.BASE, RearContract.METHOD_PUT, key, b);
        } catch (RuntimeException e) {
            Log.w(TAG, "put " + key + ": " + e);
        }
    }

    public void putList(String key, List<String> values) {
        put(key, TextUtils.join(",", values));
    }
}
