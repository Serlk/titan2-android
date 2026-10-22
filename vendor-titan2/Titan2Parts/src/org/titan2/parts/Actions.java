package org.titan2.parts;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.StatusBarManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.SensorPrivacyManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.input.InputManager;
import android.media.AudioManager;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.WindowManager;

import com.android.internal.util.ScreenshotHelper;

import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runs actions from system_server (system permissions: DEVICE_POWER, EXPAND_STATUS_BAR, etc.). */
class Actions {
    private static final String TAG = "Titan2Actions";
    /** Screenshot after 800 ms so the hint has disappeared. */
    private static final long SCREENSHOT_DELAY_MS = 800;
    /** PTT broadcasts (understood by Zello and others). */
    static final String ACTION_PTT_DOWN = "android.intent.action.PTT.down";
    static final String ACTION_PTT_UP = "android.intent.action.PTT.up";

    private final Context mContext;
    private final Handler mHandler;
    private CameraManager mCameraManager;
    private String mTorchCameraId;
    private boolean mTorchOn;
    private ScreenshotHelper mScreenshotHelper;

    Actions(Context context, Handler handler) {
        mContext = context;
        mHandler = handler;
    }

    /**
     * The camera is attached on first use: the handler is created at system_server
     * startup, when cameraserver may not be running yet.
     */
    private CameraManager cameraManager() {
        if (mCameraManager == null) {
            mCameraManager = mContext.getSystemService(CameraManager.class);
            mCameraManager.registerTorchCallback(new CameraManager.TorchCallback() {
                @Override
                public void onTorchModeChanged(String cameraId, boolean enabled) {
                    if (cameraId.equals(mTorchCameraId)) {
                        mTorchOn = enabled;
                    }
                }

                // an app opened the camera: the torch went off without onTorchModeChanged(false),
                // otherwise the next key press would "turn off" a torch that is already off
                @Override
                public void onTorchModeUnavailable(String cameraId) {
                    if (cameraId.equals(mTorchCameraId)) {
                        mTorchOn = false;
                    }
                }
            }, mHandler);
        }
        return mCameraManager;
    }

    void run(String action) {
        if (action == null || KeyConfig.NONE.equals(action)) {
            return;
        }
        // runs inside system_server: an uncaught exception here restarts the whole system
        try {
            runUnchecked(action);
        } catch (RuntimeException e) {
            Log.w(TAG, "action " + action + " failed", e);
        }
    }

    private void runUnchecked(String action) {
        haptic();
        if (KeyConfig.TORCH.equals(action)) {
            toggleTorch();
        } else if (KeyConfig.CAMERA.equals(action)) {
            launchCamera();
        } else if (KeyConfig.NOTIFICATIONS.equals(action)) {
            mContext.getSystemService(StatusBarManager.class).expandNotificationsPanel();
        } else if (KeyConfig.RINGER.equals(action)) {
            toggleRinger();
        } else if (KeyConfig.PRIVACY.equals(action)) {
            togglePrivacy();
        } else if (KeyConfig.REAR_SELFIE.equals(action)) {
            RearService.request(mContext, RearService.MODE_SELFIE);
        } else if (KeyConfig.REAR_QR.equals(action)) {
            RearService.request(mContext, RearService.MODE_QR);
        } else if (KeyConfig.REAR_LIGHT.equals(action)) {
            RearService.request(mContext, RearService.MODE_LIGHT);
        } else if (KeyConfig.RECORD.equals(action)) {
            launchRecorder();
        } else if (KeyConfig.SCREENSHOT.equals(action)) {
            mHandler.postDelayed(this::takeScreenshot, SCREENSHOT_DELAY_MS);
        } else if (KeyConfig.SCREEN_RECORD.equals(action)) {
            toggleScreenRecord();
        } else if (KeyConfig.SILENT.equals(action)) {
            toggleRingerMode(AudioManager.RINGER_MODE_SILENT);
        } else if (KeyConfig.VIBRATE.equals(action)) {
            toggleRingerMode(AudioManager.RINGER_MODE_VIBRATE);
        } else if (KeyConfig.SCREEN_OFF.equals(action)) {
            mContext.getSystemService(PowerManager.class).goToSleep(SystemClock.uptimeMillis());
        } else if (KeyConfig.KEY_BACK.equals(action)) {
            sendKeyDownUp(KeyEvent.KEYCODE_BACK);
        } else if (KeyConfig.KEY_HOME.equals(action)) {
            sendKeyDownUp(KeyEvent.KEYCODE_HOME);
        } else if (KeyConfig.KEY_RECENT.equals(action)) {
            sendKeyDownUp(KeyEvent.KEYCODE_APP_SWITCH);
        } else if (action.startsWith(KeyConfig.APP_PREFIX)) {
            launchApp(action.substring(KeyConfig.APP_PREFIX.length()));
        } else if (action.startsWith(KeyConfig.SHORTCUT_PREFIX)
                || action.startsWith(KeyConfig.INTENT_PREFIX)) {
            launchTarget(action);
        } else {
            Log.w(TAG, "unknown action " + action);
        }
    }

