package com.android.acerem.xemgp.crypto;

import android.util.Base64;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.spec.KeySpec;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Exact counterpart of family-tree-viewer/crypto.js. */
public final class CryptoManager {
    public static final int DEFAULT_ITERATIONS = 210_000;
    private CryptoManager() {}
    public static JSONObject parseEnvelope(String payload) throws Exception {
        JSONObject e = new JSONObject(payload);
        if (e.optInt("v", -1) != 1 || !"AES-GCM".equals(e.optString("algorithm"))
                || !"PBKDF2-SHA-256".equals(e.optString("kdf")) || e.optInt("iterations", -1) != DEFAULT_ITERATIONS
                || e.optString("salt").isEmpty() || e.optString("iv").isEmpty() || e.optString("ciphertext").isEmpty()) throw new IllegalArgumentException("invalid-envelope");
        return e;
    }
    public static SecretKey deriveKey(String password, byte[] salt, int iterations) throws GeneralSecurityException {
        int rounds = iterations > 0 ? iterations : DEFAULT_ITERATIONS;
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, rounds, 256);
        byte[] raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        return new SecretKeySpec(raw, "AES");
    }
    public static SecretKey keyForEnvelope(String payload, String password) throws Exception { JSONObject e=parseEnvelope(payload); return deriveKey(password, Base64.decode(e.getString("salt"), Base64.DEFAULT), DEFAULT_ITERATIONS); }
    public static byte[] decrypt(String payload, String password) throws Exception { return decryptWithKey(payload, keyForEnvelope(payload,password)); }
    public static byte[] decryptWithKey(String payload, SecretKey key) throws Exception { JSONObject e=parseEnvelope(payload); return decryptBytes(Base64.decode(e.getString("ciphertext"),Base64.DEFAULT),Base64.decode(e.getString("iv"),Base64.DEFAULT),key); }
    private static byte[] decryptBytes(byte[] ciphertext, byte[] iv, SecretKey key) throws GeneralSecurityException { Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,iv)); return c.doFinal(ciphertext); }
    public static String utf8(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }
}
