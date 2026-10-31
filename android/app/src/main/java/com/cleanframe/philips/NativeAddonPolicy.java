package com.cleanframe.philips;

import org.json.JSONObject;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;

/** A catalog cannot grant executable packages new capabilities or identities. */
final class NativeAddonPolicy {
    static final String PACKAGE = "com.cleanframe.addon.appleremote";
    static final String SERVICE = PACKAGE + ".RemoteService";
    static final String ACTIVITY = PACKAGE + ".SetupActivity";
    static final String RELEASES = "https://github.com/AntoinS/cleanframe-addons/releases/download/";

    static void validate(JSONObject item) throws Exception {
        if (item.getInt("api") != 2 || !item.getString("id").equals("apple-remote")
                || !item.getString("kind").equals("native") || !item.getString("packageName").equals(PACKAGE)
                || item.getInt("serviceInterface") != 1 || item.getLong("versionCode") < 1
                || !item.getString("minHostVersion").matches("[0-9]+\\.[0-9]+\\.[0-9]+")
                || !item.getString("sha256").matches("[a-f0-9]{64}")
                || !item.getString("signerSha256").matches("[a-f0-9]{64}"))
            throw new IOException("Unsupported native add-on identity or interface.");
        validateUrl(item.getString("apkUrl"));
    }

    static void validateUrl(String value) throws IOException { validateReleaseUrl(value, RELEASES); }
    static void validateReleaseUrl(String value, String releaseBase) throws IOException {
        if (!value.matches(java.util.regex.Pattern.quote(releaseBase) + "[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._-]*\\.apk"))
            throw new IOException("APK must be a reviewed CleanTV release asset.");
    }

    static boolean allowedRedirect(String value) {
        try {
            URI uri = new URI(value);
            return "https".equals(uri.getScheme()) && "release-assets.githubusercontent.com".equals(uri.getHost())
                    && uri.getUserInfo() == null && uri.getPort() == -1 && uri.getFragment() == null;
        } catch (Exception failure) { return false; }
    }

    static boolean hostCompatible(String actual, String minimum) {
        try {
            String[] a = actual.split("-", 2)[0].split("\\."), b = minimum.split("\\.");
            if (a.length != 3 || b.length != 3) return false;
            for (int i = 0; i < 3; i++) {
                int comparison = new BigInteger(a[i]).compareTo(new BigInteger(b[i]));
                if (comparison != 0) return comparison > 0;
            }
            return true;
        } catch (Exception failure) { return false; }
    }

    static boolean countsAsWidget(JSONObject item) {
        return item.optString("kind").matches("clock|text") && item.optBoolean("enabled", true);
    }

    static void verifyArtifact(JSONObject descriptor, String packageName, String versionName, long versionCode, String signer, String hostSigner) throws Exception {
        validate(descriptor);
        if (!descriptor.getString("packageName").equals(packageName) || !descriptor.getString("version").equals(versionName)
                || descriptor.getLong("versionCode") != versionCode) throw new IOException("APK identity or version does not match catalog.");
        verifyInstalledIdentity(descriptor, packageName, signer, hostSigner);
    }

    static void verifyInstalledIdentity(JSONObject descriptor, String packageName, String signer, String hostSigner) throws Exception {
        validate(descriptor);
        if (!descriptor.getString("packageName").equals(packageName) || !descriptor.getString("signerSha256").equals(signer) || !hostSigner.equals(signer))
            throw new IOException("Installed add-on identity or signing certificate is not trusted.");
    }

    static void verifyChecksum(String actual, String expected) throws IOException {
        if (!actual.matches("[a-f0-9]{64}") || !actual.equals(expected)) throw new IOException("APK checksum mismatch.");
    }
}
