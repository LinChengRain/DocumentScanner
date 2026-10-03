package com.documentscanner.scanner.cv;

import android.graphics.Bitmap;
import android.util.Log;

import com.documentscanner.scanner.model.ScanPage;
import com.documentscanner.scanner.util.ImageIO;
import com.documentscanner.scanner.util.Io;

import org.opencv.core.Point;

import java.io.File;
import java.io.IOException;

/**
 * 「确认裁剪」的落盘流程：original（照片）→ warp（透视矫正，无滤镜）→ result（warp + 滤镜），
 * 另存一份 thumb 给列表用。分开保存 warp 的意义在于：换滤镜只要重跑滤镜这一步，
 * 不必再解码原始大图，交互上才是「点一下就出效果」的手感。
 *
 * 包名已经是模块的，但类还住在 :app：库模块不能反向依赖宿主，而这里要用的 model.ScanPage
 * 还没搬。B3 搬 model 时这个类一起走。
 */
public final class PageRenderer {

    private static final String TAG = "PageRenderer";

    public interface Callback {
        /** 主线程回调；error 为 null 表示成功。 */
        void onFinished(Throwable error);
    }

    public interface BitmapCallback {
        /**
         * 主线程回调。返回的位图归调用方回收；若与传入的 base 是同一实例则不要回收。
         */
        void onBitmap(Bitmap preview, Throwable error);
    }

    public interface QuadCallback {
        /** 主线程回调，四角坐标与传入位图一致；未检出时为 null。 */
        void onQuad(Point[] quad);
    }

    private PageRenderer() {
    }

    /**
     * @param corners 相对 original 正立显示尺寸的归一化四角；传 null 表示自动检测
     * @param budget  本次渲染用的体积档；由调用点在动作开始时取一次，一路传下来——
     *                正在渲染的页面不该因为用户中途改了设置就换尺寸
     */
    public static void render(ScanPage page, float[] corners, ImageBudget budget,
                              Callback callback) {
        Io.bg(() -> {
            Bitmap source = null;
            Bitmap warped = null;
            Bitmap filtered = null;
            float[] normalized = null;
            Throwable failure = null;
            try {
                if (!Cv.ensure()) throw new IOException("OpenCV 尚未就绪，请稍后重试");
                source = ImageIO.load(page.original, budget.sourceDecodeEdge);
                Point[] quad = corners == null
                        ? DocPipeline.detectQuad(source, true)
                        : QuadGeometry.denormalize(corners, source.getWidth(), source.getHeight());
                if (quad == null) {
                    quad = QuadGeometry.insetFrame(source.getWidth(), source.getHeight(), 0.02);
                }
                warped = DocPipeline.warp(source, quad, budget.outputEdge);
                ImageIO.saveJpeg(warped, page.warp, budget.jpegQuality);

                filtered = DocPipeline.applyFilter(warped, page.filter);
                ImageIO.saveJpeg(filtered, page.result, budget.jpegQuality);
                Bitmap thumb = thumbnailOf(filtered);
                ImageIO.saveJpeg(thumb, page.thumb, DocPipeline.JPEG_QUALITY_THUMB);
                if (thumb != filtered) thumb.recycle();

                normalized = QuadGeometry.normalize(quad, source.getWidth(), source.getHeight());
            } catch (Throwable t) {
                Log.e(TAG, "页面渲染失败", t);
                failure = t;
            } finally {
                recycle(source);
                recycle(warped);
                recycle(filtered);
            }
            // 页面索引只在主线程改，避免与列表的增删并发
            if (normalized != null) {
                final float[] quad = normalized;
                Io.main(() -> {
                    page.quad = quad;
                    page.edited = true;
                    post(callback, null);
                });
            } else {
                post(callback, failure);
            }
        });
    }

