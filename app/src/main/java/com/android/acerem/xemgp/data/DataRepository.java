package com.android.acerem.xemgp.data;

import android.content.Context;
import android.net.Uri;
import android.graphics.BitmapFactory;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
a
/** Local family storage a. Every encrypted file gets its own fingerprint namespace. */
public final class DataRepository {
    public interface Progress { void onProgress(int done, int total); }
    private static final String PREFS = "family-tree-local";
    private static final String CURRENT = "current-family";
    private static final long MAX_IMAGE_BYTES = 50L * 1024 * 1024;
    private DataRepository() {}

    public static File root(Context c) { File f = new File(c.getFilesDir(), "families"); if (!f.exists()) f.mkdirs(); return f; }
    public static String currentFingerprint(Context c) { return c.getSharedPreferences(PREFS, 0).getString(CURRENT, null); }
    public static void setCurrentFingerprint(Context c, String fp) { c.getSharedPreferences(PREFS, 0).edit().putString(CURRENT, fp).apply(); }
    public static File familyDir(Context c, String fp) { return new File(root(c), fp); }
    public static File dataFile(Context c) { String fp = currentFingerprint(c); return fp == null ? null : new File(familyDir(c, fp), "data.enc"); }
    public static File imageDir(Context c) { String fp = currentFingerprint(c); return fp == null ? null : new File(familyDir(c, fp), "images"); }
    public static File imageFile(Context c, String fp, String filename) { return new File(new File(familyDir(c, fp), "images"), filename); }

    public static void validateEnvelope(String text) throws Exception {
        JSONObject e = new JSONObject(text);
        if (e.optInt("v", -1) != 1 || !"AES-GCM".equals(e.optString("algorithm"))
                || e.optString("salt").isEmpty() || e.optString("iv").isEmpty() || e.optString("ciphertext").isEmpty()) {
            throw new IllegalArgumentException("invalid-envelope");
        }
        Base64.decode(e.getString("salt"), Base64.DEFAULT);
        Base64.decode(e.getString("iv"), Base64.DEFAULT);
        Base64.decode(e.getString("ciphertext"), Base64.DEFAULT);
    }

    public static String importData(Context c, Uri uri) throws Exception {
        byte[] bytes = readAll(c, uri);
        String text = new String(bytes, StandardCharsets.UTF_8);
        validateEnvelope(text);
        String fp = sha256(bytes);
        File dir = familyDir(c, fp);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("cannot-create-family");
        File temp = new File(dir, ".data-" + UUID.randomUUID());
        try { writeBytes(temp, bytes); moveReplace(temp, new File(dir, "data.enc")); }
        finally { if (temp.exists()) temp.delete(); }
        setCurrentFingerprint(c, fp);
        return fp;
    }

    public static String readCurrentEncrypted(Context c) throws IOException {
        File file = dataFile(c);
        if (file == null || !file.isFile()) throw new FileNotFoundException("data-unavailable");
        return new String(readFile(file), StandardCharsets.UTF_8);
    }

