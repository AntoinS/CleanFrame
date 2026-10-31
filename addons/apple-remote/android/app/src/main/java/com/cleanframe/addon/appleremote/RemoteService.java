package com.cleanframe.addon.appleremote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.provider.Settings;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import com.cleanframe.addonapi.IRemoteAddon;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.net.Inet4Address;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class RemoteService extends Service {
    private static volatile RemoteService current;
    private boolean automaticSetupAttempted;
    private boolean automaticPairing;
    private int automaticPairings;
    public static final String ENABLE = "com.cleanframe.addon.appleremote.ENABLE";
    private static final String CHANNEL = "remote_receiver";
    private static final String HOST = "com.cleanframe.philips";
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile String cached = "{\"running\":false}";
    private volatile String serviceError = "";
    private volatile boolean enabled;
    private final java.util.concurrent.atomic.AtomicLong activationEpoch = new java.util.concurrent.atomic.AtomicLong();
    private boolean nativeAvailable;
    private volatile String startedAddress = "";
    private volatile String startedName = "";
    private ConnectivityManager connectivity;
    private WifiManager.MulticastLock multicast;
    private SecretStore secrets;
    private HostInputBridge hostInput;
    private final PairCodeWindow pairCodeWindow = new PairCodeWindow();

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) { changedNetwork(); }
        @Override public void onLost(Network network) { changedNetwork(); }
        @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) { changedNetwork(); }
    };

    private final IRemoteAddon.Stub binder = new IRemoteAddon.Stub() {
        @Override public String request(String json) {
            enforceCaller();
            try {
                JSONObject command = RequestPolicy.parse(json);
                String op = command.getString("op");
                boolean hostCaller = Binder.getCallingUid() != Process.myUid();
                if (op.equals("setupStatus")) return setupState().toString();
                if (op.equals("status")) return state(hostCaller).toString();
                if (op.equals("enable")) {
                    if (!setEnabled(true)) return failure("Receiver setting could not be saved.");
                    long epoch = activationEpoch.get();
                    main.post(() -> {
                        if (enabled && epoch == activationEpoch.get()) startForegroundService(new Intent(RemoteService.this, RemoteService.class).setAction(ENABLE).putExtra("activationEpoch", epoch));
                    });
                } else if (op.equals("disable")) {
                    if (!setEnabled(false)) return failure("Receiver setting could not be saved.");
                    long epoch = activationEpoch.get();
                    worker.execute(() -> {
                        if (enabled || epoch != activationEpoch.get()) return;
                        nativeStop();
                        main.post(() -> {
                            if (enabled || epoch != activationEpoch.get()) return;
                            if (multicast != null && multicast.isHeld()) multicast.release();
                            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
                        });
                    });
                } else {
                    if (!enabled) return failure("Receiver is not running.");
                    command.remove("api");
                    long epoch = activationEpoch.get();
                    if (op.equals("philipsPairBegin") || op.equals("philipsPairFinish")) {
                            worker.execute(() -> {
                            if (!enabled || epoch != activationEpoch.get()) return;
                            if (op.equals("philipsPairBegin")) beginPhilipsPairing();
                            else { hostInput.finishPairing(command.optString("code")); offerPairCode(state(false)); }
                            refreshPairCodeNotification();
                        });
                    } else {
                        if (!nativeAvailable || startedAddress.isEmpty()) return failure("Receiver is not running.");
                        worker.execute(() -> { if (enabled && epoch == activationEpoch.get()) invoke(command); });
                    }
                }
                return state(hostCaller).put("accepted", true).toString();
            } catch (Exception failure) { return failure("Invalid or unavailable add-on request."); }
        }
    };

    public static boolean isEnabled(Context context) {
        return context.getSharedPreferences("receiver", MODE_PRIVATE).getBoolean("enabled", false);
    }

    private synchronized boolean setEnabled(boolean value) {
        if (!getSharedPreferences("receiver", MODE_PRIVATE).edit().putBoolean("enabled", value).commit()) {
            serviceError = "Receiver setting could not be saved.";
            return false;
        }
        if (enabled == value) return true;
        enabled = value;
        activationEpoch.incrementAndGet();
        if (!value) {
            automaticSetupAttempted = false;
            if (hostInput != null) hostInput.disableGuard();
            pairCodeWindow.clear();
        }
        return true;
    }

    @Override public void onCreate() {
        super.onCreate();
        current = this;
        // Binding for status alone must not start a receiver. Boot/Activity explicitly
        // start this service; sticky restarts restore the durable enabled preference.
        enabled = false;
        secrets = new SecretStore(this);
        hostInput = new HostInputBridge(this);
        try { RemoteNative.initialize(this); RemoteNative.setHost(hostInput); nativeAvailable = true; }
        catch (LinkageError error) { serviceError = "Receiver library is missing or incompatible."; }
        connectivity = getSystemService(ConnectivityManager.class);
        connectivity.registerDefaultNetworkCallback(networkCallback);
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifi != null) { multicast = wifi.createMulticastLock("CleanFrameRemoteDiscovery"); multicast.setReferenceCounted(false); }
        worker.scheduleWithFixedDelay(() -> {
            if (enabled) {
                updateConnection();
                if (nativeAvailable && !startedAddress.isEmpty()) invoke(newCommand("status"));
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("activationEpoch") && intent.getLongExtra("activationEpoch", -1) != activationEpoch.get()) {
            if (!enabled) stopSelf(startId);
            return enabled ? START_STICKY : START_NOT_STICKY;
        }
        if ((intent != null && ENABLE.equals(intent.getAction())) || (intent == null && isEnabled(this))) {
            showNotification();
            if (!setEnabled(true)) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId); return START_NOT_STICKY; }
            if (multicast != null && !multicast.isHeld()) multicast.acquire();
            worker.execute(this::updateConnection);
            return START_STICKY;
        }
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    private void showNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "iPhone remote", NotificationManager.IMPORTANCE_LOW));
        boolean codeEntry = pairCodeWindow.available(SystemClock.elapsedRealtime());
        Intent target = codeEntry ? hostPairIntent() : new Intent(this, SetupActivity.class);
        PendingIntent setup = PendingIntent.getActivity(this, codeEntry ? 1 : 0, target, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder notification = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.remote_icon).setContentTitle("CleanTV iPhone Remote")
            .setContentText(codeEntry ? "Philips code ready · open CleanTV code entry" : "Receiver enabled · open to manage paired devices")
            .setContentIntent(setup).setOngoing(true);
        if (codeEntry) notification.addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, R.drawable.remote_icon), "CleanTV · Philips-Code eingeben", setup).build());
        startForeground(1, notification.build());
    }

    private void changedNetwork() {
        try { worker.execute(this::updateConnection); }
        catch (java.util.concurrent.RejectedExecutionException closed) { /* Service was destroyed during callback delivery. */ }
    }

    private String address() {
        Network network = connectivity.getActiveNetwork();
        LinkProperties properties = network == null ? null : connectivity.getLinkProperties(network);
        if (properties != null) for (LinkAddress link : properties.getLinkAddresses()) {
            if (link.getAddress() instanceof Inet4Address && !link.getAddress().isLoopbackAddress()) return link.getAddress().getHostAddress();
        }
        return "";
    }

    private String deviceName() {
        String name = null;
        try { name = Settings.Global.getString(getContentResolver(), Settings.Global.DEVICE_NAME); }
        catch (SecurityException unavailable) { /* Some firmware restricts global settings reads. */ }
        if (name == null || name.trim().isEmpty()) name = Build.MODEL;
        return name == null || name.trim().isEmpty() ? "Android TV" : name.trim();
    }

    private void updateConnection() {
        if (!enabled || !nativeAvailable) return;
        long epoch = activationEpoch.get();
        String address = address();
        String name = deviceName();
        if (address.equals(startedAddress) && (address.isEmpty() || name.equals(startedName))) {
            if (address.isEmpty()) serviceError = "Waiting for a local network connection.";
            else maybeAutomaticSetup();
            return;
        }
        nativeStop();
        if (address.isEmpty()) { serviceError = "Waiting for a local network connection."; return; }
        try {
            JSONObject command = newCommand("start").put("name", name).put("address", address).put("backend", "host").put("secrets", secrets.read());
            if (!enabled || epoch != activationEpoch.get()) return;
            hostInput.enable();
            if (!enabled || epoch != activationEpoch.get()) { hostInput.disableGuard(); return; }
            String result = RemoteNative.request(command.toString());
            RequestPolicy.parseRunningResponse(result);
            if (!enabled || epoch != activationEpoch.get()) { nativeStop(); return; }
            cached = result; startedAddress = address; startedName = name; serviceError = "";
            maybeAutomaticSetup();
        } catch (Exception | LinkageError failure) {
            startedAddress = "";
            hostInput.disableGuard();
            serviceError = "Receiver could not start. Stored identity and permissions remain unchanged.";
        }
    }

    static void pairingAccessReady() {
        RemoteService service = current;
        if (service != null) try { service.worker.execute(service::maybeAutomaticSetup); }
        catch (java.util.concurrent.RejectedExecutionException stopped) { /* No active receiver. */ }
    }
    static void pairingAccessLost() {
        RemoteService service = current;
        if (service != null && service.hostInput != null) service.hostInput.clearCapture();
    }
    static boolean awaitingPhilipsCode() {
        RemoteService service = current;
        return PhilipsPairingAccessibility.connected() && service != null && service.enabled && service.hostInput != null && service.hostInput.awaitingCapture();
    }
    static boolean receivePhilipsCode(String packageName, CharSequence text) {
        RemoteService service = current;
        if (service == null || !service.enabled || service.hostInput == null) return false;
        PhilipsPairCapture.Code code = service.hostInput.capture(packageName, text);
        if (code == null) return false;
        long epoch = service.activationEpoch.get(), accessGeneration = PhilipsPairingAccessibility.generation();
        try { service.worker.execute(() -> {
            if (!service.enabled || epoch != service.activationEpoch.get() || !PhilipsPairingAccessibility.connected()
                    || accessGeneration != PhilipsPairingAccessibility.generation()) return;
            JSONObject result = service.hostInput.finishCaptured(code);
            if (result.optBoolean("automaticallyConfirmed")) {
                service.automaticPairings++;
                service.prepareFirstIPhone();
                service.main.post(service::returnToSetup);
            }
            service.offerPairCode(service.state(false));
        }); } catch (java.util.concurrent.RejectedExecutionException stopped) { return false; }
        return true;
    }
    private void beginPhilipsPairing() {
        long requestedAt = SystemClock.elapsedRealtime();
        automaticPairing = PhilipsPairingAccessibility.connected();
        JSONObject result = hostInput.beginPairing();
        if (result.optString("error").isEmpty()) {
            pairCodeWindow.arm(requestedAt); offerPairCode(state(false));
        }
    }
    private void maybeAutomaticSetup() {
        if (!enabled || startedAddress.isEmpty() || automaticSetupAttempted || !PhilipsPairingAccessibility.connected()) return;
        if (hostInput.philipsState().optBoolean("paired")) return;
        automaticSetupAttempted = true; beginPhilipsPairing();
    }
    private void prepareFirstIPhone() {
        JSONObject apple = state(false).optJSONObject("apple");
        JSONArray devices = apple == null ? null : apple.optJSONArray("devices");
        if (devices != null && devices.length() > 0) return;
        if (nativeAvailable && !startedAddress.isEmpty()) invoke(newCommand("applePairBegin"));
    }
    private void returnToSetup() {
        if (!enabled) return;
        try { startActivity(new Intent().setComponent(new android.content.ComponentName(HOST, HOST + ".MainActivity"))
            .putExtra("cleanframeAddon", "apple-remote").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)); }
        catch (RuntimeException unavailable) { /* Owner can return through the existing notification. */ }
    }

    private void invoke(JSONObject command) {
        String operation = command.optString("op");
        if (!operation.equals("stop") && !enabled) return;
        long epoch = activationEpoch.get();
        try {
            String result = RemoteNative.request(command.toString());
            if (operation.equals("status")) RequestPolicy.parseRunningResponse(result);
            else RequestPolicy.parseResponse(result);
            if (operation.equals("stop") || (enabled && epoch == activationEpoch.get())) {
                cached = result;
                if (operation.equals("status")) offerPairCode(state(false));
            }
        } catch (Exception | LinkageError failure) {
            if (operation.equals("status")) { startedAddress = ""; cached = "{\"running\":false}"; hostInput.disableGuard(); pairCodeWindow.clear(); refreshPairCodeNotification(); }
            serviceError = "Receiver request failed.";
        }
    }

    private void nativeStop() {
        if (hostInput != null) hostInput.close();
        pairCodeWindow.clear();
        refreshPairCodeNotification();
        if (nativeAvailable) invoke(newCommand("stop"));
        startedAddress = "";
        startedName = "";
    }

    private void refreshPairCodeNotification() {
        main.post(() -> { if (enabled) showNotification(); });
    }

    private Intent hostPairIntent() {
        return new Intent().setComponent(new android.content.ComponentName(HOST, HOST + ".RemotePairActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    private void offerPairCode(JSONObject state) {
        long now = SystemClock.elapsedRealtime();
        boolean before = pairCodeWindow.available(now);
        boolean launch = pairCodeWindow.observe(state, now);
        if (before != pairCodeWindow.available(now)) refreshPairCodeNotification();
        if (launch) main.postDelayed(() -> {
            if (!enabled || !pairCodeWindow.available(SystemClock.elapsedRealtime())) return;
            try { startActivity(hostPairIntent()); }
            catch (RuntimeException unavailable) {
                // A notification tap is the explicit, system-approved fallback for BAL restrictions.
                serviceError = "Open the CleanTV notification to enter the Philips code.";
            }
        }, automaticPairing ? 2500 : 0);
    }

    private void enforceCaller() {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return;
        PackageManager packages = getPackageManager();
        String[] names = packages.getPackagesForUid(uid);
        if (names == null || !Arrays.asList(names).contains(HOST)
            || packages.checkSignatures(HOST, getPackageName()) != PackageManager.SIGNATURE_MATCH)
            throw new SecurityException("Only the signed CleanTV host can control this add-on.");
    }

    private JSONObject state(boolean diagnostics) {
        try {
            JSONObject state = new JSONObject(cached);
            state.put("api", 1).put("package", getPackageName()).put("version", "0.1.2").put("enabled", isEnabled(this));
            state.put("codeEntryAvailable", pairCodeWindow.available(SystemClock.elapsedRealtime()));
            state.put("backend", "philips");
            state.put("automaticPairingAccess", PhilipsPairingAccessibility.connected()).put("automaticPairings", automaticPairings);
            if (hostInput != null) state.put("philips", hostInput.philipsState()).put("keyboard", hostInput.keyboardDiagnostics());
            if (!serviceError.isEmpty()) state.put("serviceError", serviceError);
            if (!diagnostics) return state;
            // Build a bounded allowlisted view; never return secrets, text fields, pairing PINs or packet contents.
            JSONObject safe = new JSONObject();
            for (String key : new String[]{"api", "package", "version", "enabled", "running", "serviceError", "codeEntryAvailable", "backend", "automaticPairingAccess", "automaticPairings"})
                if (state.has(key)) safe.put(key, state.get(key));
            for (String section : new String[]{"android", "apple", "keyboard", "philips"}) {
                JSONObject input = state.optJSONObject(section), output = new JSONObject();
                if (input != null) {
                    for (String key : new String[]{"state", "ready", "connected", "enabled", "selected", "paired", "certificatePinned", "reason", "pairingSecondsRemaining"}) if (input.has(key)) output.put(key, input.get(key));
                    if (input.has("error")) output.put("hasError", !input.optString("error").isEmpty());
                    if (section.equals("apple")) output.put("pairedDevices", input.optJSONArray("devices") == null ? 0 : input.getJSONArray("devices").length());
                }
                safe.put(section, output);
            }
            JSONArray events = state.optJSONArray("events"), output = new JSONArray();
            if (events != null) for (int i = Math.max(0, events.length() - 200); i < events.length(); i++) {
                JSONObject input = events.optJSONObject(i), event = new JSONObject();
                if (input == null) continue;
                for (String key : new String[]{"id", "time", "kind", "action"}) {
                    Object value = input.opt(key);
                    if (value instanceof Number || value instanceof Boolean) event.put(key, value);
                    else if (value instanceof String && ((String) value).matches("[A-Za-z0-9_. :+-]{1,100}")) event.put(key, value);
                }
                output.put(event);
            }
            return safe.put("events", output);
        } catch (Exception failure) { return newCommand("status"); }
    }

    /** Only the signed host receives the current pairing window and removable device handles. */
    private JSONObject setupState() {
        JSONObject safe = state(true);
        try {
            return SetupStatus.enrich(safe, state(false));
        } catch (Exception ignored) { }
        return safe;
    }

    private static JSONObject newCommand(String op) {
        JSONObject result = new JSONObject();
        try { result.put("op", op); } catch (Exception ignored) { }
        return result;
    }

    private String failure(String error) {
        try { return state(Binder.getCallingUid() != Process.myUid()).put("accepted", false).put("error", error).toString(); }
        catch (Exception ignored) { return "{\"accepted\":false}"; }
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        writer.println(state(true).toString());
    }

    @Override public void onDestroy() {
        if (current == this) current = null;
        enabled = false;
        activationEpoch.incrementAndGet();
        if (hostInput != null) hostInput.disableGuard();
        if (nativeAvailable) RemoteNative.clearHost(hostInput);
        connectivity.unregisterNetworkCallback(networkCallback);
        if (multicast != null && multicast.isHeld()) multicast.release();
        worker.execute(this::nativeStop);
        worker.shutdown();
        super.onDestroy();
    }
}
