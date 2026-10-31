package com.cleanframe.addon.appleremote;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PairCodeWindowTest {
    private JSONObject state(boolean enabled, String phase) throws Exception {
        return new JSONObject().put("enabled", enabled).put("philips", new JSONObject().put("state", phase).put("error", ""));
    }
    @Test public void onlyAnExplicitWindowCanOpenCodeEntry() throws Exception {
        PairCodeWindow window = new PairCodeWindow();
        assertFalse(window.observe(state(true, "pairing"), 100));
        window.arm(100);
        assertFalse(window.observe(state(true, "connecting"), 110));
        assertTrue(window.observe(state(true, "pairing"), 120));
        assertTrue(window.available(130));
        assertFalse(window.observe(state(true, "pairing"), 140));
        window.observe(state(true, "connected"), 150);
        assertFalse(window.available(151));
    }
    @Test public void lateResponsesOfferNotificationButNeverAutoOpen() throws Exception {
        PairCodeWindow window = new PairCodeWindow(); window.arm(100);
        assertFalse(window.observe(state(true, "pairing"), 16000));
        assertTrue(window.available(16001));
        assertFalse(window.available(120100));
    }
    @Test public void stopAndExpiryInvalidateTheWindow() throws Exception {
        PairCodeWindow window = new PairCodeWindow(); window.arm(100);
        window.observe(state(true, "pairing"), 110);
        window.observe(state(false, "pairing"), 120);
        assertFalse(window.available(130));
        assertFalse(window.observe(state(true, "pairing"), 140));
        window.arm(200); assertFalse(window.observe(state(true, "pairing"), 120201));
    }
}
