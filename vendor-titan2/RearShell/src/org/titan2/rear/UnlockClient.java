package org.titan2.rear;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import java.util.Arrays;

/**
 * Link to Titan2RearUnlockService (SystemUI, frameworks_base): PIN/pattern check and unlocking
 * without input. The binding is held for the whole lifetime of the shell process: while it exists,
 * SystemUI locks the phone again when the rear screen goes off with the main one off; otherwise
 * an unlock from the rear screen would last until the main screen sleeps next.
 * One pending request at a time (there is one input, on the rear screen).
 */
final class UnlockClient {
    private static final String TAG = "RearShell";
    static final ComponentName SERVICE = new ComponentName("com.android.systemui",
            "com.android.systemui.keyguard.Titan2RearUnlockService");

    // Titan2RearUnlockService protocol
    static final int MSG_QUERY = 1;
    static final int MSG_UNLOCK = 2;
    static final int MSG_DISMISS = 3;
    static final int RESULT_OK = 0;
    static final int RESULT_WRONG = 1;
    static final int RESULT_LOCKOUT = 2;
    static final int RESULT_UNAVAILABLE = 3;
    // LockPatternUtils.CREDENTIAL_TYPE_*
    static final int TYPE_NONE = -1;
    static final int TYPE_PATTERN = 1;
    static final int TYPE_PIN = 3;
    static final int TYPE_PASSWORD = 4;

    private static final long TIMEOUT_MS = 3000;
    private static UnlockClient sInstance;

    interface Callback {
        /** Service reply (what is MSG_*); m == null: the service is unavailable or did not reply. */
        void onReply(int what, Message m);
    }

    private final Context mContext;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Messenger mReplies = new Messenger(new Handler(Looper.getMainLooper(),
            this::onReply));
    private Messenger mService;
    private boolean mBound;
    private Callback mCallback;
    /** what of the request in flight: the service replies with the same what; others are stale */
    private int mWhat;
    private int mPendingWhat;
    private byte[] mPendingCredential;
    private Callback mPendingCallback;

    private final Runnable mTimeout = () -> fail("timeout");

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            mService = new Messenger(binder);
            if (mPendingCallback != null) {
                Callback cb = mPendingCallback;
                byte[] cred = mPendingCredential;
                mPendingCallback = null;
                mPendingCredential = null;
                send(mPendingWhat, cred, cb);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mService = null;
            fail("disconnected");
        }

        @Override
        public void onBindingDied(ComponentName name) {
            // SystemUI restarted with a new version: bind again
            mService = null;
            unbind();
            bind();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            unbind();
        }
    };

    private UnlockClient(Context c) {
        mContext = c.getApplicationContext();
    }

    static synchronized UnlockClient get(Context c) {
        if (sInstance == null) sInstance = new UnlockClient(c);
        return sInstance;
    }

    /** Bind (a repeated call does nothing). false: there is no service. */
    boolean bind() {
        if (mBound) return true;
        try {
            mBound = mContext.bindService(new Intent().setComponent(SERVICE), mConnection,
                    Context.BIND_AUTO_CREATE);
        } catch (SecurityException e) {
            Log.w(TAG, "unlock service: " + e);
            mBound = false;
        }
        return mBound;
    }

    private void unbind() {
        if (mBound) {
            try {
                mContext.unbindService(mConnection);
            } catch (IllegalArgumentException ignored) {
            }
            mBound = false;
        }
    }

    void query(Callback cb) {
        send(MSG_QUERY, null, cb);
    }

    void dismiss(Callback cb) {
        send(MSG_DISMISS, null, cb);
    }

    /** credential is erased right here after sending. */
    void unlock(byte[] credential, Callback cb) {
        send(MSG_UNLOCK, credential, cb);
    }

    /** Forget the pending reply (the input was closed). */
    void cancel() {
        mMain.removeCallbacks(mTimeout);
        mCallback = null;
        mWhat = 0;
        if (mPendingCredential != null) Arrays.fill(mPendingCredential, (byte) 0);
        mPendingCredential = null;
        mPendingCallback = null;
    }

    private void send(int what, byte[] credential, Callback cb) {
        mMain.removeCallbacks(mTimeout);
        if (mService == null) {
            if (!bind()) {
                if (credential != null) Arrays.fill(credential, (byte) 0);
                mMain.post(() -> cb.onReply(what, null));
                return;
            }
            // still connecting: will send in onServiceConnected
            if (mPendingCredential != null) Arrays.fill(mPendingCredential, (byte) 0);
            mPendingWhat = what;
            mPendingCredential = credential;
            mPendingCallback = cb;
            mCallback = cb;
            mWhat = what;
            mMain.postDelayed(mTimeout, TIMEOUT_MS);
            return;
        }
        Message m = Message.obtain(null, what);
        m.replyTo = mReplies;
        if (credential != null) {
            Bundle b = new Bundle();
            b.putByteArray("credential", credential);
            m.setData(b);
        }
        mCallback = cb;
        mWhat = what;
        try {
            mService.send(m);
            // a pattern check in gatekeeper can take time: a longer timeout
            mMain.postDelayed(mTimeout, what == MSG_UNLOCK ? TIMEOUT_MS * 3 : TIMEOUT_MS);
        } catch (RemoteException e) {
            fail("send: " + e);
        } finally {
            // the copy went into the Parcel; erase our own buffer
            if (credential != null) Arrays.fill(credential, (byte) 0);
        }
    }

    private void fail(String why) {
        mMain.removeCallbacks(mTimeout);
        Callback cb = mCallback != null ? mCallback : mPendingCallback;
        int what = mWhat;
        mCallback = null;
        mWhat = 0;
        if (mPendingCredential != null) Arrays.fill(mPendingCredential, (byte) 0);
        mPendingCredential = null;
        mPendingCallback = null;
        if (cb != null) {
            Log.w(TAG, "unlock service: " + why);
            cb.onReply(what, null);
        }
    }

    private boolean onReply(Message m) {
        // a late reply to a cancelled or timed-out request (e.g. an UNLOCK reply arriving after
        // the bouncer was reopened and sent a QUERY) must not be taken for the current one
        if (mCallback == null || m.what != mWhat) return true;
        mMain.removeCallbacks(mTimeout);
        Callback cb = mCallback;
        mCallback = null;
        mWhat = 0;
        if (cb != null) cb.onReply(m.what, m);
        return true;
    }
}
