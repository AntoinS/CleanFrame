package com.cleanframe.addon.appleremote;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ApplePairingCountdownTest {
    private JSONObject state(Object pin, long expires, String phase) throws Exception {
        return new JSONObject().put("pin", pin).put("expiresAt", expires).put("state", phase);
    }
    @Test public void countdownUsesReceiverDeadlineAndExpiresAtZeroWithoutLeavingPinVisible() throws Exception {
        ApplePairingCountdown view = new ApplePairingCountdown();
        view.update(true, state("0042", 1120, "pairing"), 1000);
        assertEquals("02:00", view.remaining(1000)); assertEquals("01:59", view.remaining(1001));
        assertEquals("00:01", view.remaining(1119)); assertEquals("0042", view.pin(1119));
        assertEquals("00:00", view.remaining(1120)); assertEquals("—", view.pin(1120));
        assertEquals(ApplePairingCountdown.Phase.EXPIRED, view.phase(1120));
        view.update(true, state(JSONObject.NULL, 0, "listening"), 1121);
        assertEquals(ApplePairingCountdown.Phase.EXPIRED, view.phase(1121));
    }
    @Test public void newWindowReplacesExpiredDeadlineAndSuccessfulPairingIsNotExpiry() throws Exception {
        ApplePairingCountdown view = new ApplePairingCountdown();
        view.update(true, state("1234", 1120, "pairing"), 1000);
        view.update(true, state(JSONObject.NULL, 0, "paired"), 1040);
        assertEquals(ApplePairingCountdown.Phase.PAIRED, view.phase(1200)); assertEquals("—", view.pin(1200));
        view.update(true, state("5678", 1320, "pairing"), 1200);
        assertEquals("02:00", view.remaining(1200)); assertEquals("5678", view.pin(1200));
        view.phase(1320);
        view.update(true, state("9012", 1450, "pairing"), 1330);
        assertEquals(ApplePairingCountdown.Phase.ACTIVE, view.phase(1330)); assertEquals("02:00", view.remaining(1330));
    }
    @Test public void receiverDeadlineWinsOverLocalButtonTimeAndInvalidOrDisabledPinIsHidden() throws Exception {
        ApplePairingCountdown view = new ApplePairingCountdown();
        view.update(true, state("1234", 1120, "pairing"), 1080);
        assertEquals("00:40", view.remaining(1080));
        view.update(false, state("1234", 1120, "pairing"), 1081);
        assertEquals(ApplePairingCountdown.Phase.DISABLED, view.phase(1081)); assertEquals("—", view.pin(1081));
        view.update(true, state(JSONObject.NULL, 0, "listening"), 1082);
        assertEquals(ApplePairingCountdown.Phase.INACTIVE, view.phase(1082));
        view.update(true, state("null", 1200, "pairing"), 1083);
        assertEquals("—", view.pin(1083)); assertEquals("00:00", view.remaining(1083));
        view.update(true, state("1234", 1000, "pairing"), 1084);
        assertEquals(ApplePairingCountdown.Phase.EXPIRED, view.phase(1084));
    }
}