    /** 只换滤镜：复用已存的 warp 文件，成本远低于重新渲染。 */
    public static void refilter(ScanPage page, ImageBudget budget, Callback callback) {
        Io.bg(() -> {
            if (!page.hasWarp()) {
                render(page, page.quad, budget, callback);
                return;
            }
            Bitmap warped = null;
            Bitmap filtered = null;
            try {
                if (!Cv.ensure()) throw new IOException("OpenCV 尚未就绪，请稍后重试");
                warped = ImageIO.load(page.warp, budget.outputEdge);
                filtered = DocPipeline.applyFilter(warped, page.filter);
                ImageIO.saveJpeg(filtered, page.result, budget.jpegQuality);
                Bitmap thumb = thumbnailOf(filtered);
                ImageIO.saveJpeg(thumb, page.thumb, DocPipeline.JPEG_QUALITY_THUMB);
                if (thumb != filtered) thumb.recycle();
                post(callback, null);
            } catch (Throwable t) {
                Log.e(TAG, "重新应用滤镜失败", t);
                post(callback, t);
            } finally {
                recycle(warped);
                recycle(filtered);
            }
        });
    }

    /** 批量重跑多页滤镜（「应用到全部」），全部完成后只回调一次。 */
    public static void refilterAll(java.util.List<ScanPage> pages, ImageBudget budget,
                                   Callback callback) {
        Io.bg(() -> {
            Throwable failure = null;
            for (ScanPage page : new java.util.ArrayList<>(pages)) {
                Bitmap warped = null;
                Bitmap filtered = null;
                try {
                    if (!Cv.ensure()) throw new IOException("OpenCV 尚未就绪，请稍后重试");
                    if (!page.hasWarp()) continue;
                    warped = ImageIO.load(page.warp, budget.outputEdge);
                    filtered = DocPipeline.applyFilter(warped, page.filter);
                    ImageIO.saveJpeg(filtered, page.result, budget.jpegQuality);
                    Bitmap thumb = thumbnailOf(filtered);
                    ImageIO.saveJpeg(thumb, page.thumb, DocPipeline.JPEG_QUALITY_THUMB);
                    if (thumb != filtered) thumb.recycle();
                } catch (Throwable t) {
                    Log.e(TAG, "批量应用滤镜失败", t);
                    failure = t;
                } finally {
                    recycle(warped);
                    recycle(filtered);
                }
            }
            post(callback, failure);
        });
    }

    /** 在后台对位图施加滤镜，用于滤镜条的即时预览。 */
    public static void filterPreview(Bitmap base, FilterType type, BitmapCallback callback) {
        Io.bg(() -> {
            Bitmap preview = null;
            try {
                if (!Cv.ensure()) throw new IOException("OpenCV 尚未就绪");
                preview = DocPipeline.applyFilter(base, type);
                final Bitmap out = preview;
                Io.main(() -> callback.onBitmap(out, null));
            } catch (Throwable t) {
                recycle(preview);
                Io.main(() -> callback.onBitmap(null, t));
            }
        });
    }

    /** 在后台检测文档四角（相册导入 / 重新检测）。 */
    public static void detectQuad(Bitmap source, QuadCallback callback) {
        Io.bg(() -> {
            Point[] quad = null;
            try {
                if (Cv.ensure()) quad = DocPipeline.detectQuad(source, true);
            } catch (Throwable t) {
                Log.e(TAG, "边框检测失败", t);
            }
            final Point[] result = quad;
            Io.main(() -> callback.onQuad(result));
        });
    }

    private static Bitmap thumbnailOf(Bitmap source) {
        int longEdge = Math.max(source.getWidth(), source.getHeight());
        if (longEdge <= DocPipeline.EDGE_THUMB) return source;
        float ratio = DocPipeline.EDGE_THUMB / (float) longEdge;
        return Bitmap.createScaledBitmap(source,
                Math.max(1, Math.round(source.getWidth() * ratio)),
                Math.max(1, Math.round(source.getHeight() * ratio)), true);
    }

    private static void post(Callback callback, Throwable error) {
        if (callback != null) Io.main(() -> callback.onFinished(error));
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }
}
