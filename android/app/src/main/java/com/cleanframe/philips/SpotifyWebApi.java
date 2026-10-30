package com.cleanframe.philips;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** OAuth and remote transport stay native. No audio playback or device-transfer endpoint. */
final class SpotifyWebApi {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final SpotifyCredentials vault;
    private final Consumer<JSONObject> publish;
    private volatile boolean active, closed;
    private volatile HttpURLConnection inFlight;
    private volatile int generation;
    private int workerGeneration;
    private boolean busy;
    // Worker-owned tokens; never included in an event, log, URL, or JS return value.
    private JSONObject credentials;
    private String accessToken = "";
    private long expiresAt, retryAt;

    SpotifyWebApi(Context context, Consumer<JSONObject> publish) {
        vault = new SpotifyCredentials(context);
        this.publish = publish;
    }

    void setActive(boolean value) {
        if (closed || active == value) return;
        active = value;
        generation++;
        busy = false;
        if (value) run(null);
        else cancelRequest();
    }

    void control(String command) {
        if (active && !closed && !busy) run(command);
    }

    void refreshLink() {
        if (closed) return;
        int ticket = generation;
        worker.execute(() -> {
            JSONObject result;
            try {
                JSONObject saved = vault.load();
                if (saved == null || credentials == null || !saved.toString().equals(credentials.toString())) {
                    accessToken = ""; expiresAt = 0;
                }
                credentials = saved;
                result = state(saved == null ? "unlinked" : "linked", "", null);
            } catch (Exception failure) {
                result = state("reauth", "Sign in to Spotify again.", null);
            }
            JSONObject ready = result;
            main.post(() -> {
                // Account management also works with the player off or media permission denied.
                if (!closed && !active && ticket == generation) publish.accept(ready);
            });
        });
    }

    void signOut() {
        if (closed) return;
        generation++;
        busy = true;
        cancelRequest();
        // The same worker owns refresh and deletion: a late refresh cannot restore credentials.
        worker.execute(() -> {
            JSONObject result;
            accessToken = ""; expiresAt = 0; retryAt = 0; credentials = null;
            try {
                vault.clear();
                result = state("unlinked", "Sign in with QR to enable controls.", null);
            } catch (Exception failure) {
                result = state("signout_failed", "Could not sign out. Please try again.", null);
            }
            JSONObject ready = result;
            main.post(() -> { if (!closed) { busy = false; publish.accept(ready); } });
        });
    }

    private void run(String command) {
        int ticket = generation;
        busy = true;
        worker.execute(() -> {
            JSONObject result;
            workerGeneration = ticket;
            try {
                if (!active || closed || ticket != generation) return;
                if (command == null) {
                    JSONObject saved = vault.load();
                    if (saved != null && (credentials == null || !saved.toString().equals(credentials.toString()))) {
                        accessToken = ""; expiresAt = 0;
                    }
                    credentials = saved;
                }
                if (credentials == null) result = state("unlinked", "Sign in with QR to enable controls.", null);
                else {
                    if (SystemClock.elapsedRealtime() < retryAt) throw new ApiFailure("rate_limited", "Spotify is busy. Try again later.");
                    JSONObject playback = requestPlayback();
                    if (command != null) {
                        String endpoint = SpotifyPlayback.endpoint(playback, command);
                        if (endpoint.isEmpty()) throw new ApiFailure("connected", "No active Spotify device supports this control.");
                        // Resolve the device immediately before sending. A device-less play could switch output.
                        String path = "/me/player/" + endpoint + "?device_id=" + encode(SpotifyPlayback.device(playback));
                        api("previous".equals(endpoint) || "next".equals(endpoint) ? "POST" : "PUT", path);
                    }
                    result = state("connected", "", playback);
                }
            } catch (ApiFailure failure) {
                result = state(failure.status, failure.getMessage(), null);
            } catch (Exception failure) {
                result = state("unavailable", "Spotify could not connect. Check your network or sign in again.", null);
            }
            JSONObject ready = result;
            main.post(() -> {
                if (!closed && active && ticket == generation) { busy = false; publish.accept(ready); }
            });
        });
    }

    private JSONObject requestPlayback() throws Exception {
        Reply reply = api("GET", "/me/player");
        return reply.code == 204 || reply.body.isEmpty() ? new JSONObject() : new JSONObject(reply.body);
    }

