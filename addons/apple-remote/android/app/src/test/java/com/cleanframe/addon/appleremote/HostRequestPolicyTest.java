package com.cleanframe.addon.appleremote;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class HostRequestPolicyTest {
    @Test public void fieldsAndCommandsAreStrictlyAllowlisted() throws Exception {
        assertEquals(PhilipsRemote.Key.BACK, HostRequestPolicy.key("menu"));
        HostRequestPolicy.parse("{\"op\":\"state\"}");
        HostRequestPolicy.parse("{\"op\":\"key\",\"action\":\"home\"}");
        for (String raw : new String[]{"{\"op\":\"shell\"}", "{\"op\":\"key\",\"action\":\"power\"}",
                "{\"op\":\"state\",\"url\":\"https://example.com\"}", "{\"op\":\"key\",\"action\":\"home\",\"text\":\"x\"}"})
            assertThrows(Exception.class, () -> HostRequestPolicy.parse(raw));
    }
    @Test public void editsNeedMatchingTypedGenerationRevisionAndUtf16Selection() throws Exception {
        JSONObject edit = new JSONObject().put("op", "edit").put("generation", "bb46543c-1c8b-4a5d-bc47-c8d3190e9ad3")
            .put("revision", 7).put("text", "Grüße 🎵").put("selectionStart", 8).put("selectionEnd", 8).put("submit", false);
        HostRequestPolicy.parse(edit.toString());
        for (String field : new String[]{"generation", "revision", "text", "selectionStart", "selectionEnd", "submit"}) {
            JSONObject bad = new JSONObject(edit.toString()); bad.put(field, JSONObject.NULL);
            assertThrows(Exception.class, () -> HostRequestPolicy.parse(bad.toString()));
        }
        edit.put("revision", 1.5); assertThrows(Exception.class, () -> HostRequestPolicy.parse(edit.toString()));
        edit.put("revision", 7).put("selectionStart", 999); assertThrows(Exception.class, () -> HostRequestPolicy.parse(edit.toString()));
    }
    @Test public void stopRevokesOldWorkEvenAfterImmediateReenable() {
        InputActivation guard = new InputActivation();
        long firstStart = guard.ticket(); assertFalse(guard.isEnabled()); assertTrue(guard.enable(firstStart));
        long edit = guard.ticket(); assertTrue(guard.allows(edit));
        guard.disable(); assertFalse(guard.allows(edit));
        long secondStart = guard.ticket(); assertTrue(guard.enable(secondStart));
        assertFalse(guard.allows(edit)); assertTrue(guard.allows(guard.ticket()));
        guard.disable(); long delayedStart = guard.ticket(); guard.disable();
        assertFalse(guard.enable(delayedStart)); assertFalse(guard.isEnabled());
    }
}
