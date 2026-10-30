package com.cleanframe.philips;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Only the native network worker can access credentials. Never exposed to the WebView. */
final class SpotifyCredentials {
    private static final String ALIAS = "cleanframe.spotify.oauth";
    private final AtomicFile encrypted;
    private final File incoming;

    SpotifyCredentials(Context context) {
        encrypted = new AtomicFile(new File(context.getNoBackupFilesDir(), "spotify-oauth.bin"));
        incoming = new File(context.getFilesDir(), "spotify-oauth-import.json");
    }

    JSONObject load() throws Exception {
        // Personal installer provisions through ADB run-as, never an exported activity or JS bridge.
        if (incoming.isFile()) {
            if (incoming.length() > 16384) throw new IllegalArgumentException("Invalid OAuth import");
            JSONObject data = new JSONObject(new String(Files.readAllBytes(incoming.toPath()), StandardCharsets.UTF_8));
            validate(data);
            save(data);
            if (!incoming.delete()) throw new IllegalStateException("OAuth import cleanup failed");
        }
        if (!encrypted.getBaseFile().isFile()) return null;
        byte[] blob = encrypted.readFully();
        if (blob.length < 29) throw new IllegalArgumentException("Invalid encrypted credentials");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, blob, 0, 12));
        JSONObject data = new JSONObject(new String(cipher.doFinal(blob, 12, blob.length - 12), StandardCharsets.UTF_8));
        validate(data);
        return data;
    }

    void save(JSONObject data) throws Exception {
        validate(data);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        FileOutputStream stream = null;
        try {
            stream = encrypted.startWrite();
            stream.write(cipher.getIV());
            stream.write(cipher.doFinal(data.toString().getBytes(StandardCharsets.UTF_8)));
            encrypted.finishWrite(stream);
        } catch (Exception failure) {
            if (stream != null) encrypted.failWrite(stream);
            throw failure;
        }
    }

    void clear() throws Exception {
        // Remove provisioning files as well, so an old import cannot reconnect the account.
        if (incoming.exists() && !incoming.delete()) throw new IllegalStateException("OAuth import cleanup failed");
        File temporary = new File(incoming.getParentFile(), "spotify-oauth-import.tmp");
        if (temporary.exists() && !temporary.delete()) throw new IllegalStateException("OAuth import cleanup failed");
        encrypted.delete();
        if (encrypted.getBaseFile().exists()) throw new IllegalStateException("OAuth cleanup failed");
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        store.deleteEntry(ALIAS);
    }

    private static void validate(JSONObject data) {
        String refresh = data.optString("refresh_token");
        if (!data.optString("client_id").matches("[a-fA-F0-9]{32}") || refresh.isEmpty() || refresh.length() > 8192)
            throw new IllegalArgumentException("Invalid OAuth credentials");
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
    }
}
