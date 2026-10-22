package org.titan2.parts;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Per-app firewall: the netd chain FIREWALL_CHAIN_OEM_DENY_1 (unused by the system)
 * denies an app all networks: Wi-Fi, mobile, VPN. netd rules do not survive
 * a reboot: the list is kept in Settings.Global and applied on every Titan2App start
 * and on app install/update. The API is module-lib (@hide), hence reflection.
 */
final class Firewall {
    private static final String TAG = "Titan2Firewall";
    static final String SETTING = "titan2_firewall_blocked";   // comma-separated packages
    private static final int CHAIN = 7;       // ConnectivityManager.FIREWALL_CHAIN_OEM_DENY_1
    private static final int RULE_DEFAULT = 0, RULE_DENY = 2;

    private Firewall() {}

    static Set<String> blocked(Context context) {
        String v = Settings.Global.getString(context.getContentResolver(), SETTING);
        Set<String> s = new LinkedHashSet<>();
        if (!TextUtils.isEmpty(v)) {
            s.addAll(Arrays.asList(v.split(",")));
        }
        return s;
    }

    static void setBlocked(Context context, String pkg, boolean block) {
        Set<String> s = blocked(context);
        if (block) {
            s.add(pkg);
        } else {
            s.remove(pkg);
        }
        Settings.Global.putString(context.getContentResolver(), SETTING, String.join(",", s));
        apply(context, pkg, block);
    }

    /** At startup: enable the chain, set the denials, watch app installs. */
    static void start(Context context) {
        try {
            ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
            Method enable = cm.getClass().getMethod("setFirewallChainEnabled",
                    int.class, boolean.class);
            enable.invoke(cm, CHAIN, true);
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "chain", e);
            return;
        }
        for (String pkg : blocked(context)) {
            apply(context, pkg, true);
        }
        IntentFilter f = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        f.addDataScheme("package");
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                String pkg = intent.getData() != null ? intent.getData().getSchemeSpecificPart() : null;
                if (pkg != null && blocked(c).contains(pkg)) {
                    apply(c, pkg, true);   // new uid after a reinstall
                }
            }
        }, f, null, new Handler(Looper.getMainLooper()));
    }

    private static void apply(Context context, String pkg, boolean block) {
        try {
            int uid = context.getPackageManager().getPackageUid(pkg, 0);
            if (uid < android.os.Process.FIRST_APPLICATION_UID) {
                return;   // system/phone/...: a denial would cut off the system itself
            }
            ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
            Method rule = cm.getClass().getMethod("setUidFirewallRule",
                    int.class, int.class, int.class);
            rule.invoke(cm, CHAIN, uid, block ? RULE_DENY : RULE_DEFAULT);
        } catch (PackageManager.NameNotFoundException e) {
            // not installed: the rule is applied on install
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "rule " + pkg, e);
        }
    }
}
