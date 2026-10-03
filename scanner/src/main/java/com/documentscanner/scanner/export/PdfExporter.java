package com.documentscanner.scanner.export;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.util.Log;

import com.documentscanner.scanner.cv.ImageBudget;
import com.documentscanner.scanner.util.ImageIO;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * 用框架自带的 PdfDocument 合成多页 PDF，不引入第三方库。
 * 逐页解码后立即回收，峰值内存只与单页有关。
 *
 * <p>体积为什么由像素数决定而不是由页面 JPEG 的字节决定：框架只会看到我们画上去的位图，
 * 它按接近无损的质量重编码（本机实测膨胀 2.25~7.5 倍），所以页面 JPEG 存得多小都不影响
 * PDF 的字节——唯一的杠杆是交进去多少像素。
 */
public final class PdfExporter {

    private static final String TAG = "PdfExporter";

    private PdfExporter() {
    }

    public interface Progress {
        void onPage(int index, int total);
    }

    /**
     * 成品，连带「它是怎么写出来的」。
     *
     * <p>后三个字段不是给界面用的，是给用例用的：光看文件字节没法区分「按目标缩过」和
     * 「碰巧第一枪就够小」，而这两件事正是这个类全部的难度所在。
     */
    public static final class Outcome {
        public final File pdf;
        /** 最终那一枪的解码长边；连续值，不是四档之一。 */
        public final int decodeEdge;
        public final long bytes;
        /** 写了几轮，1 表示目标本来就装得进去。 */
        public final int writes;

        Outcome(File pdf, int decodeEdge, long bytes, int writes) {
            this.pdf = pdf;
            this.decodeEdge = decodeEdge;
            this.bytes = bytes;
            this.writes = writes;
        }
    }

    /**
     * @param pageImages 已矫正并加滤镜的页面图片，顺序即 PDF 页序
     * @param paper      页面尺寸策略，见 {@link PaperSize}
     * @param budget     体积档，决定每页解码到多长；一次导出取一次，别逐页现读
     * @return 写出的 PDF 文件
     */
    public static File export(List<File> pageImages, File target, PaperSize paper,
                              ImageBudget budget, Progress progress) throws IOException {
        if (budget == null) budget = ImageBudget.DEFAULT;
        writeAt(pageImages, target, paper, budget.pdfDecodeEdge, progress);
        return target;
    }

    /**
     * 带体积目标的导出：按 {@code ceiling} 档先写一份，超出 {@code maxBytes} 就缩边重导，
     * 直到装进去或落到 {@link PdfSizeFit#MIN_DECODE_EDGE}。
     *
     * <p>目标针对<b>整份 PDF</b>，不是每页——页数由调用方决定，宿主想要「每页 500KB」
     * 就把目标乘以页数，反过来模块不该猜这次导出有几页值得预留。
     *
     * <p>只缩不升：{@code ceiling} 是这次允许的最大边，目标再宽松也不会越过它出更大的成品。
     * 每一枪降多少由 {@link PdfSizeFit.Search} 按上一枪的实测斜率现算——先验对真文档乐观一倍，
     * 只按先验走会四轮打完还在目标外面。
     *
     * <p>目标够不着时（页多、目标小）交最小那份<b>而不是报错</b>：一份超标的扫描件只是大了点，
     * 一份「因为装不进 500KB 所以没生成」的扫描件是丢了。超出多少进日志。
     *
     * <p>每一轮都覆写同一个 {@code target}：调用方给的要么是按 {@code ExportNames.unique}
     * 挑出的新名字，要么是它自己的临时名（PDF 预览页重生成走的就是后者），所以覆写不会砸到成品。
     *
     * @param maxBytes 目标字节，0 或负数表示不设目标——此时与 {@link #export} 逐字节相同
     */
    public static Outcome exportFitting(List<File> pageImages, File target, PaperSize paper,
                                        ImageBudget ceiling, long maxBytes,
                                        Progress progress) throws IOException {
        if (ceiling == null) ceiling = ImageBudget.DEFAULT;
        PdfSizeFit.Search search = new PdfSizeFit.Search();
        int edge = ceiling.pdfDecodeEdge;
        for (int write = 1; ; write++) {
            writeAt(pageImages, target, paper, edge, progress);
            long bytes = target.length();
            if (PdfSizeFit.fits(bytes, maxBytes)) {
                if (write > 1) {
                    Log.i(TAG, "按体积目标 " + maxBytes + " B 缩到长边 " + edge + " 后 "
                            + bytes + " B，共写 " + write + " 轮");
                }
                return new Outcome(target, edge, bytes, write);
            }
            int next = search.nextEdge(edge, bytes, maxBytes);
            if (next == 0 || write >= PdfSizeFit.MAX_ATTEMPTS) {
                Log.w(TAG, "目标 " + maxBytes + " B 装不下：" + bytes + " B 已是长边 "
                        + edge + " 的极限，交这份");
                return new Outcome(target, edge, bytes, write);
            }
            Log.i(TAG, "PDF " + bytes + " B 超出目标 " + maxBytes + " B，第 " + (write + 1)
                    + " 轮把解码长边降到 " + next);
            edge = next;
        }
    }

    /** 按给定解码长边写一份，返回写进去的页数。 */
    private static void writeAt(List<File> pageImages, File target, PaperSize paper,
                                int maxEdge, Progress progress) throws IOException {
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

                Bitmap bitmap = decode(image, maxEdge);
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
    }

    /**
     * 先按 2 的幂次粗降采样，再精确缩到目标长边。
     * 只做前者是不行的：它是向下取整的 2 的幂，1800 的页面配 1600 的上限会原样通过，
     * 于是「调小」这一步在多数档位上根本不产生作用。
     */
    private static Bitmap decode(File file, int maxEdge) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inSampleSize = ImageIO.sampleSizeFor(
                bounds.outWidth, bounds.outHeight, maxEdge);
        Bitmap decoded = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        return decoded == null ? null : ImageIO.scaleMaxEdge(decoded, maxEdge);
    }
}
