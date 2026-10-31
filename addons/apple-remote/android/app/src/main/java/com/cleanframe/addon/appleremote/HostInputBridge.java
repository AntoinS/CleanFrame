package com.cleanframe.addon.appleremote;

import android.content.ComponentName;
import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import android.media.AudioManager;
import android.view.KeyEvent;
import android.provider.Settings;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import org.json.JSONObject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** JNI-only dispatcher. Never returns field contents to Binder, dumpsys or logs. */
final class HostInputBridge implements AutoCloseable {
    private final Context context;
    private final AudioManager audio;
    private final InputActivation activation = new InputActivation();
    private final RemoteInputMethod.Observer observer = snapshot -> { /* Rust reads the immutable snapshot; no JNI reentry. */ };
    private volatile PhilipsRemote philips;
    private volatile String storageError = "";

    HostInputBridge(Context context) {
        this.context = context.getApplicationContext();
        audio = this.context.getSystemService(AudioManager.class);
        try { philips = new PhilipsRemote(this.context); }
        catch (Exception unavailable) { storageError = "storage_unavailable"; }
    }

    /** Service worker only. Repeated enable is a no-op, preserving any open pairing window. */
    synchronized void enable() {
        if (activation.isEnabled()) return;
        long ticket = activation.ticket();
        try {
            if (philips == null || philips.state().optString("error").equals("closed")) philips = new PhilipsRemote(context);
            philips.setEnabled(true);
            if (!philips.state().optBoolean("enabled")) {
                philips = new PhilipsRemote(context); philips.setEnabled(true);
            }
            if (!activation.enable(ticket)) { philips.close(); return; }
            storageError = "";
            RemoteInputMethod.attachObserver(observer);
            if (!activation.isEnabled()) RemoteInputMethod.detachObserver(observer);
        } catch (Exception unavailable) { activation.disable(); storageError = "storage_unavailable"; }
    }

    /** Fast first step of stop, callable before waiting for the service/native worker. */
    void disableGuard() {
        activation.disable();
        PhilipsRemote current = philips;
        if (current != null) current.cancel();
        RemoteInputMethod.detachObserver(observer);
    }

    @Override public void close() {
        disableGuard();
        PhilipsRemote old = philips;
        if (old != null) old.close();
    }

    JSONObject philipsState() {
        PhilipsRemote value = philips;
        if (value == null) return json("{\"backend\":\"philips\",\"enabled\":false,\"paired\":false,\"state\":\"unpaired\",\"error\":\"storage_unavailable\"}");
        JSONObject result = value.state();
        try { result.put("enabled", activation.isEnabled()); if (!storageError.isEmpty()) result.put("error", storageError); }
        catch (Exception ignored) { }
        return result;
    }

    JSONObject beginPairing() {
        PhilipsRemote value = philips;
        return activation.isEnabled() && value != null ? value.beginPairing(PhilipsPairingAccessibility.connected()) : error("disabled");
    }
    void clearCapture() { PhilipsRemote value = philips; if (value != null) value.clearCapture(); }
    boolean awaitingCapture() { PhilipsRemote value = philips; return activation.isEnabled() && value != null && value.awaitingCapture(); }
    PhilipsPairCapture.Code capture(String packageName, CharSequence text) {
        PhilipsRemote value = philips;
        return activation.isEnabled() && value != null ? value.capture(packageName, text) : null;
    }
    JSONObject finishCaptured(PhilipsPairCapture.Code code) {
        PhilipsRemote value = philips;
        return activation.isEnabled() && value != null ? value.finishCaptured(code) : error("disabled");
    }
    JSONObject finishPairing(String pin) {
        PhilipsRemote value = philips;
        return activation.isEnabled() && value != null ? value.finishPairing(pin) : error("disabled");
    }

    /** This path is exclusively called by the Rust blocking actor, never from Android's main thread. */
    String request(String raw) {
        if (Looper.myLooper() == Looper.getMainLooper()) return error("wrong_thread").toString();
        try {
            JSONObject request = HostRequestPolicy.parse(raw);
            String op = request.getString("op");
            if (op.equals("state")) return nativeState().toString();
            long ticket = activation.ticket();
            if (!activation.allows(ticket)) return error("disabled").toString();
            if (op.equals("key")) {
                if (request.getString("action").equals("play_pause")) return mediaPlayPause(ticket).toString();
                PhilipsRemote value = philips;
                if (value == null) return error("backend_unavailable").toString();
                JSONObject result = value.send(HostRequestPolicy.key(request.getString("action")));
                String failure = result.optString("error");
                if (!activation.allows(ticket)) return error("disabled").toString();
                if (!failure.isEmpty()) return error(failure.equals("command-busy") || failure.equals("command-rate-limited") ? "busy" :
                    failure.equals("timeout-or-cancelled") ? "timeout" : "backend_unavailable").toString();
                return new JSONObject().put("ok", true).put("error", "").toString();
            }
            return edit(request, ticket).toString();
        } catch (Exception invalid) { return error("unsupported_action".equals(invalid.getMessage()) ? "unsupported_action" : "invalid_request").toString(); }
    }

