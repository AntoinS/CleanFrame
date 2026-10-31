package com.cleanframe.philips;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;

/** Frontend code-entry sheet over Philips' own confirmation screen. No network in this WebView. */
public final class RemotePairActivity extends Activity {
    private WebView view;
    private AddonManager addons;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        addons = new AddonManager(this, () -> {}, false);
        view = new WebView(this);
        view.setBackgroundColor(Color.TRANSPARENT);
        view.getSettings().setJavaScriptEnabled(true);
        view.getSettings().setDomStorageEnabled(true);
        view.getSettings().setAllowFileAccess(false);
        view.getSettings().setAllowContentAccess(false);
        view.getSettings().setBlockNetworkLoads(true);
        WebViewAssetLoader.AssetsPathHandler assets = new WebViewAssetLoader.AssetsPathHandler(this);
        WebViewAssetLoader loader = new WebViewAssetLoader.Builder().addPathHandler("/home/", path -> assets.handle("home/" + path)).build();
        view.setWebViewClient(new WebViewClientCompat() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView web, WebResourceRequest request) {
                WebResourceResponse asset = loader.shouldInterceptRequest(request.getUrl());
                return asset != null ? asset : new WebResourceResponse("text/plain", "UTF-8", new java.io.ByteArrayInputStream(new byte[0]));
            }
            @Override public boolean shouldOverrideUrlLoading(WebView web, WebResourceRequest request) { return true; }
        });
        view.addJavascriptInterface(new Bridge(), "CleanFramePair");
        setContentView(view);
        WindowManager.LayoutParams attributes = getWindow().getAttributes(); attributes.gravity = Gravity.TOP | Gravity.LEFT;
        attributes.x = dp(24); attributes.y = dp(24); getWindow().setAttributes(attributes);
        getWindow().setLayout(dp(400), dp(300));
        view.loadUrl("https://appassets.androidplatform.net/home/addon-pair.html");
        view.requestFocus();
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void back() { startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)); finish(); }
    public final class Bridge {
        @JavascriptInterface public String state() { return addons.request("{\"action\":\"remoteState\"}"); }
        @JavascriptInterface public String submit(String code) {
            if (code == null || !code.matches("[0-9]{4}")) return "{\"error\":\"Invalid code\"}";
            return addons.request("{\"action\":\"remote\",\"id\":\"apple-remote\",\"command\":{\"api\":1,\"op\":\"philipsPairFinish\",\"code\":\"" + code + "\"}}");
        }
        @JavascriptInterface public void close() { runOnUiThread(RemotePairActivity.this::back); }
    }
    @Override public void onBackPressed() { back(); }
    @Override protected void onDestroy() { if (addons != null) addons.close(); if (view != null) { view.removeJavascriptInterface("CleanFramePair"); view.destroy(); } super.onDestroy(); }
}
