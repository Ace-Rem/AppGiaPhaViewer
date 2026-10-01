package com.android.acerem.xemgp.data;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Strict source order: Cloudflare Worker first, then Google Drive, then error. */
public final class DataSyncManager {
    private static final String TAG = "DataSync";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 45_000;
    private static final long MAX_VERSION_BYTES = 64 * 1024;
    private static final long MAX_DATA_BYTES = 100L * 1024 * 1024;
    private static final long MAX_IMAGES_ZIP_BYTES = 500L * 1024 * 1024;
    private static final long MAX_HTML_BYTES = 2L * 1024 * 1024;
    private static final Pattern DRIVE_FILE_PATH = Pattern.compile("/file/d/([^/]+)");
    private static final Pattern CONFIRM_TOKEN = Pattern.compile("(?i)[?&]confirm=([^&\"'<>]+)");
    private static final Pattern SHA256 = Pattern.compile("[a-f0-9]{64}");

    public interface Progress { void onMessage(String message); }

    public static final class Result {
        public final boolean ready;
        public final String message;

        private Result(boolean ready, String message) {
            this.ready = ready;
            this.message = message;
        }

        public static Result ready(String message) { return new Result(true, message); }
        public static Result failed(String message) { return new Result(false, message); }
    }

    private DataSyncManager() {}

    /**
     * A failed Worker attempt is the only condition that permits a Google Drive attempt.
     * Existing files are never consulted to decide whether either source is skipped.
     */
    public static Result sync(Context context, Progress progress) {
        Context app = context.getApplicationContext();
        File workDir = new File(app.getFilesDir(), ".data-sync");
        if (!workDir.exists() && !workDir.mkdirs()) return Result.failed("Không thể tạo vùng dữ liệu tạm.");

        File workerVersion = temp(workDir, ".worker-version-");
        File workerData = temp(workDir, ".worker-data-");
        File driveManifest = temp(workDir, ".drive-manifest-");
        File driveData = temp(workDir, ".drive-data-");
        File driveImages = temp(workDir, ".drive-images-");
        try {
            send(progress, "Đang đồng bộ từ Cloudflare…");
            try {
                syncCloudflare(app, progress, workerVersion, workerData);
                return Result.ready("Đã đồng bộ dữ liệu từ Cloudflare.");
            } catch (Exception workerError) {
                Log.w(TAG, "Cloudflare source failed; trying Google Drive", workerError);
            }

            send(progress, "Cloudflare không khả dụng. Đang chuyển sang Google Drive…");
            try {
                syncGoogleDrive(app, progress, driveManifest, driveData, driveImages);
                return Result.ready("Đã đồng bộ dữ liệu từ Google Drive.");
            } catch (Exception driveError) {
                Log.w(TAG, "Google Drive source failed", driveError);
                return Result.failed("Không thể đồng bộ từ Cloudflare hoặc Google Drive. Vui lòng thử lại.");
            }
        } finally {
            deleteIfExists(workerVersion);
            deleteIfExists(workerData);
            deleteIfExists(driveManifest);
            deleteIfExists(driveData);
            deleteIfExists(driveImages);
        }
    }

    private static void syncCloudflare(Context app, Progress progress, File versionFile, File dataFile) throws Exception {
        String base = DataSyncConfig.CLOUDFLARE_API_BASE_URL;
        download(base + "/version", versionFile, MAX_VERSION_BYTES);
        WorkerVersion remote = WorkerVersion.parse(new String(readFile(versionFile), StandardCharsets.UTF_8));

        String dataUrl = appendQuery(base + "/data", "version", remote.dataVersion);
        send(progress, "Cloudflare đã trả version " + remote.dataVersion + ". Đang tải data.enc…");
        download(dataUrl, dataFile, MAX_DATA_BYTES);
        verifyDownloadedData(dataFile, remote.dataSize, remote.expectedHash, true);
        DataRepository.validateEnvelope(new String(readFile(dataFile), StandardCharsets.UTF_8));

        String fingerprint = fingerprint(readFile(dataFile));
        copyCurrentImages(app, DataRepository.currentFingerprint(app), fingerprint);
        send(progress, "Đang lưu dữ liệu Cloudflare…");
        DataRepository.installDownloaded(app, dataFile, null,
                (done, total) -> send(progress, "Đang xử lý ảnh… " + done + " / " + total));
    }

