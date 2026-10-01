package com.android.acerem.xemgp.data;

import android.net.Uri;

import java.util.Locale;

/** Parsed contents of the small remote update manifest. */
public final class UpdateInfo {
    public final long version;
    public final String dataUrl;
    public final String imagesUrl;
    public final long dataSize;
    public final String contentHash;

    private UpdateInfo(long version, String dataUrl, String imagesUrl, long dataSize, String contentHash) {
        this.version = version;
        this.dataUrl = dataUrl;
        this.imagesUrl = imagesUrl;
        this.dataSize = dataSize;
        this.contentHash = contentHash;
    }

    public static UpdateInfo parse(String text) {
        if (text == null) throw new IllegalArgumentException("update-empty");
        String versionValue = null;
        String dataValue = null;
        String imagesValue = "";
        String sizeValue = null;
        String hashValue = null;
        String[] lines = text.split("\\r?\\n");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int separator = line.indexOf('=');
            if (separator <= 0) continue;
            String key = line.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(separator + 1).trim();
            if ("version".equals(key)) versionValue = value;
            else if ("data".equals(key)) dataValue = value;
            else if ("images".equals(key)) imagesValue = value;
            else if ("datasize".equals(key)) sizeValue = value;
            else if ("contenthash".equals(key)) hashValue = value.toLowerCase(Locale.ROOT);
        }

        if (versionValue == null || dataValue == null || dataValue.isEmpty()) {
            throw new IllegalArgumentException("update-required-fields-missing");
        }
        final long version;
        try {
            version = Long.parseLong(versionValue);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("update-version-invalid", error);
        }
        long dataSize = -1;
        if (sizeValue != null && !sizeValue.isEmpty()) {
            try { dataSize = Long.parseLong(sizeValue); }
            catch (NumberFormatException error) { throw new IllegalArgumentException("update-data-size-invalid", error); }
            if (dataSize <= 0) throw new IllegalArgumentException("update-data-size-invalid");
        }
        if (version < 0 || !isHttpUrl(dataValue)
                || (!imagesValue.isEmpty() && !isHttpUrl(imagesValue))
                || (hashValue != null && !hashValue.matches("[a-f0-9]{64}"))) {
            throw new IllegalArgumentException("update-values-invalid");
        }
        return new UpdateInfo(version, dataValue, imagesValue, dataSize, hashValue);
    }

    private static boolean isHttpUrl(String value) {
        Uri uri = Uri.parse(value);
        String scheme = uri.getScheme();
        return ("https".equalsIgnoreCase(scheme))
                && uri.getHost() != null;
    }
}
