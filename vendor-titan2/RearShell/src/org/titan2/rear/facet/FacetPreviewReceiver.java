package org.titan2.rear.facet;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** "Preview" in the Titan2Parts settings (REAR_CONFIG, the settings app only). */
public class FacetPreviewReceiver extends BroadcastReceiver {
    public static final String ACTION = "org.titan2.rear.FACET_PREVIEW";

    @Override
    public void onReceive(Context c, Intent intent) {
        if (ACTION.equals(intent.getAction())) FacetController.get(c).preview();
    }
}
