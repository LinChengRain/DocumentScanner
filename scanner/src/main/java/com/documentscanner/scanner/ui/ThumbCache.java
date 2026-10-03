package com.documentscanner.scanner.ui;

import android.graphics.Bitmap;
import android.util.LruCache;

import com.documentscanner.scanner.util.ImageIO;

import java.io.File;

/** 缩略图内存缓存，键为「路径 + 文件大小」，重写同名文件后自动失效。 */
public final class ThumbCache {

    private static final int MAX_BYTES = 12 * 1024 * 1024;
    private static final int EDGE = 340;

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(MAX_BYTES) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private ThumbCache() {
    }

    public static String keyFor(File file) {
        return file.getAbsolutePath() + "@" + file.length();
    }

    public static Bitmap peek(File file) {
        if (file == null || !file.exists()) return null;
        Bitmap cached = CACHE.get(keyFor(file));
        return cached != null && !cached.isRecycled() ? cached : null;
    }

    /** 阻塞解码，调用方须在后台线程。 */
    public static Bitmap loadBlocking(File file) {
        if (file == null || !file.exists()) return null;
        Bitmap cached = peek(file);
        if (cached != null) return cached;
        try {
            Bitmap decoded = ImageIO.load(file, EDGE);
            CACHE.put(keyFor(file), decoded);
            return decoded;
        } catch (Exception e) {
            return null;
        }
    }
}