    private JSONObject mediaPlayPause(long ticket) {
        if (!activation.allows(ticket)) return error("disabled");
        if (audio == null) return error("backend_unavailable");
        try {
            long downTime = SystemClock.uptimeMillis();
            audio.dispatchMediaKeyEvent(new KeyEvent(downTime, downTime,
                KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0));
            // Complete the release even if stop occurs after the already-submitted down.
            audio.dispatchMediaKeyEvent(new KeyEvent(downTime, SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0));
            if (!activation.allows(ticket)) return error("disabled");
            // Android reports submission, not whether the active player changed playback.
            return new JSONObject().put("ok", true).put("error", "");
        } catch (Exception unavailable) { return error("backend_unavailable"); }
    }

    private JSONObject nativeState() throws Exception {
        long ticket = activation.ticket();
        boolean active = activation.allows(ticket);
        JSONObject state = philipsState();
        // Stateless OEM transport: paired/idle is usable without making a background HTTP request.
        boolean connected = active && state.optBoolean("paired");
        JSONObject result = new JSONObject().put("enabled", active).put("connected", connected)
            .put("keyboard", active ? keyboard(RemoteInputMethod.getSnapshot()) : JSONObject.NULL);
        if (!activation.allows(ticket)) result.put("enabled", false).put("connected", false).put("keyboard", JSONObject.NULL);
        return result;
    }

    private JSONObject edit(JSONObject request, long ticket) throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<JSONObject> response = new AtomicReference<>();
        RemoteInputMethod.applyEdit(request.getString("generation"), request.getLong("revision"), request.getString("text"),
            request.getInt("selectionStart"), request.getInt("selectionEnd"), request.getBoolean("submit"),
            () -> activation.allows(ticket) && !cancelled.get(), (result, latest) -> {
                try {
                    String code;
                    switch (result) {
                        case APPLIED: code = ""; break;
                        case STALE: code = "stale"; break;
                        case UNAVAILABLE: code = "field_unavailable"; break;
                        case INVALID_TEXT: code = "invalid_request"; break;
                        case UNSUPPORTED_ACTION: code = "unsupported_action"; break;
                        default: code = "edit_failed";
                    }
                    if (!activation.allows(ticket)) code = "disabled";
                    response.set(new JSONObject().put("ok", code.isEmpty()).put("error", code)
                        .put("keyboard", activation.allows(ticket) ? keyboard(latest) : JSONObject.NULL));
                } catch (Exception invalid) { response.set(error("edit_failed")); }
                finally { done.countDown(); }
            });
        try {
            if (!done.await(2500, TimeUnit.MILLISECONDS)) { cancelled.set(true); return error("timeout"); }
            JSONObject result = response.get();
            return result == null ? error("edit_failed") : result;
        } catch (InterruptedException interrupted) {
            cancelled.set(true); Thread.currentThread().interrupt(); return error("timeout");
        } finally { cancelled.set(true); }
    }

    /** No snapshot text, generation, selections or editor contents leave the local native boundary. */
    JSONObject keyboardDiagnostics() {
        JSONObject result = new JSONObject();
        try {
            String selectedId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            ComponentName selected = selectedId == null ? null : ComponentName.unflattenFromString(selectedId);
            ComponentName own = new ComponentName(context, RemoteInputMethod.class);
            boolean selectedHere = own.equals(selected), configured = false;
            InputMethodManager manager = context.getSystemService(InputMethodManager.class);
            if (manager != null) for (InputMethodInfo info : manager.getEnabledInputMethodList())
                if (own.equals(new ComponentName(info.getPackageName(), info.getServiceName()))) configured = true;
            RemoteInputPolicy.Snapshot snapshot = RemoteInputMethod.getSnapshot();
            boolean supportedPosition = snapshot.supportsRemotePosition();
            result.put("enabled", configured).put("selected", selectedHere)
                .put("ready", activation.isEnabled() && selectedHere && snapshot.ready && supportedPosition)
                .put("reason", snapshot.ready && !supportedPosition ? "unsupported_selection" : snapshot.reason);
        } catch (Exception unavailable) {
            try { result.put("ready", false).put("reason", "unavailable"); } catch (Exception ignored) { }
        }
        return result;
    }
    private static Object keyboard(RemoteInputPolicy.Snapshot snapshot) throws Exception {
        if (snapshot == null || !snapshot.ready) return JSONObject.NULL;
        return new JSONObject().put("generation", snapshot.generation).put("revision", snapshot.revision)
            .put("text", snapshot.text).put("selectionStart", snapshot.selectionStart).put("selectionEnd", snapshot.selectionEnd)
            .put("editorAction", snapshot.editorAction).put("ready", snapshot.ready).put("reason", snapshot.reason);
    }
    private static JSONObject error(String code) {
        try { return new JSONObject().put("ok", false).put("error", code); }
        catch (Exception impossible) { return new JSONObject(); }
    }
    private static JSONObject json(String value) {
        try { return new JSONObject(value); } catch (Exception impossible) { return new JSONObject(); }
    }
}
