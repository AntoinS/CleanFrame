package com.cleanframe.philips;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Android owns both confirmation dialogs. A callback alone never proves installation. */
final class NativeAddonInstaller {
    private final Context context;
    private final PackageManager packages;
    private volatile boolean cancelled;
    private volatile HttpURLConnection activeDownload;
    static volatile Runnable changed;
    private static final int MAX_APK_BYTES = 64 * 1024 * 1024;

    NativeAddonInstaller(Context context) { this.context = context; packages = context.getPackageManager(); }

    void close() {
        cancelled = true;
        HttpURLConnection connection = activeDownload;
        if (connection != null) connection.disconnect();
    }
    private void checkActive() throws IOException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new IOException("Add-on download cancelled.");
    }

    static synchronized JSONObject pending(Context context) {
        try { return new JSONObject(new String(file(context).readFully(), StandardCharsets.UTF_8)); }
        catch (FileNotFoundException missing) { return new JSONObject(); }
        catch (Exception failure) { return new JSONObject(); }
    }
    static synchronized void savePending(Context context, JSONObject value) throws IOException {
        AtomicFile file = file(context); FileOutputStream output = file.startWrite();
        try { output.write(value.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(output); }
        catch (Exception failure) { file.failWrite(output); throw new IOException("Package operation could not be saved."); }
    }
    private static AtomicFile file(Context context) { return new AtomicFile(new File(context.getFilesDir(), "addon-install.json")); }

    boolean present(String name) {
        try { packages.getPackageInfo(name, 0); return true; }
        catch (PackageManager.NameNotFoundException absent) { return false; }
    }
    PackageInfo verifiedInstalled(JSONObject descriptor) throws Exception {
        PackageInfo info = packages.getPackageInfo(descriptor.getString("packageName"), PackageManager.GET_SIGNING_CERTIFICATES);
        verify(info, descriptor); return info;
    }
    PackageInfo installedIdentity(JSONObject descriptor) throws Exception {
        PackageInfo info = packages.getPackageInfo(descriptor.getString("packageName"), PackageManager.GET_SIGNING_CERTIFICATES);
        NativeAddonPolicy.verifyInstalledIdentity(descriptor, info.packageName, signer(info),
                signer(packages.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES)));
        return info;
    }
    JSONObject installedVersion(JSONObject descriptor) throws Exception {
        PackageInfo info = installedIdentity(descriptor);
        return new JSONObject(descriptor.toString()).put("version", info.versionName).put("versionCode", info.getLongVersionCode());
    }
    void verify(PackageInfo info, JSONObject descriptor) throws Exception {
        if (info == null) throw new IOException("APK could not be inspected.");
        NativeAddonPolicy.verifyArtifact(descriptor, info.packageName, info.versionName, info.getLongVersionCode(), signer(info),
                signer(packages.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES)));
    }
    private static String signer(PackageInfo info) throws Exception {
        if (info.signingInfo == null || info.signingInfo.getApkContentsSigners().length != 1) throw new IOException("A single verified APK signer is required.");
        return hex(MessageDigest.getInstance("SHA-256").digest(info.signingInfo.getApkContentsSigners()[0].toByteArray()));
    }
    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
    void install(JSONObject descriptor) throws Exception {
        checkActive();
        NativeAddonPolicy.validate(descriptor);
        if (present(descriptor.getString("packageName")) && !AddonVersions.newer(descriptor, installedVersion(descriptor)))
            throw new IOException("This version is already installed or older than the installed add-on.");
        String host = packages.getPackageInfo(context.getPackageName(), 0).versionName;
        if (!NativeAddonPolicy.hostCompatible(host, descriptor.getString("minHostVersion"))) throw new IOException("Update CleanTV before installing this add-on.");
        if (!packages.canRequestPackageInstalls()) {
            savePending(context, new JSONObject().put("action", "install").put("id", descriptor.getString("id")).put("status", "permission-required"));
            context.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        File apk = File.createTempFile("reviewed-addon-", ".apk", context.getCacheDir());
        PackageInstaller installer = packages.getPackageInstaller();
        int sessionId = -1;
        boolean committed = false;
        try {
            download(descriptor, apk);
            checkActive();
            verify(packages.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES), descriptor);
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(descriptor.getString("packageName"));
            params.setSize(apk.length()); params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
            sessionId = installer.createSession(params);
            try (PackageInstaller.Session session = installer.openSession(sessionId)) {
                try (OutputStream output = session.openWrite("base.apk", 0, apk.length())) {
                    java.nio.file.Files.copy(apk.toPath(), output); session.fsync(output);
                }
                savePending(context, new JSONObject().put("action", "install").put("id", descriptor.getString("id")).put("descriptor", descriptor)
                        .put("sessionId", sessionId).put("status", "awaiting-confirmation"));
                checkActive();
                session.commit(callback(sessionId).getIntentSender()); committed = true;
            }
        } finally {
            if (!committed && sessionId >= 0) {
                installer.abandonSession(sessionId);
                savePending(context, new JSONObject());
            }
            if (apk.exists()) apk.delete();
        }
    }
    void remove(JSONObject descriptor) throws Exception {
        checkActive();
        installedIdentity(descriptor);
        // No privileged DELETE_PACKAGES permission: Android always asks the user.
        int request = (int) (System.nanoTime() & 0x3fffffff);
        savePending(context, new JSONObject().put("action", "remove").put("id", descriptor.getString("id")).put("descriptor", descriptor)
                .put("sessionId", request).put("status", "awaiting-confirmation"));
        try { packages.getPackageInstaller().uninstall(descriptor.getString("packageName"), callback(request).getIntentSender()); }
        catch (Exception failure) { savePending(context, new JSONObject()); throw failure; }
    }
    private PendingIntent callback(int id) {
        Intent result = new Intent(context, AddonInstallReceiver.class).setAction("com.cleanframe.philips.ADDON_INSTALL_RESULT")
                .setData(Uri.parse("cleanframe-install:" + id));
        // Explicit private receiver; PackageInstaller needs to add its result extras.
        return PendingIntent.getBroadcast(context, id, result, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
    }
    private void download(JSONObject descriptor, File file) throws Exception {
        String location = descriptor.getString("apkUrl"); NativeAddonPolicy.validateUrl(location);
        for (int redirect = 0; redirect < 4; redirect++) {
            checkActive();
            HttpURLConnection connection = (HttpURLConnection) new URL(location).openConnection();
            activeDownload = connection;
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(10000); connection.setReadTimeout(20000);
            try {
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    location = connection.getHeaderField("Location");
                    if (!NativeAddonPolicy.allowedRedirect(location)) throw new IOException("Untrusted APK download redirect.");
                    continue;
                }
                if (status != 200) throw new IOException("APK download failed (HTTP " + status + ").");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream input = connection.getInputStream(); OutputStream output = new FileOutputStream(file)) {
                    byte[] buffer = new byte[32768]; int count; long total = 0;
                    while ((count = input.read(buffer)) != -1) {
                        checkActive();
                        total += count; if (total > MAX_APK_BYTES) throw new IOException("APK exceeds size limit.");
                        output.write(buffer, 0, count); digest.update(buffer, 0, count);
                    }
                }
                NativeAddonPolicy.verifyChecksum(hex(digest.digest()), descriptor.getString("sha256"));
                return;
            } finally { activeDownload = null; connection.disconnect(); }
        }
        throw new IOException("Too many APK redirects.");
    }
}
