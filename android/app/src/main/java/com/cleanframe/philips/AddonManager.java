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

/** API 1 installs declarative widget packages, never executable code or credentials. */
final class AddonManager implements AutoCloseable {
    static final String BASE = "https://raw.githubusercontent.com/AntoinS/cleanframe-addons/main/";
    private final AtomicFile storage;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable changed;
    private JSONObject installed = new JSONObject();
    private JSONArray catalog = new JSONArray();
    private boolean busy;
    private String error = "";

    AddonManager(Context context, Runnable changed) {
        this.changed = changed;
        storage = new AtomicFile(new File(context.getFilesDir(), "addons.json"));
        try {
            if (storage.getBaseFile().exists()) {
                JSONObject saved = new JSONObject(new String(storage.readFully(), StandardCharsets.UTF_8));
                installed = saved.getJSONObject("installed");
                catalog = saved.optJSONArray("catalog");
                if (catalog == null) catalog = new JSONArray();
            } else {
                try (InputStream input = context.getAssets().open("home/addons/spotify.json")) {
                    installed.put("spotify", new JSONObject(new String(read(input, 65536), StandardCharsets.UTF_8)).put("enabled", true));
                }
                save();
            }
        } catch (Exception failure) { error = "Add-on storage could not be read."; }
    }
    synchronized boolean enabled(String id) {
        JSONObject item = installed.optJSONObject(id);
        return item != null && item.optBoolean("enabled", true);
    }
    synchronized String state() {
        try { return new JSONObject().put("installed", installed).put("catalog", catalog).put("busy", busy).put("error", error).toString(); }
        catch (Exception failure) { return "{\"error\":\"Add-on state unavailable\"}"; }
    }
    synchronized String request(String value) {
        try {
            JSONObject request = new JSONObject(value);
            String action = request.getString("action"), id = request.optString("id");
            if (action.equals("state")) return state();
            if (busy) throw new IllegalStateException("Another add-on operation is running.");
            if (!action.matches("refresh|install|remove|toggle")) throw new IllegalArgumentException("Unknown add-on action.");
            if (!action.equals("refresh") && !id.matches("[a-z][a-z0-9-]{0,47}")) throw new IllegalArgumentException("Invalid add-on ID.");
            busy = true; error = "";
            worker.execute(() -> {
                try { operate(action, id); }
                catch (Exception failure) { synchronized (this) { error = failure.getMessage() == null ? "Add-on operation failed." : failure.getMessage(); } }
                finally { synchronized (this) { busy = false; } changed.run(); }
            });
            return "{\"ok\":true}";
        } catch (Exception failure) {
            try { return new JSONObject().put("error", failure.getMessage()).toString(); }
            catch (Exception ignored) { return "{\"error\":\"Invalid request\"}"; }
        }
    }
    private void operate(String action, String id) throws Exception {
        JSONObject packageData = null;
        JSONArray downloaded = null;
        if (action.equals("refresh")) {
            JSONObject index = new JSONObject(new String(download("catalog.json", 131072), StandardCharsets.UTF_8));
            if (index.getInt("api") != 1) throw new IOException("Unsupported catalog API.");
            downloaded = index.getJSONArray("addons");
            if (downloaded.length() > 32) throw new IOException("Catalog is too large.");
            java.util.HashSet<String> ids = new java.util.HashSet<>();
            for (int i = 0; i < downloaded.length(); i++) {
                JSONObject item = downloaded.getJSONObject(i); validate(item);
                if (!ids.add(item.getString("id"))) throw new IOException("Duplicate add-on ID.");
                validatePath(item.getString("path"));
                if (!item.getString("sha256").matches("[a-f0-9]{64}")) throw new IOException("Invalid package checksum.");
            }
        } else if (action.equals("install")) {
            JSONObject entry = null;
            synchronized (this) { for (int i = 0; i < catalog.length(); i++) if (catalog.getJSONObject(i).getString("id").equals(id)) entry = new JSONObject(catalog.getJSONObject(i).toString()); }
            if (entry == null) throw new IOException("Refresh the catalog before downloading.");
            byte[] bytes = download(entry.getString("path"), 65536);
            StringBuilder sha = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) sha.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            if (!sha.toString().equals(entry.getString("sha256"))) throw new IOException("Package checksum mismatch.");
            packageData = new JSONObject(new String(bytes, StandardCharsets.UTF_8)); validate(packageData);
            if (!id.equals(packageData.getString("id")) || !entry.getString("version").equals(packageData.getString("version")) || !entry.getString("kind").equals(packageData.getString("kind"))) throw new IOException("Package does not match catalog.");
        }
        synchronized (this) {
            JSONObject oldInstalled = new JSONObject(installed.toString());
            JSONArray oldCatalog = catalog;
            try {
                if (downloaded != null) catalog = downloaded;
                else if (packageData != null) installed.put(id, packageData.put("enabled", enabled(id) || !installed.has(id)));
                else if (action.equals("remove")) installed.remove(id);
                else if (action.equals("toggle")) installed.getJSONObject(id).put("enabled", !enabled(id));
                int active = 0;
                java.util.Iterator<String> ids = installed.keys();
                while (ids.hasNext()) { String key = ids.next(); if (!key.equals("spotify") && enabled(key)) active++; }
                if (active > 3) throw new IOException("Disable another widget first (maximum three active widgets).");
                save();
            } catch (Exception failure) { installed = oldInstalled; catalog = oldCatalog; throw failure; }
        }
    }
    static void validate(JSONObject item) throws Exception {
        if (item.getInt("api") != 1 || !item.getString("id").matches("[a-z][a-z0-9-]{0,47}") || !item.getString("version").matches("[0-9]+\\.[0-9]+\\.[0-9]+")) throw new IOException("Unsupported package identity or API.");
        String kind = item.getString("kind");
        if (!kind.matches("spotify|clock|text") || (kind.equals("spotify") && !item.getString("id").equals("spotify"))) throw new IOException("Unsupported widget kind.");
        for (String key : new String[]{"name", "description", "author", "license"}) if (item.getString(key).isEmpty() || item.getString(key).length() > 240) throw new IOException("Invalid package metadata.");
        if (kind.equals("clock") && item.has("timeZone")) java.time.ZoneId.of(item.getString("timeZone"));
        if (item.optString("text").length() > 500) throw new IOException("Widget text is too long.");
    }
    static void validatePath(String path) throws IOException {
        if (!path.matches("packages/[a-z0-9-]+/[0-9]+\\.[0-9]+\\.[0-9]+\\.json")) throw new IOException("Invalid catalog package path.");
    }
    private static byte[] download(String path, int limit) throws Exception {
        if (!path.equals("catalog.json")) validatePath(path);
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
        byte[] bytes = new JSONObject().put("installed", installed).put("catalog", catalog).toString().getBytes(StandardCharsets.UTF_8);
        FileOutputStream output = storage.startWrite();
        try { output.write(bytes); storage.finishWrite(output); }
        catch (Exception failure) { storage.failWrite(output); throw failure; }
    }
    public void close() { worker.shutdownNow(); }
}
