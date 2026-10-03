package com.documentscanner.scanner.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import org.opencv.core.Point;

/**
 * 拍摄页的实时边框叠加层：把分析帧里检出的四边形映射到预览上绘制。
 * 未检出时显示九宫格构图参考线。
 */
public class QuadOverlayView extends View {

    private static final float HANDLE_RADIUS_DP = 9f;
    private static final float LINE_WIDTH_DP = 2.5f;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleInnerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private Point[] quad;
    private int imageWidth;
    private int imageHeight;
    private boolean stable;
    private boolean showGrid = true;
    private final float density;

    public QuadOverlayView(Context context) {
        this(context, null);
    }

    public QuadOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;

        fillPaint.setStyle(Paint.Style.FILL);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(LINE_WIDTH_DP * density);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        handlePaint.setStyle(Paint.Style.FILL);
        handlePaint.setColor(Color.WHITE);
        handleInnerPaint.setStyle(Paint.Style.STROKE);
        handleInnerPaint.setStrokeWidth(1.5f * density);
        handleInnerPaint.setColor(Color.parseColor("#33000000"));
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1f * density);
        gridPaint.setColor(Color.parseColor("#33FFFFFF"));
        setClickable(false);
    }

    public void setImageSize(int width, int height) {
        if (width <= 0 || height <= 0) return;
        if (imageWidth != width || imageHeight != height) {
            imageWidth = width;
            imageHeight = height;
            invalidate();
        }
    }

    public void setQuad(@Nullable Point[] quad) {
        this.quad = quad;
        invalidate();
    }

    public void setStable(boolean stable) {
        if (this.stable != stable) {
            this.stable = stable;
            invalidate();
        }
    }

    public void setShowGrid(boolean showGrid) {
        this.showGrid = showGrid;
        invalidate();
    }

    public boolean isGridShown() {
        return showGrid;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int viewWidth = getWidth();
        int viewHeight = getHeight();
        if (viewWidth == 0 || viewHeight == 0) return;

        if (quad == null || imageWidth <= 0 || imageHeight <= 0) {
            if (showGrid) drawGrid(canvas, viewWidth, viewHeight);
            return;
        }

        RectF drawn = ImageGeom.fitCenterRect(imageWidth, imageHeight, viewWidth, viewHeight);
        PointF[] corners = new PointF[4];
        for (int i = 0; i < 4; i++) {
            corners[i] = ImageGeom.toView(quad[i], drawn, imageWidth, imageHeight);
        }

        int accent = stable ? Color.parseColor("#FF2BD46D") : Color.parseColor("#FF2F80ED");
        fillPaint.setColor(stable ? 0x332BD46D : 0x262F80ED);
        linePaint.setColor(accent);

        path.reset();
        path.moveTo(corners[0].x, corners[0].y);
        path.lineTo(corners[1].x, corners[1].y);
        path.lineTo(corners[2].x, corners[2].y);
        path.lineTo(corners[3].x, corners[3].y);
        path.close();
        canvas.drawPath(path, fillPaint);
        canvas.drawPath(path, linePaint);

        float radius = HANDLE_RADIUS_DP * density;
        for (PointF corner : corners) {
            canvas.drawCircle(corner.x, corner.y, radius, handlePaint);
            canvas.drawCircle(corner.x, corner.y, radius, handleInnerPaint);
        }
    }

    private void drawGrid(Canvas canvas, int viewWidth, int viewHeight) {
        for (int i = 1; i <= 2; i++) {
            float x = viewWidth * i / 3f;
            float y = viewHeight * i / 3f;
            canvas.drawLine(x, 0, x, viewHeight, gridPaint);
            canvas.drawLine(0, y, viewWidth, y, gridPaint);
        }
    }
}