    public static byte[] readAll(Context c, Uri uri) throws IOException {
        try (InputStream in = c.getContentResolver().openInputStream(uri); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new IOException("file-unavailable");
            byte[] buffer = new byte[32 * 1024]; int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }
    private static byte[] readFile(File file) throws IOException { try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) { byte[] b = new byte[32 * 1024]; int n; while ((n=in.read(b))!=-1) out.write(b,0,n); return out.toByteArray(); } }
    private static void writeBytes(File f, byte[] bytes) throws IOException { try (FileOutputStream out = new FileOutputStream(f)) { out.write(bytes); out.getFD().sync(); } }
    private static String sha256(byte[] bytes) throws Exception { byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder result = new StringBuilder(); for (byte b : digest) result.append(String.format(Locale.US, "%02x", b)); return result.toString(); }
    private static void moveReplace(File source, File target) throws IOException { if (target.exists() && !target.delete()) throw new IOException("cannot-replace-file"); if (!source.renameTo(target)) { try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(target)) { byte[] b = new byte[32*1024]; int n; while ((n=in.read(b))!=-1) out.write(b,0,n); } if (!source.delete()) throw new IOException("cannot-clean-temp"); } }

    /** Extracts only root-level supported images. Existing images stay intact until commit. */
    public static int extractImages(Context c, Uri uri, String fp, Progress progress) throws Exception {
        File family = familyDir(c, fp); if (!family.exists() && !family.mkdirs()) throw new IOException("cannot-create-family");
        File temp = new File(family, ".images-" + UUID.randomUUID());
        if (!temp.mkdirs()) throw new IOException("cannot-create-temp");
        int total = 0, done = 0, accepted = 0;
        try {
            try (InputStream raw = c.getContentResolver().openInputStream(uri); ZipInputStream zip = new ZipInputStream(new BufferedInputStream(requireStream(raw)))) {
                ZipEntry entry; while ((entry = zip.getNextEntry()) != null) if (!entry.isDirectory() && isRootImage(entry.getName())) total++;
            }
            try (InputStream raw = c.getContentResolver().openInputStream(uri); ZipInputStream zip = new ZipInputStream(new BufferedInputStream(requireStream(raw)))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (entry.isDirectory() || !isRootImage(name)) continue;
                    File target = new File(temp, name); long size = 0;
                    try (FileOutputStream out = new FileOutputStream(target)) {
                        byte[] b = new byte[32 * 1024]; int n;
                        while ((n = zip.read(b)) != -1) { size += n; if (size > MAX_IMAGE_BYTES) throw new IOException("image-too-large"); out.write(b,0,n); }
                    }
                    BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(target.getAbsolutePath(), bounds);
                    if (bounds.outWidth > 0 && bounds.outHeight > 0) accepted++; else target.delete();
                    done++; if (progress != null) progress.onProgress(done, total);
                }
            }
            File live = new File(family, "images"); if (!live.exists() && !live.mkdirs()) throw new IOException("cannot-create-images");
            File[] files = temp.listFiles(); if (files != null) for (File image : files) moveReplace(image, new File(live, image.getName()));
            return accepted;
        } finally { deleteTree(temp); }
    }
    private static InputStream requireStream(InputStream input) throws IOException { if (input == null) throw new IOException("file-unavailable"); return input; }
    private static boolean isRootImage(String name) { if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..")) return false; String n=name.toLowerCase(Locale.US); return n.endsWith(".webp") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png"); }
    private static void deleteTree(File f) { if (f == null || !f.exists()) return; if (f.isDirectory()) { File[] files=f.listFiles(); if(files!=null) for(File x:files) deleteTree(x); } f.delete(); }

    public static FamilyData parse(String json, String fp) throws Exception {
        JSONObject root = new JSONObject(json); JSONObject auth=root.optJSONObject("auth"), family=root.optJSONObject("family"); JSONArray array=root.optJSONArray("members");
        if (family == null || array == null || array.length() == 0) throw new IllegalArgumentException("data-invalid");
        List<Member> members=new ArrayList<>(); Set<String> ids=new HashSet<>();
        for(int i=0;i<array.length();i++){ JSONObject o=array.optJSONObject(i); if(o==null||o.optString("id").isEmpty()||o.optString("fullName").trim().isEmpty()||!ids.add(o.optString("id"))) throw new IllegalArgumentException("data-invalid"); members.add(new Member(o)); }
        for(Member m:members){ List<String> refs=new ArrayList<>(); if(m.fatherId!=null)refs.add(m.fatherId); if(m.motherId!=null)refs.add(m.motherId); refs.addAll(m.spouseIds); refs.addAll(m.siblingIds); for(String id:refs) if(!ids.contains(id)||m.id.equals(id)) throw new IllegalArgumentException("data-invalid"); }
        String user=auth==null?"":auth.optString("username",""); if(user.isEmpty()) throw new IllegalArgumentException("data-invalid");
        int offset=family.has("generationOffset")&&!family.isNull("generationOffset")?Math.max(0,family.optInt("generationOffset",0)):0;
        return new FamilyData(user,family.optString("name","Gia phả gia đình"),family.optString("heroTitle","Gốc rễ của chúng ta"),family.optString("description",""),family.optString("rootPersonId",null),offset,members,fp);
    }
}
