package com.cleanframe.addon.appleremote;

import org.json.JSONObject;
import java.util.Locale;

/** Presentation only: the receiver's expiresAt (UTC seconds) is the sole deadline. */
final class ApplePairingCountdown {
    enum Phase { INACTIVE, ACTIVE, EXPIRED, PAIRED, DISABLED }
    private Phase phase = Phase.INACTIVE;
    private long expiresAt;
    private String pin = "";

    void update(boolean enabled, JSONObject apple, long nowSeconds) {
        if (!enabled) { clear(Phase.DISABLED); return; }
        if (apple == null) { clear(Phase.INACTIVE); return; }
        String candidate = apple.opt("pin") instanceof String ? apple.optString("pin") : "";
        long expiry = apple.optLong("expiresAt", 0);
        if (candidate.matches("[0-9]{4}") && expiry > nowSeconds) {
            pin = candidate; expiresAt = expiry; phase = Phase.ACTIVE; return;
        }
        String state = apple.optString("state");
        if (state.equals("paired") || state.equals("connected")) { clear(Phase.PAIRED); return; }
        if ((expiry > 0 && expiry <= nowSeconds)
            || (phase == Phase.ACTIVE && expiresAt <= nowSeconds)
            || phase == Phase.EXPIRED) {
            pin = ""; phase = Phase.EXPIRED; return;
        }
        clear(Phase.INACTIVE);
    }
    Phase phase(long nowSeconds) {
        if (phase == Phase.ACTIVE && nowSeconds >= expiresAt) { pin = ""; phase = Phase.EXPIRED; }
        return phase;
    }
    String pin(long nowSeconds) { return phase(nowSeconds) == Phase.ACTIVE ? pin : "—"; }
    String remaining(long nowSeconds) {
        long seconds = phase(nowSeconds) == Phase.ACTIVE ? Math.min(120, Math.max(0, expiresAt - nowSeconds)) : 0;
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }
    private void clear(Phase next) { phase = next; pin = ""; expiresAt = 0; }
}
