package com.cleanframe.philips;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import org.json.JSONObject;

/** Receives only the explicit, one-operation PackageInstaller PendingIntent. */
public final class AddonInstallReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        boolean software = "com.cleanframe.philips.SOFTWARE_INSTALL_RESULT".equals(intent.getAction());
        if (!software && !"com.cleanframe.philips.ADDON_INSTALL_RESULT".equals(intent.getAction())) return;
        try {
            JSONObject pending = NativeAddonInstaller.pending(context, software);
            if (intent.getData() == null || !intent.getData().toString().equals((software ? "cleanframe-software-install:" : "cleanframe-install:") + pending.optInt("sessionId", -1))) return;
            int result = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (result == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirmation == null) throw new IllegalStateException();
                pending.put("status", "awaiting-confirmation"); NativeAddonInstaller.savePending(context, software, pending);
                context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else {
                pending.put("status", result == PackageInstaller.STATUS_SUCCESS ? "checking" : result == PackageInstaller.STATUS_FAILURE_ABORTED ? "cancelled" : "failed");
                NativeAddonInstaller.savePending(context, software, pending);
            }
        } catch (Exception failure) {
            try { JSONObject value = NativeAddonInstaller.pending(context, software); value.put("status", "failed"); NativeAddonInstaller.savePending(context, software, value); }
            catch (Exception ignored) { }
        }
        Runnable listener = software ? NativeAddonInstaller.softwareChanged : NativeAddonInstaller.changed;
        if (listener != null) listener.run();
    }
}
