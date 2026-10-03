package com.documentscanner.scanner.cv;

import android.util.Log;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档四角检测：Canny 边缘 + 轮廓多边形拟合为主，自适应阈值二值化为兜底。
 * 输入必须是屏幕正立方向的 CV_8UC1，输出角点与输入同坐标系、顺序为 TL/TR/BR/BL。
 */
public class DocDetector {

    private static final String TAG = "DocDetector";

    /** 实时帧的处理宽度：越小越快，480 在常见手机上单帧 <15ms。 */
    public static final int PROCESS_WIDTH_REALTIME = 480;
    /** 静帧（拍照/相册导入）用更大尺寸换取精度。 */
    public static final int PROCESS_WIDTH_STILL = 900;

    private static final double MIN_AREA_RATIO = 0.12;
    private static final double MIN_INTERIOR_ANGLE = 22;
    private static final double MAX_ASPECT = 4.0;
    private static final double[] EPSILONS = {0.018, 0.025, 0.032, 0.040, 0.050};
    private static final int MAX_CANDIDATES = 10;

    private final int processWidth;

    public DocDetector() {
        this(PROCESS_WIDTH_REALTIME);
    }

    public DocDetector(int processWidth) {
        this.processWidth = processWidth;
    }

    public Point[] detect(Mat gray) {
        if (!Cv.ensure() || gray == null || gray.empty()) return null;

        int origW = gray.cols();
        int origH = gray.rows();
        double scale = Math.min(1.0, processWidth / (double) origW);
        int workW = Math.max(16, (int) Math.round(origW * scale));
        int workH = Math.max(16, (int) Math.round(origH * scale));

        long started = System.nanoTime();
        Mat resized = new Mat();
        if (scale < 1.0) {
            Imgproc.resize(gray, resized, new Size(workW, workH), 0, 0, Imgproc.INTER_AREA);
        } else {
            gray.copyTo(resized);
        }

        Point[] quad = detectInWorkImage(resized);
        resized.release();

        if (quad == null) {
            Log.d(TAG, "未检出文档边框, 耗时 " + msSince(started) + "ms");
            return null;
        }

        Point[] full = new Point[4];
        for (int i = 0; i < 4; i++) {
            full[i] = new Point(quad[i].x / scale, quad[i].y / scale);
        }
        full = QuadGeometry.clamp(QuadGeometry.sortCorners(full), origW, origH, 1);
        Log.d(TAG, "检出边框, 耗时 " + msSince(started) + "ms");
        return full;
    }

    private Point[] detectInWorkImage(Mat gray) {
        int w = gray.cols();
        int h = gray.rows();
        double frameArea = (double) w * h;

        // 文档常常贴出画面边缘，补一圈边缘像素后轮廓才能闭合
        int pad = Math.max(2, (int) (Math.min(w, h) * 0.02));
        Mat padded = new Mat();
        Core.copyMakeBorder(gray, padded, pad, pad, pad, pad, Core.BORDER_REPLICATE, new Scalar(0));

        Point[] quad = detectByEdges(padded, frameArea);
        if (quad == null) {
            quad = detectByThreshold(padded, frameArea);
        }
        padded.release();

        if (quad == null) return null;
        Point[] shifted = new Point[4];
        for (int i = 0; i < 4; i++) {
            shifted[i] = new Point(quad[i].x - pad, quad[i].y - pad);
        }
        return shifted;
    }

