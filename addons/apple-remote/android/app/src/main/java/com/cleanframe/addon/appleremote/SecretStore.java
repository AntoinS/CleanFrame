package com.cleanframe.addon.appleremote;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** App-private authenticated storage; losing the Keystore key never silently resets identity. */
final class SecretStore {
    private static final String ALIAS = "cleanframe.remote.secrets.v1";
    private static final int MAX_BYTES = 524288;
    private final AtomicFile file;
    private final String alias;

    SecretStore(Context context) {
        this(context, "remote-secrets.bin", ALIAS);
    }

    SecretStore(Context context, String filename, String alias) {
        if (!filename.matches("[a-z][a-z0-9-]{0,63}\\.bin")
                || !alias.matches("cleanframe\\.remote\\.[a-z0-9.]{1,64}"))
            throw new IllegalArgumentException("Invalid storage namespace.");
        this.alias = alias;
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), filename));
    }

    private SecretKey key(boolean mayCreate) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(alias)) return (SecretKey) store.getKey(alias, null);
        if (!mayCreate) throw new IllegalStateException("Stored receiver identity cannot be decrypted.");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build());
        return generator.generateKey();
    }

    synchronized JSONObject read() throws Exception {
        if (!exists()) return new JSONObject();
        if (file.getBaseFile().length() > MAX_BYTES + 64) throw new IllegalStateException("Receiver storage exceeds its limit.");
        byte[] stored = file.readFully();
        if (stored.length < 30 || stored[0] != 1) throw new IllegalStateException("Receiver storage format is invalid.");
        ByteBuffer buffer = ByteBuffer.wrap(stored);
        buffer.get(); byte[] iv = new byte[12]; buffer.get(iv);
        byte[] encrypted = new byte[buffer.remaining()]; buffer.get(encrypted);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, iv));
        cipher.updateAAD(alias.getBytes(StandardCharsets.UTF_8));
        return new JSONObject(new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8));
    }

    synchronized void write(String json) throws Exception {
        byte[] plain = json.getBytes(StandardCharsets.UTF_8);
        if (plain.length > MAX_BYTES) throw new IllegalArgumentException("Receiver storage exceeds its limit.");
        new JSONObject(json);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(!exists()));
        cipher.updateAAD(alias.getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(plain);
        byte[] stored = ByteBuffer.allocate(1 + cipher.getIV().length + encrypted.length)
            .put((byte) 1).put(cipher.getIV()).put(encrypted).array();
        FileOutputStream output = file.startWrite();
        try { output.write(stored); file.finishWrite(output); }
        catch (Exception failure) { file.failWrite(output); throw failure; }
        finally { java.util.Arrays.fill(plain, (byte) 0); }
    }

    private boolean exists() {
        return file.getBaseFile().exists() || new File(file.getBaseFile().getPath() + ".bak").exists();
    }
}
