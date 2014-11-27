package cc.bran.bnotify;

import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.util.Pair;

import com.google.android.gms.gcm.GoogleCloudMessaging;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public class GcmIntentService extends IntentService {

    private static final String LOG_TAG = "GcmIntentService";
    private static final String PROPERTY_PASSWORD = "password";
    private static final String PAYLOAD_KEY = "payload";
    private static final int AES_KEY_SIZE = 16; // TODO(bran): find a better source for this
    private static final int AES_BLOCK_SIZE = 16; // TODO(bran): find a better source for this
    private static final int PBKDF2_ITERATION_COUNT = 4096;
    private static final String CACHED_KEY_FILENAME = "cache.key";
    private static final String KEY_ALGORITHM = "PBKDF2WithHmacSHA1";

    private final AtomicInteger nextId;

    public GcmIntentService() {
        super("GcmIntentService");

        this.nextId = new AtomicInteger(0);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        try {
            GoogleCloudMessaging gcm = GoogleCloudMessaging.getInstance(this);
            Bundle extras = intent.getExtras();
            String messageType = gcm.getMessageType(intent);

            if (GoogleCloudMessaging.MESSAGE_TYPE_MESSAGE.equals(messageType) && !extras.isEmpty()) {
                String base64Payload = extras.getString(PAYLOAD_KEY);

                // Base64-decode to encrypted payload bytes.
                byte[] encryptedPayload = Base64.decode(base64Payload, Base64.DEFAULT);

                // Read salt and IV from encrypted payload.
                byte[] salt = Arrays.copyOf(encryptedPayload, AES_BLOCK_SIZE);
                IvParameterSpec iv = new IvParameterSpec(encryptedPayload, AES_BLOCK_SIZE, AES_BLOCK_SIZE);

                // Derive the key (or use the cached key).
                SecretKey key = getKey(salt);

                // Decrypt the message.
                Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                cipher.init(Cipher.DECRYPT_MODE, key, iv);
                byte[] payload = cipher.doFinal(encryptedPayload, 2*AES_BLOCK_SIZE, encryptedPayload.length - 2*AES_BLOCK_SIZE);

                // Parse the payload.
                JSONObject message = new JSONObject(new String(payload, "UTF-8"));
                String title = message.getString("title");
                String text = message.getString("text");

                sendNotification(title, text);
            }
        } catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException | NoSuchPaddingException | InvalidKeyException | JSONException | BadPaddingException | InvalidAlgorithmParameterException | IllegalBlockSizeException exception) {
            Log.e(LOG_TAG, "Error showing notification", exception);
        } finally {
            GcmBroadcastReceiver.completeWakefulIntent(intent);
        }
    }

    private void sendNotification(String title, String text) {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, new Intent(this, SettingsActivity.class), 0);

        int notificationId = nextId.getAndIncrement();
        Notification notification = new Notification.Builder(this)
                .setSmallIcon(R.drawable.logo_white)
                .setContentTitle(title)
                .setStyle(new Notification.BigTextStyle()
                        .bigText(text))
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setVibrate(new long[]{0, 300, 200, 300})
                .build();

        notificationManager.notify(notificationId, notification);
    }

    private SecretKey getKey(byte[] salt) throws NoSuchAlgorithmException, InvalidKeySpecException {
        // Try to use cached key first.
        Pair<byte[], SecretKey> cachedSaltAndKey = getCachedSaltAndKey();
        if (cachedSaltAndKey != null && Arrays.equals(salt, cachedSaltAndKey.first)) {
            return cachedSaltAndKey.second;
        }

        // Derive key, store it in the cache, and return it.
        Log.i(LOG_TAG, "Cached key missing or salt mismatch, deriving key");
        String password = getPassword();
        PBEKeySpec keySpec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATION_COUNT, 8 * AES_KEY_SIZE);
        SecretKeyFactory secretKeyFactory = SecretKeyFactory.getInstance(KEY_ALGORITHM);
        SecretKey key = secretKeyFactory.generateSecret(keySpec);
        storeCachedSaltAndKey(salt, key);
        return key;
    }

    private Pair<byte[], SecretKey> getCachedSaltAndKey() {
        File cachedKeyFile = new File(getCacheDir(), CACHED_KEY_FILENAME);
        try (FileInputStream cachedKeyStream = new FileInputStream(cachedKeyFile)) {
            byte[] salt = new byte[AES_BLOCK_SIZE];
            if (cachedKeyStream.read(salt) != AES_BLOCK_SIZE) {
                return null;
            }

            byte[] keyMaterial = new byte[AES_KEY_SIZE];
            if (cachedKeyStream.read(keyMaterial) != AES_KEY_SIZE) {
                return null;
            }

            SecretKey key = new SecretKeySpec(keyMaterial, KEY_ALGORITHM);
            return new Pair<>(salt, key);
        } catch (IOException exception) {
            Log.w(LOG_TAG, "Error reading cached key", exception);
            return null;
        }
    }

    private boolean storeCachedSaltAndKey(byte[] salt, SecretKey key) {
        assert(salt.length == AES_BLOCK_SIZE);

        File cachedKeyFile = new File(getCacheDir(), CACHED_KEY_FILENAME);
        try (FileOutputStream cachedKeyStream = new FileOutputStream(cachedKeyFile)) {
            cachedKeyStream.write(salt);
            byte[] keyMaterial = key.getEncoded();
            cachedKeyStream.write(keyMaterial);
            return true;
        } catch (IOException exception) {
            Log.w(LOG_TAG, "Error storing cached key", exception);
            return false;
        }
    }

    private String getPassword() {
        SharedPreferences prefs = getGCMPreferences();
        return prefs.getString(PROPERTY_PASSWORD, "");
    }

    private SharedPreferences getGCMPreferences() {
        return getSharedPreferences(SettingsActivity.class.getSimpleName(), Context.MODE_PRIVATE);
    }
}
