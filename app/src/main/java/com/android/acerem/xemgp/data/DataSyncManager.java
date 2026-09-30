package com.android.acerem.xemgp.data;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.util.Log;

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

/** Downloads and commits the online archive while keeping the current archive untouched on failure. */
public final class DataSyncManager {
    private static final String TAG = "DataSync";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 45_000;
    private static final long MAX_UPDATE_BYTES = 64 * 1024;
    private static final long MAX_DATA_BYTES = 100L * 1024 * 1024;
    private static final long MAX_IMAGES_ZIP_BYTES = 500L * 1024 * 1024;
    private static final long MAX_HTML_BYTES = 2L * 1024 * 1024;
    private static final Pattern DRIVE_FILE_PATH = Pattern.compile("/file/d/([^/]+)");
    private static final Pattern CONFIRM_TOKEN = Pattern.compile("(?i)[?&]confirm=([^&\"'<>]+)");

    public interface Progress {
        void onMessage(String message);
    }

    public static final class Result {
        public final boolean ready;
        public final boolean usedLocal;
        public final String message;

        private Result(boolean ready, boolean usedLocal, String message) {
            this.ready = ready;
            this.usedLocal = usedLocal;
            this.message = message;
        }

        public static Result ready(boolean local, String message) {
            return new Result(true, local, message);
        }

        public static Result failed(String message) {
            return new Result(false, false, message);
        }
    }

    private DataSyncManager() {}

    public static Result sync(Context context, Progress progress) {
        Context app = context.getApplicationContext();
        boolean localReady = DataRepository.hasValidCurrentData(app);

        if (!hasNetwork(app)) {
            Log.d(TAG, localReady ? "Offline; using local data" : "Offline; no local data");
            return localReady
                    ? Result.ready(true, "Không có Internet. Đang dùng dữ liệu local.")
                    : Result.failed("Không có kết nối Internet. Dữ liệu chưa được tải về thiết bị. Vui lòng kết nối Internet và thử lại.");
        }

        File workDir = new File(app.getFilesDir(), ".data-sync");
        if (!workDir.exists() && !workDir.mkdirs()) {
            return localReady
                    ? Result.ready(true, "Đang dùng dữ liệu local.")
                    : Result.failed("Không thể tạo vùng dữ liệu tạm.");
        }

        File updateFile = new File(workDir, ".update-" + UUID.randomUUID());
        try {
            send(progress, "Đang kiểm tra phiên bản dữ liệu…");
            Log.d(TAG, "Checking update");
            download(DataSyncConfig.UPDATE_URL, updateFile, MAX_UPDATE_BYTES);

            UpdateInfo remote = UpdateInfo.parse(new String(readFile(updateFile), StandardCharsets.UTF_8));
            long localVersion = DataRepository.localDataVersion(app);
            Log.d(TAG, "Remote version: " + remote.version);
            Log.d(TAG, "Local version: " + localVersion);

            boolean needsUpdate = !localReady || remote.version > localVersion;
            if (!needsUpdate) {
                Log.d(TAG, "Local data is current");
                return Result.ready(true, remote.version == localVersion
                        ? "Dữ liệu đã được cập nhật."
                        : "Đang dùng dữ liệu mới hơn trên thiết bị.");
            }

            Log.d(TAG, "New data available");
            send(progress, "Đang tải data.enc…");
            File dataFile = new File(workDir, ".data-" + UUID.randomUUID());
            File imagesFile = new File(workDir, ".images-" + UUID.randomUUID());
            try {
                Log.d(TAG, "Downloading data.enc");
                download(remote.dataUrl, dataFile, MAX_DATA_BYTES);
                DataRepository.validateEnvelope(new String(readFile(dataFile), StandardCharsets.UTF_8));

                boolean hasImages = remote.imagesUrl != null && !remote.imagesUrl.isEmpty();
                if (hasImages) {
                    send(progress, "Đang tải images.zip…");
                    Log.d(TAG, "Downloading images.zip");
                    download(remote.imagesUrl, imagesFile, MAX_IMAGES_ZIP_BYTES);
                    validateZip(imagesFile);
                } else {
                    send(progress, "Đang giữ ảnh local…");
                    copyCurrentImages(app, DataRepository.currentFingerprint(app), fingerprint(readFile(dataFile)));
                }

                send(progress, "Đang kiểm tra và lưu dữ liệu…");
                DataRepository.installDownloaded(app, dataFile, hasImages ? imagesFile : null,
                        (done, total) -> send(progress, "Đang xử lý ảnh… " + done + " / " + total));
                DataRepository.setLocalDataVersion(app, remote.version);
                Log.d(TAG, "Update successful");
                return Result.ready(false, "Đã cập nhật dữ liệu.");
            } catch (Exception updateError) {
                Log.w(TAG, "Update failed; keeping local data", updateError);
                return localReady
                        ? Result.ready(true, "Không thể cập nhật. Đang dùng dữ liệu local.")
                        : Result.failed("Không thể tải hoặc xử lý dữ liệu ban đầu. Vui lòng thử lại khi mạng ổn định.");
            } finally {
                deleteIfExists(dataFile);
                deleteIfExists(imagesFile);
            }
        } catch (Exception onlineError) {
            Log.w(TAG, "Update check failed; keeping local data", onlineError);
            return localReady
                    ? Result.ready(true, "Không thể kiểm tra online. Đang dùng dữ liệu local.")
                    : Result.failed("Không thể tải update.txt. Vui lòng kiểm tra Internet và thử lại.");
        } finally {
            deleteIfExists(updateFile);
        }
    }

    private static boolean hasNetwork(Context context) {
        try {
            ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return false;
            Network network = manager.getActiveNetwork();
            NetworkCapabilities caps = network == null ? null : manager.getNetworkCapabilities(network);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (SecurityException ignored) {
            return true;
        }
    }

    /** Downloads raw bytes from a normal HTTPS URL or a public Google Drive sharing URL. */
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
        try {
            return URLDecoder.decode(matcher.group(1), "UTF-8");
        } catch (Exception ignored) {
            return matcher.group(1);
        }
    }

    private static String cookieHeader(HttpURLConnection connection) {
        StringBuilder result = new StringBuilder();
        Map<String, List<String>> headers = connection.getHeaderFields();
        if (headers != null) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() == null || !entry.getKey().equalsIgnoreCase("Set-Cookie")
                        || entry.getValue() == null) continue;
                for (String cookie : entry.getValue()) {
                    if (result.length() > 0) result.append("; ");
                    result.append(cookie.split(";", 2)[0]);
                }
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
                while (zip.read(buffer) != -1) {
                    // Read every entry so truncated archives are detected before installation.
                }
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

    private static String urlEncode(String value) throws IOException {
        return URLEncoder.encode(value, "UTF-8");
    }

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
        try {
            if (closeable != null) closeable.close();
        } catch (IOException ignored) {
        }
    }

    private static void deleteIfExists(File file) {
        if (file != null && file.exists()) file.delete();
    }

    private static void send(Progress progress, String message) {
        if (progress != null) progress.onMessage(message);
    }

    private static final class HttpStatusException extends IOException {
        final int statusCode;

        HttpStatusException(int statusCode) {
            super("http-" + statusCode);
            this.statusCode = statusCode;
        }
    }
}
