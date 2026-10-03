package com.documentscanner.scanner.export;

import android.content.ContentResolver;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * 保存到系统相册/下载目录。
 * Android 10+ 走 MediaStore 相对路径（无需存储权限）；
 * 8.x 及以下写入应用外部目录并触发媒体扫描，同样不需要申请 WRITE_EXTERNAL_STORAGE。
 */
public final class GallerySaver {

    public static final String ALBUM = "DocumentScanner";
    private static final int BUFFER = 16 * 1024;

    private GallerySaver() {
    }

    public static Uri saveImage(Context context, File source, String displayName) throws IOException {
        return save(context, source, displayName, "image/jpeg", MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                Environment.DIRECTORY_PICTURES);
    }

    /** 批量保存，返回成功的 Uri 列表；单个失败不影响其余页面。 */
    public static int saveImages(Context context, List<File> sources, String namePrefix,
                                 java.util.function.IntConsumer progress) {
        int saved = 0;
        for (int i = 0; i < sources.size(); i++) {
            try {
                saveImage(context, sources.get(i), namePrefix + "-" + (i + 1) + ".jpg");
                saved++;
            } catch (IOException ignored) {
                // 单页失败继续处理后面的页面
            }
            if (progress != null) progress.accept(i + 1);
        }
        return saved;
    }

    private static Uri save(Context context, File source, String displayName, String mime,
                            Uri collection, String relativeParent) throws IOException {
        if (!source.exists()) throw new IOException("文件不存在: " + source);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return saveWithPending(context, source, displayName, mime, collection, relativeParent);
        }
        return saveLegacy(context, source, displayName, relativeParent);
    }

    private static Uri saveWithPending(Context context, File source, String displayName, String mime,
                                       Uri collection, String relativeParent) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        android.content.ContentValues values = new android.content.ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                relativeParent + "/" + ALBUM);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri uri = resolver.insert(collection, values);
        if (uri == null) throw new IOException("无法写入媒体库");
        try (InputStream in = new FileInputStream(source);
             OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null) throw new IOException("无法打开输出流");
            copy(in, out);
        } catch (IOException e) {
            resolver.delete(uri, null, null);
            throw e;
        }
        values.clear();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        resolver.update(uri, values, null, null);
        return uri;
    }

    private static Uri saveLegacy(Context context, File source, String displayName, String relativeParent)
            throws IOException {
        File directory = new File(context.getExternalFilesDir(relativeParent), ALBUM);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("无法创建目录 " + directory);
        }
        File target = new File(directory, displayName);
        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(target)) {
            copy(in, out);
        }
        MediaScannerConnection.scanFile(context.getApplicationContext(),
                new String[]{target.getAbsolutePath()}, null, null);
        return Uri.fromFile(target);
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[BUFFER];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        out.flush();
    }
}