    /** 主路径：双边滤波 + 自动 Canny + 闭运算 + 轮廓多边形拟合。 */
    private Point[] detectByEdges(Mat gray, double frameArea) {
        Mat blurred = new Mat();
        Mat edges = new Mat();
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
        try {
            Imgproc.bilateralFilter(gray, blurred, 7, 45, 45);

            double median = medianLevel(blurred);
            double lower = Math.max(0, median * (1 - 0.33));
            double upper = Math.min(255, median * (1 + 0.33));
            Imgproc.Canny(blurred, edges, lower, Math.max(upper, lower + 12));

            Imgproc.dilate(edges, edges, kernel, new Point(-1, -1), 2);
            Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel,
                    new Point(-1, -1), 2);

            return bestQuadOf(edges, frameArea, MIN_AREA_RATIO);
        } finally {
            blurred.release();
            edges.release();
            kernel.release();
        }
    }

    /** 兜底路径：弱纹理/低对比度场景下用局部阈值把纸张整体分离出来。 */
    private Point[] detectByThreshold(Mat gray, double frameArea) {
        Mat blurred = new Mat();
        Mat binary = new Mat();
        Mat closed = new Mat();
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(9, 9));
        try {
            Imgproc.GaussianBlur(gray, blurred, new Size(5, 5), 0);
            int blockSize = Math.max(9, (int) (Math.min(blurred.cols(), blurred.rows()) * 0.12) | 1);
            Imgproc.adaptiveThreshold(blurred, binary, 255,
                    Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, blockSize, 8);
            Imgproc.morphologyEx(binary, closed, Imgproc.MORPH_OPEN, kernel, new Point(-1, -1), 1);
            Imgproc.morphologyEx(closed, closed, Imgproc.MORPH_CLOSE, kernel, new Point(-1, -1), 3);
            return bestQuadOf(closed, frameArea, MIN_AREA_RATIO * 0.8);
        } finally {
            blurred.release();
            binary.release();
            closed.release();
            kernel.release();
        }
    }

    /** 从二值图中挑出面积最大且几何上合理的四边形。 */
    private Point[] bestQuadOf(Mat binary, double frameArea, double minAreaRatio) {
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Point[] best = null;
        double bestArea = 0;
        try {
            Imgproc.findContours(binary, contours, hierarchy,
                    Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);
            contours.sort((a, b) -> Double.compare(Imgproc.contourArea(b), Imgproc.contourArea(a)));

            int scanned = 0;
            for (MatOfPoint contour : contours) {
                if (scanned++ >= MAX_CANDIDATES) break;
                double area = Imgproc.contourArea(contour);
                if (area < minAreaRatio * frameArea) break; // 已按面积降序，后面只会更小
                Point[] quad = approximateQuad(contour);
                if (quad != null && area > bestArea) {
                    best = quad;
                    bestArea = area;
                }
            }
            return best;
        } finally {
            for (MatOfPoint c : contours) c.release();
            hierarchy.release();
        }
    }

    private Point[] approximateQuad(MatOfPoint contour) {
        Point[] raw = contour.toArray();
        if (raw.length < 4) return null;
        MatOfPoint2f curve = new MatOfPoint2f(raw);
        MatOfPoint2f approx = new MatOfPoint2f();
        try {
            double perimeter = Imgproc.arcLength(curve, true);
            for (double eps : EPSILONS) {
                Imgproc.approxPolyDP(curve, approx, eps * perimeter, true);
                Point[] pts = approx.toArray();
                if (pts.length != 4) continue;
                Point[] sorted = QuadGeometry.sortCorners(pts);
                if (isPlausibleDocument(sorted)) return sorted;
            }
            return null;
        } finally {
            curve.release();
            approx.release();
        }
    }

    private boolean isPlausibleDocument(Point[] quad) {
        if (quad == null || !QuadGeometry.isConvex(quad)) return false;
        if (QuadGeometry.minInteriorAngleDeg(quad) < MIN_INTERIOR_ANGLE) return false;
        double width = QuadGeometry.targetWidth(quad);
        double height = QuadGeometry.targetHeight(quad);
        if (width < 8 || height < 8) return false;
        double aspect = width / height;
        return aspect >= 1.0 / MAX_ASPECT && aspect <= MAX_ASPECT;
    }

    /** 亮度的中位数，用来自动决定 Canny 阈值（取直方图第 50 百分位）。 */
    private static double medianLevel(Mat gray) {
        int[] histogram = new int[256];
        int rows = gray.rows();
        int cols = gray.cols();
        byte[] line = new byte[cols];
        for (int y = 0; y < rows; y += 2) {
            gray.get(y, 0, line);
            for (int x = 0; x < cols; x += 2) {
                histogram[line[x] & 0xFF]++;
            }
        }
        int total = 0;
        for (int level : histogram) total += level;
        if (total == 0) return 128;
        int running = 0;
        for (int level = 0; level < 256; level++) {
            running += histogram[level];
            if (running * 2 >= total) return level;
        }
        return 128;
    }

    private static long msSince(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