    private static void syncGoogleDrive(Context app, Progress progress, File manifestFile,
                                        File dataFile, File imagesFile) throws Exception {
        send(progress, "Đang kiểm tra manifest Google Drive…");
        download(DataSyncConfig.UPDATE_URL, manifestFile, MAX_VERSION_BYTES);
        UpdateInfo remote = UpdateInfo.parse(new String(readFile(manifestFile), StandardCharsets.UTF_8));

        send(progress, "Đang tải data.enc từ Google Drive…");
        download(remote.dataUrl, dataFile, MAX_DATA_BYTES);
        verifyDownloadedData(dataFile, remote.dataSize, remote.contentHash, false);
        DataRepository.validateEnvelope(new String(readFile(dataFile), StandardCharsets.UTF_8));

        boolean hasImages = remote.imagesUrl != null && !remote.imagesUrl.isEmpty();
        if (hasImages) {
            send(progress, "Đang tải images.zip từ Google Drive…");
            download(remote.imagesUrl, imagesFile, MAX_IMAGES_ZIP_BYTES);
            validateZip(imagesFile);
        }

        String fingerprint = fingerprint(readFile(dataFile));
        if (!hasImages) copyCurrentImages(app, DataRepository.currentFingerprint(app), fingerprint);
        send(progress, "Đang kiểm tra và lưu dữ liệu Google Drive…");
        DataRepository.installDownloaded(app, dataFile, hasImages ? imagesFile : null,
                (done, total) -> send(progress, "Đang xử lý ảnh… " + done + " / " + total));
    }

    private static void verifyDownloadedData(File file, long expectedSize, String expectedHash,
                                             boolean requireHash) throws Exception {
        long actualSize = file.length();
        if (actualSize <= 0 || actualSize > MAX_DATA_BYTES) throw new IOException("data-size-invalid");
        if (expectedSize > 0 && actualSize != expectedSize) throw new IOException("data-size-mismatch");
        String actualHash = fingerprint(readFile(file));
        if (expectedHash == null || expectedHash.isEmpty()) {
            if (requireHash) throw new IOException("data-hash-missing");
        } else if (!SHA256.matcher(expectedHash).matches() || !expectedHash.equalsIgnoreCase(actualHash)) {
            throw new IOException("data-hash-mismatch");
        }
    }

    private static File temp(File dir, String prefix) { return new File(dir, prefix + UUID.randomUUID()); }

    private static void download(String source, File target, long maxBytes) throws Exception {
        String normalized = normalizeGoogleDriveUrl(source);
        if (!normalized.startsWith("https://")) throw new IOException("https-required");
        try {
            HttpURLConnection first = open(normalized, null);
            try {
                int code = first.getResponseCode();
                if (code < 200 || code >= 300) throw new HttpStatusException(code);

                String contentType = first.getContentType();
                InputStream input = new BufferedInputStream(first.getInputStream());
                input.mark(8192);
                byte[] prefix = readPrefix(input, 512);
                input.reset();
                if (isHtml(contentType, prefix)) {
                    byte[] page = readLimited(input, MAX_HTML_BYTES);
                    String token = findConfirmToken(new String(page, StandardCharsets.UTF_8));
                    if (token == null) throw new IOException("google-drive-html-response");
                    String cookie = cookieHeader(first);
                    String confirmed = appendQuery(normalized, "confirm", token);
                    closeQuietly(input);
                    first.disconnect();
                    streamRaw(confirmed, target, maxBytes, cookie);
                } else {
                    stream(input, target, maxBytes, first.getContentLengthLong());
                    first.disconnect();
                }
            } catch (Exception error) {
                first.disconnect();
                throw error;
            }
        } catch (Exception error) {
            deleteIfExists(target);
            throw error;
        }
    }

    private static HttpURLConnection open(String source, String cookie) throws IOException {
        String request = appendQuery(source, "_cacheBust", String.valueOf(System.currentTimeMillis()));
        HttpURLConnection connection = (HttpURLConnection) new URL(request).openConnection();
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

    private static void streamRaw(String source, File target, long maxBytes, String cookie) throws Exception {
        HttpURLConnection connection = open(source, cookie);
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new HttpStatusException(code);
            InputStream input = new BufferedInputStream(connection.getInputStream());
            input.mark(8192);
            byte[] prefix = readPrefix(input, 512);
            input.reset();
            if (isHtml(connection.getContentType(), prefix)) throw new IOException("google-drive-confirmation-failed");
            stream(input, target, maxBytes, connection.getContentLengthLong());
        } finally {
            connection.disconnect();
        }
    }

