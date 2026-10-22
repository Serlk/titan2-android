package org.titan2.parts;

import android.util.Log;

/**
 * Virtual mouse for the touchpad mouse modes 3/4, which need a real pointer device.
 * Injected events do not move the pointer, so a real input device is
 * needed: uinput via libtitan2_uinput (system_ext). The device exists only while
 * an app with a mouse mode is focused; when it is removed, Android hides the pointer.
 */
final class VirtualMouse {
    private static final String TAG = "Titan2Mouse";
    private static final String LIB = "/system_ext/lib64/libtitan2_uinput.so";
    private static boolean sLoaded, sLoadFailed;

    /** Guarded by this: a write after close() would hit whatever reuses the fd number. */
    private int mFd = -1;

    private static native int nativeOpen(String name);
    private static native int nativeMove(int fd, int dx, int dy);
    private static native int nativeButton(int fd, int button, boolean down);
    private static native void nativeClose(int fd);

    private static synchronized boolean load() {
        if (!sLoaded && !sLoadFailed) {
            try {
                System.load(LIB);
                sLoaded = true;
            } catch (UnsatisfiedLinkError e) {
                sLoadFailed = true;
                Log.w(TAG, "load " + LIB, e);
            }
        }
        return sLoaded;
    }

    synchronized boolean isOpen() {
        return mFd >= 0;
    }

    synchronized void open() {
        if (mFd >= 0 || !load()) {
            return;
        }
        int fd = nativeOpen("Titan2 Touchpad Mouse");
        if (fd < 0) {
            Log.w(TAG, "uinput: errno " + -fd);
            return;
        }
        mFd = fd;
    }

    synchronized void close() {
        if (mFd >= 0) {
            nativeClose(mFd);
            mFd = -1;
        }
    }

    synchronized void move(int dx, int dy) {
        if (mFd >= 0 && (dx != 0 || dy != 0)) {
            nativeMove(mFd, dx, dy);
        }
    }

    /** Left click at the pointer: DOWN, 1 ms, UP. */
    synchronized void click() {
        if (mFd < 0) {
            return;
        }
        nativeButton(mFd, 0, true);
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            // nothing
        }
        nativeButton(mFd, 0, false);
    }
}
