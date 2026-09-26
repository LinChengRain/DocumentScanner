package com.documentscanner.cv;

import android.graphics.Bitmap;

import org.opencv.core.Mat;
import org.opencv.core.Point;

/** Bitmap 级别的处理入口，全部为阻塞调用，必须放在后台线程执行。 */
public final class DocPipeline {

    /** 送去检测前把原图降采样到此长边：再大对四边形精度无收益。 */
    public static final int EDGE_DETECT_SOURCE = 1500;
    /** 矫正结果的长边上限，兼顾清晰度与内存。 */
    public static final int EDGE_OUTPUT = 1800;
    /** 缩略图长边。 */
    public static final int EDGE_THUMB = 300;
    public static final int JPEG_QUALITY = 92;
    public static final int JPEG_QUALITY_THUMB = 80;

    private DocPipeline() {
    }

    /** 检测位图中的文档四角，坐标与传入位图一致；未检出返回 null。 */
    public static Point[] detectQuad(Bitmap bitmap, boolean still) {
        if (!Cv.ensure() || bitmap == null) return null;
        Mat rgba = CvImage.rgba(bitmap);
        Mat bgr = CvImage.rgbaToBgr(rgba);
        Mat gray = CvImage.bgrToGray(bgr);
        Point[] quad;
        try {
            quad = new DocDetector(still ? DocDetector.PROCESS_WIDTH_STILL
                    : DocDetector.PROCESS_WIDTH_REALTIME).detect(gray);
        } finally {
            rgba.release();
            bgr.release();
            gray.release();
        }
        return quad;
    }

    /** 透视矫正，返回未加滤镜的正立文档位图。 */
    public static Bitmap warp(Bitmap source, Point[] quad, int maxEdge) {
        if (!Cv.ensure() || source == null) return source;
        Mat rgba = CvImage.rgba(source);
        Mat bgr = CvImage.rgbaToBgr(rgba);
        Mat warped = PerspectiveCorrector.warp(bgr, quad, maxEdge);
        Mat warpedRgba = CvImage.bgrToRgba(warped);
        Bitmap out = CvImage.toBitmap(warpedRgba);
        rgba.release();
        bgr.release();
        warped.release();
        warpedRgba.release();
        return out;
    }

    /** 对位图施加滤镜。 */
    public static Bitmap applyFilter(Bitmap source, FilterType type) {
        if (!Cv.ensure() || source == null || type == null) return source;
        Mat rgba = CvImage.rgba(source);
        Mat bgr = CvImage.rgbaToBgr(rgba);
        Mat filtered = DocFilter.apply(bgr, type);
        Mat filteredRgba = CvImage.bgrToRgba(filtered);
        Bitmap out = CvImage.toBitmap(filteredRgba);
        rgba.release();
        bgr.release();
        filtered.release();
        filteredRgba.release();
        return out;
    }
}
