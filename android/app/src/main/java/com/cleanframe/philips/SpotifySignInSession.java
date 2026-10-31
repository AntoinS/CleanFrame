package com.cleanframe.philips;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native PKCE session. Only QR pixels, a display code and fixed status reach the UI. */
final class SpotifySignInSession implements AutoCloseable {
    private static final String ORIGIN = "https://cleanframe-spotify-link.earthydune259.chatgpt.site";
    private static final String REDIRECT = ORIGIN + "/spotify/callback";
    private static final String CLIENT_ID = "656602ccdccc4cb8b5b91ced7ed042e2";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final SecureRandom random = new SecureRandom();
    private volatile HttpURLConnection inFlight;
    private volatile boolean closed;
    private String verifier, state, pollSecret, pairing;
    private long deadline;
    private final Context context;
    private final Runnable changed, connected;
    private volatile String snapshot = "{\"phase\":\"preparing\"}";
    SpotifySignInSession(Context context, Runnable changed, Runnable connected) {
        this.context = context.getApplicationContext(); this.changed = changed; this.connected = connected;
    }
    void start() { worker.execute(this::begin); }
    String state() { return snapshot; }
    private void publish(String phase, String image, String code) {
        if (closed) return;
        try { snapshot = new JSONObject().put("phase", phase).put("qr", image).put("code", code)
            .put("expiresAt", phase.equals("pending") ? System.currentTimeMillis() + Math.max(0, deadline - SystemClock.elapsedRealtime()) : 0).toString(); }
        catch (Exception ignored) { return; }
        main.post(() -> { if (!closed) changed.run(); });
    }
    private String randomToken(int bytes) { byte[] data = new byte[bytes]; random.nextBytes(data); return Base64.encodeToString(data, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING); }
    private byte[] digest(String value) throws Exception { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)); }

    private void begin() {
        try {
            verifier = randomToken(64); state = randomToken(32); pollSecret = randomToken(32);
            deadline = SystemClock.elapsedRealtime() + 600000;
            String challenge = Base64.encodeToString(digest(verifier), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            String hash = String.format(java.util.Locale.ROOT, "%064x", new BigInteger(1,digest(pollSecret)));
            JSONObject created = request(ORIGIN + "/api/pair", new JSONObject().put("state", state).put("challenge", challenge).put("pollHash", hash).toString(), false);
            pairing = created.getString("id");
            if (!pairing.matches("[a-f0-9-]{36}") || !(ORIGIN + "/pair/" + pairing).equals(created.getString("url"))) throw new IllegalStateException();
            BitMatrix matrix = new QRCodeWriter().encode(created.getString("url"), BarcodeFormat.QR_CODE, 512, 512, Collections.singletonMap(EncodeHintType.MARGIN,4));
            int[] pixels = new int[512*512];
            for (int y=0;y<512;y++) for (int x=0;x<512;x++) pixels[y*512+x] = matrix.get(x,y) ? Color.BLACK : Color.WHITE;
            Bitmap bitmap = Bitmap.createBitmap(pixels,512,512,Bitmap.Config.ARGB_8888);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, png); bitmap.recycle();
            publish("pending", "data:image/png;base64," + Base64.encodeToString(png.toByteArray(), Base64.NO_WRAP), pairing.substring(0,6).toUpperCase(java.util.Locale.ROOT));
            main.post(this::schedule);
        } catch (Exception failure) { fail("unreachable"); }
    }
    private void schedule() { if (!closed) main.postDelayed(() -> { if (!closed) worker.execute(this::poll); },2500); }
    private void poll() {
        try {
            if (SystemClock.elapsedRealtime() >= deadline) { fail("expired"); return; }
            JSONObject result = request(ORIGIN + "/api/pair/" + pairing, new JSONObject().put("secret",pollSecret).toString(),false);
            if ("pending".equals(result.optString("status"))) { main.post(this::schedule); return; }
            if (!"complete".equals(result.optString("status")) || !state.equals(result.optString("state")) || !REDIRECT.equals(result.optString("redirectUri"))) throw new IllegalStateException();
            if (!result.isNull("error")) { fail("cancelled"); return; }
            String code = result.getString("code");
            if (code.isEmpty() || code.length() > 4096) throw new IllegalStateException();
            String form = "grant_type=authorization_code&client_id=" + encode(CLIENT_ID) + "&redirect_uri=" + encode(REDIRECT)
                    + "&code_verifier=" + encode(verifier) + "&code=" + encode(code);
            JSONObject tokens = request("https://accounts.spotify.com/api/token", form, true);
            if (!"Bearer".equalsIgnoreCase(tokens.optString("token_type"))) throw new IllegalStateException();
            JSONObject saved = new JSONObject().put("client_id",CLIENT_ID).put("refresh_token",tokens.getString("refresh_token"));
            synchronized (this) {
                if (closed) return;
                new SpotifyCredentials(context).save(saved);
            }
            publish("connected", "", "");
            main.post(() -> { if (!closed) connected.run(); });
        } catch (Exception failure) { fail("failed"); }
    }
    private void fail(String phase) { publish(phase, "", ""); }
    private static String encode(String value) throws Exception { return URLEncoder.encode(value,"UTF-8"); }
    private JSONObject request(String endpoint, String body, boolean form) throws Exception {
        if (closed) throw new IllegalStateException();
        HttpURLConnection connection = (HttpURLConnection)new URL(endpoint).openConnection(); inFlight = connection;
        try {
            connection.setConnectTimeout(8000); connection.setReadTimeout(8000); connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
            connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setRequestProperty("Accept","application/json");
            connection.setRequestProperty("Content-Type",form ? "application/x-www-form-urlencoded" : "application/json");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8); connection.setFixedLengthStreamingMode(bytes.length);
            try (java.io.OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) throw new IllegalStateException();
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[2048]; int count;
                while ((count=input.read(buffer))!=-1) { if (output.size()+count>16384) throw new IllegalStateException(); output.write(buffer,0,count); }
                return new JSONObject(output.toString("UTF-8"));
            }
        } finally { connection.disconnect(); if (inFlight==connection) inFlight=null; }
    }
    @Override public synchronized void close() {
        closed = true; main.removeCallbacksAndMessages(null);
        HttpURLConnection request = inFlight; if (request != null) request.disconnect();
        worker.shutdownNow(); verifier = null; pollSecret = null; state = null;
        snapshot = "{\"phase\":\"idle\"}";
    }
}
