package com.cleanframe.addon.appleremote;

import android.content.Context;

public final class RemoteNative {
    private static SecretStore secrets;
    private static volatile HostInputBridge host;
    static { System.loadLibrary("cleanframe_remote"); }
    private RemoteNative() {}

    static synchronized void initialize(Context context) {
        if (secrets == null) secrets = new SecretStore(context.getApplicationContext());
    }

    public static native String request(String json);

    static void setHost(HostInputBridge value) { host = value; }
    static void clearHost(HostInputBridge expected) { if (host == expected) host = null; }

    /** Rust calls from its bounded blocking worker; never logs input or reenters request(). */
    public static String hostInput(String json) {
        HostInputBridge value = host;
        return value == null ? "{\"ok\":false,\"error\":\"disabled\",\"enabled\":false,\"connected\":false,\"keyboard\":null}" : value.request(json);
    }

    /** Called synchronously from Rust before acknowledging pairing changes. Never log this payload. */
    public static synchronized boolean persistSecrets(String json) {
        try {
            if (secrets == null) return false;
            secrets.write(json);
            return true;
        } catch (Exception failure) { return false; }
    }
}
