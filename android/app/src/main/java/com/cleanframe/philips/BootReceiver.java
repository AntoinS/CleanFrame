package com.cleanframe.philips;

import android.app.role.RoleManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;

/** Philips opens Live TV at boot; open CleanFrame only while it is the selected Home. */
public final class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "CleanFrameBoot";

    @Override
    public void onReceive(Context context, Intent received) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(received.getAction())) return;
        RoleManager roles = context.getSystemService(RoleManager.class);
        if (roles == null || !roles.isRoleHeld(RoleManager.ROLE_HOME)) return;

        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo resolved = context.getPackageManager().resolveActivity(
                home, PackageManager.MATCH_DEFAULT_ONLY);
        ComponentName ownHome = new ComponentName(context, context.getPackageName() + ".HomeActivity");
        if (resolved == null || resolved.activityInfo == null
                || !ownHome.getPackageName().equals(resolved.activityInfo.packageName)
                || !ownHome.getClassName().equals(resolved.activityInfo.name)) return;

        try {
            context.startActivity(home.setComponent(ownHome).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            Log.i(TAG, "Requested selected Home after BOOT_COMPLETED");
        } catch (RuntimeException failure) {
            Log.e(TAG, "Could not open selected Home after boot", failure);
        }
    }
}