    private Reply api(String method, String path) throws Exception {
        token(false);
        Reply reply = request(method, "https://api.spotify.com/v1" + path, null, accessToken);
        if (reply.code == 401) {
            token(true);
            reply = request(method, "https://api.spotify.com/v1" + path, null, accessToken);
        }
        if (reply.code == 401) throw new ApiFailure("reauth", "Sign in to Spotify again.");
        if (reply.code == 403) throw new ApiFailure("unavailable", "Spotify denied access. Check Premium and the Developer app's users.");
        if (reply.code == 404) throw new ApiFailure("connected", "Spotify no longer has an active device.");
        check(reply);
        return reply;
    }

    private void token(boolean force) throws Exception {
        if (!force && !accessToken.isEmpty() && SystemClock.elapsedRealtime() < expiresAt) return;
        String body = "grant_type=refresh_token&client_id=" + encode(credentials.getString("client_id"))
                + "&refresh_token=" + encode(credentials.getString("refresh_token"));
        Reply reply = request("POST", "https://accounts.spotify.com/api/token", body, null);
        if (reply.code == 400 || reply.code == 401) throw new ApiFailure("reauth", "Sign in to Spotify again.");
        check(reply);
        JSONObject data = new JSONObject(reply.body);
        String token = data.getString("access_token");
        if (!"Bearer".equalsIgnoreCase(data.optString("token_type")) || token.isEmpty()) throw new IllegalStateException("Invalid token response");
        String replacement = data.optString("refresh_token");
        if (!replacement.isEmpty()) {
            credentials.put("refresh_token", replacement);
            vault.save(credentials);
        }
        accessToken = token;
        expiresAt = SystemClock.elapsedRealtime() + Math.max(0, data.getLong("expires_in") - 60) * 1000;
    }

    private Reply request(String method, String url, String form, String bearer) throws Exception {
        if (!active || closed || workerGeneration != generation || Thread.currentThread().isInterrupted()) throw new IllegalStateException("Inactive");
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        inFlight = connection;
        try {
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/json");
            if (bearer != null) connection.setRequestProperty("Authorization", "Bearer " + bearer);
            if (!"GET".equals(method)) {
                byte[] body = form == null ? new byte[0] : form.getBytes(StandardCharsets.UTF_8);
                connection.setRequestProperty("Content-Type", form == null ? "application/json" : "application/x-www-form-urlencoded");
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);
                try (java.io.OutputStream output = connection.getOutputStream()) { output.write(body); }
            }
            int status = connection.getResponseCode();
            String body = "";
            try (InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                if (input != null) {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (output.size() + count > 262144) throw new IllegalStateException("Response too large");
                        output.write(buffer, 0, count);
                    }
                    body = output.toString(StandardCharsets.UTF_8.name());
                }
            }
            return new Reply(status, body, connection.getHeaderField("Retry-After"));
        } finally {
            connection.disconnect();
            if (inFlight == connection) inFlight = null;
        }
    }

    private void check(Reply reply) throws ApiFailure {
        if (reply.code == 429) {
            long seconds = 30;
            try { seconds = Math.max(1, Long.parseLong(reply.retryAfter)); } catch (RuntimeException ignored) { }
            retryAt = SystemClock.elapsedRealtime() + Math.min(seconds, Integer.MAX_VALUE) * 1000L;
            throw new ApiFailure("rate_limited", "Spotify is busy. Try again later.");
        }
        if (reply.code < 200 || reply.code >= 300) throw new ApiFailure("unavailable", "Spotify could not apply the request. Try again later.");
    }

    private JSONObject state(String status, String message, JSONObject playback) {
        JSONObject result = new JSONObject(), actions = new JSONObject();
        try {
            result.put("webApi", status).put("webMessage", message).put("webLinked", credentials != null);
            JSONObject device = playback == null ? null : playback.optJSONObject("device");
            result.put("deviceName", device == null ? "" : device.optString("name"));
            for (String command : new String[]{"previous", "toggle", "next"}) actions.put(command, playback != null && SpotifyPlayback.allowed(playback, command));
            result.put("webActions", actions);
        } catch (org.json.JSONException ignored) { }
        return result;
    }

    private static String encode(String value) throws java.io.UnsupportedEncodingException { return URLEncoder.encode(value, "UTF-8"); }
    private void cancelRequest() { HttpURLConnection request = inFlight; if (request != null) request.disconnect(); }
    void close() { closed = true; active = false; generation++; cancelRequest(); worker.shutdownNow(); }
    private static final class Reply {
        final int code;
        final String body, retryAfter;
        Reply(int code, String body, String retryAfter) { this.code = code; this.body = body; this.retryAfter = retryAfter; }
    }
    private static final class ApiFailure extends Exception {
        final String status;
        ApiFailure(String status, String message) { super(message); this.status = status; }
    }
}