    private void haptic() {
        Vibrator v = mContext.getSystemService(Vibrator.class);
        if (v != null && v.hasVibrator()) {
            v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK));
        }
    }

    private String torchCameraId() throws CameraAccessException {
        if (mTorchCameraId == null) {
            for (String id : cameraManager().getCameraIdList()) {
                CameraCharacteristics c = cameraManager().getCameraCharacteristics(id);
                Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(flash)
                        && facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    mTorchCameraId = id;
                    break;
                }
            }
        }
        return mTorchCameraId;
    }

    private void toggleTorch() {
        try {
            String id = torchCameraId();
            if (id == null) {
                Log.w(TAG, "no back camera with flash");
                return;
            }
            cameraManager().setTorchMode(id, !mTorchOn);
        } catch (CameraAccessException | IllegalArgumentException e) {
            // the camera is busy with an app: the torch is unavailable
            Log.w(TAG, "torch unavailable", e);
        }
    }

    private void wakeUp() {
        mContext.getSystemService(PowerManager.class).wakeUp(SystemClock.uptimeMillis(),
                PowerManager.WAKE_REASON_GESTURE, "titan2:key");
    }

    /** Over the lock screen: the secure camera mode (like the "double press power" gesture). */
    private void launchCamera() {
        wakeUp();
        boolean locked = mContext.getSystemService(KeyguardManager.class).isKeyguardLocked();
        Intent intent = new Intent(locked ? MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE
                : MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            mContext.startActivityAsUser(intent, UserHandle.CURRENT);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "no camera app", e);
        }
    }

    /**
     * Both sensors at once: if at least one is open, close both, otherwise open them.
     * Enabling is confirmed with a double vibration (the normal single one already happened in run()).
     */
    private void togglePrivacy() {
        SensorPrivacyManager spm = mContext.getSystemService(SensorPrivacyManager.class);
        boolean blocked = spm.isSensorPrivacyEnabled(SensorPrivacyManager.Sensors.CAMERA)
                && spm.isSensorPrivacyEnabled(SensorPrivacyManager.Sensors.MICROPHONE);
        spm.setSensorPrivacy(SensorPrivacyManager.Sensors.CAMERA, !blocked);
        spm.setSensorPrivacy(SensorPrivacyManager.Sensors.MICROPHONE, !blocked);
        if (!blocked) {
            mHandler.postDelayed(this::haptic, 150);
        }
    }

    private void toggleRinger() {
        AudioManager am = mContext.getSystemService(AudioManager.class);
        am.setRingerModeInternal(am.getRingerModeInternal() == AudioManager.RINGER_MODE_NORMAL
                ? AudioManager.RINGER_MODE_VIBRATE : AudioManager.RINGER_MODE_NORMAL);
    }

    /**
     * Silent/vibrate toggle: "silent": if the mode is not SILENT, set SILENT, otherwise
     * NORMAL; "vibrate": the same with VIBRATE. The AOSP sound tile tracks the mode itself.
     */
    private void toggleRingerMode(int mode) {
        AudioManager am = mContext.getSystemService(AudioManager.class);
        am.setRingerModeInternal(am.getRingerModeInternal() != mode
                ? mode : AudioManager.RINGER_MODE_NORMAL);
    }

    /** Whoever answers RECORD_SOUND (Lineage Recorder). */
    private void launchRecorder() {
        wakeUp();
        Intent intent = new Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            mContext.startActivityAsUser(intent, UserHandle.CURRENT);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "no sound recorder", e);
        }
    }

    private void takeScreenshot() {
        if (mScreenshotHelper == null) {
            mScreenshotHelper = new ScreenshotHelper(mContext);
        }
        mScreenshotHelper.takeScreenshot(WindowManager.ScreenshotSource.SCREENSHOT_KEY_OTHER,
                mHandler, null);
    }

    /** Key down + up: keyboard source, FLAG_FROM_SYSTEM. */
    void sendKeyDownUp(int keyCode) {
        long now = SystemClock.uptimeMillis();
        injectKey(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        injectKey(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
    }

    /**
     * Replay a side key press with another code (Programmable mode of Func1/Func2: their
     * devices are not alphabetic, KeyRemapper does not remap them). Down and up go separately, so
     * a long press of Power/Back/Home works as with a real key.
     */
    void injectAs(KeyEvent original, int keyCode) {
        injectKey(new KeyEvent(original.getDownTime(), original.getEventTime(),
                original.getAction(), keyCode, original.getRepeatCount(), 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM,
                InputDevice.SOURCE_KEYBOARD));
    }

    /** Pass on a held-back press (long space): same code and scan code, virtual device. */
    void injectTap(KeyEvent original) {
        long now = SystemClock.uptimeMillis();
        for (int action : new int[] {KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
            injectKey(new KeyEvent(now, now, action, original.getKeyCode(), 0,
                    original.getMetaState(), KeyCharacterMap.VIRTUAL_KEYBOARD,
                    original.getScanCode(), KeyEvent.FLAG_FROM_SYSTEM,
                    InputDevice.SOURCE_KEYBOARD));
        }
    }

    /**
     * Insert text into the focused field. InputConnection is unavailable from system_server, and injecting
     * per character (KeyCharacterMap.getEvents) only handles symbols of the virtual layout (no Cyrillic
     * etc.), so: clipboard + Ctrl+V, the previous clipboard content is restored after 1 s.
     */
    void pasteText(String text) {
        final ClipboardManager cm = mContext.getSystemService(ClipboardManager.class);
        ClipData old = null;
        try {
            old = cm.getPrimaryClip();
        } catch (RuntimeException e) {
            Log.w(TAG, "clipboard read failed", e);
        }
        cm.setPrimaryClip(ClipData.newPlainText("titan2", text));
        long now = SystemClock.uptimeMillis();
        int meta = KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
        for (int action : new int[] {KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
            injectKey(new KeyEvent(now, now, action, KeyEvent.KEYCODE_V, 0, meta,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM,
                    InputDevice.SOURCE_KEYBOARD));
        }
        final ClipData restore = old;
        mHandler.postDelayed(() -> {
            try {
                if (restore != null) {
                    cm.setPrimaryClip(restore);
                } else {
                    cm.clearPrimaryClip();
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "clipboard restore failed", e);
            }
        }, 1000);
    }

    private static final Pattern TEMPLATE = Pattern.compile("\\{(date|time)(?::([^}]*))?\\}");

    /** {date}, {time}: system format; {date:pattern} / {time:pattern}: SimpleDateFormat. */
    static String expandTemplate(Context context, String text) {
        Matcher m = TEMPLATE.matcher(text);
        StringBuilder out = new StringBuilder();
        Date now = new Date();
        while (m.find()) {
            String repl;
            try {
                if (m.group(2) != null && !m.group(2).isEmpty()) {
                    repl = new SimpleDateFormat(m.group(2), Locale.getDefault()).format(now);
                } else if ("date".equals(m.group(1))) {
                    repl = android.text.format.DateFormat.getDateFormat(context).format(now);
                } else {
                    repl = android.text.format.DateFormat.getTimeFormat(context).format(now);
                }
            } catch (IllegalArgumentException e) {
                repl = m.group();
            }
            m.appendReplacement(out, Matcher.quoteReplacement(repl));
        }
        m.appendTail(out);
        return out.toString();
    }

    private void injectKey(KeyEvent ev) {
        ev.setSource(InputDevice.SOURCE_KEYBOARD);
        if ((ev.getFlags() & KeyEvent.FLAG_FROM_SYSTEM) == 0) {
            ev = KeyEvent.changeFlags(ev, ev.getFlags() | KeyEvent.FLAG_FROM_SYSTEM);
        }
        mContext.getSystemService(InputManager.class).injectInputEvent(ev,
                InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
    }

    /** PTT: PTT.down/up broadcast to the chosen app (Zello, Weila, etc.). */
    void sendPtt(String pkg, boolean down) {
        Intent intent = new Intent(down ? ACTION_PTT_DOWN : ACTION_PTT_UP);
        intent.setPackage(pkg);
        mContext.sendBroadcastAsUser(intent, UserHandle.CURRENT);
    }

    private void launchApp(String pkg) {
        Intent intent = mContext.getPackageManager().getLaunchIntentForPackage(pkg);
        if (intent == null) {
            Log.w(TAG, "no launcher activity in " + pkg);
            return;
        }
        wakeUp();
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivityAsUser(intent, UserHandle.CURRENT);
    }

    /**
     * Letter shortcut targets: a LauncherApps shortcut "package/id" or
     * an Intent URI of the old ACTION_CREATE_SHORTCUT.
     */
    private void launchTarget(String value) {
        try {
            if (value.startsWith(KeyConfig.SHORTCUT_PREFIX)) {
                String s = value.substring(KeyConfig.SHORTCUT_PREFIX.length());
                int slash = s.indexOf('/');
                if (slash <= 0) return;
                mContext.getSystemService(LauncherApps.class).startShortcut(
                        s.substring(0, slash), s.substring(slash + 1), null, null,
                        UserHandle.of(ActivityManager.getCurrentUser()));
            } else {
                Intent intent = Intent.parseUri(
                        value.substring(KeyConfig.INTENT_PREFIX.length()), Intent.URI_INTENT_SCHEME);
                // The intent came from another app (ACTION_CREATE_SHORTCUT), but we launch it
                // from system_server: without this it could open a non-exported activity or
                // hand out grants to foreign content:// (contacts, SMS) via FLAG_GRANT_*.
                intent.setSelector(null);
                intent.setClipData(null);
                intent.removeFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                ResolveInfo ri = mContext.getPackageManager().resolveActivityAsUser(intent,
                        PackageManager.MATCH_DEFAULT_ONLY, ActivityManager.getCurrentUser());
                if (ri == null || ri.activityInfo == null || !ri.activityInfo.exported
                        || ri.activityInfo.permission != null) {
                    Log.w(TAG, "shortcut target is not a public activity: " + value);
                    return;
                }
                intent.setComponent(new ComponentName(
                        ri.activityInfo.packageName, ri.activityInfo.name));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivityAsUser(intent, UserHandle.CURRENT);
            }
        } catch (URISyntaxException | RuntimeException e) {
            Log.w(TAG, "launch " + value + " failed", e);
        }
    }

    /**
     * crDroid screen recording (SystemUI RecordingService, IRemoteRecording): running, stop it;
     * not running, open the standard recording dialog (audio, touches; MediaProjection consent).
     */
    private void toggleScreenRecord() {
        Intent intent = new Intent("com.android.systemui.screenrecord.RecordingService")
                .setPackage("com.android.systemui");
        android.content.ServiceConnection conn = new android.content.ServiceConnection() {
            @Override
            public void onServiceConnected(android.content.ComponentName name,
                    android.os.IBinder service) {
                try {
                    com.android.systemui.screenrecord.IRemoteRecording rec =
                            com.android.systemui.screenrecord.IRemoteRecording.Stub
                                    .asInterface(service);
                    if (rec.isRecording() || rec.isStarting()) {
                        rec.stopRecording();
                    } else {
                        rec.startRecording();
                    }
                } catch (android.os.RemoteException e) {
                    Log.w(TAG, "screen record", e);
                }
                mContext.unbindService(this);
            }

            @Override
            public void onServiceDisconnected(android.content.ComponentName name) {
            }
        };
        if (!mContext.bindServiceAsUser(intent, conn, Context.BIND_AUTO_CREATE,
                android.os.UserHandle.CURRENT)) {
            Log.w(TAG, "screen record: SystemUI RecordingService not found");
            // a failed bind still holds the connection until unbound
            mContext.unbindService(conn);
        }
    }
}
