package com.cleanframe.addon.appleremote;

import org.json.JSONObject;

/** One explicitly requested pairing flow; never restored after process restart. */
final class PairCodeWindow {
    private long expires, autoUntil;
    private boolean ready;

    synchronized void arm(long now) {
        expires = now + 30000; autoUntil = now + 15000; ready = false;
    }

    synchronized boolean observe(JSONObject state, long now) {
        if (expires == 0 || now >= expires || !state.optBoolean("enabled")) { clear(); return false; }
        JSONObject philips = state.optJSONObject("philips");
        String phase = philips == null ? "" : philips.optString("state");
        if (ready) { if (!phase.equals("pairing")) clear(); return false; }
        if (!phase.equals("pairing") || !philips.optString("error").isEmpty()) return false;
        ready = true;
        return now <= autoUntil;
    }

    synchronized boolean available(long now) { return ready && now < expires; }
    synchronized void clear() { expires = 0; autoUntil = 0; ready = false; }
}
