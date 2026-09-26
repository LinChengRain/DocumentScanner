package com.documentscanner.util;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** 位图读写：按目标边长降采样 + 把 EXIF 方向烘焙进像素，避免下游各处重复处理旋转。 */
public final class ImageIO {

    private static final String TAG = "ImageIO";

    private ImageIO() {
    }

    /** 读入并降采样到 longEdge <= maxEdge，同时应用 EXIF 方向（返回的位图永远是正立的）。 */
    public static Bitmap load(File file, int maxEdge) throws IOException {
        String path = file.getAbsolutePath();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("无法解码图片: " + path);
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        opts.inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdge);
        Bitmap raw = BitmapFactory.decodeFile(path, opts);
        if (raw == null) {
            throw new IOException("解码失败: " + path);
        }

        Bitmap oriented = applyOrientation(raw, exifOrientation(path));
        if (oriented != raw) raw.recycle();
        return scaleMaxEdge(oriented, maxEdge);
    }

    /**
     * 读取相册/文档 Uri 指向的图片，同样降采样并烘焙 EXIF 方向。
     * 用于「从相册导入」，避免为了复用文件路径版而先拷一份临时文件。
     */
    public static Bitmap load(Context context, Uri uri, int maxEdge) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "r")) {
            if (descriptor == null) throw new IOException("无法打开图片: " + uri);
            BitmapFactory.decodeFileDescriptor(descriptor.getFileDescriptor(), null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("无法解码图片: " + uri);

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdge);
        Bitmap raw;
        try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "r")) {
            if (descriptor == null) throw new IOException("无法打开图片: " + uri);
            raw = BitmapFactory.decodeFileDescriptor(descriptor.getFileDescriptor(), null, options);
        }
        if (raw == null) throw new IOException("解码失败: " + uri);

        Bitmap oriented = applyOrientation(raw, exifOrientation(resolver, uri));
        if (oriented != raw) raw.recycle();
        return scaleMaxEdge(oriented, maxEdge);
    }

    private static int exifOrientation(ContentResolver resolver, Uri uri) {
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) return ExifInterface.ORIENTATION_NORMAL;
            return new ExifInterface(in).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException e) {
            Log.w(TAG, "读取 Uri EXIF 失败", e);
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    public static int sampleSizeFor(int width, int height, int maxEdge) {
        int longEdge = Math.max(width, height);
        int sample = 1;
        while (longEdge / (sample * 2f) >= maxEdge) sample *= 2;
        return sample;
    }

    /** 等比缩放到最长边不超过 maxEdge；已经够小则原样返回。 */
    public static Bitmap scaleMaxEdge(Bitmap src, int maxEdge) {
        int longEdge = Math.max(src.getWidth(), src.getHeight());
        if (longEdge <= maxEdge || maxEdge <= 0) return src;
        float ratio = maxEdge / (float) longEdge;
        int w = Math.max(1, Math.round(src.getWidth() * ratio));
        int h = Math.max(1, Math.round(src.getHeight() * ratio));
        Bitmap out = Bitmap.createScaledBitmap(src, w, h, true);
        if (out != src) src.recycle();
        return out;
    }

    /** 与 scaleMaxEdge 同义，但不回收入参，便于把副本单独存成缩略图。 */
    public static Bitmap scaledCopy(Bitmap src, int maxEdge) {
        int longEdge = Math.max(src.getWidth(), src.getHeight());
        if (longEdge <= maxEdge || maxEdge <= 0) return src;
        float ratio = maxEdge / (float) longEdge;
        return Bitmap.createScaledBitmap(src,
                Math.max(1, Math.round(src.getWidth() * ratio)),
                Math.max(1, Math.round(src.getHeight() * ratio)), true);
    }

    /** 旋转并返回新位图，不回收入参，便于边编辑边预览。 */
    public static Bitmap rotatedCopy(Bitmap src, int degrees) {
        Matrix m = new Matrix();
        m.postRotate(degrees);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
    }

    public static void saveJpeg(Bitmap bmp, File out, int quality) throws IOException {
        File parent = out.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建目录: " + parent);
        }
        try (OutputStream os = new FileOutputStream(out)) {
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, quality, os)) {
                throw new IOException("JPEG 编码失败: " + out);
            }
            os.flush();
        }
    }

    private static int exifOrientation(String path) {
        try {
            return new ExifInterface(path).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException e) {
            Log.w(TAG, "读取 EXIF 失败", e);
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    private static Bitmap applyOrientation(Bitmap src, int orientation) {
        Matrix m = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                m.postRotate(90);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                m.postRotate(180);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                m.postRotate(270);
                break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                m.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                m.postScale(1, -1);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                m.postRotate(90);
                m.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                m.postRotate(270);
                m.postScale(-1, 1);
                break;
            default:
                return src;
        }
        Bitmap out = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        if (out != src) src.recycle();
        return out;
    }
}
