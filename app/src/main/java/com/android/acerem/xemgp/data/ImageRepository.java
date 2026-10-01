package com.android.acerem.xemgp.data;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.Log;


import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.json.JSONObject;

/**
 * The only image source used by the UI. The UI reads app-internal files through
 * this class; network access is limited to the explicit online sync operation.
 */
public final class ImageRepository {
    public interface Callback { void onImageLoaded(Bitmap bitmap); }
    public interface Progress { void onMessage(String message); }
    public interface UpdateListener { void onImagesUpdated(); }

    public static final class SyncResult {
        public final int total;
        public final int downloaded;
        public final int skipped;
        public final int failed;
        public final boolean sourceAvailable;

        private SyncResult(int total, int downloaded, int skipped, int failed) {
            this(total, downloaded, skipped, failed, downloaded > 0 || skipped > 0);
        }

        private SyncResult(int total, int downloaded, int skipped, int failed,
                           boolean sourceAvailable) {
            this.total = total;
            this.downloaded = downloaded;
            this.skipped = skipped;
            this.failed = failed;
            this.sourceAvailable = sourceAvailable;
        }
    }

    private static final String TAG = "ImageRepository";
    private static final String DIRECTORY = "family_tree_images";
    private static final String MANIFEST = ".manifest.json";
    private static final String FAMILY_INDEX = ".family-index.json";
    private static final int MAX_CONCURRENT_DOWNLOADS = 4;
    private static final int MAX_ATTEMPTS = 3;
    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final long MAX_MANIFEST_BYTES = 64 * 1024;
    private static final long MAX_IMAGES_ARCHIVE_BYTES = 500L * 1024 * 1024;
    private static final long MAX_ARCHIVE_ENTRY_BYTES = 12L * 1024 * 1024;
    private static final long MAX_IMAGE_BYTES = 12L * 1024 * 1024;
    private static final long MAX_HTML_BYTES = 2L * 1024 * 1024;
    private static final Pattern DRIVE_FILE_PATH = Pattern.compile("/file/d/([^/]+)");
    private static final Pattern CONFIRM_TOKEN = Pattern.compile("(?i)[?&]confirm=([^&\"'<>]+)");
    private static final Object SYNC_LOCK = new Object();
    private static volatile ImageRepository instance;

    private final Context context;
    private final File imageDir;
    private final File manifestFile;
    private final File familyIndexFile;
    private final ExecutorService executor = Executors.newFixedThreadPool(MAX_CONCURRENT_DOWNLOADS);
    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean backgroundSyncRunning = new AtomicBoolean();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, List<Callback>> pending = new HashMap<>();
    private final Set<UpdateListener> updateListeners = new CopyOnWriteArraySet<>();
    private final LruCache<String, Bitmap> memory;
    private volatile Map<String, String> familyIndex;

    private ImageRepository(Context context) {
        this.context = context.getApplicationContext();
        this.imageDir = new File(this.context.getFilesDir(), DIRECTORY);
        this.manifestFile = new File(imageDir, MANIFEST);
        this.familyIndexFile = new File(imageDir, FAMILY_INDEX);
        this.familyIndex = readFamilyIndex();
        int memoryKb = Math.max(4 * 1024, (int) (Runtime.getRuntime().maxMemory() / 1024 / 8));
        this.memory = new LruCache<String, Bitmap>(memoryKb) {
            @Override protected int sizeOf(String key, Bitmap value) {
                return value == null ? 0 : value.getByteCount() / 1024;
            }
        };
    }

    public static ImageRepository get(Context context) {
        if (instance == null) synchronized (ImageRepository.class) {
            if (instance == null) instance = new ImageRepository(context);
        }
        return instance;
    }

    public void addUpdateListener(UpdateListener listener) {
        if (listener != null) updateListeners.add(listener);
    }

    public void removeUpdateListener(UpdateListener listener) {
        if (listener != null) updateListeners.remove(listener);
    }

