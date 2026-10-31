package com.cleanframe.addon.appleremote;

import org.junit.Test;
import static org.junit.Assert.*;

public class RequestPolicyTest {
    @Test public void nativeErrorsCannotBecomeSuccessfulReceiverState() throws Exception {
        assertThrows(Exception.class, () -> RequestPolicy.parseResponse("{\"error\":\"private failure detail\"}"));
        assertThrows(Exception.class, () -> RequestPolicy.parseResponse("not JSON"));
        assertThrows(Exception.class, () -> RequestPolicy.parseResponse("x".repeat(65537)));
        assertTrue(RequestPolicy.parseResponse("{\"running\":true,\"android\":{\"state\":\"unpaired\",\"error\":\"\"}}").getBoolean("running"));
        assertFalse(RequestPolicy.parseResponse("{\"running\":false}").getBoolean("running"));
        assertThrows(Exception.class, () -> RequestPolicy.parseRunningResponse("{\"running\":false,\"android\":{\"error\":\"startup failed asynchronously\"}}"));
        assertThrows(Exception.class, () -> RequestPolicy.parseRunningResponse("{}"));
        assertTrue(RequestPolicy.parseRunningResponse("{\"running\":true}").getBoolean("running"));
    }

    @Test public void knownCommandsAcceptOnlyTheirFields() throws Exception {
        assertEquals("status", RequestPolicy.parse("{\"api\":1,\"op\":\"status\"}").getString("op"));
        assertEquals("1234", RequestPolicy.parse("{\"op\":\"philipsPairFinish\",\"code\":\"1234\"}").getString("code"));
        RequestPolicy.parse("{\"op\":\"unpair\",\"id\":\"a-device-id\"}");
    }

    @Test public void rejectsNativeSecretsShellAndInvalidPin() {
        for (String request : new String[]{
            "{\"op\":\"start\",\"secrets\":{}}", "{\"op\":\"exportSecrets\"}",
            "{\"op\":\"shell\",\"command\":\"anything\"}", "{\"op\":\"philipsPairFinish\",\"code\":\"A1B2C3\"}", "{\"op\":\"androidPairBegin\"}",
            "{\"op\":\"status\",\"address\":\"other-tv\"}", "{\"op\":\"status\",\"api\":2}",
            "{\"op\":\"status\",\"api\":\"invalid\"}", "{\"op\":\"status\",\"api\":null}",
            "{\"op\":\"unpair\",\"id\":\"\"}"}) {
            assertThrows(Exception.class, () -> RequestPolicy.parse(request));
        }
    }
}
