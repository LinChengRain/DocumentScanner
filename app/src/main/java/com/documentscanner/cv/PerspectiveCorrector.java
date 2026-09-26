package com.documentscanner.cv;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/** 四点透视矫正：把任意四边形拉成正立矩形。 */
public final class PerspectiveCorrector {

    /** 输出长边小于该值时适度放大，保证放进 PDF 后仍有可用分辨率。 */
    private static final int MIN_OUTPUT_EDGE = 1000;

    private PerspectiveCorrector() {
    }

    /**
     * @param src           源图（CV_8UC3 或 CV_8UC4），quad 使用与 src 相同的像素坐标
     * @param quad          已按 TL/TR/BR/BL 排序的四角
     * @param maxOutputEdge 输出长边上限，控制内存占用
     * @return 新建的矫正结果，调用方负责 release
     */
    public static Mat warp(Mat src, Point[] quad, int maxOutputEdge) {
        if (!Cv.ensure() || src == null || src.empty()) {
            return src == null ? new Mat() : src.clone();
        }
        Point[] q = QuadGeometry.sortCorners(quad);
        double rawW = QuadGeometry.targetWidth(q);
        double rawH = QuadGeometry.targetHeight(q);
        if (rawW < 4 || rawH < 4) return src.clone();

        double factor = 1.0;
        double longEdge = Math.max(rawW, rawH);
        if (maxOutputEdge > 0 && longEdge > maxOutputEdge) {
            factor = maxOutputEdge / longEdge;
        } else if (longEdge < MIN_OUTPUT_EDGE) {
            factor = MIN_OUTPUT_EDGE / longEdge;
        }

        int outW = Math.max(2, (int) Math.round(rawW * factor));
        int outH = Math.max(2, (int) Math.round(rawH * factor));

        Mat sourcePoints = new MatOfPoint2f(
                q[QuadGeometry.TOP_LEFT], q[QuadGeometry.TOP_RIGHT],
                q[QuadGeometry.BOTTOM_RIGHT], q[QuadGeometry.BOTTOM_LEFT]);
        Mat targetPoints = new MatOfPoint2f(
                new Point(0, 0), new Point(outW - 1, 0),
                new Point(outW - 1, outH - 1), new Point(0, outH - 1));
        Mat transform = Imgproc.getPerspectiveTransform(sourcePoints, targetPoints);
        Mat dst = new Mat();
        try {
            Imgproc.warpPerspective(src, dst, transform, new Size(outW, outH),
                    Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE);
        } finally {
            sourcePoints.release();
            targetPoints.release();
            transform.release();
        }
        return dst;
    }
}
