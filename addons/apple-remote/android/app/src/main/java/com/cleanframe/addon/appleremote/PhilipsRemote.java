package com.cleanframe.addon.appleremote;

import android.content.Context;
import com.burgstaller.okhttp.digest.Credentials;
import com.burgstaller.okhttp.digest.DigestAuthenticator;
import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Fixed-loopback Philips API6 backend. Blocking operations belong on the service worker.
 * Construction never connects, pairs or enables. No external URL or arbitrary command API.
 */
final class PhilipsRemote implements AutoCloseable {
    static final String ORIGIN = "https://127.0.0.1:1926";
    static final int MAX_RESPONSE = 65536;
    private static final long PAIR_LIMIT_MS = 30000; // Verified OEM UI/reset timeout; HTTP advertises 60s.
    private final PhilipsPairCapture capture = new PhilipsPairCapture();
    private static final String PROTOCOL_KEY =
        "ZmVay1EQVFOaZhwQ4Kv81ypLAZNczV9sG4KkseXWn1NEk6cXmPKO/MCa9sryslvLCFMnNe4Z4CPXzToowvhHvA==";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    enum Key {
        UP("CursorUp"), DOWN("CursorDown"), LEFT("CursorLeft"), RIGHT("CursorRight"),
        OK("Confirm"), BACK("Back"), HOME("Home"), PLAY_PAUSE("PlayPause"),
        VOLUME_UP("VolumeUp"), VOLUME_DOWN("VolumeDown"), MUTE("Mute");
        final String wire;
        Key(String wire) { this.wire = wire; }
    }
    enum Endpoint {
        SYSTEM("/6/system"), PAIR_REQUEST("/6/pair/request"),
        PAIR_GRANT("/6/pair/grant"), KEY("/6/input/key");
        final String path;
        Endpoint(String path) { this.path = path; }
    }
    interface Vault {
        JSONObject read() throws Exception;
        void write(JSONObject value) throws Exception;
    }
    interface Wire extends AutoCloseable {
        JSONObject request(Endpoint endpoint, JSONObject body) throws Exception;
        String pin();
        void close();
    }
    interface WireFactory {
        Wire open(String pin, boolean explicitTrustOnFirstUse, String username, String password) throws Exception;
    }
    private final Vault vault;
    private final WireFactory wires;
    private final LongSupplier clock;
    private JSONObject secrets;
    private JSONObject pending;
    private long pairingDeadline;
    private long lastCommand = Long.MIN_VALUE;
    private long lastPairAttempt = Long.MIN_VALUE;
    private final AtomicBoolean operationBusy = new AtomicBoolean();
    private int pinAttempts;
    private boolean enabled;
    private volatile boolean closed;
    private volatile Wire active;
    private volatile String snapshot = "{\"enabled\":false,\"paired\":false,\"state\":\"disabled\"}";
    private String error = "";
    private String connection = "unpaired";

