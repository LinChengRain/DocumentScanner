package com.documentscanner.scanner.cv;

import org.opencv.core.Point;

/**
 * 四边形（文档四角）几何工具。约定角点顺序恒为 左上、右上、右下、左下。
 */
public final class QuadGeometry {

    public static final int TOP_LEFT = 0;
    public static final int TOP_RIGHT = 1;
    public static final int BOTTOM_RIGHT = 2;
    public static final int BOTTOM_LEFT = 3;

    private static final double CORNER_EPSILON = 1e-3;

    private QuadGeometry() {
    }

    /** 按几何位置重排为 TL/TR/BR/BL，输入顺序任意。 */
    public static Point[] sortCorners(Point[] pts) {
        if (pts == null || pts.length != 4) return null;
        Point tl = pts[0], tr = pts[0], br = pts[0], bl = pts[0];
        for (Point p : pts) {
            double sum = p.x + p.y;
            double diff = p.x - p.y;
            if (sum < tl.x + tl.y) tl = p;
            if (sum > br.x + br.y) br = p;
            if (diff > tr.x - tr.y) tr = p;
            if (diff < bl.x - bl.y) bl = p;
        }
        return new Point[]{tl, tr, br, bl};
    }

    public static double area(Point[] q) {
        if (q == null || q.length != 4) return 0;
        double s = 0;
        for (int i = 0; i < 4; i++) {
            Point a = q[i];
            Point b = q[(i + 1) % 4];
            s += a.x * b.y - b.x * a.y;
        }
        return Math.abs(s) / 2.0;
    }

    public static double distance(Point a, Point b) {
        return Math.hypot(a.x - b.x, a.y - b.y);
    }

    public static Point[] copy(Point[] q) {
        Point[] out = new Point[q.length];
        for (int i = 0; i < q.length; i++) out[i] = new Point(q[i].x, q[i].y);
        return out;
    }

    /** 把角点约束到图像范围内，并保证留出一丝余量避免贴边被判为退化四边形。 */
    public static Point[] clamp(Point[] q, int width, int height, double margin) {
        Point[] out = copy(q);
        for (Point p : out) {
            p.x = Math.max(margin, Math.min(width - 1 - margin, p.x));
            p.y = Math.max(margin, Math.min(height - 1 - margin, p.y));
        }
        return out;
    }

    /** 对角线长度，用作「相对位移」归一化基准。 */
    public static double diagonal(Point[] q) {
        return Math.max(distance(q[TOP_LEFT], q[BOTTOM_RIGHT]),
                distance(q[TOP_RIGHT], q[BOTTOM_LEFT]));
    }

    public static double maxCornerDelta(Point[] a, Point[] b) {
        double max = 0;
        for (int i = 0; i < 4; i++) {
            max = Math.max(max, distance(a[i], b[i]));
        }
        return max;
    }

    /** 指数平滑，抑制实时边框抖动。 */
    public static Point[] smooth(Point[] previous, Point[] current, double alpha) {
        if (previous == null) return copy(current);
        Point[] out = new Point[4];
        for (int i = 0; i < 4; i++) {
            out[i] = new Point(
                    previous[i].x * (1 - alpha) + current[i].x * alpha,
                    previous[i].y * (1 - alpha) + current[i].y * alpha);
        }
        return out;
    }

    public static double edgeLength(Point[] q, int from, int to) {
        return distance(q[from], q[to]);
    }

    /** 顶边/底边平均长度与左边/右边平均长度，即矫正后目标矩形的尺寸。 */
    public static double targetWidth(Point[] q) {
        return (edgeLength(q, TOP_LEFT, TOP_RIGHT) + edgeLength(q, BOTTOM_LEFT, BOTTOM_RIGHT)) / 2.0;
    }

    public static double targetHeight(Point[] q) {
        return (edgeLength(q, TOP_LEFT, BOTTOM_LEFT) + edgeLength(q, TOP_RIGHT, BOTTOM_RIGHT)) / 2.0;
    }

    /** 最小内角，用于剔除细长条/退化四边形这类误检。 */
    public static double minInteriorAngleDeg(Point[] q) {
        double min = 180;
        for (int i = 0; i < 4; i++) {
            Point prev = q[(i + 3) % 4];
            Point cur = q[i];
            Point next = q[(i + 1) % 4];
            min = Math.min(min, angleDeg(prev, cur, next));
        }
        return min;
    }