    /**
     * Persists the data-to-image relationship before any network work starts.
     * This keeps the filename stable across process restarts and allows the
     * viewer to resolve local images while offline.
     */
    public void prepareFamilyIndex(FamilyData family) {
        if (family == null) return;
        Map<String, String> next = new HashMap<>();
        for (Member member : family.members) {
            String filename = ImageFilenameResolver.forMember(member);
            if (member != null && member.id != null && !member.id.isEmpty()
                    && isSafeFilename(filename)) next.put(member.id, filename);
        }
        Map<String, String> immutable = Collections.unmodifiableMap(next);
        if (immutable.equals(familyIndex)) {
            logImageStartupCheck(family);
            return;
        }
        familyIndex = immutable;
        try {
            if (!imageDir.exists() && !imageDir.mkdirs()) return;
            JSONObject root = new JSONObject();
            root.put("fingerprint", family.fingerprint == null ? "" : family.fingerprint);
            JSONObject members = new JSONObject();
            for (Map.Entry<String, String> item : next.entrySet()) {
                if (isSafeFilename(item.getValue())) members.put(item.getKey(), item.getValue());
            }
            root.put("members", members);
            File temp = new File(imageDir, ".family-index-" + UUID.randomUUID());
            try (FileOutputStream output = new FileOutputStream(temp)) {
                output.write(root.toString().getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            moveReplace(temp, familyIndexFile);
        } catch (Exception error) {
            Log.w(TAG, "cannot save family image index", error);
        }
        logImageStartupCheck(family);
    }

    public String filenameFor(Member member) {
        if (member == null) return null;
        String indexed = familyIndex.get(member.id);
        return isSafeFilename(indexed) ? indexed : ImageFilenameResolver.forMember(member);
    }

    /** Emits a safe local-cache summary for debug builds without exposing data secrets. */
    public void logImageStartupCheck(FamilyData family) {
        if (family == null) return;
        Set<String> expected = new HashSet<>();
        for (Member member : family.members) {
            String filename = filenameFor(member);
            if (isSafeFilename(filename)) expected.add(filename);
        }
        int local = 0;
        for (String filename : expected) if (localFile(filename).isFile()) local++;
        Log.d(TAG, "IMAGE STARTUP CHECK: localImageCount=" + local
                + " missingImageCount=" + (expected.size() - local)
                + " directory=" + imageDir.getAbsolutePath());
    }

    /** Debug-only storage inspection; release builds intentionally produce no output. */
    public void printImageStorageStatus() {
        if (!isDebugBuild()) return;
        File[] files = imageDir.listFiles();
        long totalBytes = 0;
        int imageCount = 0;
        if (files != null) {
            for (File file : files) {
                if (!file.isFile() || !isSafeFilename(file.getName())) continue;
                imageCount++;
                totalBytes += file.length();
                Log.d(TAG, "IMAGE STORAGE FILE: " + file.getName() + " " + file.length() + " bytes");
            }
        }
        Log.d(TAG, "IMAGE STORAGE STATUS: directory=" + imageDir.getAbsolutePath()
                + " imageCount=" + imageCount + " totalBytes=" + totalBytes);
    }

    /**
     * Reads only the local original file and decodes a bounded thumbnail. It
     * never falls back to HTTP, even when the file is missing.
     */
    public void loadLocal(String filename, int width, int height, Callback callback) {
        if (!isSafeFilename(filename) || callback == null) return;
        File sourceFile = localFile(filename);
        Log.d(TAG, "IMAGE LOCAL CHECK: filename=" + filename
                + " exists=" + sourceFile.isFile() + " path=" + sourceFile.getAbsolutePath());
        int targetWidth = Math.max(1, width);
        int targetHeight = Math.max(1, height);
        String key = filename + "#" + targetWidth + "x" + targetHeight;
        Bitmap cached = memory.get(key);
        if (cached != null) {
            Log.d(TAG, "IMAGE SOURCE: LOCAL filename=" + filename + " cache=memory");
            main.post(() -> callback.onImageLoaded(cached));
            return;
        }

        boolean start;
        synchronized (pending) {
            List<Callback> callbacks = pending.get(key);
            if (callbacks == null) {
                callbacks = new ArrayList<>();
                pending.put(key, callbacks);
                start = true;
            } else {
                start = false;
            }
            callbacks.add(callback);
        }
        if (!start) return;

        executor.execute(() -> {
            Bitmap bitmap = decodeSampled(sourceFile, targetWidth, targetHeight);
            if (bitmap != null) memory.put(key, bitmap);
            Log.d(TAG, "IMAGE SOURCE: " + (bitmap == null ? "PLACEHOLDER" : "LOCAL")
                    + " filename=" + filename);
            List<Callback> callbacks;
            synchronized (pending) { callbacks = pending.remove(key); }
            if (callbacks == null) return;
            Bitmap result = bitmap;
            main.post(() -> {
                synchronized (callbacks) {
                    for (Callback item : callbacks) item.onImageLoaded(result);
                }
            });
        });
    }

    public boolean hasLocal(String filename) {
        return isSafeFilename(filename) && localFile(filename).isFile();
    }

    /**
     * Starts image synchronization without making the caller wait. The worker
     * lives in this repository, so Activity.finish()/onDestroy() cannot cancel
     * the image work after the data screen has opened the viewer.
     */
    public void syncForFamilyAsync(FamilyData family) {
        if (family == null || !backgroundSyncRunning.compareAndSet(false, true)) return;
        syncExecutor.execute(() -> {
            try {
                syncForFamily(family, null);
            } catch (Exception error) {
                Log.w(TAG, "background image sync failed", error);
            } finally {
                backgroundSyncRunning.set(false);
            }
        });
    }

    /**
     * Synchronizes the current data's image set. Call only after data has been
     * installed and decrypted. Four workers are used, failures are isolated,
     * and the manifest preserves HTTP validators to avoid needless downloads.
     */
    public SyncResult syncForFamily(FamilyData family, Progress progress) {
        if (family == null) return new SyncResult(0, 0, 0, 0);
        prepareFamilyIndex(family);
        if (!isOnline()) return new SyncResult(0, 0, 0, 0);
        synchronized (SYNC_LOCK) {
            CloudflareManifest manifest = readCloudflareManifest();
            SyncResult cloudflare = syncLocked(family, progress, manifest);
            if (cloudflare.sourceAvailable) {
                notifyImagesUpdated();
                return cloudflare;
            }

            // Google Drive is a strict fallback: one usable Cloudflare image
            // is enough to prevent downloading the Drive archive.
            SyncResult drive = syncFromDrive(family, progress);
            if (drive.downloaded > 0 || drive.skipped > 0) {
                notifyImagesUpdated();
                return drive;
            }
            notifyImagesUpdated();
            return cloudflare;
        }
    }

    private SyncResult syncLocked(FamilyData family, Progress progress,
                                   CloudflareManifest cloudflareManifest) {
        if (!imageDir.exists() && !imageDir.mkdirs()) {
            send(progress, "Không thể tạo bộ nhớ ảnh local.");
            return new SyncResult(0, 0, 0, 0, false);
        }

        Log.d(TAG, "[IMAGE_SYNC] SOURCE=Cloudflare");

        Set<String> names = new HashSet<>();
        for (Member member : family.members) {
            String filename = filenameFor(member);
            if (filename != null && isSafeFilename(filename)) names.add(filename);
        }
        List<String> filenames = new ArrayList<>(names);
        Collections.sort(filenames);
        Map<String, ImageEntry> previous = readManifest();
        // Keep older local files. A newer data file must not blank an image
        // that is already available offline while the background sync runs.
        Map<String, ImageEntry> current = new ConcurrentHashMap<>(previous);
        List<ImageJob> jobs = new ArrayList<>();

        for (String filename : filenames) {
            File local = localFile(filename);
            ImageEntry old = previous.get(filename);
            RemoteImage remote = cloudflareManifest.available
                    ? cloudflareManifest.images.get(filename) : null;
            if (cloudflareManifest.available && remote == null) continue;
            if (local.isFile()) {
                current.put(filename, old == null ? new ImageEntry() : old);
                jobs.add(new ImageJob(filename, local, old, remote, true));
            } else {
                jobs.add(new ImageJob(filename, local, old, remote, false));
            }
        }

        AtomicInteger completed = new AtomicInteger();
        AtomicInteger downloaded = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicInteger unchanged = new AtomicInteger();
        AtomicInteger newImages = new AtomicInteger();
        AtomicInteger changedImages = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean sourceAvailable =
                new java.util.concurrent.atomic.AtomicBoolean(
                        cloudflareManifest.available && !cloudflareManifest.images.isEmpty());
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();

        for (ImageJob job : jobs) {
            futures.add(executor.submit(() -> {
                DownloadResult result = downloadWithRetry(job);
                if (result.success) {
                    sourceAvailable.set(true);
                    current.put(job.filename, result.entry);
                    if (result.downloaded) {
                        downloaded.incrementAndGet();
                        if (job.hadLocal) changedImages.incrementAndGet();
                        else newImages.incrementAndGet();
                    } else unchanged.incrementAndGet();
                    if (result.downloaded) memory.evictAll();
                } else {
                    failed.incrementAndGet();
                    // Keep a valid old file/metadata when a refresh fails.
                    if (job.file.isFile() && job.old != null) current.put(job.filename, job.old);
                }
                int done = completed.incrementAndGet();
                send(progress, "Đang tải ảnh " + done + "/" + jobs.size()
                        + (failed.get() > 0 ? " · lỗi " + failed.get() : ""));
            }));
        }
        for (java.util.concurrent.Future<?> future : futures) {
            try { future.get(); } catch (Exception error) { Log.w(TAG, "image-job", error); }
        }

        writeManifest(current, family.fingerprint);
        logImageStartupCheck(family);
        printImageStorageStatus();
        send(progress, failed.get() == 0
                ? "Đã đồng bộ " + filenames.size() + " ảnh local."
                : "Đã đồng bộ ảnh local; còn " + failed.get() + " ảnh sẽ thử lại lần sau.");
        Log.d(TAG, "[IMAGE_SYNC] CLOUDFLARE_IMAGE_COUNT="
                + (cloudflareManifest.available ? cloudflareManifest.images.size() : "unknown")
                + " LOCAL_IMAGE_COUNT=" + countLocalImages(filenames)
                + " NEW=" + newImages.get() + " CHANGED=" + changedImages.get()
                + " UNCHANGED=" + unchanged.get() + " MISSING=" + failed.get()
                + " SKIPPED=" + unchanged.get());
        return new SyncResult(filenames.size(), downloaded.get(), unchanged.get(), failed.get(),
                sourceAvailable.get());
    }

    private SyncResult syncFromDrive(FamilyData family, Progress progress) {
        File archive = new File(imageDir, ".drive-images-" + UUID.randomUUID());
        try {
            UpdateInfo remote = readDriveManifest();
            if (remote.imagesUrl == null || remote.imagesUrl.trim().isEmpty()) {
                return new SyncResult(0, 0, 0, 0);
            }
            Log.d(TAG, "[IMAGE_SYNC] SOURCE=GoogleDrive");
            send(progress, "Cloudflare không có ảnh. Đang dùng Google Drive…");
            downloadExternal(remote.imagesUrl, archive, MAX_IMAGES_ARCHIVE_BYTES);
            return importDriveArchive(family, archive, progress);
        } catch (Exception error) {
            Log.w(TAG, "Google Drive image fallback failed", error);
            return new SyncResult(0, 0, 0, 1);
        } finally {
            if (archive.exists()) archive.delete();
        }
    }

    private UpdateInfo readDriveManifest() throws Exception {
        File manifest = new File(imageDir, ".drive-manifest-" + UUID.randomUUID());
        try {
            downloadExternal(DataSyncConfig.UPDATE_URL, manifest, MAX_MANIFEST_BYTES);
            return UpdateInfo.parse(readText(manifest));
        } finally {
            if (manifest.exists()) manifest.delete();
        }
    }

    private CloudflareManifest readCloudflareManifest() {
        HttpURLConnection connection = null;
        String endpoint = DataSyncConfig.CLOUDFLARE_API_BASE_URL.replaceAll("/+$", "")
                + DataSyncConfig.CLOUDFLARE_IMAGE_MANIFEST_PATH;
        try {
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestMethod("GET");
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_NOT_FOUND
                    || status == HttpURLConnection.HTTP_BAD_METHOD) {
                return CloudflareManifest.unavailable();
            }
            if (status < 200 || status >= 300) return CloudflareManifest.unavailable();
            byte[] bytes = readLimited(new BufferedInputStream(connection.getInputStream()),
                    MAX_MANIFEST_BYTES);
            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            org.json.JSONArray images = root.optJSONArray("images");
            if (images == null) return CloudflareManifest.unavailable();
            Map<String, RemoteImage> result = new HashMap<>();
            for (int i = 0; i < images.length(); i++) {
                JSONObject item = images.optJSONObject(i);
                if (item == null) continue;
                String filename = item.optString("filename", "");
                if (!isSafeFilename(filename)) continue;
                long size = item.has("size") && !item.isNull("size")
                        ? item.optLong("size", -1) : -1;
                String sha256 = item.optString("sha256", "");
                result.put(filename, new RemoteImage(size, sha256));
            }
            return CloudflareManifest.available(result);
        } catch (Exception error) {
            Log.d(TAG, "[IMAGE_SYNC] Cloudflare manifest unavailable", error);
            return CloudflareManifest.unavailable();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private SyncResult importDriveArchive(FamilyData family, File archive, Progress progress) throws Exception {
        Set<String> expected = new HashSet<>();
        for (Member member : family.members) {
            String filename = filenameFor(member);
            if (filename != null && isSafeFilename(filename)) expected.add(filename);
        }
        if (expected.isEmpty()) return new SyncResult(0, 0, 0, 0);

        Map<String, ImageEntry> previous = readManifest();
        Map<String, ImageEntry> current = new ConcurrentHashMap<>(previous);
        Set<String> seen = new HashSet<>();
        int imported = 0;
        int unchanged = 0;
        int failed = 0;
        long extractedBytes = 0;

        try (InputStream raw = new BufferedInputStream(new FileInputStream(archive));
             ZipInputStream zip = new ZipInputStream(raw)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (name == null || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                        || name.contains("..") || !expected.contains(name) || !seen.add(name)) continue;

                File temp = new File(imageDir, ".drive-entry-" + UUID.randomUUID());
                try {
                    long size = copyLimited(zip, temp, MAX_ARCHIVE_ENTRY_BYTES);
                    extractedBytes += size;
                    if (extractedBytes > MAX_IMAGES_ARCHIVE_BYTES || !isImage(temp)) {
                        failed++;
                        continue;
                    }

                    String digest = sha256(temp);
                    File target = localFile(name);
                    ImageEntry old = previous.get(name);
                    boolean needsInstall = !target.isFile() || !isImage(target)
                            || target.length() != size;
                    if (needsInstall) {
                        moveReplace(temp, target);
                        imported++;
                    } else {
                        unchanged++;
                    }
                    current.put(name, new ImageEntry(digest, null, 0));
                } catch (Exception error) {
                    failed++;
                    Log.w(TAG, "drive image entry failed: " + name, error);
                } finally {
                    if (temp.exists()) temp.delete();
                }
            }
        }

        writeManifest(current, family.fingerprint);
        send(progress, "Đã đồng bộ ảnh Google Drive: " + (imported + unchanged) + " ảnh.");
        return new SyncResult(imported + unchanged, imported, unchanged, failed);
    }

    private void notifyImagesUpdated() {
        for (UpdateListener listener : updateListeners) {
            main.post(listener::onImagesUpdated);
        }
    }

    private static void downloadExternal(String source, File target, long maxBytes) throws Exception {
        String normalized = normalizeGoogleDriveUrl(source);
        if (!normalized.startsWith("https://")) throw new IOException("https-required");
        HttpURLConnection first = openExternal(normalized, null);
        try {
            int code = first.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("http-" + code);
            InputStream input = new BufferedInputStream(first.getInputStream());
            input.mark(8192);
            byte[] prefix = readPrefix(input, 512);
            input.reset();
            if (isHtml(first.getContentType(), prefix)) {
                byte[] page = readLimited(input, MAX_HTML_BYTES);
                String token = findConfirmToken(new String(page, StandardCharsets.UTF_8));
                if (token == null) throw new IOException("google-drive-html-response");
                String cookie = cookieHeader(first);
                String confirmed = appendQuery(normalized, "confirm", token);
                first.disconnect();
                streamExternal(confirmed, target, maxBytes, cookie);
            } else {
                streamExternal(input, target, maxBytes, first.getContentLengthLong());
                first.disconnect();
            }
        } catch (Exception error) {
            first.disconnect();
            if (target.exists()) target.delete();
            throw error;
        }
    }

    private static void streamExternal(String source, File target, long maxBytes, String cookie) throws Exception {
        HttpURLConnection connection = openExternal(source, cookie);
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("http-" + code);
            streamExternal(new BufferedInputStream(connection.getInputStream()), target,
                    maxBytes, connection.getContentLengthLong());
        } finally {
            connection.disconnect();
        }
    }

    private static void streamExternal(InputStream input, File target, long maxBytes,
                                       long contentLength) throws IOException {
        try (InputStream in = input; FileOutputStream output = new FileOutputStream(target)) {
            if (contentLength > maxBytes) throw new IOException("download-too-large");
            byte[] buffer = new byte[32 * 1024];
            long total = 0;
            int count;
            while ((count = in.read(buffer)) != -1) {
                total += count;
                if (total > maxBytes) throw new IOException("download-too-large");
                output.write(buffer, 0, count);
            }
            output.getFD().sync();
        }
    }

    private static long copyLimited(InputStream input, File target, long maxBytes) throws IOException {
        try (FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[32 * 1024];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > maxBytes) throw new IOException("archive-entry-too-large");
                output.write(buffer, 0, count);
            }
            output.getFD().sync();
            return total;
        }
    }

    private static HttpURLConnection openExternal(String source, String cookie) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                appendQuery(source, "_cacheBust", String.valueOf(System.currentTimeMillis())))
                .openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestMethod("GET");
        connection.setUseCaches(false);
        connection.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0");
        connection.setRequestProperty("Pragma", "no-cache");
        connection.setRequestProperty("Accept", "*/*");
        if (cookie != null && !cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
        return connection;
    }

    private static String normalizeGoogleDriveUrl(String source) throws IOException {
        if (source == null || source.trim().isEmpty()) throw new IOException("empty-download-url");
        Uri uri = Uri.parse(source);
        String host = uri.getHost();
        if (host == null || !(host.equalsIgnoreCase("drive.google.com")
                || host.equalsIgnoreCase("drive.usercontent.google.com"))) return source;

        String id = uri.getQueryParameter("id");
        if (id == null || id.isEmpty()) {
            Matcher matcher = DRIVE_FILE_PATH.matcher(uri.getPath() == null ? "" : uri.getPath());
            if (matcher.find()) id = Uri.decode(matcher.group(1));
        }
        if (id == null || id.isEmpty()) throw new IOException("google-drive-file-id-missing");
        return "https://drive.usercontent.google.com/download?id=" + urlEncode(id) + "&export=download";
    }

    private static String findConfirmToken(String page) {
        Matcher matcher = CONFIRM_TOKEN.matcher(page);
        if (!matcher.find()) return null;
        try { return URLDecoder.decode(matcher.group(1), "UTF-8"); }
        catch (Exception ignored) { return matcher.group(1); }
    }

    private static String cookieHeader(HttpURLConnection connection) {
        StringBuilder result = new StringBuilder();
        Map<String, List<String>> headers = connection.getHeaderFields();
        if (headers != null) for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() == null || !entry.getKey().equalsIgnoreCase("Set-Cookie")
                    || entry.getValue() == null) continue;
            for (String cookie : entry.getValue()) {
                if (result.length() > 0) result.append("; ");
                result.append(cookie.split(";", 2)[0]);
            }
        }
        return result.toString();
    }

    private static boolean isHtml(String contentType, byte[] prefix) {
        if (contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).contains("text/html")) return true;
        String text = new String(prefix, StandardCharsets.UTF_8).trim().toLowerCase(java.util.Locale.ROOT);
        return text.startsWith("<!doctype html") || text.startsWith("<html")
                || text.startsWith("<head") || text.startsWith("<body");
    }

    private static byte[] readPrefix(InputStream input, int max) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[max];
        int count = input.read(buffer);
        if (count > 0) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    private static byte[] readLimited(InputStream input, long max) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32 * 1024];
            long total = 0;
            int count;
            while ((count = in.read(buffer)) != -1) {
                total += count;
                if (total > max) throw new IOException("response-too-large");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static String appendQuery(String url, String key, String value) throws IOException {
        return url + (url.contains("?") ? "&" : "?") + urlEncode(key) + "=" + urlEncode(value);
    }

    private static String urlEncode(String value) throws IOException {
        return URLEncoder.encode(value, "UTF-8");
    }

    private DownloadResult downloadWithRetry(ImageJob job) {
        Exception last = null;
        String url = ImageUrlResolver.forFilename(job.filename, null);
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                Log.d(TAG, "IMAGE SOURCE: REMOTE filename=" + job.filename + " attempt=" + attempt);
                DownloadResult result = downloadOnce(job);
                Log.d(TAG, "IMAGE DOWNLOAD: filename=" + job.filename
                        + " url=" + url
                        + " destination=" + job.file.getAbsolutePath()
                        + " success=" + result.success
                        + " downloaded=" + result.downloaded);
                if (result.success || !result.retryable) return result;
                last = new IOException("image-http-failed");
            } catch (Exception error) {
                last = error;
                Log.d(TAG, "IMAGE DOWNLOAD: filename=" + job.filename
                        + " url=" + url
                        + " destination=" + job.file.getAbsolutePath()
                        + " success=false", error);
            }
            if (attempt < MAX_ATTEMPTS) {
                try { Thread.sleep(350L * attempt); } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        Log.d(TAG, "image failed: " + job.filename, last);
        return DownloadResult.failure();
    }

    private DownloadResult downloadOnce(ImageJob job) throws Exception {
        if (!isOnline()) return DownloadResult.failure();
        String url = ImageUrlResolver.forFilename(job.filename, null);
        if (url == null) return DownloadResult.failure();

        // Prefer the manifest metadata. If the Worker has no manifest yet,
        // retain the compatibility HEAD probe instead of downloading bodies.
        if (job.file.isFile()) {
            if (job.remote != null) {
                boolean sameSize = job.remote.size < 0 || job.remote.size == job.file.length();
                if (sameSize && isImage(job.file)) {
                    String digest = job.old == null ? "" : job.old.sha256;
                    if (digest.isEmpty()) digest = sha256(job.file);
                    if (job.remote.sha256.isEmpty()
                            || job.remote.sha256.equalsIgnoreCase(digest)) {
                        return DownloadResult.unchanged(new ImageEntry(digest, null, 0));
                    }
                }
            } else {
                RemoteProbe probe = probeRemote(url);
                if (probe.notFound) return DownloadResult.failure(false);
                if (probe.length >= 0 && probe.length == job.file.length() && isImage(job.file)) {
                    String digest = job.old == null ? "" : job.old.sha256;
                    if (digest.isEmpty()) digest = sha256(job.file);
                    return DownloadResult.unchanged(new ImageEntry(digest, probe.etag, probe.lastModified));
                }
            }
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestMethod("GET");
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "image/webp,image/*");
        if (job.old != null) {
            if (job.old.etag != null) connection.setRequestProperty("If-None-Match", job.old.etag);
            if (job.old.lastModified > 0) connection.setIfModifiedSince(job.old.lastModified);
        }
        File temp = new File(imageDir, ".tmp-" + UUID.randomUUID());
        try {
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED && job.file.isFile()) {
                ImageEntry entry = job.old == null ? new ImageEntry() : job.old;
                return DownloadResult.unchanged(entry);
            }
            if (status == HttpURLConnection.HTTP_NOT_FOUND) return DownloadResult.failure(false);
            if (status < 200 || status >= 300) return DownloadResult.failure();
            long contentLength = connection.getContentLengthLong();
            if (contentLength > MAX_IMAGE_BYTES) return DownloadResult.failure();
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream output = new FileOutputStream(temp)) {
                byte[] buffer = new byte[32 * 1024];
                long total = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_IMAGE_BYTES) throw new IOException("image-too-large");
                    output.write(buffer, 0, count);
                }
                output.getFD().sync();
            }
            if (job.remote != null && job.remote.size >= 0 && temp.length() != job.remote.size) {
                return DownloadResult.failure();
            }
            if (!isImage(temp)) return DownloadResult.failure();
            String digest = sha256(temp);
            if (job.remote != null && !job.remote.sha256.isEmpty()
                    && !job.remote.sha256.equalsIgnoreCase(digest)) {
                return DownloadResult.failure();
            }
            String existingDigest = "";
            if (job.file.isFile()) {
                existingDigest = job.old == null ? "" : job.old.sha256;
                if (existingDigest.isEmpty()) existingDigest = sha256(job.file);
            }
            boolean needsInstall = !job.file.isFile()
                    || !digest.equalsIgnoreCase(existingDigest);
            if (needsInstall) moveReplace(temp, job.file);
            ImageEntry entry = new ImageEntry(digest, connection.getHeaderField("ETag"),
                    connection.getLastModified());
            return needsInstall ? DownloadResult.downloaded(entry) : DownloadResult.unchanged(entry);
        } finally {
            connection.disconnect();
            if (temp.exists()) temp.delete();
        }
    }

    private RemoteProbe probeRemote(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestMethod("HEAD");
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "image/webp,image/*");
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_NOT_FOUND) return RemoteProbe.notFound();
            if (status < 200 || status >= 300) return RemoteProbe.unknown();
            return new RemoteProbe(connection.getContentLengthLong(),
                    connection.getHeaderField("ETag"), connection.getLastModified(), false);
        } catch (Exception error) {
            Log.d(TAG, "image HEAD probe unavailable", error);
            return RemoteProbe.unknown();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private Map<String, ImageEntry> readManifest() {
        if (!manifestFile.isFile()) return new HashMap<>();
        try {
            String text = readText(manifestFile);
            JSONObject root = new JSONObject(text);
            JSONObject files = root.optJSONObject("files");
            if (files == null) return new HashMap<>();
            Map<String, ImageEntry> result = new HashMap<>();
            java.util.Iterator<String> keys = files.keys();
            while (keys.hasNext()) {
                String name = keys.next();
                if (!isSafeFilename(name)) continue;
                JSONObject value = files.optJSONObject(name);
                if (value != null) result.put(name, ImageEntry.from(value));
            }
            return result;
        } catch (Exception error) {
            Log.w(TAG, "invalid image manifest", error);
            return new HashMap<>();
        }
    }

    private Map<String, String> readFamilyIndex() {
        if (!familyIndexFile.isFile()) return Collections.emptyMap();
        try {
            JSONObject root = new JSONObject(readText(familyIndexFile));
            JSONObject members = root.optJSONObject("members");
            if (members == null) return Collections.emptyMap();
            Map<String, String> result = new HashMap<>();
            java.util.Iterator<String> keys = members.keys();
            while (keys.hasNext()) {
                String id = keys.next();
                String filename = members.optString(id, "");
                if (!id.isEmpty() && isSafeFilename(filename)) result.put(id, filename);
            }
            return Collections.unmodifiableMap(result);
        } catch (Exception error) {
            Log.w(TAG, "invalid family image index", error);
            return Collections.emptyMap();
        }
    }

    private void writeManifest(Map<String, ImageEntry> files, String fingerprint) {
        try {
            JSONObject root = new JSONObject();
            root.put("fingerprint", fingerprint == null ? "" : fingerprint);
            JSONObject jsonFiles = new JSONObject();
            for (Map.Entry<String, ImageEntry> item : files.entrySet()) {
                if (isSafeFilename(item.getKey()) && item.getValue() != null && localFile(item.getKey()).isFile()) {
                    jsonFiles.put(item.getKey(), item.getValue().toJson());
                }
            }
            root.put("files", jsonFiles);
            File temp = new File(imageDir, ".manifest-" + UUID.randomUUID());
            try (FileOutputStream output = new FileOutputStream(temp)) {
                output.write(root.toString().getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            moveReplace(temp, manifestFile);
        } catch (Exception error) {
            Log.w(TAG, "cannot save image manifest", error);
        }
    }

    private File localFile(String filename) { return new File(imageDir, filename); }

    private static boolean isSafeFilename(String filename) {
        return filename != null && filename.matches("[a-z0-9]+\\d{4}\\.webp");
    }

    private static boolean isImage(File file) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        return options.outWidth > 0 && options.outHeight > 0;
    }

    private static Bitmap decodeSampled(File file, int width, int height) {
        if (file == null || !file.isFile()) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, width, height);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private static int sampleSize(int sourceWidth, int sourceHeight, int targetWidth, int targetHeight) {
        int sample = 1;
        while (sourceWidth / (sample * 2) >= targetWidth
                && sourceHeight / (sample * 2) >= targetHeight) sample *= 2;
        return sample;
    }

    private int countLocalImages(List<String> filenames) {
        int count = 0;
        for (String filename : filenames) if (localFile(filename).isFile()) count++;
        return count;
    }

    private static String readText(File file) throws IOException {
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString("UTF-8");
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format(java.util.Locale.US, "%02x", value));
        return result.toString();
    }

    private static void moveReplace(File source, File target) throws IOException {
        // Both files are in the same app-private directory. Let the platform
        // rename first so an existing image remains visible until replacement.
        if (source.renameTo(target)) return;
        if (target.exists() && !target.delete()) throw new IOException("cannot-replace-image");
        if (!source.renameTo(target)) {
            try (InputStream input = new FileInputStream(source); FileOutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[32 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.getFD().sync();
            }
            if (!source.delete()) throw new IOException("cannot-clean-image-temp");
        }
    }

    private boolean isOnline() {
        try {
            ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return false;
            Network network = manager.getActiveNetwork();
            NetworkCapabilities capabilities = network == null ? null : manager.getNetworkCapabilities(network);
            return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (SecurityException denied) {
            Log.w(TAG, "Network state permission unavailable", denied);
            return false;
        }
    }

    private boolean isDebugBuild() {
        return (context.getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    private static void send(Progress progress, String message) { if (progress != null) progress.onMessage(message); }

    private static final class ImageJob {
        final String filename; final File file; final ImageEntry old; final RemoteImage remote;
        final boolean hadLocal;
        ImageJob(String filename, File file, ImageEntry old, RemoteImage remote, boolean hadLocal) {
            this.filename = filename; this.file = file; this.old = old; this.remote = remote;
            this.hadLocal = hadLocal;
        }
    }

    private static final class DownloadResult {
        final boolean success, downloaded, retryable; final ImageEntry entry;
        private DownloadResult(boolean success, boolean downloaded, boolean retryable, ImageEntry entry) {
            this.success = success; this.downloaded = downloaded; this.retryable = retryable; this.entry = entry;
        }
        static DownloadResult downloaded(ImageEntry entry) { return new DownloadResult(true, true, false, entry); }
        static DownloadResult unchanged(ImageEntry entry) { return new DownloadResult(true, false, false, entry); }
        static DownloadResult failure() { return failure(true); }
        static DownloadResult failure(boolean retryable) { return new DownloadResult(false, false, retryable, null); }
    }

    private static final class RemoteProbe {
        final long length;
        final String etag;
        final long lastModified;
        final boolean notFound;

        RemoteProbe(long length, String etag, long lastModified, boolean notFound) {
            this.length = length;
            this.etag = etag;
            this.lastModified = lastModified;
            this.notFound = notFound;
        }

        static RemoteProbe unknown() { return new RemoteProbe(-1, null, 0, false); }
        static RemoteProbe notFound() { return new RemoteProbe(-1, null, 0, true); }
    }

    private static final class RemoteImage {
        final long size;
        final String sha256;

        RemoteImage(long size, String sha256) {
            this.size = size;
            this.sha256 = sha256 == null ? "" : sha256;
        }
    }

    private static final class CloudflareManifest {
        final boolean available;
        final Map<String, RemoteImage> images;

        CloudflareManifest(boolean available, Map<String, RemoteImage> images) {
            this.available = available;
            this.images = images;
        }

        static CloudflareManifest unavailable() {
            return new CloudflareManifest(false, Collections.emptyMap());
        }

        static CloudflareManifest available(Map<String, RemoteImage> images) {
            return new CloudflareManifest(true, Collections.unmodifiableMap(images));
        }
    }

    private static final class ImageEntry {
        final String sha256, etag; final long lastModified;
        ImageEntry() { this("", null, 0); }
        ImageEntry(String sha256, String etag, long lastModified) { this.sha256 = sha256 == null ? "" : sha256; this.etag = etag; this.lastModified = lastModified; }
        static ImageEntry from(JSONObject object) { return new ImageEntry(object.optString("sha256", ""), object.optString("etag", null), object.optLong("lastModified", 0)); }
        JSONObject toJson() throws Exception { JSONObject value = new JSONObject(); value.put("sha256", sha256); if (etag != null) value.put("etag", etag); if (lastModified > 0) value.put("lastModified", lastModified); return value; }
    }
}
