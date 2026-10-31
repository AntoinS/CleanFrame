package com.cleanframe.addon.appleremote;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Ephemeral correlation for our own OEM dialog. Never stores or logs screen contents. */
final class PhilipsPairCapture {
    static final String PACKAGE = "org.droidtv.xtv";
    private static final Pattern CODE = Pattern.compile(":\\s*([0-9]{4})\\s*$");
    static final class Code { final String pin, marker; Code(String pin, String marker) { this.pin = pin; this.marker = marker; } }
    private String marker = "";
    private long deadline;
    private boolean consumed;

    synchronized String arm(long now) {
        marker = "[CF-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "]";
        deadline = now + 30000; consumed = false;
        return "CleanFrame iPhone Remote " + marker;
    }
    synchronized boolean active(long now) { return !marker.isEmpty() && !consumed && now < deadline; }
    synchronized Code take(String packageName, CharSequence text, long now) {
        if (!active(now) || !PACKAGE.equals(packageName) || text == null || text.length() > 4096) return null;
        String value = text.toString();
        if (!value.contains(marker)) return null;
        Matcher match = CODE.matcher(value);
        if (!match.find()) return null;
        consumed = true;
        return new Code(match.group(1), marker);
    }
    synchronized boolean current(Code code, long now) { return code != null && consumed && !marker.isEmpty() && marker.equals(code.marker) && now < deadline; }
    synchronized void clear() { marker = ""; deadline = 0; consumed = false; }
}
