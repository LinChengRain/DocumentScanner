package com.documentscanner.scanner.ui.widget;

import android.graphics.PointF;
import android.graphics.RectF;

import org.opencv.core.Point;

/**
 * 图像坐标 <-> 视图坐标换算。
 * 预览与编辑视图都统一用 FIT_CENTER（等比居中留黑边），因此一套换算通用。
 */
public final class ImageGeom {

    private ImageGeom() {
    }

    public static RectF fitCenterRect(int imageWidth, int imageHeight, int viewWidth, int viewHeight) {
        if (imageWidth <= 0 || imageHeight <= 0) return new RectF();
        float scale = Math.min(viewWidth / (float) imageWidth, viewHeight / (float) imageHeight);
        float width = imageWidth * scale;
        float height = imageHeight * scale;
        float left = (viewWidth - width) / 2f;
        float top = (viewHeight - height) / 2f;
        return new RectF(left, top, left + width, top + height);
    }

    public static PointF toView(Point imagePoint, RectF drawn, int imageWidth, int imageHeight) {
        return new PointF(drawn.left + (float) imagePoint.x * drawn.width() / Math.max(1, imageWidth),
                drawn.top + (float) imagePoint.y * drawn.height() / Math.max(1, imageHeight));
    }

    public static Point toImage(float viewX, float viewY, RectF drawn, int imageWidth, int imageHeight) {
        float scaleX = drawn.width() / Math.max(1, imageWidth);
        float scaleY = drawn.height() / Math.max(1, imageHeight);
        float x = (viewX - drawn.left) / (scaleX <= 0 ? 1 : scaleX);
        float y = (viewY - drawn.top) / (scaleY <= 0 ? 1 : scaleY);
        return new Point(clamp(x, 0, imageWidth - 1), clamp(y, 0, imageHeight - 1));
    }

    public static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
