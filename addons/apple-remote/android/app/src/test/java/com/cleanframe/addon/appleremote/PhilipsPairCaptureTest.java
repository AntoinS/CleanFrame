package com.cleanframe.addon.appleremote;

import org.junit.Test;
import static org.junit.Assert.*;

public class PhilipsPairCaptureTest {
    @Test public void acceptsOnlyOurVisibleSessionOnce() {
        PhilipsPairCapture capture = new PhilipsPairCapture(); String name = capture.arm(100);
        assertNull(capture.take("other.app", name + ": 1234", 101));
        assertNull(capture.take(PhilipsPairCapture.PACKAGE, "Other pairing: 1234", 101));
        assertNull(capture.take(PhilipsPairCapture.PACKAGE, name + ": １２３４", 101));
        assertEquals("1234", capture.take(PhilipsPairCapture.PACKAGE, "Gib den Code für " + name + " ein: 1234", 101).pin);
        assertNull(capture.take(PhilipsPairCapture.PACKAGE, name + ": 1234", 102));
    }
    @Test public void rejectsExpiredReplacedCancelledAndOversizedMessages() {
        PhilipsPairCapture capture = new PhilipsPairCapture(); String old = capture.arm(0);
        assertNull(capture.take(PhilipsPairCapture.PACKAGE, old + ": 1234", 30000));
        String current = capture.arm(31000);
        assertNull(capture.take(PhilipsPairCapture.PACKAGE, old + ": 1234", 31001));
        assertNull(capture.take(PhilipsPairCapture.PACKAGE, "x".repeat(4096) + current + ": 1234", 31001));
        capture.clear(); assertNull(capture.take(PhilipsPairCapture.PACKAGE, current + ": 1234", 31002));
    }
}
