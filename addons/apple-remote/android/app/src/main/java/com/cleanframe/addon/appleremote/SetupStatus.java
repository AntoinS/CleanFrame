package com.cleanframe.addon.appleremote;

import org.json.JSONArray;
import org.json.JSONObject;

/** Extends already redacted diagnostics with only visible pairing controls for the signed host. */
final class SetupStatus {
    static JSONObject enrich(JSONObject diagnostics, JSONObject source) throws Exception {
        JSONObject safe = new JSONObject(diagnostics.toString()); safe.remove("events"); safe.put("setupInterface", 1);
        JSONObject input = source.optJSONObject("apple"), apple = safe.optJSONObject("apple");
        if (apple == null) { apple = new JSONObject(); safe.put("apple", apple); }
        if (input == null) return safe;
        String pin = input.optString("pin"); long expires = input.optLong("expiresAt");
        if (pin.matches("[0-9]{4}") && expires > 0) apple.put("pin", pin).put("expiresAt", expires);
        JSONArray devices = input.optJSONArray("devices"), out = new JSONArray();
        if (devices != null) for (int i = 0; i < Math.min(32, devices.length()); i++) {
            JSONObject device = devices.optJSONObject(i); if (device == null) continue;
            String id = device.optString("id"), name = device.optString("name", "iPhone");
            if (id.isEmpty() || id.length() > 256 || id.chars().anyMatch(Character::isISOControl)) continue;
            out.put(new JSONObject().put("id", id).put("name", name.substring(0, Math.min(80, name.length()))));
        }
        apple.put("devices", out); return safe;
    }
}