    private static double angleDeg(Point a, Point vertex, Point b) {
        double v1x = a.x - vertex.x, v1y = a.y - vertex.y;
        double v2x = b.x - vertex.x, v2y = b.y - vertex.y;
        double dot = v1x * v2x + v1y * v2y;
        double n = Math.hypot(v1x, v1y) * Math.hypot(v2x, v2y);
        if (n < CORNER_EPSILON) return 0;
        double cos = Math.max(-1, Math.min(1, dot / n));
        return Math.toDegrees(Math.acos(cos));
    }

    public static boolean isConvex(Point[] q) {
        int sign = 0;
        for (int i = 0; i < 4; i++) {
            double ax = q[(i + 1) % 4].x - q[i].x;
            double ay = q[(i + 1) % 4].y - q[i].y;
            double bx = q[(i + 2) % 4].x - q[(i + 1) % 4].x;
            double by = q[(i + 2) % 4].y - q[(i + 1) % 4].y;
            double cross = ax * by - ay * bx;
            if (Math.abs(cross) < CORNER_EPSILON) continue;
            int s = cross > 0 ? 1 : -1;
            if (sign == 0) sign = s;
            else if (sign != s) return false;
        }
        return true;
    }

    /** 整帧内缩得到的默认选区，检测失败时兜底。 */
    public static Point[] insetFrame(int width, int height, double insetRatio) {
        double mx = width * insetRatio;
        double my = height * insetRatio;
        return new Point[]{
                new Point(mx, my),
                new Point(width - 1 - mx, my),
                new Point(width - 1 - mx, height - 1 - my),
                new Point(mx, height - 1 - my)
        };
    }

    /** 图像顺时针旋转 90° 后，角点坐标跟着变换（连续坐标，忽略 1px 偏移）。 */
    public static Point[] rotate90Clockwise(Point[] q, int oldWidth, int oldHeight) {
        Point[] out = new Point[q.length];
        for (int i = 0; i < q.length; i++) {
            out[i] = new Point(oldHeight - q[i].y, q[i].x);
        }
        return sortCorners(out);
    }

    /**
     * 归一化到 0..1，让选区与具体分辨率无关：
     * 编辑时用的是预览尺寸，渲染时用的是导出尺寸，靠这一层衔接。
     * 返回 8 个值：TL.x, TL.y, TR.x, TR.y, BR.x, BR.y, BL.x, BL.y
     */
    public static float[] normalize(Point[] q, int width, int height) {
        float[] out = new float[8];
        for (int i = 0; i < 4; i++) {
            out[i * 2] = (float) (q[i].x / Math.max(1, width));
            out[i * 2 + 1] = (float) (q[i].y / Math.max(1, height));
        }
        return out;
    }

    public static Point[] denormalize(float[] normalized, int width, int height) {
        if (normalized == null || normalized.length != 8) return null;
        Point[] out = new Point[4];
        for (int i = 0; i < 4; i++) {
            out[i] = new Point(normalized[i * 2] * width, normalized[i * 2 + 1] * height);
        }
        return sortCorners(out);
    }

    /** 手动调整选区的下限：面积不足画面 1%、内角小于 8° 就不再是可用的四边形。 */
    private static final double MIN_USABLE_AREA_RATIO = 0.01;
    private static final double MIN_USABLE_ANGLE_DEG = 8.0;

    /** 把手动拖动的角点重排成约定顺序并约束到画面内，交叉拖拽在这里被还原成简单四边形。 */
    public static Point[] sanitize(Point[] q, int width, int height) {
        if (q == null || q.length != 4) return null;
        return clamp(sortCorners(copy(q)), width, height, 1.0);
    }

    /**
     * 选区是否还能拿去做透视变换。凹形说明角点被拖成交叉，变换结果会与用户在画面上
     * 看到的选区不一致，宁可由调用方提示重拖。
     */
    public static boolean isUsableQuad(Point[] q, int width, int height) {
        if (q == null || q.length != 4) return false;
        return isConvex(q)
                && area(q) >= (double) width * height * MIN_USABLE_AREA_RATIO
                && minInteriorAngleDeg(q) >= MIN_USABLE_ANGLE_DEG;
    }

    /** 归一化坐标下顺时针旋转 90°：(x, y) -> (1 - y, x)。 */
    public static float[] rotateNormalized90Clockwise(float[] normalized) {
        float[] out = new float[8];
        for (int i = 0; i < 4; i++) {
            out[i * 2] = 1f - normalized[i * 2 + 1];
            out[i * 2 + 1] = normalized[i * 2];
        }
        return out;
    }

    public static boolean isValidNormalized(float[] normalized) {
        if (normalized == null || normalized.length != 8) return false;
        for (float value : normalized) {
            if (value < -0.01f || value > 1.01f) return false;
        }
        return true;
    }
}
