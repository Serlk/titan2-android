package org.titan2.rear;

import android.app.Application;
import android.content.Intent;

/**
 * Persistent process: the gesture service comes up right at startup, without the home screen;
 * it also subscribes to the Titan2Parts settings and binds to the SystemUI unlock service.
 */
public class RearApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Cfg.get(this);
        UnlockClient.get(this).bind();
        startService(new Intent(this, RearGestureService.class));
    }
}
