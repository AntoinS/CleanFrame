package com.cleanframe.philips;

import org.json.JSONObject;

/** Spotify's current device and disallow flags determine every transport request. */
final class SpotifyPlayback {
    static String device(JSONObject playback) {
        JSONObject device = playback.optJSONObject("device");
        if (device == null || device.isNull("id") || !device.optBoolean("is_active") || device.optBoolean("is_restricted")) return "";
        return device.optString("id");
    }

    static boolean allowed(JSONObject playback, String command) {
        if (device(playback).isEmpty() || playback.isNull("item")) return false;
        String action = "previous".equals(command) ? "skipping_prev" : "next".equals(command) ? "skipping_next"
                : "toggle".equals(command) ? playback.optBoolean("is_playing") ? "pausing" : "resuming" : "";
        JSONObject actions = playback.optJSONObject("actions");
        JSONObject disallows = actions == null ? null : actions.optJSONObject("disallows");
        return !action.isEmpty() && (disallows == null || !disallows.optBoolean(action));
    }

    static String endpoint(JSONObject playback, String command) {
        if (!allowed(playback, command)) return "";
        if ("previous".equals(command)) return "previous";
        if ("next".equals(command)) return "next";
        return playback.optBoolean("is_playing") ? "pause" : "play";
    }
}
