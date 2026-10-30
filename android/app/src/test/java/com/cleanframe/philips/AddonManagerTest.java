package com.cleanframe.philips;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class AddonManagerTest {
    JSONObject clock() throws Exception { return new JSONObject("{\"api\":1,\"id\":\"world-clock\",\"name\":\"World clock\",\"version\":\"1.2.3\",\"kind\":\"clock\",\"description\":\"Clock\",\"author\":\"Example\",\"license\":\"MIT\",\"timeZone\":\"Europe/Berlin\"}"); }
    @Test public void acceptsSupportedPackage() throws Exception { AddonManager.validate(clock()); AddonManager.validatePath("packages/world-clock/1.2.3.json"); }
    @Test public void rejectsTraversalAndRemoteUrls() {
        for (String path : new String[]{"../secret", "https://example.com/a", "packages/a/../../a.json", "packages/a/1.json"}) assertThrows(Exception.class, () -> AddonManager.validatePath(path));
    }
    @Test public void rejectsUnsupportedApiKindAndInvalidZone() throws Exception {
        for (String key : new String[]{"api", "kind", "timeZone", "id", "version"}) {
            JSONObject p=clock(); p.put(key, key.equals("api") ? 2 : "unsupported/invalid");
            assertThrows(Exception.class, () -> AddonManager.validate(p));
        }
    }
    @Test public void reservesSpotifyProvider() throws Exception { JSONObject p=clock().put("kind", "spotify"); assertThrows(Exception.class, () -> AddonManager.validate(p)); }
}
