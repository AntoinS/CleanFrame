package com.cleanframe.philips;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class NativeAddonPolicyTest {
    static final String HASH = "ab".repeat(32), OTHER = "cd".repeat(32);
    JSONObject nativePackage() throws Exception {
        return new JSONObject().put("api", 2).put("id", "apple-remote").put("kind", "native")
                .put("name", "iPhone Remote").put("description", "TV receiver").put("author", "CleanFrame").put("license", "MIT")
                .put("version", "0.1.0").put("versionCode", 1).put("minHostVersion", "0.8.0")
                .put("packageName", NativeAddonPolicy.PACKAGE).put("serviceInterface", 1)
                .put("apkUrl", NativeAddonPolicy.RELEASES + "apple-remote-v0.1.0/remote.apk").put("sha256", HASH).put("signerSha256", HASH);
    }
    @Test public void supportsNativeAndExistingDeclarativePackages() throws Exception {
        AddonManager.validate(nativePackage()); AddonManager.validate(new AddonManagerTest().clock());
        assertFalse(NativeAddonPolicy.countsAsWidget(nativePackage().put("enabled", true)));
        assertTrue(NativeAddonPolicy.countsAsWidget(new AddonManagerTest().clock()));
        assertFalse(NativeAddonPolicy.countsAsWidget(new AddonManagerTest().clock().put("enabled", false)));
    }
    @Test public void rejectsUnsupportedIdentityApiAndInterface() throws Exception {
        for (String key : new String[]{"api", "id", "kind", "packageName", "serviceInterface", "sha256", "signerSha256", "versionCode", "minHostVersion"}) {
            JSONObject value = nativePackage().put(key, key.equals("api") || key.equals("serviceInterface") || key.equals("versionCode") ? -1 : "invalid");
            assertThrows(Exception.class, () -> AddonManager.validate(value));
        }
    }
    @Test public void validatesArtifactIdentityAndBothCertificates() throws Exception {
        JSONObject item = nativePackage();
        NativeAddonPolicy.verifyArtifact(item, NativeAddonPolicy.PACKAGE, "0.1.0", 1, HASH, HASH);
        assertThrows(Exception.class, () -> NativeAddonPolicy.verifyArtifact(item, "evil.package", "0.1.0", 1, HASH, HASH));
        assertThrows(Exception.class, () -> NativeAddonPolicy.verifyArtifact(item, NativeAddonPolicy.PACKAGE, "0.2.0", 1, HASH, HASH));
        assertThrows(Exception.class, () -> NativeAddonPolicy.verifyArtifact(item, NativeAddonPolicy.PACKAGE, "0.1.0", 2, HASH, HASH));
        assertThrows(Exception.class, () -> NativeAddonPolicy.verifyArtifact(item, NativeAddonPolicy.PACKAGE, "0.1.0", 1, OTHER, HASH));
        assertThrows(Exception.class, () -> NativeAddonPolicy.verifyArtifact(item, NativeAddonPolicy.PACKAGE, "0.1.0", 1, HASH, OTHER));
        NativeAddonPolicy.verifyChecksum(HASH, HASH);
        assertThrows(Exception.class, () -> NativeAddonPolicy.verifyChecksum(OTHER, HASH));
    }
    @Test public void allowsOnlyReviewedReleaseUrlsAndGithubAssetRedirects() throws Exception {
        NativeAddonPolicy.validateUrl(NativeAddonPolicy.RELEASES + "v1.0.0/remote.apk");
        for (String url : new String[]{"http://github.com/AntoinS/cleanframe-addons/releases/download/v1/remote.apk", NativeAddonPolicy.RELEASES + "../remote.apk", NativeAddonPolicy.RELEASES + "v1/remote.apk?redirect=evil", "https://github.com/other/project/releases/download/v1/remote.apk"})
            assertThrows(Exception.class, () -> NativeAddonPolicy.validateUrl(url));
        assertTrue(NativeAddonPolicy.allowedRedirect("https://release-assets.githubusercontent.com/github-production-release-asset/123/test?sig=example"));
        for (String url : new String[]{"http://release-assets.githubusercontent.com/a", "https://release-assets.githubusercontent.com.evil.test/a", "https://evil@release-assets.githubusercontent.com/a", "https://release-assets.githubusercontent.com:8443/a", "file:///tmp/remote.apk"})
            assertFalse(NativeAddonPolicy.allowedRedirect(url));
    }
    @Test public void comparesHostVersionsNumerically() {
        assertTrue(NativeAddonPolicy.hostCompatible("0.8.0-android17", "0.8.0"));
        assertTrue(NativeAddonPolicy.hostCompatible("0.8.0-android17", "0.7.0"));
        assertFalse(NativeAddonPolicy.hostCompatible("0.8.0-android17", "0.9.0"));
        assertTrue(NativeAddonPolicy.hostCompatible("0.10.0-android18", "0.8.0"));
        assertFalse(NativeAddonPolicy.hostCompatible("0.7.99-android16", "0.8.0"));
        assertFalse(NativeAddonPolicy.hostCompatible("unknown", "0.8.0"));
    }
}
