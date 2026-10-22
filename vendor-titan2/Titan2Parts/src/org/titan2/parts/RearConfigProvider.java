package org.titan2.parts;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;

import org.titan2.rear.common.RearContract;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Rear screen settings and themes for the RearShell shell (the contract is RearContract in the shared
 * library). Access: the signature permission org.titan2.permission.REAR_CONFIG; data in
 * device-protected storage, the provider is directBootAware: the shell works before the first
 * unlock. The shell writes only the RearContract.SHELL_WRITABLE keys.
 */
public class RearConfigProvider extends ContentProvider {
    private static final String TAG = "Titan2Rear";

    @Override
    public boolean onCreate() {
        RearConfig.init(getContext());
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args,
            String sort) {
        List<String> seg = uri.getPathSegments();
        if (seg.isEmpty()) return null;
        final long token = Binder.clearCallingIdentity();
        try {
            switch (seg.get(0)) {
                case "settings": {
                    MatrixCursor c = new MatrixCursor(new String[] {
                            RearContract.COL_KEY, RearContract.COL_VALUE});
                    if (seg.size() > 1) {
                        String k = seg.get(1);
                        c.addRow(new Object[] {k, RearConfig.get(getContext(), k)});
                    } else {
                        for (Map.Entry<String, String> e
                                : RearConfig.all(getContext()).entrySet()) {
                            c.addRow(new Object[] {e.getKey(), e.getValue()});
                        }
                    }
                    c.setNotificationUri(getContext().getContentResolver(),
                            RearContract.SETTINGS);
                    return c;
                }
                case "themes": {
                    if (seg.size() > 1) {
                        String id = seg.get(1);
                        MatrixCursor c = new MatrixCursor(new String[] {RearContract.COL_ID,
                                RearContract.COL_NAME, RearContract.COL_JSON,
                                RearContract.COL_RAW});
                        try {
                            String json = RearThemes.resolve(getContext(), id).toString();
                            String raw = RearThemes.rawText(getContext(), id);
                            c.addRow(new Object[] {id,
                                    RearThemes.info(getContext(), id).name, json, raw});
                        } catch (Exception e) {
                            Log.w(TAG, "theme " + id + ": " + e);
                        }
                        return c;
                    }
                    MatrixCursor c = new MatrixCursor(new String[] {RearContract.COL_ID,
                            RearContract.COL_NAME, RearContract.COL_AUTHOR,
                            RearContract.COL_BUILTIN, RearContract.COL_EXTENDS});
                    for (RearThemes.Info i : RearThemes.list(getContext())) {
                        c.addRow(new Object[] {i.id, i.name, i.author, i.builtin ? 1 : 0,
                                i.extendsId});
                    }
                    c.setNotificationUri(getContext().getContentResolver(), RearContract.THEMES);
                    return c;
                }
                default:
                    return null;
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!RearContract.METHOD_PUT.equals(method) || TextUtils.isEmpty(arg) || extras == null) {
            return null;
        }
        // call() does not check the provider permissions itself, check here
        if (Binder.getCallingUid() != Process.myUid()) {
            getContext().enforceCallingPermission(RearContract.PERMISSION, "rear config");
        }
        // own process/uid system: any keys; the shell: only its own
        if (Binder.getCallingUid() != Process.myUid()
                && !Arrays.asList(RearContract.SHELL_WRITABLE).contains(arg)) {
            throw new SecurityException("key " + arg + " is not writable by the rear shell");
        }
        String value = extras.getString(RearContract.EXTRA_VALUE, "");
        final long token = Binder.clearCallingIdentity();
        try {
            RearConfig.put(getContext(), arg, value);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return Bundle.EMPTY;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read only");
        List<String> seg = uri.getPathSegments();
        if (seg.size() < 3 || !"file".equals(seg.get(0))) {
            throw new FileNotFoundException(uri.toString());
        }
        String path = TextUtils.join("/", seg.subList(2, seg.size()));
        final long token = Binder.clearCallingIdentity();
        try {
            File f = RearThemes.fileFor(getContext(), seg.get(1), path);
            if (f == null) throw new FileNotFoundException(uri.toString());
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("use call(\"put\")");
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException("use call(\"put\")");
    }
}
