package com.cleanframe.philips;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.UserManager;
import android.provider.Settings;
import android.util.AtomicFile;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MainActivity extends Activity {
    private static final String ASSET_HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + ASSET_HOST + "/assets/home/index.html";
    // Saved original launcher, verified before preparing this independent APK.
    private static final ComponentName ORIGINAL_HOME = new ComponentName(
            "com.google.android.tvlauncher", "com.google.android.tvlauncher.MainActivity");
    private final Object iconCacheLock = new Object();
    private WebView webView;
    private BroadcastReceiver unlockReceiver;
    private SpotifyMedia spotify;
    private volatile SpotifySignInSession spotifySignIn;
    private AddonManager addons;
    private SoftwareUpdateManager software;
    private boolean resumed;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        UserManager users = getSystemService(UserManager.class);
        if (users.isUserUnlocked()) {
            loadUnlockedUi();
        } else {
            // The Home activity must exist during Direct Boot, but WebView and
            // its saved preferences must wait for credential-encrypted storage.
            TextView starting = new TextView(this);
            starting.setText(R.string.starting);
            starting.setGravity(android.view.Gravity.CENTER);
            setContentView(starting);
            unlockReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (unlockReceiver != null && Intent.ACTION_USER_UNLOCKED.equals(intent.getAction())) {
                        loadUnlockedUi();
                    }
                }
            };
            IntentFilter filter = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(unlockReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(unlockReceiver, filter);
            }
            // Unlock can occur between the first check and registration.
            if (users.isUserUnlocked()) loadUnlockedUi();
        }
    }

    private void loadUnlockedUi() {
        if (unlockReceiver != null) {
            unregisterReceiver(unlockReceiver);
            unlockReceiver = null;
        }
        try {
            createWebView();
        } catch (RuntimeException failure) {
            showFailure("Android WebView ist nicht verfügbar.");
        }
    }

    private void createWebView() {
        if (addons == null) addons = new AddonManager(this, () -> runOnUiThread(() -> {
            if (webView != null && !isDestroyed()) webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('cleanframe:addons',{detail:" + addons.state() + "}));", null);
        }));
        if (software == null) software = new SoftwareUpdateManager(this, addons::operating, () -> runOnUiThread(() -> {
            if (webView != null && !isDestroyed()) webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('cleanframe:software',{detail:" + software.state() + "}));", null);
        }));
        spotify = new SpotifyMedia(this, state -> {
            if (webView != null) webView.evaluateJavascript(
                    "window.dispatchEvent(new CustomEvent('cleanframe:spotify',{detail:" + state + "}));", null);
        });
        spotify.resume(resumed);
        WebView.setWebContentsDebuggingEnabled(
                (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/icons/", this::serveIcon)
                .addPathHandler("/media/", path -> spotify == null ? null : spotify.serveArtwork(path))
                .build();
        webView = new WebView(this);
        webView.setBackgroundColor(android.graphics.Color.BLACK);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(false);
        webView.getSettings().setAllowContentAccess(false);
        webView.getSettings().setBlockNetworkLoads(true);
        webView.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        webView.addJavascriptInterface(new NativeBridge(), "CleanFrameNative");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !isLocalAsset(request.getUrl())
                        || !"/assets/home/index.html".equals(request.getUrl().getPath());
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (!isLocalAsset(request.getUrl())) return blocked();
                WebResourceResponse response = loader.shouldInterceptRequest(request.getUrl());
                return response == null ? blocked() : response;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) showFailure(error.getDescription().toString());
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame()) showFailure("Die lokalen UI-Dateien konnten nicht geladen werden.");
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                showFailure("Der WebView-Prozess wurde beendet.");
                return true;
            }
        });
        setContentView(webView);
        webView.requestFocus();
        webView.loadUrl(START_URL);
    }

    private static boolean isLocalAsset(Uri uri) {
        String path = uri.getPath();
        return "https".equals(uri.getScheme()) && ASSET_HOST.equals(uri.getHost())
                && (uri.getPort() == -1 || uri.getPort() == 443)
                && path != null && (path.startsWith("/assets/home/") || path.startsWith("/icons/") || path.startsWith("/media/"));
    }

    private static WebResourceResponse blocked() {
        return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked",
                java.util.Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
    }

    private static boolean usable(ResolveInfo info) {
        ActivityInfo activity = info.activityInfo;
        return activity != null && activity.exported && activity.enabled
                && activity.applicationInfo != null && activity.applicationInfo.enabled;
    }

    private Intent tvIntent(String packageName) {
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        if (packageName != null) intent.setPackage(packageName);
        return intent;
    }

    private List<ResolveInfo> tvApps(String packageName) {
        List<ResolveInfo> apps = new ArrayList<>();
        for (ResolveInfo info : getPackageManager().queryIntentActivities(tvIntent(packageName), 0)) {
            if (usable(info) && !getPackageName().equals(info.activityInfo.packageName)) apps.add(info);
        }
        return apps;
    }

    private WebResourceResponse serveIcon(String path) {
        if (!path.endsWith(".png")) return blocked();
        String packageName = path.substring(0, path.length() - 4);
        if (!packageName.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) return blocked();
        try {
            List<ResolveInfo> apps = tvApps(packageName);
            if (apps.isEmpty()) return blocked();
            long updated = getPackageManager().getPackageInfo(packageName, 0).lastUpdateTime;
            File folder = new File(getCacheDir(), "app-icons");
            if (!folder.isDirectory() && !folder.mkdirs() && !folder.isDirectory()) throw new IOException("Icon-Cache ist nicht verfügbar.");
            AtomicFile icon = new AtomicFile(new File(folder, packageName + "-" + updated + ".png"));
            synchronized (iconCacheLock) {
                if (!icon.getBaseFile().isFile()) {
                    Drawable drawable = apps.get(0).loadIcon(getPackageManager());
                    int width = Math.max(1, drawable.getIntrinsicWidth());
                    int height = Math.max(1, drawable.getIntrinsicHeight());
                    float scale = Math.min(192f / width, 192f / height);
                    int bitmapWidth = Math.max(1, Math.round(width * scale));
                    int bitmapHeight = Math.max(1, Math.round(height * scale));
                    Bitmap bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
                    drawable.setBounds(0, 0, bitmapWidth, bitmapHeight);
                    drawable.draw(new Canvas(bitmap));
                    FileOutputStream stream = null;
                    try {
                        stream = icon.startWrite();
                        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new IOException("Icon kann nicht gespeichert werden.");
                        icon.finishWrite(stream);
                    } catch (IOException failure) {
                        if (stream != null) icon.failWrite(stream);
                        throw failure;
                    } finally {
                        bitmap.recycle();
                    }
                }
                return new WebResourceResponse("image/png", null, icon.openRead());
            }
        } catch (Exception failure) {
            return blocked();
        }
    }

    private static String error(String message) {
        return "{\"error\":" + JSONObject.quote(message) + "}";
    }

    private interface DeviceAction {
        void run() throws Exception;
    }

    private String performAction(DeviceAction action) {
        // JavaScript calls are synchronous on WebView's bridge thread. Never wait
        // for its UI thread here; these actions touch PackageManager/Binder only.
        try {
            action.run();
            return "{\"ok\":true}";
        } catch (Exception failure) {
            String message = failure.getMessage();
            return error(message == null ? "Die Aktion konnte nicht ausgeführt werden." : message);
        }
    }

    private void startNativeActivity(Intent intent) {
        // ContextImpl uses ActivityTaskManager Binder without Activity/View work.
        getApplicationContext().startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void openOriginalHome() throws PackageManager.NameNotFoundException {
        ActivityInfo activity = getPackageManager().getActivityInfo(
                ORIGINAL_HOME, PackageManager.MATCH_DISABLED_COMPONENTS);
        if (!activity.enabled || !activity.exported || !activity.applicationInfo.enabled) {
            Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + ORIGINAL_HOME.getPackageName()));
            if (details.resolveActivity(getPackageManager()) == null) {
                throw new IllegalStateException("Original Home is disabled. Use the saved recovery script on your Mac.");
            }
            startNativeActivity(details);
            return;
        }
        startNativeActivity(new Intent(Intent.ACTION_MAIN).setComponent(ORIGINAL_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        if (webView != null) webView.evaluateJavascript("window.dispatchEvent(new Event('cleanframe:open-addon'));", null);
    }

    public final class NativeBridge {
        @JavascriptInterface public String requestedAddon() {
            String id = getIntent().getStringExtra("cleanframeAddon");
            getIntent().removeExtra("cleanframeAddon");
            return "apple-remote".equals(id) ? id : "";
        }

        @JavascriptInterface
        public String addons(String request) {
            try {
                String action = new JSONObject(request).optString("action");
                if (software != null && software.operating() && action.matches("install|remove|updateAll"))
                    return "{\"error\":\"A software update is running.\"}";
            } catch (Exception invalid) { return "{\"error\":\"Invalid add-on request.\"}"; }
            return addons.request(request);
        }

        @JavascriptInterface
        public String software(String request) { return software.request(request); }

        @JavascriptInterface
        public String spotifyState() { return spotify.state(); }

        @JavascriptInterface
        public String spotifyObserve(boolean enabled) {
            spotify.observe(enabled && addons.enabled("spotify"));
            return "{\"ok\":true}";
        }

        @JavascriptInterface
        public String spotifyControl(String command) {
            if (!"previous".equals(command) && !"toggle".equals(command) && !"next".equals(command)) {
                return error("Unknown Spotify command.");
            }
            spotify.control(command);
            return "{\"ok\":true}";
        }

        @JavascriptInterface
        public String spotifyAccess() {
            return performAction(() -> startNativeActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        }

        @JavascriptInterface
        public String spotifyLink(String operation) {
            if ("state".equals(operation)) return spotifySignIn == null ? "{\"phase\":\"idle\"}" : spotifySignIn.state();
            if (!"begin".equals(operation) && !"cancel".equals(operation)) return error("Unknown sign-in operation.");
            runOnUiThread(() -> {
                if (spotifySignIn != null) { spotifySignIn.close(); spotifySignIn = null; }
                if ("begin".equals(operation) && resumed) {
                    spotifySignIn = new SpotifySignInSession(MainActivity.this, () -> {
                        if (webView != null) webView.evaluateJavascript("window.dispatchEvent(new Event('cleanframe:spotify-link'));", null);
                    }, () -> { if (spotify != null) spotify.resume(resumed); });
                    spotifySignIn.start();
                }
            });
            return "{\"ok\":true}";
        }

        @JavascriptInterface
        public String remotePermission(String operation) {
            if ("pairing".equals(operation)) return performAction(() -> startNativeActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
            if ("enable".equals(operation)) return performAction(() -> startNativeActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
            if ("select".equals(operation)) return performAction(() -> getSystemService(android.view.inputmethod.InputMethodManager.class).showInputMethodPicker());
            return error("Unknown add-on permission action.");
        }

        @JavascriptInterface
        public String spotifySignOut() {
            spotify.signOut();
            return "{\"ok\":true}";
        }

        // Installer-only operation after the owner's separate Home approval.
        // Android permits an app to manage its own alias; this TV rejects shell
        // component changes. false restores the manifest's disabled baseline.
        @JavascriptInterface
        public String setHomeEnabled(boolean enabled) {
            return performAction(() -> getPackageManager().setComponentEnabledSetting(
                    new ComponentName(MainActivity.this, "com.cleanframe.philips.HomeActivity"),
                    enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                    PackageManager.DONT_KILL_APP));
        }

        @JavascriptInterface
        public String apps() {
            try {
                PackageManager manager = getPackageManager();
                Map<String, ResolveInfo> unique = new LinkedHashMap<>();
                for (ResolveInfo info : tvApps(null)) unique.putIfAbsent(info.activityInfo.packageName, info);
                List<ResolveInfo> sorted = new ArrayList<>(unique.values());
                Collator collator = Collator.getInstance();
                sorted.sort((first, second) -> collator.compare(first.loadLabel(manager).toString(), second.loadLabel(manager).toString()));
                JSONArray result = new JSONArray();
                for (ResolveInfo info : sorted) {
                    String packageName = info.activityInfo.packageName;
                    JSONObject app = new JSONObject();
                    app.put("appId", packageName);
                    app.put("name", info.loadLabel(manager).toString());
                    app.put("icon", "/icons/" + packageName + ".png");
                    result.put(app);
                }
                return result.toString();
            } catch (Exception failure) {
                return error("Could not read installed TV apps.");
            }
        }

        @JavascriptInterface
        public String launchApp(String packageName) {
            return performAction(() -> {
                if (packageName == null || tvApps(packageName).isEmpty()) {
                    throw new IllegalArgumentException("This app is not installed as a launchable TV app.");
                }
                Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(packageName);
                if (launch == null) throw new IllegalStateException("This TV app has no launch screen.");
                startNativeActivity(launch);
            });
        }

        @JavascriptInterface
        public String home() {
            return performAction(MainActivity.this::openOriginalHome);
        }

        @JavascriptInterface
        public String settings() {
            return performAction(() -> {
                Intent intent = new Intent(Settings.ACTION_SETTINGS);
                if (intent.resolveActivity(getPackageManager()) == null) {
                    throw new IllegalStateException("Android TV settings are unavailable.");
                }
                startNativeActivity(intent);
            });
        }

        @JavascriptInterface
        public String art() {
            return error("Samsung Art Mode is not available on this Philips TV.");
        }
    }

    private void dispatchWebKey(String type, int code, boolean repeat) {
        if (webView == null) return;
        String key = code == 13 ? "Enter" : "Escape";
        webView.evaluateJavascript("(function(){var e=new KeyboardEvent(" + JSONObject.quote(type)
                + ",{key:" + JSONObject.quote(key) + ",code:" + JSONObject.quote(key)
                + ",keyCode:" + code + ",which:" + code + ",bubbles:true,cancelable:true,repeat:"
                + repeat + "});Object.defineProperty(e,'keyCode',{value:" + code
                + "});document.dispatchEvent(e);}())", null);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (webView != null && (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN || event.getAction() == KeyEvent.ACTION_UP) {
                dispatchWebKey(event.getAction() == KeyEvent.ACTION_DOWN ? "keydown" : "keyup",
                        code == KeyEvent.KEYCODE_BACK ? 27 : 13, event.getRepeatCount() > 0);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (webView != null) {
            dispatchWebKey("keydown", 27, false);
            dispatchWebKey("keyup", 27, false);
        } else {
            performAction(this::openOriginalHome);
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        if (spotifySignIn != null) { spotifySignIn.close(); spotifySignIn = null; }
        if (spotify != null) spotify.resume(false);
        if (webView != null) {
            // Native visibility drives document.hidden and the existing rotation code.
            webView.setVisibility(View.INVISIBLE);
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (addons != null) addons.resume();
        if (software != null) software.resume();
        if (spotify != null) spotify.resume(true);
        if (webView != null) {
            webView.onResume();
            webView.setVisibility(View.VISIBLE);
            webView.requestFocus();
        }
    }

    private void showFailure(String detail) {
        if (isFinishing() || isDestroyed()) return;
        if (spotify != null) { spotify.close(); spotify = null; }
        if (webView != null) {
            WebView failed = webView;
            webView = null;
            if (failed.getParent() instanceof ViewGroup) ((ViewGroup) failed.getParent()).removeView(failed);
            failed.removeJavascriptInterface("CleanFrameNative");
            failed.destroy();
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        TextView message = new TextView(this);
        message.setText(getString(R.string.load_failed) + "\n" + detail);
        content.addView(message);
        Button home = new Button(this);
        home.setText(R.string.open_original_home);
        home.setOnClickListener(view -> {
            String result = performAction(this::openOriginalHome);
            if (!"{\"ok\":true}".equals(result)) {
                try {
                    message.setText(new JSONObject(result).optString("error", getString(R.string.load_failed)));
                } catch (org.json.JSONException failure) {
                    message.setText(R.string.load_failed);
                }
            }
        });
        content.addView(home);
        setContentView(content);
        home.requestFocus();
    }

    @Override
    protected void onDestroy() {
        if (addons != null) addons.close();
        if (software != null) software.close();
        if (spotify != null) { spotify.close(); spotify = null; }
        if (unlockReceiver != null) {
            unregisterReceiver(unlockReceiver);
            unlockReceiver = null;
        }
        if (webView != null) {
            if (webView.getParent() instanceof ViewGroup) ((ViewGroup) webView.getParent()).removeView(webView);
            webView.removeJavascriptInterface("CleanFrameNative");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}

