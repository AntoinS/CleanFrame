package com.cleanframe.philips;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class AddonUpdatesTest {
    private JSONObject widget(String id, String version) throws Exception { return new JSONObject().put("id", id).put("kind", "text").put("version", version); }
    @Test public void onlyNewerVersionsAreUpdates() throws Exception {
        JSONObject current = widget("clock", "1.9.2");
        assertTrue(AddonVersions.newer(widget("clock", "1.10.0"), current));
        assertFalse(AddonVersions.newer(widget("clock", "1.9.2"), current));
        assertFalse(AddonVersions.newer(widget("clock", "1.8.99"), current));
        assertFalse(AddonVersions.newer(widget("other", "2.0.0"), current));
        assertFalse(AddonVersions.newer(widget("clock", "2.0.0").put("kind", "clock"), current));
        assertFalse(AddonVersions.newer(widget("clock", "invalid"), current));
    }
    @Test public void nativeUpdatesUseInstalledAndroidCodeNotVersionLabel() throws Exception {
        JSONObject current = new NativeAddonPolicyTest().nativePackage().put("versionCode", 21);
        assertTrue(AddonVersions.newer(new JSONObject(current.toString()).put("versionCode", 22), current));
        assertFalse(AddonVersions.newer(new JSONObject(current.toString()).put("version", "9.0.0").put("versionCode", 20), current));
    }
    @Test public void installationIdentityIsIndependentOfCatalogVersionButNotSigner() throws Exception {
        JSONObject descriptor = new NativeAddonPolicyTest().nativePackage();
        NativeAddonPolicy.verifyInstalledIdentity(descriptor, NativeAddonPolicy.PACKAGE, NativeAddonPolicyTest.HASH, NativeAddonPolicyTest.HASH);
        descriptor.put("version", "9.0.0").put("versionCode", 900);
        NativeAddonPolicy.verifyInstalledIdentity(descriptor, NativeAddonPolicy.PACKAGE, NativeAddonPolicyTest.HASH, NativeAddonPolicyTest.HASH);
        assertThrows(Exception.class, () -> NativeAddonPolicy.verifyInstalledIdentity(descriptor, "other.package", NativeAddonPolicyTest.HASH, NativeAddonPolicyTest.HASH));
        assertThrows(Exception.class, () -> NativeAddonPolicy.verifyInstalledIdentity(descriptor, NativeAddonPolicy.PACKAGE, NativeAddonPolicyTest.OTHER, NativeAddonPolicyTest.HASH));
        assertThrows(Exception.class, () -> NativeAddonPolicy.verifyInstalledIdentity(descriptor, NativeAddonPolicy.PACKAGE, NativeAddonPolicyTest.HASH, NativeAddonPolicyTest.OTHER));
        assertThrows(Exception.class, () -> NativeAddonPolicy.verifyArtifact(descriptor, NativeAddonPolicy.PACKAGE, "0.1.0", 21, NativeAddonPolicyTest.HASH, NativeAddonPolicyTest.HASH));
    }
    @Test public void batchResumesWithoutRepeatingConfirmedInstalls() throws Exception {
        JSONArray targets = new JSONArray().put(widget("one", "2.0.0")).put(widget("two", "2.0.0"));
        AddonUpdateBatch batch = AddonUpdateBatch.start(targets);
        targets.getJSONObject(0).put("version", "3.0.0");
        assertEquals("2.0.0", batch.current().getString("version"));
        batch.confirm("two"); assertEquals(0, batch.state(false).getInt("completed"));
        batch.confirm("one"); batch.confirm("one");
        batch = new AddonUpdateBatch(new JSONObject(batch.state(true).toString()));
        assertEquals("two", batch.current().getString("id")); assertEquals(1, batch.state(false).getInt("completed"));
        batch.confirm("two"); assertFalse(batch.active()); assertEquals("complete", batch.state(false).getString("status"));
        assertFalse(batch.state(false).has("targets"));
    }
    @Test public void cancellationAndFailureNeverAdvanceOrRunRemainingTargets() throws Exception {
        for (String reason : new String[]{"cancelled", "failed"}) {
            AddonUpdateBatch batch = AddonUpdateBatch.start(new JSONArray().put(widget("one", "2.0.0")).put(widget("two", "2.0.0")));
            batch.stop(reason); batch.confirm("one");
            AddonUpdateBatch restored = new AddonUpdateBatch(batch.state(true));
            assertFalse(restored.active()); assertNull(restored.current()); assertEquals(0, restored.state(false).getInt("completed"));
            assertEquals(reason, restored.state(false).getString("status"));
        }
    }
    @Test public void emptyBatchCompletesWithoutInstallingAnything() throws Exception {
        AddonUpdateBatch batch = AddonUpdateBatch.start(new JSONArray()); assertFalse(batch.active()); assertEquals(0, batch.state(false).getInt("total"));
    }
}