    PhilipsRemote(Context context) throws Exception {
        this(new Vault() {
            final SecretStore store = new SecretStore(context, "philips-secrets.bin", "cleanframe.remote.philips.v1");
            public JSONObject read() throws Exception { return store.read(); }
            public void write(JSONObject value) throws Exception { store.write(value.toString()); }
        }, HttpWire::new, () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    }

    PhilipsRemote(Vault vault, WireFactory wires, LongSupplier clock) throws Exception {
        this.vault = vault; this.wires = wires; this.clock = clock;
        try {
            secrets = vault.read();
            validateStored(secrets);
        } catch (Exception invalid) {
            throw new IOException("philips-storage-unavailable");
        }
        publish();
    }

    /** Explicit host decision, never persisted over the receiver's own enable setting. */
    synchronized JSONObject setEnabled(boolean value) {
        if (closed) return state();
        enabled = value;
        pending = null; pairingDeadline = 0; pinAttempts = 0; capture.clear();
        error = ""; connection = paired() ? "idle" : "unpaired";
        publish(); return state();
    }

    /** Called only by explicit local setup. First use pins the local leaf before asking for a PIN. */
    JSONObject beginPairing() { return beginPairing(false); }
    JSONObject beginPairing(boolean automatic) {
        if (!operationBusy.compareAndSet(false, true)) return busy();
        try { return beginPairingSerialized(automatic); } finally { operationBusy.set(false); }
    }
    private synchronized JSONObject beginPairingSerialized(boolean automatic) {
        if (!usable()) return state();
        long now = clock.getAsLong();
        if (lastPairAttempt != Long.MIN_VALUE && now - lastPairAttempt < 30000)
            return fail("pairing-rate-limited");
        lastPairAttempt = now;
        pending = null; pairingDeadline = 0; pinAttempts = 0; capture.clear();
        error = ""; connection = "connecting"; publish();
        try {
            String pin = secrets.optString("certificateSha256", "");
            if (pin.isEmpty()) {
                // The only TOFU path; no credentials, fixed loopback, certificate validity required.
                String discovered;
                try (Wire wire = open("", true, "", "")) {
                    wire.request(Endpoint.SYSTEM, null);
                    discovered = wire.pin();
                    requirePin(discovered);
                }
                requireActive();
                JSONObject pinned = copy(secrets).put("certificateSha256", discovered);
                vault.write(pinned); // Fail closed before initiating pairing if identity cannot be saved.
                secrets = pinned; pin = discovered;
            }
            String appName = automatic ? capture.arm(clock.getAsLong()) : "CleanFrame iPhone Remote";
            long requestedAt = clock.getAsLong();
            JSONObject device = new JSONObject()
                .put("device_name", "CleanFrame iPhone Remote")
                .put("device_os", "Android").put("type", "native")
                .put("id", randomId()).put("app_id", "com.cleanframe.addon.appleremote")
                .put("app_name", appName);
            JSONObject request = new JSONObject().put("device", device)
                .put("access", new JSONObject().put("scope", new JSONArray().put("read").put("write").put("control")));
            JSONObject reply;
            try (Wire wire = open(pin, false, "", "")) {
                reply = wire.request(Endpoint.PAIR_REQUEST, request);
            }
            requireActive();
            requireSuccess(reply);
            String password = reply.optString("auth_key", "");
            if (!validPassword(password) || !(reply.opt("timestamp") instanceof Number))
                throw new Failure("invalid-response");
            long timestamp = reply.getLong("timestamp");
            if (timestamp < 0) throw new Failure("invalid-response");
            long seconds = reply.optLong("timeout", 120);
            if (seconds < 1) throw new Failure("pairing-expired");
            pairingDeadline = requestedAt + Math.min(PAIR_LIMIT_MS / 1000L, seconds) * 1000L;
            pending = new JSONObject().put("device", device).put("timestamp", timestamp).put("password", password);
            connection = "pairing"; error = ""; publish();
            return state();
        } catch (Exception failure) { pending = null; pairingDeadline = 0; capture.clear(); return fail(code(failure)); }
        finally { active = null; }
    }

    boolean awaitingCapture() { return !closed && capture.active(clock.getAsLong()); }
    PhilipsPairCapture.Code capture(String packageName, CharSequence text) {
        return closed ? null : capture.take(packageName, text, clock.getAsLong());
    }
    void clearCapture() { capture.clear(); }
    JSONObject finishCaptured(PhilipsPairCapture.Code code) {
        if (closed || !capture.current(code, clock.getAsLong())) return state();
        JSONObject result = finishPairing(code.pin);
        try { result.put("automaticallyConfirmed", result.optString("state").equals("connected") && result.optString("error").isEmpty()); }
        catch (Exception ignored) { }
        return result;
    }

    JSONObject finishPairing(String pin) {
        if (!operationBusy.compareAndSet(false, true)) return busy();
        try { return finishPairingSerialized(pin); } finally { operationBusy.set(false); }
    }
    private synchronized JSONObject finishPairingSerialized(String pin) {
        if (!usable()) return state();
        if (pending == null || clock.getAsLong() >= pairingDeadline) {
            pending = null; pairingDeadline = 0; return fail("pairing-expired");
        }
        if (pin == null || !pin.matches("[0-9]{4}")) return fail("invalid-pin");
        if (++pinAttempts > 3) { pending = null; pairingDeadline = 0; return fail("pairing-attempt-limit"); }
        try {
            JSONObject device = pending.getJSONObject("device");
            String username = device.getString("id"), password = pending.getString("password");
            long timestamp = pending.getLong("timestamp");
            JSONObject body = new JSONObject().put("device", device).put("auth", new JSONObject()
                .put("auth_appId", "1").put("auth_timestamp", timestamp).put("pin", pin)
                .put("auth_signature", pairingSignature(timestamp, pin)));
            JSONObject reply;
            try (Wire wire = open(secrets.getString("certificateSha256"), false, username, password)) {
                reply = wire.request(Endpoint.PAIR_GRANT, body);
            }
            requireActive(); requireSuccess(reply);
            JSONObject pairedSecrets = copy(secrets).put("username", username).put("password", password);
            vault.write(pairedSecrets); // Never announce ready before encrypted durable storage succeeds.
            secrets = pairedSecrets; pending = null; pairingDeadline = 0; capture.clear();
            connection = "connected"; error = ""; publish(); return state();
        } catch (Exception failure) { return fail(code(failure)); }
        finally { active = null; }
    }

    JSONObject send(Key key) {
        if (!operationBusy.compareAndSet(false, true)) return busy();
        try { return sendSerialized(key); }
        finally { operationBusy.set(false); }
    }

    private JSONObject busy() {
        JSONObject result = state();
        try { result.put("error", "command-busy"); } catch (Exception ignored) { }
        return result;
    }

    private synchronized JSONObject sendSerialized(Key key) {
        if (!usable()) return state();
        if (key == null) return fail("unsupported-key");
        if (!paired()) return fail("not-paired");
        long now = clock.getAsLong();
        if (lastCommand != Long.MIN_VALUE && now - lastCommand < 50) return fail("command-rate-limited");
        lastCommand = now;
        try (Wire wire = open(secrets.getString("certificateSha256"), false,
                secrets.getString("username"), secrets.getString("password"))) {
            wire.request(Endpoint.KEY, new JSONObject().put("key", key.wire));
            requireActive(); connection = "connected"; error = ""; publish(); return state();
        } catch (Exception failure) { return fail(code(failure)); }
        finally { active = null; }
    }

    /** Redacted, local-only state. Never fetches identifiers, PINs, keys or TV contents. */
    JSONObject state() {
        try {
            JSONObject result = new JSONObject(snapshot);
            if (result.optString("state").equals("pairing")) {
                long remaining = Math.max(0, result.optLong("pairingDeadline") - clock.getAsLong());
                result.put("pairingSecondsRemaining", (remaining + 999) / 1000);
                if (remaining == 0) result.put("state", "pairing-expired");
            }
            result.remove("pairingDeadline");
            return result;
        } catch (Exception impossible) { return new JSONObject(); }
    }

    /** Cancels in-flight I/O immediately; a closed instance cannot be re-enabled. */
    void cancel() {
        closed = true; capture.clear();
        Wire current = active;
        if (current != null) current.close();
    }
    @Override public void close() {
        cancel();
        synchronized (this) {
            enabled = false; pending = null; pairingDeadline = 0; error = "";
            publish();
        }
    }

    private Wire open(String pin, boolean tofu, String username, String password) throws Exception {
        requireActive();
        Wire wire = wires.open(pin, tofu, username, password);
        active = wire;
        if (closed) { wire.close(); throw new Failure("closed"); }
        return wire;
    }
    private boolean usable() {
        if (closed || !enabled) { fail(closed ? "closed" : "disabled"); return false; }
        return true;
    }
    private void requireActive() throws Failure { if (closed || !enabled) throw new Failure("disabled"); }
    private boolean paired() { return secrets != null && secrets.has("username") && secrets.has("password"); }
    private JSONObject fail(String code) {
        error = code;
        if (pending == null) connection = paired() ? "disconnected" : "unpaired";
        publish(); return state();
    }
    private void publish() {
        try {
            snapshot = new JSONObject().put("backend", "philips").put("enabled", enabled && !closed)
                .put("paired", paired()).put("certificatePinned", secrets != null && secrets.has("certificateSha256"))
                .put("state", !enabled || closed ? "disabled" : connection).put("error", error)
                .put("pairingDeadline", pairingDeadline).put("pinDigits", 4)
                .put("textSupported", false).put("standbySupported", false).put("wakeSupported", false).toString();
        } catch (Exception ignored) { /* only primitive known keys */ }
    }
    private static JSONObject copy(JSONObject value) throws Exception { return new JSONObject(value.toString()); }
    private static String randomId() {
        byte[] bytes = new byte[16]; new SecureRandom().nextBytes(bytes); return hex(bytes);
    }
    static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
    static String pairingSignature(long timestamp, String pin) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(Base64.getDecoder().decode(PROTOCOL_KEY), "HmacSHA1"));
        return Base64.getEncoder().encodeToString(mac.doFinal((Long.toString(timestamp) + pin).getBytes(StandardCharsets.UTF_8)));
    }
    private static boolean validPassword(String value) {
        return value.length() >= 1 && value.length() <= 4096 && value.chars().noneMatch(Character::isISOControl);
    }
    private static void validateStored(JSONObject value) throws Exception {
        if (value.has("certificateSha256")) requirePin(value.getString("certificateSha256"));
        if (value.has("username") || value.has("password")) {
            if (!(value.opt("username") instanceof String) || !(value.opt("password") instanceof String)
                || !value.has("certificateSha256") || !value.optString("username", "").matches("[A-Za-z0-9._:@+-]{1,256}")
                || !validPassword(value.optString("password", ""))) throw new Failure("invalid-storage");
        }
    }
    static void requirePin(String pin) throws Failure {
        if (pin == null || !pin.matches("[0-9a-f]{64}")) throw new Failure("invalid-certificate-pin");
    }
    private static void requireSuccess(JSONObject response) throws Failure {
        if (!"SUCCESS".equals(response.optString("error_id"))) throw new Failure("pairing-rejected");
    }
    private static String code(Exception error) {
        if (error instanceof Failure) return error.getMessage();
        if (error instanceof javax.net.ssl.SSLException || error instanceof CertificateException) return "tls-rejected";
        if (error instanceof java.io.InterruptedIOException) return "timeout-or-cancelled";
        return "connection-or-storage-failed";
    }
    static final class Failure extends IOException { Failure(String code) { super(code); } }

    /** TLS authenticates this fixed endpoint by its saved leaf fingerprint, not a wildcard hostname. */
    static final class PinTrust implements X509TrustManager {
        private String pin;
        private final boolean firstUse;
        PinTrust(String pin, boolean firstUse) throws Failure {
            if (!pin.isEmpty()) requirePin(pin);
            if (pin.isEmpty() && !firstUse) throw new Failure("missing-certificate-pin");
            if (!pin.isEmpty() && firstUse) throw new Failure("pin-replacement-forbidden");
            this.pin = pin; this.firstUse = firstUse;
        }
        public synchronized void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (chain == null || chain.length == 0 || chain.length > 8) throw new CertificateException("Invalid local certificate.");
            chain[0].checkValidity();
            try {
                String actual = hex(MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded()));
                if (pin.isEmpty() && firstUse) pin = actual;
                else if (!MessageDigest.isEqual(pin.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII)))
                    throw new CertificateException("Local certificate changed.");
            } catch (CertificateException rejected) { throw rejected; }
            catch (Exception invalid) { throw new CertificateException("Invalid local certificate."); }
        }
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException { throw new CertificateException("Client role unsupported."); }
        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        synchronized String pin() { return pin; }
    }

    static final class HttpWire implements Wire {
        private final OkHttpClient client;
        private final PinTrust trust;
        private volatile Call activeCall;
        private volatile boolean closed;
        HttpWire(String pin, boolean firstUse, String username, String password) throws Exception {
            trust = new PinTrust(pin, firstUse);
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(null, new TrustManager[]{trust}, new SecureRandom());
            OkHttpClient.Builder builder = new OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
                .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                .sslSocketFactory(tls.getSocketFactory(), trust)
                .hostnameVerifier((host, session) -> host.equals("127.0.0.1") && session.getPeerPort() == 1926)
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .connectTimeout(3, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS)
                .writeTimeout(3, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS)
                .connectionPool(new ConnectionPool(0, 1, TimeUnit.SECONDS));
            if (!username.isEmpty()) {
                if (firstUse) throw new Failure("credentials-before-pin");
                DigestAuthenticator digest = new DigestAuthenticator(new Credentials(username, password));
                builder.authenticator((route, response) -> {
                    // One Digest challenge only; no Basic/proxy fallback, stale nonce loop or URL escape.
                    if (!fixedUrl(response.request().url()) || response.code() != 401
                        || response.request().header("Authorization") != null) return null;
                    return digest.authenticate(route, response);
                });
            }
            client = builder.build();
        }
        public JSONObject request(Endpoint endpoint, JSONObject body) throws Exception {
            if (closed) throw new Failure("closed");
            Request.Builder request = new Request.Builder().url(ORIGIN + endpoint.path)
                .header("Accept", "application/json");
            if (body != null) request.post(RequestBody.create(body.toString(), JSON));
            activeCall = client.newCall(request.build());
            if (endpoint == Endpoint.KEY) activeCall.timeout().timeout(2500, TimeUnit.MILLISECONDS);
            if (closed) { activeCall.cancel(); throw new Failure("closed"); }
            try (Response response = activeCall.execute()) {
                if (response.code() != 200) throw new Failure(response.code() == 401 || response.code() == 403 ? "authentication-rejected" : "http-rejected");
                ResponseBody responseBody = response.body();
                if (responseBody == null) throw new Failure("invalid-response");
                byte[] bytes = boundedRead(responseBody.byteStream(), responseBody.contentLength());
                if (endpoint == Endpoint.KEY) {
                    // Firmware may return HTML, empty text or JSON for accepted keys.
                    String text = new String(bytes, StandardCharsets.UTF_8).trim();
                    if (text.startsWith("{")) {
                        JSONObject reply = new JSONObject(text);
                        if (reply.has("error_id") && !"SUCCESS".equals(reply.optString("error_id")))
                            throw new Failure("command-rejected");
                    }
                    return new JSONObject();
                }
                return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            } finally { activeCall = null; }
        }
        public String pin() { return trust.pin(); }
        public void close() {
            closed = true;
            Call call = activeCall;
            if (call != null) call.cancel();
            client.dispatcher().cancelAll(); client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdown();
        }
    }
    static boolean fixedUrl(HttpUrl url) {
        if (!url.scheme().equals("https") || !url.host().equals("127.0.0.1") || url.port() != 1926
            || !url.username().isEmpty() || !url.password().isEmpty() || url.query() != null || url.fragment() != null) return false;
        for (Endpoint endpoint : Endpoint.values()) if (endpoint.path.equals(url.encodedPath())) return true;
        return false;
    }
    static byte[] boundedRead(InputStream source, long declared) throws Exception {
        if (declared > MAX_RESPONSE) throw new Failure("response-too-large");
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] chunk = new byte[2048]; int count;
        while ((count = source.read(chunk)) != -1) {
            if (result.size() + count > MAX_RESPONSE) throw new Failure("response-too-large");
            result.write(chunk, 0, count);
        }
        return result.toByteArray();
    }
}
