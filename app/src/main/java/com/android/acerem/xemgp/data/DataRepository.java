package com.android.acerem.xemgp.data;

import android.content.Context;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Local family storage. Every encrypted file gets its own fingerprint namespace. */
public final class DataRepository {
    private static final String PREFS = "family-tree-local";
    private static final String CURRENT = "current-family";
    private DataRepository() {}

    public static File root(Context c) { File f = new File(c.getFilesDir(), "families"); if (!f.exists()) f.mkdirs(); return f; }
    public static String currentFingerprint(Context c) { return c.getSharedPreferences(PREFS, 0).getString(CURRENT, null); }
    public static void setCurrentFingerprint(Context c, String fp) { c.getSharedPreferences(PREFS, 0).edit().putString(CURRENT, fp).apply(); }
    public static File familyDir(Context c, String fp) { return new File(root(c), fp); }
    public static File dataFile(Context c) { String fp = currentFingerprint(c); return fp == null ? null : new File(familyDir(c, fp), "data.enc"); }
    public static void validateEnvelope(String text) throws Exception {
        JSONObject e = new JSONObject(text);
        if (e.optInt("v", -1) != 1 || !"AES-GCM".equals(e.optString("algorithm"))
                || !"PBKDF2-SHA-256".equals(e.optString("kdf")) || e.optInt("iterations", -1) != 210000
                || e.optString("salt").isEmpty() || e.optString("iv").isEmpty() || e.optString("ciphertext").isEmpty()) {
            throw new IllegalArgumentException("invalid-envelope");
        }
        byte[] salt = Base64.decode(e.getString("salt"), Base64.DEFAULT);
        byte[] iv = Base64.decode(e.getString("iv"), Base64.DEFAULT);
        byte[] ciphertext = Base64.decode(e.getString("ciphertext"), Base64.DEFAULT);
        if (salt.length == 0 || iv.length == 0 || ciphertext.length == 0) throw new IllegalArgumentException("invalid-envelope");
    }

    /** Commits encrypted data only; ImageRepository owns the separate local image copy. */
    public static String installDownloaded(Context c, File downloadedData) throws Exception {
        byte[] bytes = readFile(downloadedData);
        validateEnvelope(new String(bytes, StandardCharsets.UTF_8));
        String fp = sha256(bytes);
        File dir = familyDir(c, fp);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("cannot-create-family");
        File temp = new File(dir, ".data-" + UUID.randomUUID());
        try {
            writeBytes(temp, bytes);
            moveReplace(temp, new File(dir, "data.enc"));
            setCurrentFingerprint(c, fp);
            return fp;
        } finally { if (temp.exists()) temp.delete(); }
    }

    public static String readCurrentEncrypted(Context c) throws IOException {
        File file = dataFile(c);
        if (file == null || !file.isFile()) throw new FileNotFoundException("data-unavailable");
        return new String(readFile(file), StandardCharsets.UTF_8);
    }
    private static byte[] readFile(File file) throws IOException { try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) { byte[] b = new byte[32 * 1024]; int n; while ((n=in.read(b))!=-1) out.write(b,0,n); return out.toByteArray(); } }
    private static void writeBytes(File f, byte[] bytes) throws IOException { try (FileOutputStream out = new FileOutputStream(f)) { out.write(bytes); out.getFD().sync(); } }
    private static String sha256(byte[] bytes) throws Exception { byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder result = new StringBuilder(); for (byte b : digest) result.append(String.format(Locale.US, "%02x", b)); return result.toString(); }
    private static void moveReplace(File source, File target) throws IOException { if (target.exists() && !target.delete()) throw new IOException("cannot-replace-file"); if (!source.renameTo(target)) { try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(target)) { byte[] b = new byte[32*1024]; int n; while ((n=in.read(b))!=-1) out.write(b,0,n); } if (!source.delete()) throw new IOException("cannot-clean-temp"); } }

    public static FamilyData parse(String json, String fp) throws Exception {
        JSONObject root=new JSONObject(json);JSONObject auth=root.optJSONObject("auth"),family=root.optJSONObject("family");JSONArray array=root.optJSONArray("members");
        if(family==null||array==null||array.length()==0)throw new IllegalArgumentException("data-invalid");
        List<Member> members=new ArrayList<>();Set<String> ids=new HashSet<>();
        for(int i=0;i<array.length();i++){JSONObject o=array.optJSONObject(i);if(o==null||o.optString("id").isEmpty()||o.optString("fullName").trim().isEmpty()||!ids.add(o.optString("id")))throw new IllegalArgumentException("data-invalid");members.add(new Member(o));}
        for(Member m:members){List<String> refs=new ArrayList<>();if(m.fatherId!=null)refs.add(m.fatherId);if(m.motherId!=null)refs.add(m.motherId);refs.addAll(m.spouseIds);refs.addAll(m.siblingIds);for(String id:refs)if(!ids.contains(id)||m.id.equals(id))throw new IllegalArgumentException("data-invalid");}
        String user=auth==null?"":auth.optString("username","");if(user.isEmpty())throw new IllegalArgumentException("data-invalid");
        int offset=family.has("generationOffset")&&!family.isNull("generationOffset")?Math.max(0,family.optInt("generationOffset",0)):0;
        return new FamilyData(user,family.optString("name","Gia phả gia đình"),family.optString("heroTitle","Gốc rễ của chúng ta"),family.optString("description",""),family.optString("rootPersonId",null),offset,members,fp);
    }
}