    private static void stream(InputStream input, File target, long maxBytes, long contentLength) throws IOException {
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
            if (entry.getKey() == null || !entry.getKey().equalsIgnoreCase("Set-Cookie") || entry.getValue() == null) continue;
            for (String cookie : entry.getValue()) {
                if (result.length() > 0) result.append("; ");
                result.append(cookie.split(";", 2)[0]);
            }
        }
        return result.toString();
    }

    private static boolean isHtml(String contentType, byte[] prefix) {
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("text/html")) return true;
        String text = new String(prefix, StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
        return text.startsWith("<!doctype html") || text.startsWith("<html")
                || text.startsWith("<head") || text.startsWith("<body");
    }

    private static void validateZip(File file) throws IOException {
        int entries = 0;
        try (InputStream raw = new FileInputStream(file);
             ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries++;
                byte[] buffer = new byte[32 * 1024];
                while (zip.read(buffer) != -1) { /* read to detect truncated archives */ }
            }
        }
        Log.d(TAG, "images.zip validation passed; entries: " + entries);
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
                if (total > max) throw new IOException("html-response-too-large");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static String appendQuery(String url, String key, String value) throws IOException {
        return url + (url.contains("?") ? "&" : "?") + urlEncode(key) + "=" + urlEncode(value);
    }

    private static String urlEncode(String value) throws IOException { return URLEncoder.encode(value, "UTF-8"); }

    private static byte[] readFile(File file) throws IOException {
        try (InputStream input = new BufferedInputStream(new FileInputStream(file));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private static String fingerprint(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder();
        for (byte b : digest) result.append(String.format(Locale.US, "%02x", b));
        return result.toString();
    }

    private static void copyCurrentImages(Context context, String oldFingerprint, String newFingerprint) throws IOException {
        if (oldFingerprint == null || oldFingerprint.equals(newFingerprint)) return;
        File oldDir = DataRepository.imageDir(context);
        if (oldDir == null || !oldDir.isDirectory()) return;
        File newDir = new File(DataRepository.familyDir(context, newFingerprint), "images");
        if (!newDir.exists() && !newDir.mkdirs()) throw new IOException("cannot-create-images");
        File[] files = oldDir.listFiles();
        if (files == null) return;
        for (File source : files) {
            if (!source.isFile()) continue;
            File target = new File(newDir, source.getName());
            try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[32 * 1024];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            }
        }
    }

    private static void closeQuietly(Closeable closeable) {
        try { if (closeable != null) closeable.close(); } catch (IOException ignored) { }
    }

    private static void deleteIfExists(File file) { if (file != null && file.exists()) file.delete(); }
    private static void send(Progress progress, String message) { if (progress != null) progress.onMessage(message); }

    private static final class WorkerVersion {
        final String dataVersion;
        final String expectedHash;
        final long dataSize;

        private WorkerVersion(String dataVersion, String expectedHash, long dataSize) {
            this.dataVersion = dataVersion;
            this.expectedHash = expectedHash;
            this.dataSize = dataSize;
        }

        static WorkerVersion parse(String text) throws Exception {
            JSONObject json = new JSONObject(text);
            String dataVersion = first(json.optString("dataVersion", null), json.optString("version", null));
            String expectedHash = first(json.optString("contentHash", null), dataVersion);
            if (dataVersion == null || !SHA256.matcher(dataVersion.toLowerCase(Locale.ROOT)).matches()
                    || expectedHash == null || !SHA256.matcher(expectedHash.toLowerCase(Locale.ROOT)).matches()) {
                throw new IOException("worker-version-invalid");
            }
            long dataSize = -1;
            if (json.has("dataSize") && !json.isNull("dataSize")) {
                dataSize = json.getLong("dataSize");
                if (dataSize <= 0 || dataSize > MAX_DATA_BYTES) throw new IOException("worker-data-size-invalid");
            }
            return new WorkerVersion(dataVersion.toLowerCase(Locale.ROOT), expectedHash.toLowerCase(Locale.ROOT), dataSize);
        }

        private static String first(String value, String fallback) {
            return value == null || value.trim().isEmpty() ? fallback : value.trim();
        }
    }

    private static final class HttpStatusException extends IOException {
        HttpStatusException(int statusCode) { super("http-" + statusCode); }
    }
}
