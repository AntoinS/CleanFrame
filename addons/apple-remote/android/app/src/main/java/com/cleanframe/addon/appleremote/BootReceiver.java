package com.cleanframe.addon.appleremote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if ((Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) && RemoteService.isEnabled(context)) {
            context.startForegroundService(new Intent(context, RemoteService.class).setAction(RemoteService.ENABLE));
        }
    }
}
