package com.cleanframe.philips;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.WebResourceResponse;
import androidx.media3.common.Player;
import androidx.media3.session.SessionToken;
import androidx.core.graphics.ColorUtils;
import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Main-thread media lifecycle; bridge reads are cached and never wait for the UI. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class SpotifyMedia {
    static final String PACKAGE = "com.spotify.tv.android";
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final MediaSessionManager sessions;
    private final ComponentName listener;
    private final Consumer<String> publish;
    private final ExecutorService images = Executors.newSingleThreadExecutor();
    private volatile String snapshot = "{\"permission\":false,\"status\":\"permission\"}";
    private volatile Artwork artwork;
    private ListenableFuture<androidx.media3.session.MediaController> connectionFuture;
    private ListenableFuture<SessionToken> tokenFuture;
    private androidx.media3.session.MediaController modernController;
    private MediaController controller;
    private final SpotifyWebApi webApi;
    private JSONObject webState = new JSONObject();
    private boolean observing, resumed, registered, closed;
    private String connection = "idle", controlError = "", imageKey = "";
    private int imageRevision;

    private static final class Artwork {
        final String path;
        final byte[] bytes;
        final String color;
        final boolean light;
        Artwork(String path, byte[] bytes, int color) {
            this.path = path; this.bytes = bytes;
            this.color = String.format(java.util.Locale.ROOT, "#%06x", color & 0xffffff);
            light = ColorUtils.calculateContrast(Color.BLACK, color) >= ColorUtils.calculateContrast(Color.WHITE, color);
        }
    }

    SpotifyMedia(Context context, Consumer<String> publish) {
        this.context = context.getApplicationContext();
        this.publish = publish;
        sessions = context.getSystemService(MediaSessionManager.class);
        listener = new ComponentName(context, SpotifyNotificationListener.class);
        SpotifyNotificationListener.changed = () -> handler.post(() -> {
            if (!closed && observing && resumed) sync();
        });
        webApi = new SpotifyWebApi(context, state -> { webState = state; update(false); });
        webApi.refreshLink();
        update();
    }

    String state() { return snapshot; }
    void signOut() { handler.post(() -> { if (!closed) webApi.signOut(); }); }
    void observe(boolean value) { handler.post(() -> { observing = value; sync(); }); }
    void resume(boolean value) { resumed = value; if (value) webApi.refreshLink(); sync(); }
    private boolean permitted() {
        return context.getSystemService(NotificationManager.class).isNotificationListenerAccessGranted(listener);
    }

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionChanges = this::select;
    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { update(); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { update(); }
        @Override public void onAudioInfoChanged(MediaController.PlaybackInfo info) { update(); }
        @Override public void onSessionDestroyed() {
            setController(null);
            refresh();
        }
    };

    private void sync() {
        if (closed) return;
        webApi.setActive(observing && resumed && permitted());
        if (!observing || !resumed || !permitted()) { disconnect(); update(); return; }
        if (!registered) {
            try {
                sessions.addOnActiveSessionsChangedListener(sessionChanges, listener, handler);
                registered = true;
            } catch (SecurityException denied) { controlError = "Media access is unavailable. Check notification access."; }
        }
        refresh();
        if (connectionFuture == null && tokenFuture == null && !"unavailable".equals(connection)) connect();
    }

    private void connect() {
        if (controller != null) {
            connection = "connecting";
            ListenableFuture<SessionToken> future = SessionToken.createSessionToken(context, controller.getSessionToken());
            tokenFuture = future;
            future.addListener(() -> {
                if (closed || tokenFuture != future) return;
                try { connectToken(future.get()); }
                catch (Exception failure) { connection = "unavailable"; update(); }
            }, context.getMainExecutor());
            return;
        }
        Intent intent = new Intent("androidx.media3.session.MediaLibraryService").setPackage(PACKAGE);
        List<ResolveInfo> services = context.getPackageManager().queryIntentServices(intent, 0);
        ComponentName component = null;
        for (ResolveInfo info : services) {
            if (info.serviceInfo != null && info.serviceInfo.enabled && info.serviceInfo.exported
                    && info.serviceInfo.applicationInfo.enabled) {
                component = new ComponentName(PACKAGE, info.serviceInfo.name);
                break;
            }
        }
        if (component == null) { connection = "unavailable"; return; }
        connectToken(new SessionToken(context, component));
    }

    private void connectToken(SessionToken token) {
        connection = "connecting";
        try {
            ListenableFuture<androidx.media3.session.MediaController> future =
                    new androidx.media3.session.MediaController.Builder(context, token)
                            .setListener(new androidx.media3.session.MediaController.Listener() {
                                @Override public void onError(androidx.media3.session.MediaController value, androidx.media3.session.SessionError error) {
                                    controlError = "Spotify: " + error.message;
                                    android.util.Log.w("CleanFrameSpotify", "Spotify session error " + error.code + ": " + error.message);
                                    update();
                                }
                                @Override public void onDisconnected(androidx.media3.session.MediaController value) {
                                    if (modernController == value) { modernController = null; connection = "unavailable"; refresh(); }
                                }
                            }).buildAsync();
            connectionFuture = future;
            future.addListener(() -> {
                if (closed || connectionFuture != future) return;
                try {
                    modernController = future.get();
                    connection = "connected";
                    modernController.addListener(new Player.Listener() {
                        @Override public void onEvents(Player player, Player.Events events) { refresh(); }
                    });
                    android.util.Log.i("CleanFrameSpotify", "Media3 connected, commands=" + modernController.getAvailableCommands().size());
                } catch (Exception failure) {
                    connection = "unavailable";
                    android.util.Log.w("CleanFrameSpotify", "Media3 connection failed", failure);
                }
                refresh();
            }, context.getMainExecutor());
        } catch (RuntimeException failure) { connection = "unavailable"; }
    }

    private static int rank(MediaController candidate) {
        PlaybackState state = candidate.getPlaybackState();
        if (state == null) return 0;
        if (state.getState() == PlaybackState.STATE_PLAYING) return 3;
        if (state.getState() == PlaybackState.STATE_PAUSED) return 2;
        return 1;
    }

    private void refresh() {
        if (!observing || !resumed) { update(); return; }
        try { select(sessions.getActiveSessions(listener)); }
        catch (SecurityException denied) { setController(null); update(); }
    }

    private void select(List<MediaController> active) {
        if (!observing || !resumed || !permitted()) { setController(null); update(); return; }
        List<MediaController> candidates = active == null ? new ArrayList<>() : new ArrayList<>(active);
        MediaController best = null;
        for (MediaController candidate : candidates) {
            if (PACKAGE.equals(candidate.getPackageName()) && (best == null || rank(candidate) > rank(best))) best = candidate;
        }
        setController(best);
        update();
    }

    private void setController(MediaController next) {
        if (controller != null && next != null && controller.getSessionToken().equals(next.getSessionToken())) return;
        if (controller != null) controller.unregisterCallback(callback);
        controller = next;
        controlError = "";
        imageKey = "";
        artwork = null;
        imageRevision++;
        if (controller != null) controller.registerCallback(callback, handler);
    }

    void control(String command) {
        handler.post(() -> {
            if (!closed && observing && resumed && permitted()) webApi.control(command);
        });
    }

    private static String text(MediaMetadata metadata, String key) {
        CharSequence value = metadata == null ? null : metadata.getText(key);
        return value == null ? "" : value.toString();
    }

    private void update() { update(true); }

    private void update(boolean refreshArtwork) {
        if (closed) return;
        try {
            boolean permission = permitted();
            MediaMetadata metadata = controller == null ? null : controller.getMetadata();
            PlaybackState playback = controller == null ? null : controller.getPlaybackState();
            int code = playback == null ? PlaybackState.STATE_NONE : playback.getState();
            boolean playing = code == PlaybackState.STATE_PLAYING;
            String title = text(metadata, MediaMetadata.METADATA_KEY_TITLE);
            if (title.isEmpty()) title = text(metadata, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            String artist = text(metadata, MediaMetadata.METADATA_KEY_ARTIST);
            if (artist.isEmpty()) artist = text(metadata, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            long duration = metadata == null ? 0 : Math.max(0, metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
            long position = playback == null ? 0 : Math.max(0, playback.getPosition());
            if (playing && playback.getLastPositionUpdateTime() > 0) {
                position += (long) ((SystemClock.elapsedRealtime() - playback.getLastPositionUpdateTime()) * playback.getPlaybackSpeed());
            }
            position = Math.max(0, duration > 0 ? Math.min(position, duration) : position);
            long actions = playback == null ? 0 : playback.getActions();
            String status = !permission ? "permission" : !title.isEmpty() && (playing || code == PlaybackState.STATE_PAUSED)
                    ? playing ? "playing" : "paused" : "unavailable".equals(connection) ? "unavailable" : "idle";
            if (refreshArtwork) prepareArtwork(metadata, title, artist);
            JSONObject result = new JSONObject();
            result.put("permission", permission).put("status", status).put("connection", connection)
                    .put("title", title).put("artist", artist).put("duration", duration).put("position", position)
                    .put("speed", playback == null ? 0 : playback.getPlaybackSpeed()).put("errorMessage", controlError)
                    .put("art", artwork == null ? "" : "/media/" + artwork.path)
                    .put("artColor", artwork == null ? "" : artwork.color).put("artLight", artwork != null && artwork.light)
                    .put("actions", new JSONObject().put("previous", (actions & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0)
                            .put("next", (actions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0)
                            .put("toggle", (actions & (PlaybackState.ACTION_PLAY_PAUSE | (playing ? PlaybackState.ACTION_PAUSE : PlaybackState.ACTION_PLAY))) != 0));
            String webMessage = webState.optString("webMessage");
            result.put("webApi", webState.optString("webApi", "unlinked"))
                    .put("webLinked", webState.optBoolean("webLinked"))
                    .put("webMessage", webMessage).put("deviceName", webState.optString("deviceName"));
            if (!webMessage.isEmpty()) result.put("errorMessage", webMessage);
            if (!webState.optBoolean("webLinked") || "reauth".equals(webState.optString("webApi")))
                result.put("actions", new JSONObject().put("previous", false).put("toggle", false).put("next", false));
            snapshot = result.toString();
            publish.accept(snapshot);
        } catch (RuntimeException | org.json.JSONException failure) {
            snapshot = "{\"status\":\"unavailable\",\"errorMessage\":\"Spotify media information is unavailable.\"}";
            publish.accept(snapshot);
        }
    }

    private void prepareArtwork(MediaMetadata metadata, String title, String artist) {
        Bitmap bitmap = metadata == null ? null : metadata.getDescription().getIconBitmap();
        String key = text(metadata, MediaMetadata.METADATA_KEY_MEDIA_ID) + "\n" + title + "\n" + artist + "\n" + (bitmap != null);
        if (imageKey.equals(key)) return;
        imageKey = key;
        artwork = null;
        int revision = ++imageRevision;
        if (bitmap == null) return;
        images.execute(() -> {
            byte[] bytes = null;
            Bitmap scaled = null;
            int average = Color.rgb(39, 39, 39);
            try {
                float ratio = Math.min(1f, 256f / Math.max(bitmap.getWidth(), bitmap.getHeight()));
                scaled = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * ratio)), Math.max(1, Math.round(bitmap.getHeight() * ratio)), true);
                // Bounded artwork work runs once per track, off the UI/animation thread.
                Bitmap sample = Bitmap.createScaledBitmap(scaled, 32, 32, true);
                int[] pixels = new int[1024];
                sample.getPixels(pixels, 0, 32, 0, 0, 32, 32);
                long red = 0, green = 0, blue = 0, weight = 0;
                for (int pixel : pixels) {
                    int alpha = Color.alpha(pixel);
                    red += Color.red(pixel) * alpha; green += Color.green(pixel) * alpha;
                    blue += Color.blue(pixel) * alpha; weight += alpha;
                }
                if (weight > 0) average = Color.rgb((int) (red / weight), (int) (green / weight), (int) (blue / weight));
                if (sample != scaled && sample != bitmap) sample.recycle();
                if (scaled != null) {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    if (scaled.compress(Bitmap.CompressFormat.PNG, 100, output)) bytes = output.toByteArray();
                }
            } catch (RuntimeException failure) { /* Missing artwork uses the bundled Spotify mark. */ }
            finally { if (scaled != null && scaled != bitmap) scaled.recycle(); }
            byte[] ready = bytes;
            int color = average;
            handler.post(() -> {
                if (!closed && revision == imageRevision && ready != null) {
                    artwork = new Artwork("spotify-" + revision + ".png", ready, color);
                    update(false);
                }
            });
        });
    }

    WebResourceResponse serveArtwork(String path) {
        Artwork current = artwork;
        if (current == null || !current.path.equals(path)) return null;
        return new WebResourceResponse("image/png", null, 200, "OK",
                java.util.Collections.singletonMap("Cache-Control", "no-store"), new ByteArrayInputStream(current.bytes));
    }

    private void disconnect() {
        if (registered) sessions.removeOnActiveSessionsChangedListener(sessionChanges);
        registered = false;
        modernController = null;
        if (connectionFuture != null) androidx.media3.session.MediaController.releaseFuture(connectionFuture);
        connectionFuture = null;
        if (tokenFuture != null) tokenFuture.cancel(false);
        tokenFuture = null;
        connection = "idle";
        setController(null);
    }

    void close() {
        closed = true;
        SpotifyNotificationListener.changed = null;
        disconnect();
        images.shutdownNow();
        webApi.close();
    }
}
