package com.cleanframe.philips;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/** User-requested same-package updates. Uses the add-on download/signature/Android installer path. */
final class SoftwareUpdateManager implements AutoCloseable {
    static final String CHANNEL = "https://raw.githubusercontent.com/AntoinS/CleanTV/main/updates/stable.json";
    private final Context context;
    private final NativeAddonInstaller installer;
    private final AtomicFile cache;
    private final Runnable changed;
    private final Runnable installerChanged = this::resume;
    private final BooleanSupplier addonsBusy;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private JSONObject available;
    private long checkedAt;
    private boolean busy;
    private String error = "", result = "";
    private volatile boolean closed;
    private volatile HttpURLConnection connection;

    SoftwareUpdateManager(Context context, BooleanSupplier addonsBusy, Runnable changed) {
        this.context = context.getApplicationContext(); this.addonsBusy = addonsBusy; this.changed = changed;
        installer = new NativeAddonInstaller(this.context, true);
        cache = new AtomicFile(new File(context.getFilesDir(), "software-release.json"));
        try {
            JSONObject saved = new JSONObject(new String(cache.readFully(), StandardCharsets.UTF_8));
            JSONObject descriptor = saved.getJSONObject("available"); SoftwareUpdatePolicy.validate(descriptor, context.getPackageName());
            installer.installedIdentity(descriptor);
            available = descriptor; checkedAt = saved.optLong("checkedAt");
        } catch (Exception ignored) { /* Missing or invalid cache does not imply that the installed software is current. */ }
        NativeAddonInstaller.softwareChanged = installerChanged;
    }
    synchronized boolean operating() {
        String status = NativeAddonInstaller.pending(context, true).optString("status");
        return busy || status.equals("awaiting-confirmation") || status.equals("checking");
    }
    synchronized String state() {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            JSONObject current = new JSONObject().put("name", context.getApplicationInfo().loadLabel(context.getPackageManager()).toString())
                .put("versionName", info.versionName).put("version", info.versionName.split("-", 2)[0]).put("versionCode", info.getLongVersionCode());
            boolean newer = available != null && available.getLong("versionCode") > info.getLongVersionCode();
            return new JSONObject().put("current", current).put("available", available == null ? JSONObject.NULL : available).put("updateAvailable", newer)
                .put("compatible", available == null || android.os.Build.VERSION.SDK_INT >= available.getInt("minAndroidApi"))
                .put("busy", busy).put("addonsBusy", addonsBusy.getAsBoolean()).put("pending", NativeAddonInstaller.pending(context, true))
                .put("error", error).put("result", result).put("checkedAt", checkedAt).toString();
        } catch (Exception failure) { return "{\"error\":\"state\"}"; }
    }
    synchronized String request(String value) {
        try {
            String action = new JSONObject(value).getString("action");
            if (action.equals("state")) return state();
            if (!action.equals("refresh") && !action.equals("install")) throw new IOException();
            if (operating() || action.equals("install") && addonsBusy.getAsBoolean()) return "{\"error\":\"Another package operation is running.\"}";
            if (action.equals("install") && available == null) return "{\"error\":\"Check for a software release first.\"}";
            JSONObject target = available == null ? null : new JSONObject(available.toString());
            busy = true; error = ""; result = "";
            worker.execute(() -> {
                try { if (action.equals("refresh")) refresh(); else installer.install(target); }
                catch (Exception failure) { synchronized (this) { error = failure instanceof FileNotFoundException ? "not-published" : action.equals("refresh") ? "check" : "install"; } }
                finally { synchronized (this) { busy = false; } publish(); }
            });
            return "{\"ok\":true}";
        } catch (Exception invalid) { return "{\"error\":\"Invalid software update request.\"}"; }
    }
    private void refresh() throws Exception {
        HttpURLConnection request = (HttpURLConnection) new URL(CHANNEL).openConnection(); connection = request;
        request.setInstanceFollowRedirects(false); request.setConnectTimeout(10000); request.setReadTimeout(15000);
        try {
            if (request.getResponseCode() == 404) throw new FileNotFoundException();
            if (request.getResponseCode() != 200) throw new IOException();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = request.getInputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (closed || bytes.size() + count > 65536) throw new IOException();
                    bytes.write(buffer, 0, count);
                }
            }
            JSONObject descriptor = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
            SoftwareUpdatePolicy.validate(descriptor, context.getPackageName());
            installer.installedIdentity(descriptor);
            long now = System.currentTimeMillis();
            byte[] saved = new JSONObject().put("available", descriptor).put("checkedAt", now).toString().getBytes(StandardCharsets.UTF_8);
            FileOutputStream stream = cache.startWrite();
            try { stream.write(saved); cache.finishWrite(stream); }
            catch (Exception failure) { cache.failWrite(stream); throw failure; }
            synchronized (this) { available = descriptor; checkedAt = now; }
        } finally { connection = null; request.disconnect(); }
    }
    void resume() {
        if (closed) return;
        worker.execute(() -> {
            try {
                JSONObject pending = NativeAddonInstaller.pending(context, true);
                String status = pending.optString("status");
                JSONObject descriptor = pending.optJSONObject("descriptor");
                if (descriptor != null && (status.equals("checking") || status.equals("awaiting-confirmation"))) {
                    // The old process may be replaced before its callback. Verify the real installed artifact on reopen.
                    try {
                        installer.verifiedInstalled(descriptor);
                        NativeAddonInstaller.savePending(context, true, new JSONObject());
                        synchronized (this) { result = "installed"; error = ""; }
                    } catch (Exception notInstalled) { if (status.equals("checking")) throw notInstalled; }
                } else if (status.equals("cancelled") || status.equals("failed")) {
                    NativeAddonInstaller.savePending(context, true, new JSONObject());
                    synchronized (this) { error = status; }
                }
            } catch (Exception failure) {
                synchronized (this) { error = "verify"; }
                try { NativeAddonInstaller.savePending(context, true, new JSONObject()); } catch (Exception ignored) { }
            }
            publish();
        });
    }
    private void publish() { if (!closed) changed.run(); }
    public synchronized void close() {
        closed = true; installer.close();
        HttpURLConnection request = connection; if (request != null) request.disconnect();
        if (NativeAddonInstaller.softwareChanged == installerChanged) NativeAddonInstaller.softwareChanged = null;
        worker.shutdownNow();
    }
}
