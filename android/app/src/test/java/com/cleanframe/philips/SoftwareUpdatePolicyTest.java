package com.cleanframe.philips;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class SoftwareUpdatePolicyTest {
    static final String PACKAGE = "com.cleanframe.philips", HASH = "ab".repeat(32), OTHER = "cd".repeat(32);
    JSONObject release() throws Exception {
        return new JSONObject().put("api", 1).put("id", "software").put("kind", "native").put("packageName", PACKAGE)
            .put("version", "0.9.0").put("versionName", "0.9.0-android27").put("versionCode", 27).put("minAndroidApi", 31)
            .put("apkUrl", SoftwareUpdatePolicy.RELEASES + "v0.9.0/CleanTV-0.9.0-android27.apk").put("sha256", HASH).put("signerSha256", HASH);
    }
    @Test public void acceptsOnlyOwnPackageAuthorAndExactReleaseArtifact() throws Exception {
        JSONObject item = release(); SoftwareUpdatePolicy.verifyArtifact(item, PACKAGE, PACKAGE, "0.9.0-android27", 27, HASH, HASH);
        assertThrows(Exception.class, () -> SoftwareUpdatePolicy.verifyArtifact(item, PACKAGE, "other.package", "0.9.0-android27", 27, HASH, HASH));
        assertThrows(Exception.class, () -> SoftwareUpdatePolicy.verifyArtifact(item, PACKAGE, PACKAGE, "0.9.0-android26", 26, HASH, HASH));
        assertThrows(Exception.class, () -> SoftwareUpdatePolicy.verifyArtifact(item, PACKAGE, PACKAGE, "0.9.0-android27", 27, OTHER, HASH));
        assertThrows(Exception.class, () -> SoftwareUpdatePolicy.verifyArtifact(item, PACKAGE, PACKAGE, "0.9.0-android27", 27, HASH, OTHER));
    }
    @Test public void rejectsChangedIdentityVersionAndUntrustedDownloadLocations() throws Exception {
        for (String key : new String[]{"api", "id", "kind", "packageName", "version", "versionName", "versionCode", "minAndroidApi", "sha256", "signerSha256", "apkUrl"}) {
            JSONObject item = release().put(key, key.equals("api") || key.equals("versionCode") || key.equals("minAndroidApi") ? -1 : "invalid");
            assertThrows(key, Exception.class, () -> SoftwareUpdatePolicy.validate(item, PACKAGE));
        }
        assertThrows(Exception.class, () -> SoftwareUpdatePolicy.validate(release().put("versionCode", 2147483648L), PACKAGE));
        assertThrows(Exception.class, () -> SoftwareUpdatePolicy.validate(release().put("notes", "a".repeat(4001)), PACKAGE));
    }
    @Test public void neverOffersSameOrOlderBuildAsAnUpdate() throws Exception {
        JSONObject current = release();
        assertFalse(AddonVersions.newer(release(), current));
        assertFalse(AddonVersions.newer(release().put("versionCode", 26), current));
        assertTrue(AddonVersions.newer(release().put("versionCode", 28), current));
    }
}
