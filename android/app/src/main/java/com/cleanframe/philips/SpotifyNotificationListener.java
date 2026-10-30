package com.cleanframe.philips;

import android.service.notification.NotificationListenerService;

/** System-authorised session access. Notification contents are never read or stored. */
public final class SpotifyNotificationListener extends NotificationListenerService {
    static volatile Runnable changed;

    private static void notifyClient() {
        Runnable callback = changed;
        if (callback != null) callback.run();
    }

    @Override public void onListenerConnected() { notifyClient(); }
    @Override public void onListenerDisconnected() { notifyClient(); }
}
