package com.documentscanner.scanner.cv;

import android.graphics.Bitmap;
import android.util.Log;

import androidx.camera.core.ImageProxy;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;

/** CameraX 帧 / Android 位图 与 OpenCV Mat 之间的转换。 */
public final class CvImage {

    private static final String TAG = "CvImage";

    private CvImage() {
    }

    /**
     * 取出亮度平面并旋转到屏幕方向，返回 CV_8UC1。
     * 边框检测只需要亮度信息，跳过色度转换能显著降低每帧开销。
     */
    public static Mat grayFrom(ImageProxy image, int rotationDegrees) {
        ImageProxy.PlaneProxy yPlane = image.getPlanes()[0];
        Mat gray = extractPlane(yPlane.getBuffer(), yPlane.getRowStride(),
                yPlane.getPixelStride(), image.getWidth(), image.getHeight());
        Mat rotated = rotateToDisplay(gray, rotationDegrees);
        if (rotated != gray) {
            gray.release();
        }
        return rotated;
    }

    /** CameraX 的 rotationDegrees 语义是「顺时针旋转多少度才能正立显示」。 */
    public static Mat rotateToDisplay(Mat src, int rotationDegrees) {
        switch (((rotationDegrees % 360) + 360) % 360) {
            case 90:
                return rotate(src, Core.ROTATE_90_CLOCKWISE);
            case 180:
                return rotate(src, Core.ROTATE_180);
            case 270:
                return rotate(src, Core.ROTATE_90_COUNTERCLOCKWISE);
            default:
                return src;
        }
    }

    private static Mat rotate(Mat src, int code) {
        Mat dst = new Mat();
        Core.rotate(src, dst, code);
        return dst;
    }

    /** 按 rowStride / pixelStride 把平面压缩成连续内存，返回 CV_8UC1。 */
    private static Mat extractPlane(ByteBuffer buffer, int rowStride, int pixelStride,
                                    int width, int height) {
        ByteBuffer src = buffer.duplicate();
        byte[] all = new byte[src.remaining()];
        src.get(all);

        int usableRows = Math.max(1, Math.min(height, all.length / Math.max(1, rowStride)));
        if (usableRows < height) {
            Log.w(TAG, "亮度平面不足整帧: " + usableRows + " < " + height);
        }

        byte[] packed = new byte[width * usableRows];
        for (int row = 0; row < usableRows; row++) {
            int from = row * rowStride;
            if (pixelStride == 1) {
                int len = Math.min(width, all.length - from);
                if (len <= 0) break;
                System.arraycopy(all, from, packed, row * width, len);
            } else {
                for (int col = 0; col < width; col++) {
                    int idx = from + col * pixelStride;
                    if (idx >= all.length) break;
                    packed[row * width + col] = all[idx];
                }
            }
        }

        Mat mat = new Mat(usableRows, width, CvType.CV_8UC1);
        mat.put(0, 0, packed);
        return mat;
    }

    /** 位图 -> CV_8UC4（RGBA 通道序）。 */
    public static Mat rgba(Bitmap bitmap) {
        Mat rgba = new Mat(bitmap.getHeight(), bitmap.getWidth(), CvType.CV_8UC4, new Scalar(0, 0, 0, 255));
        Utils.bitmapToMat(bitmap, rgba, true);
        return rgba;
    }

    /** CV_8UC4（RGBA）-> ARGB_8888 位图。 */
    public static Bitmap toBitmap(Mat rgba) {
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, rgba.cols()), Math.max(1, rgba.rows()),
                Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(rgba, bitmap, true);
        return bitmap;
    }

    public static Mat rgbaToBgr(Mat rgba) {
        Mat bgr = new Mat();
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR);
        return bgr;
    }

    public static Mat bgrToRgba(Mat bgr) {
        Mat rgba = new Mat();
        Imgproc.cvtColor(bgr, rgba, Imgproc.COLOR_BGR2RGBA);
        return rgba;
    }

    public static Mat bgrToGray(Mat bgr) {
        Mat gray = new Mat();
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY);
        return gray;
    }

    public static Mat grayToBgr(Mat gray) {
        Mat bgr = new Mat();
        Imgproc.cvtColor(gray, bgr, Imgproc.COLOR_GRAY2BGR);
        return bgr;
    }
}
