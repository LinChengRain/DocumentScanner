package com.documentscanner.scanner.ui;

import android.graphics.Bitmap;
import android.widget.ImageView;

import com.documentscanner.scanner.model.ScanPage;
import com.documentscanner.scanner.util.Io;

import java.io.File;

/**
 * 列表位图加载：命中缓存则同帧显示，否则后台解码。
 * 用 tag 记录期望内容，回收复用时丢弃过期结果，避免「图片串页」。
 */
public class PageImageLoader {

    public void loadInto(ImageView view, ScanPage page) {
        loadInto(view, page.thumb.exists() ? page.thumb : page.bestOutput());
    }

    /** 历史列表的封面直接给了文件，走同一套缓存与 tag 校验。 */
    public void loadInto(ImageView view, File file) {
        String key = ThumbCache.keyFor(file);
        view.setTag(key);

        Bitmap cached = ThumbCache.peek(file);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageDrawable(null);
        Io.bg(() -> {
            Bitmap bitmap = ThumbCache.loadBlocking(file);
            Io.main(() -> {
                if (key.equals(view.getTag()) && bitmap != null && !bitmap.isRecycled()) {
                    view.setImageBitmap(bitmap);
                }
            });
        });
    }
}
