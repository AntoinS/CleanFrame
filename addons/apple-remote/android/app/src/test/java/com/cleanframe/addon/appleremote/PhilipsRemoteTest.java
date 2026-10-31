package com.cleanframe.addon.appleremote;

import org.junit.Test;
import org.json.JSONObject;
import okhttp3.HttpUrl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class PhilipsRemoteTest {
    static final String PIN = "1".repeat(64);
    static final class MemoryVault implements PhilipsRemote.Vault {
        JSONObject value = new JSONObject();
        boolean failWrite;
        public JSONObject read() throws Exception { return new JSONObject(value.toString()); }
        public void write(JSONObject next) throws Exception {
            if (failWrite) throw new IOException("private vault contents must not escape");
            value = new JSONObject(next.toString());
        }
    }
    static final class FakeWireFactory implements PhilipsRemote.WireFactory {
        final List<PhilipsRemote.Endpoint> calls = new ArrayList<>();
        final List<Boolean> trustFirst = new ArrayList<>();
        JSONObject lastBody;
        String responseError = "SUCCESS";
        long timeout = 120;
        boolean leakError;
        boolean closed;
        public PhilipsRemote.Wire open(String pin, boolean tofu, String username, String password) {
            trustFirst.add(tofu);
            return new PhilipsRemote.Wire() {
                public JSONObject request(PhilipsRemote.Endpoint endpoint, JSONObject body) throws Exception {
                    calls.add(endpoint); lastBody = body;
                    if (leakError) throw new IOException("secret-password and typed PIN 1234");
                    if (endpoint == PhilipsRemote.Endpoint.PAIR_REQUEST)
                        return new JSONObject().put("error_id", responseError).put("auth_key", "private-password")
                            .put("timestamp", 1700000000).put("timeout", timeout);
                    return new JSONObject().put("error_id", responseError);
                }
                public String pin() { return PIN; }
                public void close() { closed = true; }
            };
        }
    }
    static final class Fixture {
        final MemoryVault vault = new MemoryVault();
        final FakeWireFactory wires = new FakeWireFactory();
        final long[] now = {1000};
        PhilipsRemote remote;
        Fixture() throws Exception { remote = new PhilipsRemote(vault, wires, () -> now[0]); }
        void start() { remote.setEnabled(true); remote.beginPairing(); }
    }
    @Test public void explicitEnableThenPairingPersistsBeforeReadyAndNeverExposesSecrets() throws Exception {
        Fixture f = new Fixture();
        assertEquals("disabled", f.remote.state().getString("state"));
        f.remote.send(PhilipsRemote.Key.HOME);
        f.remote.beginPairing();
        assertTrue(f.wires.calls.isEmpty());
        f.start();
        assertEquals(List.of(PhilipsRemote.Endpoint.SYSTEM, PhilipsRemote.Endpoint.PAIR_REQUEST), f.wires.calls);
        assertEquals(PIN, f.vault.value.getString("certificateSha256"));
        assertFalse(f.vault.value.has("password"));
        assertEquals("pairing", f.remote.state().getString("state"));
        JSONObject done = f.remote.finishPairing("1234");
        assertEquals("connected", done.getString("state"));
        assertEquals("private-password", f.vault.value.getString("password"));
        assertFalse(done.toString().contains("private-password"));
        assertFalse(done.toString().contains("1234"));
        assertFalse(done.has("username"));
        assertFalse(done.has("pairingDeadline"));
        f.remote.send(PhilipsRemote.Key.LEFT);
        assertEquals("CursorLeft", f.wires.lastBody.getString("key"));
        f.remote.setEnabled(false);
        int before = f.wires.calls.size();
        f.remote.send(PhilipsRemote.Key.HOME);
        assertEquals(before, f.wires.calls.size());
        PhilipsRemote reopened = new PhilipsRemote(f.vault, f.wires, () -> 1000L);
        assertTrue(reopened.state().getBoolean("paired"));
        assertEquals("disabled", reopened.state().getString("state"));
    }
    @Test public void automaticCaptureSurvivesHttpCloseButNotNewRequestsOrDisable() throws Exception {
        Fixture f = new Fixture(); f.remote.setEnabled(true); f.remote.beginPairing(true);
        String name = f.wires.lastBody.getJSONObject("device").getString("app_name");
        assertTrue(f.remote.awaitingCapture());
        PhilipsPairCapture.Code code = f.remote.capture(PhilipsPairCapture.PACKAGE, name + ": 1234");
        assertNotNull(code);
        assertTrue(f.remote.finishCaptured(code).getBoolean("automaticallyConfirmed"));
        assertFalse(f.remote.finishCaptured(code).optBoolean("automaticallyConfirmed"));
        assertFalse(f.remote.state().toString().contains(name));
        f.now[0] += 30001; f.remote.beginPairing(true);
        int before = f.wires.calls.size();
        f.remote.finishCaptured(code); assertEquals(before, f.wires.calls.size());
        f.remote.setEnabled(false); assertFalse(f.remote.awaitingCapture());
    }
    @Test public void pinPersistenceFailurePreventsPairRequest() throws Exception {
        Fixture f = new Fixture(); f.vault.failWrite = true; f.start();
        assertEquals(List.of(PhilipsRemote.Endpoint.SYSTEM), f.wires.calls);
        assertFalse(f.remote.state().getBoolean("paired"));
        assertFalse(f.remote.state().toString().contains("private vault"));
    }
    @Test public void credentialPersistenceFailureNeverAnnouncesPaired() throws Exception {
        Fixture f = new Fixture(); f.start(); f.vault.failWrite = true;
        assertFalse(f.remote.finishPairing("1234").getBoolean("paired"));
        assertFalse(f.vault.value.has("password"));
    }
    @Test public void pinExpiryAttemptsAndRequestRateAreBounded() throws Exception {
        Fixture f = new Fixture(); f.start();
        assertEquals("pairing-rate-limited", f.remote.beginPairing().getString("error"));
        assertEquals("invalid-pin", f.remote.finishPairing("１２３４").getString("error"));
        f.wires.responseError = "WRONG_PIN";
        for (int i=0; i<3; i++) assertEquals("pairing-rejected", f.remote.finishPairing("1234").getString("error"));
        int before = f.wires.calls.size();
        assertEquals("pairing-attempt-limit", f.remote.finishPairing("1234").getString("error"));
        assertEquals(before, f.wires.calls.size());
        Fixture expired = new Fixture(); expired.start(); expired.now[0] += 30001;
        assertEquals("pairing-expired", expired.remote.state().getString("state"));
        assertEquals("pairing-expired", expired.remote.finishPairing("1234").getString("error"));
        Fixture overflow = new Fixture(); overflow.wires.timeout = Long.MAX_VALUE; overflow.start();
        assertEquals(30, overflow.remote.state().getInt("pairingSecondsRemaining"));
    }
    @Test public void storedPinIsNeverReplacedAndErrorsStayRedacted() throws Exception {
        Fixture f = new Fixture(); f.start(); f.now[0] += 30001;
        f.remote.beginPairing();
        assertEquals(List.of(true, false, false), f.wires.trustFirst);
        f.wires.leakError = true;
        String output = f.remote.finishPairing("1234").toString();
        assertFalse(output.contains("secret-password")); assertFalse(output.contains("1234"));
        f.remote.close();
        int before = f.wires.calls.size();
        f.remote.setEnabled(true); f.remote.beginPairing();
        assertEquals(before, f.wires.calls.size());
    }
    @Test public void onlyKnownKeysAndFixedLoopbackEndpointsExist() throws Exception {
        for (PhilipsRemote.Endpoint endpoint : PhilipsRemote.Endpoint.values())
            assertTrue(PhilipsRemote.fixedUrl(HttpUrl.get(PhilipsRemote.ORIGIN + endpoint.path)));
        for (String url : List.of("https://192.0.2.10:1926/6/input/key", "http://127.0.0.1:1926/6/input/key",
                "https://127.0.0.1:443/6/input/key", "https://127.0.0.1:1926/6/input/key?x=1",
                "https://user:pass@127.0.0.1:1926/6/input/key", "https://127.0.0.1:1926/6/powerstate"))
            assertFalse(PhilipsRemote.fixedUrl(HttpUrl.get(url)));
        for (PhilipsRemote.Key key : PhilipsRemote.Key.values())
            assertFalse(key.wire.toLowerCase().contains("power") || key.wire.equals("Standby"));
        Fixture f = new Fixture(); f.start(); f.remote.finishPairing("1234");
        f.remote.send(PhilipsRemote.Key.HOME);
        assertEquals("command-rate-limited", f.remote.send(PhilipsRemote.Key.HOME).getString("error"));
    }
    @Test public void tlsPinValidityAndOneTimeCaptureAreEnforced() throws Exception {
        HeldCertificate cert = new HeldCertificate.Builder().commonName("loopback test").build();
        X509Certificate[] chain = {cert.certificate()};
        String hash = PhilipsRemote.hex(MessageDigest.getInstance("SHA-256").digest(cert.certificate().getEncoded()));
        PhilipsRemote.PinTrust trust = new PhilipsRemote.PinTrust(hash, false);
        trust.checkServerTrusted(chain, "RSA");
        assertThrows(CertificateException.class, () -> new PhilipsRemote.PinTrust(PIN, false).checkServerTrusted(chain, "RSA"));
        assertThrows(IOException.class, () -> new PhilipsRemote.PinTrust("", false));
        assertThrows(IOException.class, () -> new PhilipsRemote.PinTrust(hash, true));
        PhilipsRemote.PinTrust first = new PhilipsRemote.PinTrust("", true);
        first.checkServerTrusted(chain, "RSA"); assertEquals(hash, first.pin());
        HeldCertificate other = new HeldCertificate.Builder().commonName("other").build();
        assertThrows(CertificateException.class, () -> first.checkServerTrusted(new X509Certificate[]{other.certificate()}, "RSA"));
        HeldCertificate expired = new HeldCertificate.Builder().validityInterval(1, 2).build();
        assertThrows(CertificateException.class, () -> new PhilipsRemote.PinTrust("", true).checkServerTrusted(new X509Certificate[]{expired.certificate()}, "RSA"));
    }
    @Test public void protocolHmacUsesBase64OfRawMacAndResponsesHaveAHardCap() throws Exception {
        assertEquals("Up43OnIb8sG9V66+HISRwCPnYns=", PhilipsRemote.pairingSignature(1700000000, "1234"));
        byte[] over = new byte[PhilipsRemote.MAX_RESPONSE + 1];
        assertThrows(IOException.class, () -> PhilipsRemote.boundedRead(new ByteArrayInputStream(over), -1));
        assertThrows(IOException.class, () -> PhilipsRemote.boundedRead(new ByteArrayInputStream(new byte[0]), over.length));
    }
    @Test public void actualLoopbackTlsDigestUsesLibraryAndRejectsRedirectsAndRepeatChallenges() throws Exception {
        // Entirely local JVM fixture. Never contacts the TV; production endpoint is still fixed.
        HeldCertificate cert = new HeldCertificate.Builder().commonName("test.local").build();
        String pin = PhilipsRemote.hex(MessageDigest.getInstance("SHA-256").digest(cert.certificate().getEncoded()));
        try (MockWebServer server = new MockWebServer()) {
            server.useHttps(new HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false);
            server.start(InetAddress.getByName("127.0.0.1"), 1926);
            MockResponse challenge = new MockResponse().setResponseCode(401)
                .addHeader("WWW-Authenticate", "Digest realm=\"test\", nonce=\"unique-test-nonce\", qop=\"auth\", algorithm=MD5");
            server.enqueue(challenge); server.enqueue(new MockResponse().setBody("{}"));
            try (PhilipsRemote.Wire wire = new PhilipsRemote.HttpWire(pin, false, "test-user", "test-secret")) {
                wire.request(PhilipsRemote.Endpoint.KEY, new JSONObject().put("key", "Home"));
            }
            RecordedRequest first = server.takeRequest(1, TimeUnit.SECONDS), second = server.takeRequest(1, TimeUnit.SECONDS);
            assertNotNull(first); assertNotNull(second);
            assertNull(first.getHeader("Authorization"));
            assertTrue(second.getHeader("Authorization").startsWith("Digest "));
            assertFalse(second.getHeader("Authorization").contains("test-secret"));
            assertEquals("/6/input/key", second.getPath());
            server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", "https://example.com/"));
            try (PhilipsRemote.Wire wire = new PhilipsRemote.HttpWire(pin, false, "test-user", "test-secret")) {
                assertThrows(IOException.class, () -> wire.request(PhilipsRemote.Endpoint.KEY, new JSONObject().put("key", "Home")));
            }
            server.takeRequest(1, TimeUnit.SECONDS);
            server.enqueue(challenge); server.enqueue(challenge);
            try (PhilipsRemote.Wire wire = new PhilipsRemote.HttpWire(pin, false, "test-user", "test-secret")) {
                assertThrows(IOException.class, () -> wire.request(PhilipsRemote.Endpoint.KEY, new JSONObject().put("key", "Home")));
            }
            server.takeRequest(1, TimeUnit.SECONDS); server.takeRequest(1, TimeUnit.SECONDS);
            assertEquals(5, server.getRequestCount());
        }
    }
}
