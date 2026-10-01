package com.android.acerem.xemgp.data;

/** Public endpoints only. No R2 or publishing credentials belong in the APK. */
public final class DataSyncConfig {
    public static final String CLOUDFLARE_API_BASE_URL =
            "https://family-tree-api.acerem.workers.dev";
    public static final String CLOUDFLARE_IMAGE_PATH = "/images";
    public static final String CLOUDFLARE_IMAGE_MANIFEST_PATH = "/images/manifest";

    /** Public Google Drive manifest used only after the Worker source fails. */
    public static final String UPDATE_URL =
            "https://drive.google.com/uc?export=download&id=1yoBk7d4vCIGvAm5vrJIsFknAAs9prdRO";

    private DataSyncConfig() {}
}
