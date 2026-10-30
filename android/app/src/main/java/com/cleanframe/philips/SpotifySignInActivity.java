package com.cleanframe.philips;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
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

/** Phone approval, native PKCE exchange. Credentials never enter the QR or WebView. */
public final class SpotifySignInActivity extends Activity {
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
    private ImageView qr;
    private TextView title, detail, pairingLabel;
    private Button back;
    private android.content.Context localized;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        android.content.res.Configuration configuration = new android.content.res.Configuration(getResources().getConfiguration());
        configuration.setLocale(java.util.Locale.forLanguageTag("de".equals(getIntent().getStringExtra("language")) ? "de" : "en"));
        localized = createConfigurationContext(configuration);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        LinearLayout root = new LinearLayout(this);
        root.setGravity(Gravity.CENTER); root.setPadding(dp(48), dp(32), dp(48), dp(32)); root.setBackgroundColor(Color.rgb(24,24,24));
        qr = new ImageView(this); qr.setContentDescription(localized.getString(R.string.spotify_qr_description)); qr.setBackgroundColor(Color.rgb(39,39,39));
        root.addView(qr, new LinearLayout.LayoutParams(dp(256), dp(256)));
        LinearLayout copy = new LinearLayout(this); copy.setOrientation(LinearLayout.VERTICAL); copy.setPadding(dp(40),0,0,0);
        root.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        title = text(30); title.setText(localized.getString(R.string.spotify_connect)); copy.addView(title);
        detail = text(18); detail.setPadding(0,dp(16),0,dp(16)); detail.setText(localized.getString(R.string.spotify_preparing)); copy.addView(detail);
        pairingLabel = text(20); copy.addView(pairingLabel);
        back = new Button(this); back.setText(localized.getString(R.string.back)); back.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams button = new LinearLayout.LayoutParams(dp(200), dp(48)); button.topMargin = dp(24); copy.addView(back,button);
        setContentView(root); back.requestFocus();
        DesignOverrides.applyQr(root, qr, title, detail, pairingLabel, back);
        worker.execute(this::begin);
    }
    private TextView text(int size) { TextView value = new TextView(this); value.setTextColor(Color.rgb(244,244,241)); value.setTextSize(size); return value; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
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
            main.post(() -> {
                if (closed) return;
                qr.setImageBitmap(bitmap); detail.setText(localized.getString(R.string.spotify_scan));
                pairingLabel.setText(localized.getString(R.string.spotify_pairing_code, pairing.substring(0,6).toUpperCase(java.util.Locale.ROOT)));
                schedule();
            });
        } catch (Exception failure) { fail(localized.getString(R.string.spotify_unreachable)); }
    }
    private void schedule() { if (!closed) main.postDelayed(() -> { if (!closed) worker.execute(this::poll); },2500); }
    private void poll() {
        try {
            if (SystemClock.elapsedRealtime() >= deadline) { fail(localized.getString(R.string.spotify_expired)); return; }
            JSONObject result = request(ORIGIN + "/api/pair/" + pairing, new JSONObject().put("secret",pollSecret).toString(),false);
            if ("pending".equals(result.optString("status"))) { main.post(this::schedule); return; }
            if (!"complete".equals(result.optString("status")) || !state.equals(result.optString("state")) || !REDIRECT.equals(result.optString("redirectUri"))) throw new IllegalStateException();
            if (!result.isNull("error")) { fail(localized.getString(R.string.spotify_cancelled)); return; }
            String code = result.getString("code");
            if (code.isEmpty() || code.length() > 4096) throw new IllegalStateException();
            String form = "grant_type=authorization_code&client_id=" + encode(CLIENT_ID) + "&redirect_uri=" + encode(REDIRECT)
                    + "&code_verifier=" + encode(verifier) + "&code=" + encode(code);
            JSONObject tokens = request("https://accounts.spotify.com/api/token", form, true);
            if (!"Bearer".equalsIgnoreCase(tokens.optString("token_type"))) throw new IllegalStateException();
            JSONObject saved = new JSONObject().put("client_id",CLIENT_ID).put("refresh_token",tokens.getString("refresh_token"));
            if (closed) return;
            new SpotifyCredentials(this).save(saved);
            main.post(() -> {
                if (closed) return;
                qr.setVisibility(View.INVISIBLE); title.setText(localized.getString(R.string.spotify_connected)); detail.setText(localized.getString(R.string.spotify_returning)); pairingLabel.setText("");
                main.postDelayed(this::finish,1500);
            });
        } catch (Exception failure) { fail(localized.getString(R.string.spotify_failed)); }
    }
    private void fail(String message) { main.post(() -> { if (!closed) { qr.setVisibility(View.INVISIBLE); pairingLabel.setText(""); detail.setText(message); } }); }
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
    @Override public void onStop() {
        closed=true; main.removeCallbacksAndMessages(null);
        HttpURLConnection request=inFlight; if(request!=null)request.disconnect(); worker.shutdownNow();
        verifier=null; pollSecret=null; state=null;
        super.onStop(); finish();
    }
}
