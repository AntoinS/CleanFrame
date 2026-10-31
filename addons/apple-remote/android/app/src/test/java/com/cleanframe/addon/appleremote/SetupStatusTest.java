package com.cleanframe.addon.appleremote;
import org.junit.Test;
import org.json.JSONObject;
import static org.junit.Assert.*;
public class SetupStatusTest {
    @Test public void exposesOnlyVisiblePairingFields() throws Exception {
        JSONObject source = new JSONObject("{\"secret\":\"private\",\"text\":\"private\",\"apple\":{\"pin\":\"1234\",\"expiresAt\":2000000000,\"key\":\"private\",\"devices\":[{\"id\":\"device\",\"name\":\"iPhone\",\"secret\":\"private\"}]}}");
        JSONObject safe = SetupStatus.enrich(new JSONObject("{\"enabled\":true,\"events\":[{}],\"apple\":{\"pairedDevices\":1}}"), source);
        assertFalse(safe.toString().contains("private")); assertFalse(safe.has("events"));
        assertEquals("1234", safe.getJSONObject("apple").getString("pin"));
        assertEquals(2, safe.getJSONObject("apple").getJSONArray("devices").getJSONObject(0).length());
        assertEquals(1, safe.getInt("setupInterface"));
    }
    @Test public void setupRequestCannotAddDebugOrInputArguments() throws Exception {
        RequestPolicy.parse("{\"api\":1,\"op\":\"setupStatus\"}");
        assertThrows(Exception.class, () -> RequestPolicy.parse("{\"api\":1,\"op\":\"setupStatus\",\"debug\":true}"));
        assertThrows(Exception.class, () -> RequestPolicy.parse("{\"api\":1,\"op\":\"shell\"}"));
    }
}
