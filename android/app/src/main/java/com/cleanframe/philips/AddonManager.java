package com.cleanframe.philips;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Declarative API 1 widgets and separately installed, verified API 2 applications. */
final class AddonManager implements AutoCloseable {
    static final String BASE = "https://raw.githubusercontent.com/AntoinS/cleanframe-addons/main/";
    private final AtomicFile storage;
    private final Context context;
    private final NativeAddonInstaller nativeInstaller;
    private JSONObject setup = new JSONObject();
    private boolean setupPolling;
    private final JSONObject runtime = new JSONObject();
    private long lastRuntimePoll;
    private long lastCatalogCheck;
    private boolean polling;
    private volatile boolean closed;
    private final Runnable installerChanged = this::resume;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable changed;
    private JSONObject installed = new JSONObject();
    private JSONArray catalog = new JSONArray();
    private AddonUpdateBatch batch = new AddonUpdateBatch(null);
    private boolean busy;
    private String error = "";

    AddonManager(Context context, Runnable changed) { this(context, changed, true); }
    AddonManager(Context context, Runnable changed, boolean installObserver) {
        this.changed = changed;
        this.context = context.getApplicationContext();
        nativeInstaller = new NativeAddonInstaller(this.context);
        if (installObserver) NativeAddonInstaller.changed = installerChanged;
        storage = new AtomicFile(new File(context.getFilesDir(), "addons.json"));
        try {
            if (storage.getBaseFile().exists()) {
                JSONObject saved = new JSONObject(new String(storage.readFully(), StandardCharsets.UTF_8));
                installed = saved.getJSONObject("installed");
                batch = new AddonUpdateBatch(saved.optJSONObject("batch"));
                catalog = saved.optJSONArray("catalog");
                if (catalog == null) catalog = new JSONArray();
            } else {
                try (InputStream input = context.getAssets().open("home/addons/spotify.json")) {
                    installed.put("spotify", new JSONObject(new String(read(input, 65536), StandardCharsets.UTF_8)).put("enabled", true));
                }
                save();
            }
            // Bundled, reviewed descriptor makes previously sideloaded test builds manageable offline.
            try (InputStream input = context.getAssets().open("home/addons/apple-remote.json")) {
                JSONObject bundled = new JSONObject(new String(read(input, 65536), StandardCharsets.UTF_8)); validate(bundled);
                int found = -1;
                for (int i = 0; i < catalog.length(); i++) if (catalog.getJSONObject(i).getString("id").equals(bundled.getString("id"))) found = i;
                if (found < 0) catalog.put(bundled);
                else if (catalog.getJSONObject(found).optLong("versionCode") < bundled.getLong("versionCode")) catalog.put(found, bundled);
                save();
            }
        } catch (Exception failure) { error = "Add-on storage could not be read."; }
    }
    synchronized boolean operating() { return busy || batch.active() || pendingOperation(); }
    synchronized boolean enabled(String id) {
        JSONObject item = installed.optJSONObject(id);
        return item != null && item.optBoolean("enabled", true);
    }
    synchronized String state() {
        try { return new JSONObject().put("installed", installed).put("catalog", catalog).put("busy", busy).put("error", error).put("api", 2).put("runtime", runtime).put("pending", NativeAddonInstaller.pending(context)).put("batch", batch.state(false)).toString(); }
        catch (Exception failure) { return "{\"error\":\"Add-on state unavailable\"}"; }
    }
    synchronized String request(String value) {
        try {
            JSONObject request = new JSONObject(value);
            String action = request.getString("action"), id = request.optString("id");
            if (action.equals("remoteState")) {
                if (!setupPolling) {
                    setupPolling = true;
                    worker.execute(() -> {
                        try { JSONObject result = remoteCommand(new JSONObject().put("api", 1).put("op", "setupStatus")); synchronized (this) { setup = result; } }
                        catch (Exception failure) { synchronized (this) { setup = new JSONObject(); try { setup.put("error", "Receiver unavailable. Retry or update the add-on."); } catch (Exception ignored) { } } }
                        finally { synchronized (this) { setupPolling = false; } }
                    });
                }
                return setup.toString();
            }
            if (action.equals("remote")) {
                JSONObject command = request.getJSONObject("command");
                if (command.toString().length() > 4096) throw new IOException("Invalid setup request.");
                worker.execute(() -> {
                    try { JSONObject result = remoteCommand(command); synchronized (this) { setup = result; } }
                    catch (Exception failure) { synchronized (this) { error = "Receiver request failed. Please retry."; } }
                    if (!closed) changed.run();
                });
                return "{\"ok\":true}";
            }
            if (action.equals("state")) {
                pollRuntime();
                if (!busy && !pendingOperation() && !batch.active() && System.currentTimeMillis() - lastCatalogCheck > 15 * 60 * 1000)
                    request("{\"action\":\"refresh\"}");
                return state();
            }
            if (busy || pendingOperation() || batch.active()) throw new IllegalStateException("Another add-on operation is running.");
            if (!action.matches("refresh|install|remove|toggle|updateAll")) throw new IllegalArgumentException("Unknown add-on action.");
            if (!action.equals("refresh") && !action.equals("updateAll") && !id.matches("[a-z][a-z0-9-]{0,47}")) throw new IllegalArgumentException("Invalid add-on ID.");
            busy = true; error = "";
            if (action.equals("refresh")) lastCatalogCheck = System.currentTimeMillis();
            worker.execute(() -> {
                try { if (action.equals("updateAll")) { startBatch(); runBatch(); } else operate(action, id); }
                catch (Exception failure) { synchronized (this) { error = failure.getMessage() == null ? "Add-on operation failed." : failure.getMessage(); batch.stop("failed"); try { save(); } catch (Exception ignored) { } } }
                finally { synchronized (this) { busy = false; } if (!closed) changed.run(); }
            });
            return "{\"ok\":true}";
        } catch (Exception failure) {
            try { return new JSONObject().put("error", failure.getMessage()).toString(); }
            catch (Exception ignored) { return "{\"error\":\"Invalid request\"}"; }
        }
    }
    private JSONObject remoteCommand(JSONObject command) throws Exception {
        JSONObject descriptor;
        synchronized (this) { descriptor = installed.optJSONObject("apple-remote"); if (descriptor != null) descriptor = new JSONObject(descriptor.toString()); }
        if (descriptor == null) throw new IOException("Remote add-on is not installed.");
        return NativeAddonClient.request(context, nativeInstaller, descriptor, command);
    }
    private void operate(String action, String id) throws Exception { operate(action, id, null); }
    private void operate(String action, String id, JSONObject target) throws Exception {
        if (closed) throw new IOException("Add-on manager closed.");
        JSONObject packageData = null;
        JSONArray downloaded = null;
        if (action.equals("refresh")) {
            JSONObject index = new JSONObject(new String(download("catalog-v2.json", 131072), StandardCharsets.UTF_8));
            if (index.getInt("api") != 2) throw new IOException("Unsupported catalog API.");
            downloaded = index.getJSONArray("addons");
            if (downloaded.length() > 32) throw new IOException("Catalog is too large.");
            java.util.HashSet<String> ids = new java.util.HashSet<>();
            for (int i = 0; i < downloaded.length(); i++) {
                JSONObject item = downloaded.getJSONObject(i); validate(item);
                if (!ids.add(item.getString("id"))) throw new IOException("Duplicate add-on ID.");
                if (!item.getString("kind").equals("native")) validatePath(item.getString("path"));
                if (!item.getString("sha256").matches("[a-f0-9]{64}")) throw new IOException("Invalid package checksum.");
            }
        } else if (action.equals("install")) {
            JSONObject entry = null;
            synchronized (this) { for (int i = 0; i < catalog.length(); i++) if (catalog.getJSONObject(i).getString("id").equals(id)) entry = new JSONObject(catalog.getJSONObject(i).toString()); }
            if (target != null) entry = new JSONObject(target.toString());
            if (entry == null) throw new IOException("Refresh the catalog before downloading.");
            synchronized (this) {
                JSONObject current = installed.optJSONObject(id);
                if (current != null && !entry.optString("kind").equals(current.optString("kind"))) throw new IOException("An update cannot change the add-on type.");
                if (current != null && !entry.optString("kind").equals("native") && !AddonVersions.newer(entry, current)) throw new IOException("This add-on is already up to date.");
            }
            if (entry.getString("kind").equals("native")) { nativeInstaller.install(entry); return; }
            byte[] bytes = download(entry.getString("path"), 65536);
            StringBuilder sha = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) sha.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            if (!sha.toString().equals(entry.getString("sha256"))) throw new IOException("Package checksum mismatch.");
            packageData = new JSONObject(new String(bytes, StandardCharsets.UTF_8)); validate(packageData);
            if (!id.equals(packageData.getString("id")) || !entry.getString("version").equals(packageData.getString("version")) || !entry.getString("kind").equals(packageData.getString("kind"))) throw new IOException("Package does not match catalog.");
        }
        if (action.matches("remove|toggle|setup")) {
            JSONObject item;
            synchronized (this) { item = installed.optJSONObject(id); if (item != null) item = new JSONObject(item.toString()); }
            if (item == null) throw new IOException("Add-on is not installed.");
            if (item.optString("kind").equals("native")) {
                if (action.equals("remove")) { nativeInstaller.remove(item); return; }
                JSONObject actual = NativeAddonClient.request(context, nativeInstaller, item, "status");
                boolean enable = !actual.optBoolean("enabled", false);
                JSONObject result = NativeAddonClient.request(context, nativeInstaller, item, enable ? "enable" : "disable");
                synchronized (this) { runtime.put(id, result); }
                // The receiver owns activation persistence. Refresh its status asynchronously.
                return;
            }
            if (action.equals("setup")) throw new IOException("This widget is configured in CleanTV.");
        }
        synchronized (this) {
            if (closed) throw new IOException("Add-on manager closed.");
            JSONObject oldInstalled = new JSONObject(installed.toString());
            JSONArray oldCatalog = catalog;
            try {
                if (downloaded != null) catalog = downloaded;
                else if (packageData != null) installed.put(id, packageData.put("enabled", enabled(id) || !installed.has(id)));
                else if (action.equals("remove")) installed.remove(id);
                else if (action.equals("toggle")) installed.getJSONObject(id).put("enabled", !enabled(id));
                int active = 0;
                java.util.Iterator<String> ids = installed.keys();
                while (ids.hasNext()) { String key = ids.next(); if (NativeAddonPolicy.countsAsWidget(installed.getJSONObject(key))) active++; }
                if (active > 3) throw new IOException("Disable another widget first (maximum three active widgets).");
                save();
            } catch (Exception failure) { installed = oldInstalled; catalog = oldCatalog; throw failure; }
        }
    }
    private synchronized void startBatch() throws Exception {
        JSONArray targets = new JSONArray();
        for (int i = 0; i < catalog.length(); i++) {
            JSONObject entry = catalog.getJSONObject(i), current = installed.optJSONObject(entry.getString("id"));
            if (current == null) continue;
            if (current.optString("kind").equals("native")) current = nativeInstaller.installedVersion(current);
            if (AddonVersions.newer(entry, current)) targets.put(entry);
        }
        batch = AddonUpdateBatch.start(targets); save();
    }
    private void runBatch() throws Exception {
        while (!closed) {
            JSONObject target, current;
            synchronized (this) {
                if (!batch.active() || pendingOperation()) return;
                target = batch.current(); current = installed.optJSONObject(target.getString("id"));
            }
            String id = target.getString("id");
            try {
                if (current != null && current.optString("kind").equals("native")) current = nativeInstaller.installedVersion(current);
                if (AddonVersions.newer(target, current)) {
                    operate("install", id, target);
                    synchronized (this) {
                        if (pendingOperation()) return;
                        if (NativeAddonInstaller.pending(context).optString("status").equals("permission-required")) throw new IOException("Allow add-on installation in Android, then start updates again.");
                    }
                }
                synchronized (this) { batch.confirm(id); save(); }
                if (!closed) changed.run();
            } catch (Exception failure) {
                synchronized (this) { batch.stop("failed"); error = "Update stopped: " + failure.getMessage(); save(); }
                return;
            }
        }
    }

    static void validate(JSONObject item) throws Exception {
        boolean nativeKind = item.optString("kind").equals("native");
        if (item.getInt("api") != (nativeKind ? 2 : 1) || !item.getString("id").matches("[a-z][a-z0-9-]{0,47}") || !item.getString("version").matches("[0-9]+\\.[0-9]+\\.[0-9]+")) throw new IOException("Unsupported package identity or API.");
        String kind = item.getString("kind");
        if (!kind.matches("spotify|clock|text|native") || (kind.equals("spotify") && !item.getString("id").equals("spotify"))) throw new IOException("Unsupported widget kind.");
        for (String key : new String[]{"name", "description", "author", "license"}) if (item.getString(key).isEmpty() || item.getString(key).length() > 240) throw new IOException("Invalid package metadata.");
        if (nativeKind) NativeAddonPolicy.validate(item);
        if (kind.equals("clock") && item.has("timeZone")) java.time.ZoneId.of(item.getString("timeZone"));
        if (item.optString("text").length() > 500) throw new IOException("Widget text is too long.");
    }
    static void validatePath(String path) throws IOException {
        if (!path.matches("packages/[a-z0-9-]+/[0-9]+\\.[0-9]+\\.[0-9]+\\.json")) throw new IOException("Invalid catalog package path.");
    }
    private static byte[] download(String path, int limit) throws Exception {
        if (!path.equals("catalog.json") && !path.equals("catalog-v2.json")) validatePath(path);
        HttpURLConnection connection = (HttpURLConnection) new URL(BASE + path).openConnection();
        connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(10000); connection.setReadTimeout(15000);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Catalog download failed (HTTP " + connection.getResponseCode() + ").");
            try (InputStream input = connection.getInputStream()) { return read(input, limit); }
        } finally { connection.disconnect(); }
    }
    private static byte[] read(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int size;
        while ((size = input.read(buffer)) != -1) { if (out.size() + size > limit) throw new IOException("Package exceeds size limit."); out.write(buffer, 0, size); }
        return out.toByteArray();
    }
    private void save() throws Exception {
        byte[] bytes = new JSONObject().put("installed", installed).put("catalog", catalog).put("batch", batch.state(true)).toString().getBytes(StandardCharsets.UTF_8);
        FileOutputStream output = storage.startWrite();
        try { output.write(bytes); storage.finishWrite(output); }
        catch (Exception failure) { storage.failWrite(output); throw failure; }
    }
    private synchronized boolean pendingOperation() {
        String status = NativeAddonInstaller.pending(context).optString("status");
        return status.equals("awaiting-confirmation") || status.equals("checking");
    }
    synchronized void resume() {
        if (closed) return;
        worker.execute(() -> {
            try { reconcile(); runBatch(); }
            catch (Exception failure) {
                synchronized (this) { error = "The package operation could not be verified. Existing settings are retained."; batch.stop("failed"); try { save(); } catch (Exception ignored) { } }
                try { NativeAddonInstaller.savePending(context, new JSONObject()); } catch (Exception ignored) { }
            }
            if (!closed) changed.run();
            synchronized (this) { lastRuntimePoll = 0; pollRuntime(); }
        });
    }
    private synchronized void reconcile() throws Exception {
        JSONObject pending = NativeAddonInstaller.pending(context);
        String status = pending.optString("status");
        JSONObject descriptor = pending.optJSONObject("descriptor");
        if (descriptor != null && status.equals("checking")) {
            if (pending.optString("action").equals("install")) {
                nativeInstaller.verifiedInstalled(descriptor);
                String id = descriptor.getString("id");
                boolean firstInstall = !installed.has(id);
                boolean keepEnabled = firstInstall || installed.getJSONObject(id).optBoolean("enabled", true);
                try { keepEnabled = firstInstall || NativeAddonClient.request(context, nativeInstaller, descriptor, "status").optBoolean("enabled", keepEnabled); }
                catch (Exception unavailable) { /* Installation is verified; a restarting service does not invalidate it. */ }
                installed.put(id, new JSONObject(descriptor.toString()).put("enabled", keepEnabled));
                save();
                // A confirmed first installation opts into setup. Updates retain an owner's disabled state.
                if (firstInstall) NativeAddonClient.request(context, nativeInstaller, descriptor, "enable");
            } else if (!nativeInstaller.present(descriptor.getString("packageName"))) {
                installed.remove(descriptor.getString("id")); runtime.remove(descriptor.getString("id")); save();
            } else throw new IOException("Package removal was not confirmed.");
            NativeAddonInstaller.savePending(context, new JSONObject());
            if (pending.optString("action").equals("install")) { batch.confirm(descriptor.getString("id")); save(); }
        } else if (status.equals("cancelled") || status.equals("failed")) {
            error = status.equals("cancelled") ? "Package operation cancelled. Remaining updates were stopped." : "Package operation failed; the previous version was kept. Remaining updates were stopped.";
            batch.stop(status); save();
            NativeAddonInstaller.savePending(context, new JSONObject());
        }
        for (int i = 0; i < catalog.length(); i++) {
            JSONObject entry = catalog.getJSONObject(i);
            if (!entry.optString("kind").equals("native") || !nativeInstaller.present(entry.optString("packageName"))) continue;
            JSONObject previous = installed.optJSONObject(entry.getString("id"));
            if (previous != null && previous.optLong("versionCode") == entry.optLong("versionCode")) continue;
            try { nativeInstaller.verifiedInstalled(entry); installed.put(entry.getString("id"), new JSONObject(entry.toString())); save(); }
            catch (Exception ignored) { /* Keep the existing trusted descriptor until the installed artifact matches. */ }
        }
        java.util.Iterator<String> ids = installed.keys();
        boolean dirty = false;
        while (ids.hasNext()) {
            String id = ids.next(); JSONObject item = installed.getJSONObject(id);
            if (item.optString("kind").equals("native") && !nativeInstaller.present(item.getString("packageName"))) { ids.remove(); runtime.remove(id); dirty = true; }
        }
        if (dirty) save();
    }
    private synchronized void pollRuntime() {
        if (closed || polling || System.currentTimeMillis() - lastRuntimePoll < 3000) return;
        polling = true; lastRuntimePoll = System.currentTimeMillis();
        worker.execute(() -> {
            try {
                reconcile();
                runBatch();
                JSONObject snapshot;
                synchronized (this) { snapshot = new JSONObject(installed.toString()); }
                java.util.Iterator<String> ids = snapshot.keys();
                while (ids.hasNext()) {
                    String id = ids.next(); JSONObject item = snapshot.getJSONObject(id);
                    if (!item.optString("kind").equals("native")) continue;
                    JSONObject result;
                    try { result = NativeAddonClient.request(context, nativeInstaller, item, "status"); }
                    catch (Exception failure) {
                        result = new JSONObject().put("available", false).put("error", "Add-on service is unavailable or untrusted.");
                        try { JSONObject actual = nativeInstaller.installedVersion(item); result.put("installedVersion", actual.getString("version")).put("installedVersionCode", actual.getLong("versionCode")); }
                        catch (Exception untrusted) { /* Never infer activation from stale package metadata. */ }
                    }
                    synchronized (this) { runtime.put(id, result); }
                }
            } catch (Exception ignored) { }
            finally { synchronized (this) { polling = false; } if (!closed) changed.run(); }
        });
    }
    public synchronized void close() {
        closed = true;
        nativeInstaller.close();
        if (NativeAddonInstaller.changed == installerChanged) NativeAddonInstaller.changed = null;
        worker.shutdownNow();
    }
}
