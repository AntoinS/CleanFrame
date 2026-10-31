package com.cleanframe.philips;

import org.json.JSONObject;
import java.io.IOException;

/** Public release metadata cannot change the installed launcher's package or author. */
final class SoftwareUpdatePolicy {
    static final String RELEASES = "https://github.com/AntoinS/CleanTV/releases/download/";
    static void validateUrl(String value) throws IOException { NativeAddonPolicy.validateReleaseUrl(value, RELEASES); }
    static void validate(JSONObject item, String ownPackage) throws Exception {
        long code = item.getLong("versionCode");
        String version = item.getString("version");
        if (item.getInt("api") != 1 || !item.getString("id").equals("software") || !item.getString("kind").equals("native")
                || !ownPackage.equals(item.getString("packageName")) || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")
                || code < 1 || code > Integer.MAX_VALUE || !item.getString("versionName").equals(version + "-android" + code)
                || item.getInt("minAndroidApi") < 31 || item.getInt("minAndroidApi") > 100
                || !item.getString("sha256").matches("[a-f0-9]{64}") || !item.getString("signerSha256").matches("[a-f0-9]{64}")
                || item.optString("name").length() > 100 || item.optString("notes").length() > 4000)
            throw new IOException("Unsupported software release metadata.");
        validateUrl(item.getString("apkUrl"));
    }
    static void verifyIdentity(JSONObject item, String ownPackage, String installedPackage, String signer, String ownSigner) throws Exception {
        validate(item, ownPackage);
        if (!ownPackage.equals(installedPackage) || !ownSigner.equals(signer) || !ownSigner.equals(item.getString("signerSha256")))
            throw new IOException("Software update has a different package or signing certificate.");
    }
    static void verifyArtifact(JSONObject item, String ownPackage, String packageName, String versionName, long versionCode, String signer, String ownSigner) throws Exception {
        verifyIdentity(item, ownPackage, packageName, signer, ownSigner);
        if (!item.getString("versionName").equals(versionName) || item.getLong("versionCode") != versionCode)
            throw new IOException("Software APK version does not match release metadata.");
    }
}
