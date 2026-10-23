package org.titan2.rear.widget;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.titan2.rear.R;
import org.titan2.rear.common.RearKeys;

import java.util.List;

/**
 * Music: the active media session (MEDIA_CONTENT_CONTROL, no notification access needed):
 * cover, title, artist, app, progress with seeking, previous / play-pause / next.
 */
public class MusicPage extends WidgetPage {
    private static final String TAG = "RearShell";
    private static final long PROGRESS_TICK_MS = 500;

    private final MediaSessionManager mMsm;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private MediaController mController;

    private ImageView mArt;
    private TextView mTitle, mArtist, mApp, mEmpty, mPos, mDur;
    private ProgressBar mProgress;
    private ImageView mPlay;
    private View mContent;

    private final MediaSessionManager.OnActiveSessionsChangedListener mSessions =
            controllers -> pick(controllers);

    private final MediaController.Callback mCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            onRefresh();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            onRefresh();
        }

        @Override
        public void onSessionDestroyed() {
            pick(sessions());
        }
    };

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            if (isActive() && isPlaying()) mHandler.postDelayed(this, PROGRESS_TICK_MS);
        }
    };

    public MusicPage(Context c) {
        super(c);
        mMsm = c.getSystemService(MediaSessionManager.class);
    }

    @Override
    public String id() {
        return RearKeys.W_MUSIC;
    }

    @Override
    protected void build() {
        Context c = getContext();
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(px(14), px(10), px(14), px(18));

        mArt = new ImageView(c);
        mArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mArt.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), px(14));
            }
        });
        mArt.setClipToOutline(true);
        mArt.setBackground(mStyle.cardBackground(14));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(px(88), px(88));
        ap.bottomMargin = px(8);
        col.addView(mArt, ap);

        mTitle = line(c, 15, mStyle.text, true);
        mTitle.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        mTitle.setMarqueeRepeatLimit(-1);
        mTitle.setSelected(true);
        col.addView(mTitle, wrap());
        mArtist = line(c, 12, mStyle.textDim, false);
        col.addView(mArtist, wrap());
        mApp = line(c, 10, mStyle.textDim, false);
        col.addView(mApp, wrap());

        LinearLayout bar = new LinearLayout(c);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        mPos = line(c, 10, mStyle.textDim, false);
        mDur = line(c, 10, mStyle.textDim, false);
        mProgress = new ProgressBar(c);
        bar.addView(mPos);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(0, px(24), 1f);
        pp.leftMargin = pp.rightMargin = px(6);
        bar.addView(mProgress, pp);
        bar.addView(mDur);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = px(6);
        col.addView(bar, bp);

        LinearLayout buttons = new LinearLayout(c);
        buttons.setGravity(Gravity.CENTER);
        ImageView prev = button(c, android.R.drawable.ic_media_previous, false);
        mPlay = button(c, android.R.drawable.ic_media_play, true);
        ImageView next = button(c, android.R.drawable.ic_media_next, false);
        press(prev, () -> {
            if (mController != null) mController.getTransportControls().skipToPrevious();
        });
        press(mPlay, this::togglePlay);
        press(next, () -> {
            if (mController != null) mController.getTransportControls().skipToNext();
        });
        buttons.addView(prev, new LinearLayout.LayoutParams(px(44), px(44)));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(px(56), px(56));
        mp.leftMargin = mp.rightMargin = px(16);
        buttons.addView(mPlay, mp);
        buttons.addView(next, new LinearLayout.LayoutParams(px(44), px(44)));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = px(6);
        col.addView(buttons, blp);

        mContent = col;
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        addView(col, cl);

        mEmpty = line(c, 14, mStyle.textDim, false);
        mEmpty.setGravity(Gravity.CENTER);
        mEmpty.setSingleLine(false);
        mEmpty.setText(R.string.widget_music_none);
        addView(mEmpty, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        mArt.setVisibility(mCfg.bool(RearKeys.MUSIC_ART) ? VISIBLE : GONE);
        mArtist.setVisibility(mCfg.bool(RearKeys.MUSIC_ARTIST) ? VISIBLE : GONE);
        mApp.setVisibility(mCfg.bool(RearKeys.MUSIC_APP) ? VISIBLE : GONE);
        bar.setVisibility(mCfg.bool(RearKeys.MUSIC_PROGRESS) ? VISIBLE : GONE);
    }

    private TextView line(Context c, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        mStyle.text(t, sp, color, bold);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private ImageView button(Context c, int icon, boolean main) {
        ImageView b = new ImageView(c);
        b.setImageResource(icon);
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = px(main ? 14 : 10);
        b.setPadding(pad, pad, pad, pad);
        b.setColorFilter(main ? mStyle.theme.onPrimary : mStyle.onCard);
        b.setBackground(mStyle.pill(main ? mStyle.accent : mStyle.card));
        return b;
    }

    // ------------------------------------------------------------ sessions

    private List<MediaController> sessions() {
        try {
            return mMsm.getActiveSessions(null);
        } catch (SecurityException e) {
            Log.w(TAG, "media sessions: " + e);
            return java.util.Collections.emptyList();
        }
    }

    @Override
    protected void onActive() {
        try {
            mMsm.addOnActiveSessionsChangedListener(mSessions, null, mHandler);
        } catch (SecurityException e) {
            Log.w(TAG, "media sessions: " + e);
        }
        pick(sessions());
    }

    @Override
    protected void onInactive() {
        mMsm.removeOnActiveSessionsChangedListener(mSessions);
        setController(null);
        mHandler.removeCallbacks(mTick);
    }

    /** The playing session, else the most recent one. */
    private void pick(List<MediaController> list) {
        MediaController best = null;
        if (list != null) {
            for (MediaController m : list) {
                PlaybackState s = m.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) {
                    best = m;
                    break;
                }
            }
            if (best == null && !list.isEmpty()) best = list.get(0);
        }
        setController(best);
        onRefresh();
    }

    private void setController(MediaController c) {
        if (mController != null && c != null
                && mController.getSessionToken().equals(c.getSessionToken())) {
            return;
        }
        if (mController != null) mController.unregisterCallback(mCallback);
        mController = c;
        if (c != null) c.registerCallback(mCallback, mHandler);
    }

    private boolean isPlaying() {
        PlaybackState s = mController != null ? mController.getPlaybackState() : null;
        return s != null && s.getState() == PlaybackState.STATE_PLAYING;
    }

    private void togglePlay() {
        if (mController == null) return;
        if (isPlaying()) {
            mController.getTransportControls().pause();
        } else {
            mController.getTransportControls().play();
        }
    }

    @Override
    protected void onRefresh() {
        if (mTitle == null) return;
        MediaMetadata md = mController != null ? mController.getMetadata() : null;
        boolean has = md != null;
        mContent.setVisibility(has ? VISIBLE : INVISIBLE);
        mEmpty.setVisibility(has ? GONE : VISIBLE);
        if (!has) return;
        CharSequence title = md.getText(MediaMetadata.METADATA_KEY_TITLE);
        CharSequence artist = md.getText(MediaMetadata.METADATA_KEY_ARTIST);
        if (TextUtils.isEmpty(artist)) artist = md.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        mTitle.setText(title);
        mArtist.setText(artist);
        mApp.setText(appLabel(mController.getPackageName()));
        Bitmap art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        mArt.setImageBitmap(art);
        mPlay.setImageResource(isPlaying() ? android.R.drawable.ic_media_pause
                : android.R.drawable.ic_media_play);
        updateProgress();
        mHandler.removeCallbacks(mTick);
        if (isActive() && isPlaying()) mHandler.postDelayed(mTick, PROGRESS_TICK_MS);
    }

    private CharSequence appLabel(String pkg) {
        try {
            PackageManager pm = getContext().getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private long duration() {
        MediaMetadata md = mController != null ? mController.getMetadata() : null;
        return md != null ? md.getLong(MediaMetadata.METADATA_KEY_DURATION) : 0;
    }

    /** The current position, extrapolated from the last state while playing. */
    private long position() {
        PlaybackState s = mController != null ? mController.getPlaybackState() : null;
        if (s == null) return 0;
        long pos = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING && s.getLastPositionUpdateTime() > 0) {
            pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime())
                    * s.getPlaybackSpeed());
        }
        long d = duration();
        return d > 0 ? Math.max(0, Math.min(d, pos)) : Math.max(0, pos);
    }

    private void updateProgress() {
        if (mProgress == null || mProgress.mDragging) return;
        long d = duration();
        long p = position();
        mProgress.setVisibility(d > 0 ? VISIBLE : INVISIBLE);
        mProgress.setValue(d > 0 ? p / (float) d : 0f);
        mPos.setText(d > 0 ? time(p) : "");
        mDur.setText(d > 0 ? time(d) : "");
    }

    private static String time(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format("%d:%02d", s / 60, s % 60);
    }

    private boolean canSeek() {
        PlaybackState s = mController != null ? mController.getPlaybackState() : null;
        return s != null && (s.getActions() & PlaybackState.ACTION_SEEK_TO) != 0 && duration() > 0;
    }

    /** A thin progress line; dragging it seeks (when the session allows it). */
    private final class ProgressBar extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float mValue;
        boolean mDragging;

        ProgressBar(Context c) {
            super(c);
            mPaint.setStrokeCap(Paint.Cap.ROUND);
        }

        void setValue(float v) {
            mValue = Math.max(0f, Math.min(1f, v));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float y = getHeight() / 2f, r = px(mDragging ? 3 : 2);
            mPaint.setStrokeWidth(2 * r);
            mPaint.setColor(mStyle.track);
            c.drawLine(r, y, getWidth() - r, y, mPaint);
            mPaint.setColor(mStyle.accent);
            float x = r + (getWidth() - 2 * r) * mValue;
            c.drawLine(r, y, x, y, mPaint);
            if (mDragging) c.drawCircle(x, y, px(6), mPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            // no seeking from a stray touch on the locked screen
            if (!canSeek() || guarded()) return false;
            float v = Math.max(0f, Math.min(1f, e.getX() / Math.max(1, getWidth())));
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // keep the pager from taking the horizontal drag
                    getParent().requestDisallowInterceptTouchEvent(true);
                    mDragging = true;
                    setValue(v);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    setValue(v);
                    mPos.setText(time((long) (v * duration())));
                    return true;
                case MotionEvent.ACTION_UP:
                    mDragging = false;
                    if (mController != null) {
                        mController.getTransportControls().seekTo((long) (v * duration()));
                    }
                    invalidate();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    mDragging = false;
                    updateProgress();
                    return true;
                default:
                    return true;
            }
        }
    }
}
