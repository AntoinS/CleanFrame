package com.cleanframe.philips;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class SpotifyPlaybackTest {
    private JSONObject playing() throws Exception {
        return new JSONObject("{\"device\":{\"id\":\"mac-active\",\"is_active\":true,\"is_restricted\":false},\"is_playing\":true,\"item\":{\"id\":\"track\"},\"actions\":{\"disallows\":{}}}");
    }
    @Test public void pauseAndResumeFollowFreshPlaybackState() throws Exception {
        JSONObject state = playing();
        assertEquals("pause", SpotifyPlayback.endpoint(state, "toggle"));
        state.put("is_playing", false);
        assertEquals("play", SpotifyPlayback.endpoint(state, "toggle"));
        assertEquals("mac-active", SpotifyPlayback.device(state));
    }
    @Test public void missingInactiveAndRestrictedDevicesNeverProduceACommand() throws Exception {
        JSONObject state = playing();
        for (String flag : new String[]{"is_active", "is_restricted"}) {
            state.getJSONObject("device").put(flag, flag.equals("is_restricted"));
            for (String command : new String[]{"toggle", "previous", "next"}) assertEquals("", SpotifyPlayback.endpoint(state, command));
            state = playing();
        }
        state.getJSONObject("device").put("id", JSONObject.NULL);
        assertEquals("", SpotifyPlayback.device(state));
        assertEquals("", SpotifyPlayback.endpoint(state, "toggle"));
        state.remove("device");
        assertEquals("", SpotifyPlayback.endpoint(state, "toggle"));
    }
    @Test public void freshDeviceSelectionNeverFallsBackToTheTV() throws Exception {
        JSONObject state = playing();
        state.getJSONObject("device").put("id", "phone-now-active");
        assertEquals("phone-now-active", SpotifyPlayback.device(state));
        state.getJSONObject("device").put("id", "");
        assertEquals("", SpotifyPlayback.endpoint(state, "toggle"));
    }
    @Test public void spotifyDisallowFlagsAreRespected() throws Exception {
        for (String[] test : new String[][]{{"toggle", "pausing"}, {"previous", "skipping_prev"}, {"next", "skipping_next"}}) {
            JSONObject state = playing();
            state.getJSONObject("actions").getJSONObject("disallows").put(test[1], true);
            assertEquals("", SpotifyPlayback.endpoint(state, test[0]));
        }
        JSONObject paused = playing().put("is_playing", false);
        paused.getJSONObject("actions").getJSONObject("disallows").put("resuming", true);
        assertEquals("", SpotifyPlayback.endpoint(paused, "toggle"));
    }
    @Test public void emptySessionAndUnknownCommandsDoNothing() throws Exception {
        assertEquals("", SpotifyPlayback.endpoint(new JSONObject(), "toggle"));
        assertEquals("", SpotifyPlayback.endpoint(playing().put("item", JSONObject.NULL), "next"));
        assertEquals("", SpotifyPlayback.endpoint(playing(), "transfer"));
        assertEquals("previous", SpotifyPlayback.endpoint(playing(), "previous"));
        assertEquals("next", SpotifyPlayback.endpoint(playing(), "next"));
    }
}
