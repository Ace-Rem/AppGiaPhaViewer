package com.android.acerem.xemgp.auth;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import com.android.acerem.xemgp.crypto.CryptoManager;
import com.android.acerem.xemgp.data.DataRepository;
import com.android.acerem.xemgp.data.FamilyData;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Locale;

/** Offline authentication and remember-login session. Never stores the password. */
public final class AuthManager {
    private static final String PREFS="secure-family-session", FP="fingerprint", USER="username", BLOB="wrapped-key", ALIAS="family-tree-session-key";
    private static FamilyData active;
    private AuthManager(){}
    public static FamilyData signIn(Context context,String username,String password,boolean remember) throws Exception {
        String payload=DataRepository.readCurrentEncrypted(context);
        SecretKey derived=CryptoManager.keyForEnvelope(payload,password);
        FamilyData data=parseAndValidate(payload,derived,context);
        if(!data.username.equalsIgnoreCase(username.trim())) throw new SecurityException("credentials-invalid");
        if(remember) saveSession(context,data.fingerprint,data.username,derived); else clearRemembered(context);
        active=data; return data;
    }
    public static FamilyData restore(Context context) throws Exception {
        SharedPreferences p=context.getSharedPreferences(PREFS,0); String fp=p.getString(FP,null),user=p.getString(USER,null),blob=p.getString(BLOB,null); String current=DataRepository.currentFingerprint(context);
        if(fp==null||user==null||blob==null||current==null||!fp.equals(current)) throw new SecurityException("session-invalid");
        SecretKey key=unwrap(blob); String payload=DataRepository.readCurrentEncrypted(context); FamilyData data=parseAndValidate(payload,key,context);
        if(!data.fingerprint.equals(fp)||!data.username.equalsIgnoreCase(user)) throw new SecurityException("session-invalid"); active=data; return data;
    }
    private static FamilyData parseAndValidate(String payload,SecretKey key,Context context) throws Exception { String json=CryptoManager.utf8(CryptoManager.decryptWithKey(payload,key)); return DataRepository.parse(json,DataRepository.currentFingerprint(context)); }
    private static void saveSession(Context c,String fp,String user,SecretKey key) throws Exception { SecretKey wrapping=getWrappingKey(); Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,wrapping); byte[] iv=cipher.getIV(),encrypted=cipher.doFinal(key.getEncoded()); byte[] packed=new byte[iv.length+encrypted.length]; System.arraycopy(iv,0,packed,0,iv.length); System.arraycopy(encrypted,0,packed,iv.length,encrypted.length); c.getSharedPreferences(PREFS,0).edit().putString(FP,fp).putString(USER,user.trim()).putString(BLOB,Base64.encodeToString(packed,Base64.NO_WRAP)).apply(); }
    private static SecretKey getWrappingKey() throws Exception { KeyStore ks=KeyStore.getInstance("AndroidKeyStore"); ks.load(null); if(!ks.containsAlias(ALIAS)){KeyGenerator kg=KeyGenerator.getInstance("AES","AndroidKeyStore"); KeyGenParameterSpec spec=new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build(); kg.init(spec); kg.generateKey();} return ((KeyStore.SecretKeyEntry)ks.getEntry(ALIAS,null)).getSecretKey(); }
    private static SecretKey unwrap(String encoded) throws Exception { byte[] packed=Base64.decode(encoded,Base64.DEFAULT); byte[] iv=new byte[12]; byte[] ciphertext=new byte[packed.length-12]; System.arraycopy(packed,0,iv,0,12); System.arraycopy(packed,12,ciphertext,0,ciphertext.length); Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE,getWrappingKey(),new GCMParameterSpec(128,iv)); return new javax.crypto.spec.SecretKeySpec(cipher.doFinal(ciphertext),"AES"); }
    public static boolean hasRememberedSession(Context c){ SharedPreferences p=c.getSharedPreferences(PREFS,0); return p.getString(FP,null)!=null&&p.getString(USER,null)!=null&&p.getString(BLOB,null)!=null; }
    public static FamilyData active(){ return active; }
    public static void clearActive(){ active=null; }
    public static void clearRemembered(Context c){c.getSharedPreferences(PREFS,0).edit().clear().apply();}
}


