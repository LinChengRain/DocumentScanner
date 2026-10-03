package com.documentscanner.scanner.camera;

import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import com.documentscanner.scanner.cv.Cv;
import com.documentscanner.scanner.cv.CvImage;
import com.documentscanner.scanner.cv.DocDetector;
import com.documentscanner.scanner.cv.QuadGeometry;
import com.documentscanner.scanner.util.Io;

import org.opencv.core.Mat;
import org.opencv.core.Point;

/**
 * 逐帧文档边框检测。在 CameraX 的分析线程上执行，结果回抛到主线程。
 * 通过「跳帧 + 角点指数平滑 + 连续稳定帧计数」把抖动压掉并给出自动拍摄信号。
 */
public class LiveDocAnalyzer implements ImageAnalysis.Analyzer {

    public interface Callback {
        /**
         * @param quad        屏幕方向帧坐标系下的四角，null 表示本帧未检出
         * @param imageWidth  旋转后帧宽，用于把坐标映射到预览视图
         * @param imageHeight 旋转后帧高
         * @param stable      边框是否已连续稳定（自动拍摄信号）
         */
        void onDetected(Point[] quad, int imageWidth, int imageHeight, boolean stable);
    }

    private static final String TAG = "LiveDocAnalyzer";
    private static final long MIN_INTERVAL_MS = 55;
    private static final int STABLE_FRAMES_NEEDED = 6;
    /** 相邻两帧角点最大位移低于对角线的该比例即视为「没动」。 */
    private static final double STABLE_MOVE_RATIO = 0.012;
    private static final double SMOOTH_ALPHA = 0.55;

    private final DocDetector detector = new DocDetector();
    private final Callback callback;

    private long lastRunAt;
    private Point[] smoothed;
    private int stableFrames;
    private int lastWidth;
    private int lastHeight;
    private volatile boolean paused;

    public LiveDocAnalyzer(Callback callback) {
        this.callback = callback;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    /** 自动拍摄后调用，避免同一份文档被连续触发。 */
    public void resetStability() {
        stableFrames = 0;
        smoothed = null;
    }

    @Override
    public void analyze(@NonNull ImageProxy image) {
        try {
            long now = SystemClock.uptimeMillis();
            if (paused || !Cv.ensure() || now - lastRunAt < MIN_INTERVAL_MS) {
                return;
            }
            lastRunAt = now;

            int rotation = image.getImageInfo().getRotationDegrees();
            Mat gray = CvImage.grayFrom(image, rotation);
            Point[] quad = detector.detect(gray);
            int width = gray.cols();
            int height = gray.rows();
            gray.release();

            publish(quad, width, height);
        } catch (Throwable t) {
            Log.e(TAG, "分析帧失败", t);
        } finally {
            image.close();
        }
    }

    private void publish(Point[] quad, int width, int height) {
        if (width != lastWidth || height != lastHeight) {
            lastWidth = width;
            lastHeight = height;
            smoothed = null;
            stableFrames = 0;
        }

        boolean stable = false;
        Point[] output = null;
        if (quad != null) {
            if (smoothed != null) {
                double delta = QuadGeometry.maxCornerDelta(smoothed, quad);
                if (delta < QuadGeometry.diagonal(smoothed) * STABLE_MOVE_RATIO) {
                    stableFrames++;
                } else {
                    stableFrames = 0;
                }
                smoothed = QuadGeometry.smooth(smoothed, quad, SMOOTH_ALPHA);
            } else {
                smoothed = QuadGeometry.copy(quad);
            }
            stable = stableFrames >= STABLE_FRAMES_NEEDED;
            output = smoothed;
        } else {
            stableFrames = 0;
            smoothed = null;
        }

        final Point[] finalQuad = output;
        final boolean finalStable = stable;
        final int w = width;
        final int h = height;
        Io.main(() -> callback.onDetected(finalQuad, w, h, finalStable));
    }
}
