package com.cleanframe.philips;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import com.cleanframe.addonapi.IRemoteAddon;
import org.json.JSONObject;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Short-lived, same-signer binding. Reading status never enables the receiver. */
final class NativeAddonClient {
    static JSONObject request(Context context, NativeAddonInstaller installer, JSONObject descriptor, String operation) throws Exception {
        return request(context, installer, descriptor, new JSONObject().put("api", 1).put("op", operation));
    }
    static JSONObject request(Context context, NativeAddonInstaller installer, JSONObject descriptor, JSONObject command) throws Exception {
        String operation = command.optString("op");
        if (!operation.matches("status|setupStatus|enable|disable|philipsPairBegin|philipsPairFinish|applePairBegin|unpair")) throw new IOException("Unsupported host operation.");
        installer.installedIdentity(descriptor);
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<IRemoteAddon> remote = new AtomicReference<>();
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                try { installer.installedIdentity(descriptor); remote.set(IRemoteAddon.Stub.asInterface(binder)); }
                catch (Exception ignored) { }
                ready.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) { remote.set(null); ready.countDown(); }
            @Override public void onNullBinding(ComponentName name) { ready.countDown(); }
            @Override public void onBindingDied(ComponentName name) { ready.countDown(); }
        };
        boolean bound = context.bindService(new Intent().setComponent(new ComponentName(NativeAddonPolicy.PACKAGE, NativeAddonPolicy.SERVICE)), connection, Context.BIND_AUTO_CREATE);
        if (!bound) throw new IOException("Add-on service is unavailable.");
        try {
            if (!ready.await(4, TimeUnit.SECONDS) || remote.get() == null) throw new IOException("Add-on service did not connect.");
            String response = remote.get().request(command.toString());
            if (response == null || response.length() > 65536) throw new IOException("Invalid add-on status.");
            JSONObject result = new JSONObject(response);
            if (result.has("error")) throw new IOException("Add-on request failed. Open setup for details.");

            android.content.pm.PackageInfo actual = installer.installedIdentity(descriptor);
            result.put("installedVersion", actual.versionName).put("installedVersionCode", actual.getLongVersionCode());
            return result;
        } finally { context.unbindService(connection); }
    }
}
