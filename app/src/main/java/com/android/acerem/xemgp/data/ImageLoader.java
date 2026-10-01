package com.android.acerem.xemgp.data;

import android.content.Context;
import android.graphics.Bitmap;

/**
 * Compatibility facade for older callers. ImageRepository is now the single
 * image source and this class intentionally performs local-only reads.
 */
@Deprecated
public final class ImageLoader {
    public interface Callback { void onImageLoaded(Bitmap bitmap); }
    private static volatile ImageLoader instance;
    private final ImageRepository repository;

    private ImageLoader(Context context) { repository = ImageRepository.get(context); }

    public static ImageLoader get(Context context) {
        if (instance == null) synchronized (ImageLoader.class) {
            if (instance == null) instance = new ImageLoader(context);
        }
        return instance;
    }

    /** The version argument is retained for source compatibility but ignored. */
    public void load(String filename, String version, int width, int height, Callback callback) {
        if (callback == null) return;
        repository.loadLocal(filename, width, height, callback::onImageLoaded);
    }
}
