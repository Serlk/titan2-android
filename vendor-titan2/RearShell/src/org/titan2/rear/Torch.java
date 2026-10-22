package org.titan2.rear;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/** Torch (main camera with the flash): state and toggle, one per process. */
final class Torch {
    private static final String TAG = "RearShell";
    private static Torch sInstance;

    private final CameraManager mCam;
    private final String mId;
    private boolean mOn;
    private final List<Runnable> mListeners = new ArrayList<>();

    private Torch(Context c) {
        mCam = c.getSystemService(CameraManager.class);
        mId = find();
        if (mId != null) {
            mCam.registerTorchCallback(new CameraManager.TorchCallback() {
                @Override
                public void onTorchModeChanged(String id, boolean enabled) {
                    if (id.equals(mId)) {
                        mOn = enabled;
                        for (Runnable r : new ArrayList<>(mListeners)) r.run();
                    }
                }
            }, new Handler(Looper.getMainLooper()));
        }
    }

    static synchronized Torch get(Context c) {
        if (sInstance == null) sInstance = new Torch(c.getApplicationContext());
        return sInstance;
    }

    boolean available() {
        return mId != null;
    }

    boolean isOn() {
        return mOn;
    }

    void addListener(Runnable r) {
        mListeners.add(r);
    }

    void removeListener(Runnable r) {
        mListeners.remove(r);
    }

    void toggle() {
        if (mId == null) return;
        try {
            mCam.setTorchMode(mId, !mOn);
        } catch (Exception e) {
            Log.w(TAG, "torch: " + e);
        }
    }

    private String find() {
        try {
            String fallback = null;
            for (String id : mCam.getCameraIdList()) {
                CameraCharacteristics cc = mCam.getCameraCharacteristics(id);
                Boolean flash = cc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (flash == null || !flash) continue;
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
                if (fallback == null) fallback = id;
            }
            return fallback;
        } catch (Exception e) {
            Log.w(TAG, "torch lookup: " + e);
            return null;
        }
    }
}
