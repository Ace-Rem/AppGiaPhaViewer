package com.android.acerem.xemgp.data;

import android.net.Uri;

/** Single public Worker URL resolver for all Android member images. */
public final class ImageUrlResolver {
    private ImageUrlResolver() {}

    public static String forFilename(String filename, String version) {
        if (filename == null || filename.trim().isEmpty()) return null;
        String base = DataSyncConfig.CLOUDFLARE_API_BASE_URL.replaceAll("/+$", "");
        String url = base + DataSyncConfig.CLOUDFLARE_IMAGE_PATH + "/" + Uri.encode(filename);
        return version == null || version.trim().isEmpty()
                ? url
                : url + "?version=" + Uri.encode(version);
    }

    public static String forMember(Member member, String version) {
        return forFilename(ImageFilenameResolver.forMember(member), version);
    }
}
