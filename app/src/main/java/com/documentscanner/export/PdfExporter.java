package com.documentscanner.export;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * 用框架自带的 PdfDocument 合成多页 PDF，不引入第三方库。
 * 逐页解码后立即回收，峰值内存只与单页有关。
 */
public final class PdfExporter {

    private static final String TAG = "PdfExporter";
    private static final int DECODE_EDGE = 1600;

    private PdfExporter() {
    }

    public interface Progress {
        void onPage(int index, int total);
    }

    /**
     * @param pageImages 已矫正并加滤镜的页面图片，顺序即 PDF 页序
     * @param paper      页面尺寸策略，见 {@link PaperSize}
     * @return 写出的 PDF 文件
     */
    public static File export(List<File> pageImages, File target, PaperSize paper,
                              Progress progress) throws IOException {
        if (pageImages == null || pageImages.isEmpty()) {
            throw new IOException("没有可导出的页面");
        }
        if (paper == null) paper = PaperSize.FOLLOW_IMAGE;
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建目录 " + parent);
        }

        PdfDocument document = new PdfDocument();
        int written = 0;
        try {
            for (int i = 0; i < pageImages.size(); i++) {
                File image = pageImages.get(i);
                if (!image.exists()) {
                    Log.w(TAG, "跳过缺失页面 " + image);
                    continue;
                }
                if (progress != null) progress.onPage(i, pageImages.size());

                Bitmap bitmap = decode(image, DECODE_EDGE);
                if (bitmap == null) {
                    Log.w(TAG, "跳过无法解码的页面 " + image);
                    continue;
                }
                try {
                    PdfDocument.PageInfo info = paper.pageInfoFor(bitmap, written + 1);
                    PdfDocument.Page page = document.startPage(info);
                    Canvas canvas = page.getCanvas();
                    canvas.drawColor(android.graphics.Color.WHITE);
                    canvas.drawBitmap(bitmap, null, paper.contentRectFor(bitmap, info),
                            new Paint(Paint.FILTER_BITMAP_FLAG));
                    document.finishPage(page);
                    written++;
                } finally {
                    bitmap.recycle();
                }
            }
        } catch (RuntimeException e) {
            document.close();
            throw new IOException("生成 PDF 失败", e);
        }

        if (written == 0) {
            document.close();
            throw new IOException("所有页面都无法处理");
        }

        try (OutputStream out = new FileOutputStream(target)) {
            document.writeTo(out);
            out.flush();
        } finally {
            document.close();
        }
        if (!target.exists() || target.length() == 0) {
            throw new IOException("PDF 写入为空");
        }
        Log.i(TAG, "已导出 " + written + " 页 PDF: " + target);
        return target;
    }

    private static Bitmap decode(File file, int maxEdge) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inSampleSize = com.documentscanner.util.ImageIO.sampleSizeFor(
                bounds.outWidth, bounds.outHeight, maxEdge);
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }
}
