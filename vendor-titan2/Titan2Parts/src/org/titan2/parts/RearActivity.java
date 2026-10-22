package org.titan2.parts;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

/** Window on the rear screen: light, QR code or the main camera viewfinder (started by RearService). */
public class RearActivity extends Activity {
    private static final String TAG = "Titan2Rear";

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (RearService.ACTION_SHUTTER.equals(intent.getAction())) {
                takePicture();
            } else {
                finish();
            }
        }
    };

    // selfie
    private HandlerThread mCamThread;
    private Handler mCamHandler;
    private CameraDevice mCamera;
    private CameraCaptureSession mSession;
    private ImageReader mReader;
    private TextView mStatus;
    private int mSensorOrientation;
    /** closeCamera() ran: a camera that opens or a session that configures late is closed at once. */
    private volatile boolean mClosed;
    /** openCamera() is waiting for its StateCallback: the camera thread must stay alive until then. */
    private volatile boolean mOpening;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        // rear screen brightness from the window (the main screen auto brightness lowers it); over the lock screen
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = RearService.MODE_LIGHT.equals(
                getIntent().getStringExtra(RearService.EXTRA_MODE)) ? 1f : 0.7f;
        getWindow().setAttributes(lp);
        setShowWhenLocked(true);
        IntentFilter f = new IntentFilter(RearService.ACTION_FINISH);
        f.addAction(RearService.ACTION_SHUTTER);
        registerReceiver(mReceiver, f, Context.RECEIVER_NOT_EXPORTED);

        String mode = getIntent().getStringExtra(RearService.EXTRA_MODE);
        if (RearService.MODE_QR.equals(mode)) {
            showQr();
        } else if (RearService.MODE_SELFIE.equals(mode)) {
            showSelfie();
        } else {
            View white = new View(this);
            white.setBackgroundColor(Color.WHITE);
            setContentView(white);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Selfie holds the main camera (the one with the flash): while it is open, the torch and flash
        // are unavailable to everyone else. Left the rear screen: release the camera and close.
        if (mCamera != null || mCamThread != null) {
            closeCamera();
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(mReceiver);
        closeCamera();
        super.onDestroy();
    }

    // ---------- QR ----------

    private void showQr() {
        String text = wifiQrText();
        String caption = text != null ? getString(R.string.rear_qr_wifi) : null;
        if (text == null) {
            text = clipboardText();
            caption = getString(R.string.rear_qr_clipboard);
        }
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        if (text == null) {
            TextView t = new TextView(this);
            t.setText(R.string.rear_qr_nothing);
            t.setTextColor(Color.BLACK);
            t.setGravity(Gravity.CENTER);
            root.addView(t);
        } else {
            ImageView qr = new ImageView(this);
            qr.setImageBitmap(encode(text, 380));
            root.addView(qr, new FrameLayout.LayoutParams(380, 380, Gravity.CENTER));
            TextView t = new TextView(this);
            t.setText(caption);
            t.setTextColor(Color.BLACK);
            root.addView(t, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        }
        setContentView(root);
    }

    /** The current Wi-Fi network in the standard WIFI:S:...;T:...;P:...;; format (phone cameras read it). */
    private String wifiQrText() {
        WifiManager wm = getSystemService(WifiManager.class);
        WifiInfo info = wm.getConnectionInfo();
        if (info == null || info.getNetworkId() == -1) {
            return null;
        }
        for (WifiConfiguration c : wm.getPrivilegedConfiguredNetworks()) {
            if (c.networkId != info.getNetworkId()) {
                continue;
            }
            String ssid = unquote(c.SSID);
            String psk = c.preSharedKey != null ? unquote(c.preSharedKey) : null;
            String type = psk == null ? "nopass" : "WPA";
            return "WIFI:S:" + escape(ssid) + ";T:" + type + ";"
                    + (psk != null ? "P:" + escape(psk) + ";" : "") + ";";
        }
        return null;
    }

    private String clipboardText() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            return null;
        }
        CharSequence t = clip.getItemAt(0).coerceToText(this);
        return t != null && t.length() > 0 ? t.toString() : null;
    }

    private static String unquote(String s) {
        return s != null && s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")
                ? s.substring(1, s.length() - 1) : s;
    }

    private static String escape(String s) {
        return s.replaceAll("([\\\\;,:\"])", "\\\\$1");
    }

    private static Bitmap encode(String text, int size) {
        try {
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size);
            Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    b.setPixel(x, y, m.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return b;
        } catch (WriterException e) {
            Log.w(TAG, "qr", e);
            return null;
        }
    }

    // ---------- selfie with the main camera ----------

    private void showSelfie() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        TextureView tv = new TextureView(this);
        // mirrored, like a mirror: easier to frame yourself
        tv.setScaleX(-1f);
        root.addView(tv);
        mStatus = new TextView(this);
        mStatus.setTextColor(Color.WHITE);
        root.addView(mStatus, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        setContentView(root);

        mCamThread = new HandlerThread(TAG);
        mCamThread.start();
        mCamHandler = new Handler(mCamThread.getLooper());
        tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                openCamera(st, w, h);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {}

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {}
        });
    }

    private void openCamera(SurfaceTexture st, int w, int h) {
        CameraManager cm = getSystemService(CameraManager.class);
        try {
            String id = null;
            CameraCharacteristics ch = null;
            for (String c : cm.getCameraIdList()) {
                CameraCharacteristics cc = cm.getCameraCharacteristics(c);
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    id = c;
                    ch = cc;
                    break;
                }
            }
            if (id == null || mClosed) {
                return;
            }
            Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
            mSensorOrientation = so != null ? so : 90;
            // the largest JPEG the HAL offers
            Size best = null;
            StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] sizes = map != null ? map.getOutputSizes(ImageFormat.JPEG) : null;
            if (sizes == null || sizes.length == 0) {
                Log.w(TAG, "no JPEG sizes for camera " + id);
                return;
            }
            for (Size sz : sizes) {
                if (best == null || (long) sz.getWidth() * sz.getHeight()
                        > (long) best.getWidth() * best.getHeight()) {
                    best = sz;
                }
            }
            // 4:3 preview on the square 410x502 screen, scaled by TextureView
            st.setDefaultBufferSize(640, 480);
            final Surface preview = new Surface(st);
            mReader = ImageReader.newInstance(best.getWidth(), best.getHeight(), ImageFormat.JPEG, 2);
            mReader.setOnImageAvailableListener(r -> save(r.acquireLatestImage()), mCamHandler);
            final HandlerThread thread = mCamThread;
            mOpening = true;
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    synchronized (RearActivity.this) {
                        mOpening = false;
                        if (mClosed) {
                            // the activity left the rear screen while the camera was opening:
                            // without this the main camera (and the torch) stays held
                            camera.close();
                            thread.quitSafely();
                            return;
                        }
                        mCamera = camera;
                    }
                    startPreview(preview);
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    mOpening = false;
                    camera.close();
                    if (mClosed) thread.quitSafely();
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    mOpening = false;
                    Log.w(TAG, "camera error " + error);
                    camera.close();
                    if (mClosed) thread.quitSafely();
                }
            }, mCamHandler);
        } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
            mOpening = false;
            Log.w(TAG, "open camera", e);
        }
    }

    private void startPreview(Surface preview) {
        final CameraDevice camera;
        final ImageReader reader;
        synchronized (this) {
            camera = mCamera;
            reader = mReader;
        }
        if (camera == null || reader == null) {
            return;
        }
        try {
            List<Surface> outs = Arrays.asList(preview, reader.getSurface());
            camera.createCaptureSession(outs, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession s) {
                    if (mClosed) {
                        s.close();
                        return;
                    }
                    mSession = s;
                    try {
                        CaptureRequest.Builder b =
                                s.getDevice().createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        b.addTarget(preview);
                        s.setRepeatingRequest(b.build(), null, mCamHandler);
                    } catch (CameraAccessException | IllegalStateException e) {
                        // IllegalStateException: closed from the main thread meanwhile
                        Log.w(TAG, "preview", e);
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession s) {
                    Log.w(TAG, "session configure failed");
                }
            }, mCamHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            Log.w(TAG, "session", e);
        }
    }

    private void takePicture() {
        final CameraCaptureSession session = mSession;
        final ImageReader reader = mReader;
        if (session == null || reader == null || mClosed) {
            return;
        }
        try {
            CaptureRequest.Builder b =
                    session.getDevice().createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(reader.getSurface());
            b.set(CaptureRequest.JPEG_ORIENTATION, mSensorOrientation);
            session.capture(b.build(), null, mCamHandler);
            runOnUiThread(() -> mStatus.setText(R.string.rear_selfie_taken));
        } catch (CameraAccessException | IllegalStateException e) {
            Log.w(TAG, "capture", e);
        }
    }

    private void save(Image image) {
        if (image == null) {
            return;
        }
        try {
            ByteBuffer buf = image.getPlanes()[0].getBuffer();
            byte[] jpeg = new byte[buf.remaining()];
            buf.get(jpeg);
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_" + System.currentTimeMillis() + ".jpg");
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera");
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri != null) {
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    out.write(jpeg);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "save", e);
        } finally {
            image.close();
        }
    }

    private synchronized void closeCamera() {
        mClosed = true;
        if (mSession != null) {
            mSession.close();
            mSession = null;
        }
        if (mCamera != null) {
            mCamera.close();
            mCamera = null;
        }
        if (mReader != null) {
            mReader.close();
            mReader = null;
        }
        if (mCamThread != null) {
            // an open still in flight: its callback closes the camera and quits the thread
            if (!mOpening) mCamThread.quitSafely();
            mCamThread = null;
        }
    }
}
