package com.documentscanner.scanner.ui.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.documentscanner.scanner.cv.QuadGeometry;

import org.opencv.core.Point;

/**
 * 裁剪页核心控件：等比居中显示照片，叠加可拖拽的四边形选区。
 * 角点坐标一律以照片像素为单位，视图只做显示换算，因此缩放窗口不会丢失编辑状态。
 */
public class CropQuadView extends View {

    public interface OnCornersChanged {
        void onCornersChanged(Point[] corners);
    }

    /** 手柄半径：留白是否够用的判据，测试也按它核对「整个手柄留在屏内」。 */
    public static final float HANDLE_RADIUS_DP = 13f;
    private static final float TOUCH_SLOP_DP = 30f;
    private static final float LINE_WIDTH_DP = 2f;
    private static final int DIM_COLOR = 0x99000000;
    private static final int ACCENT_COLOR = Color.parseColor("#FF2F80ED");

    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path maskPath = new Path();
    private final Path quadPath = new Path();
    private final Rect srcRect = new Rect();
    private final RectF dstRect = new RectF();

    private Bitmap bitmap;
    private Point[] corners;
    private int draggingIndex = -1;
    private OnCornersChanged listener;
    private final float density;

    public CropQuadView(Context context) {
        this(context, null);
    }

    public CropQuadView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;

        dimPaint.setStyle(Paint.Style.FILL);
        dimPaint.setColor(DIM_COLOR);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(LINE_WIDTH_DP * density);
        linePaint.setColor(Color.WHITE);
        handlePaint.setStyle(Paint.Style.FILL);
        handlePaint.setColor(ACCENT_COLOR);
        handleStrokePaint.setStyle(Paint.Style.STROKE);
        handleStrokePaint.setStrokeWidth(2.5f * density);
        handleStrokePaint.setColor(Color.WHITE);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1f * density);
        gridPaint.setColor(0x66FFFFFF);
    }

    public void setOnCornersChanged(OnCornersChanged listener) {
        this.listener = listener;
    }

    /** 视图持有引用，不负责回收；替换时应先自行回收旧位图。 */
    public void setBitmap(@Nullable Bitmap bitmap) {
        this.bitmap = bitmap;
        invalidate();
    }

    public void setCorners(@Nullable Point[] corners) {
        this.corners = corners == null ? null : QuadGeometry.sortCorners(QuadGeometry.copy(corners));
        invalidate();
    }

    @Nullable
    public Point[] getCorners() {
        return corners == null ? null : QuadGeometry.copy(corners);
    }

    public boolean hasImage() {
        return bitmap != null && !bitmap.isRecycled();
    }

    /**
     * 照片实际占据的矩形：在 padding 围出的内容区内等比居中。
     * 角点一律用图像像素保存，所以留白只是把最外侧的角点推离屏幕边缘，不改动选区本身；
     * 绘制与命中测试都走这里，两者不会各算一份而错位。
     */
    public RectF imageRect() {
        if (!hasImage()) return new RectF();
        RectF drawn = ImageGeom.fitCenterRect(bitmap.getWidth(), bitmap.getHeight(),
                contentWidth(), contentHeight());
        drawn.offset(getPaddingLeft(), getPaddingTop());
        return drawn;
    }

    /** padding 吃掉的部分不能超过视图尺寸，否则内容区会变成负数。 */
    private int contentWidth() {
        return Math.max(0, getWidth() - getPaddingLeft() - getPaddingRight());
    }

    private int contentHeight() {
        return Math.max(0, getHeight() - getPaddingTop() - getPaddingBottom());
    }

    /** 角点当前画在视图上的位置，顺序与 {@link #getCorners()} 一致；绘制与命中测试共用一份。 */
    public PointF[] cornerPoints() {
        if (!hasImage() || corners == null || corners.length != 4) return null;
        RectF drawn = imageRect();
        PointF[] points = new PointF[4];
        for (int i = 0; i < 4; i++) {
            points[i] = ImageGeom.toView(corners[i], drawn, bitmap.getWidth(), bitmap.getHeight());
        }
        return points;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!hasImage()) return;

        RectF drawn = imageRect();
        srcRect.set(0, 0, bitmap.getWidth(), bitmap.getHeight());
        dstRect.set(drawn);
        canvas.drawBitmap(bitmap, srcRect, dstRect, imagePaint);

        PointF[] viewCorners = cornerPoints();
        if (viewCorners == null) return;

        maskPath.reset();
        maskPath.setFillType(Path.FillType.EVEN_ODD);
        maskPath.addRect(drawn, Path.Direction.CW);
        maskPath.moveTo(viewCorners[0].x, viewCorners[0].y);
        maskPath.lineTo(viewCorners[1].x, viewCorners[1].y);
        maskPath.lineTo(viewCorners[2].x, viewCorners[2].y);
        maskPath.lineTo(viewCorners[3].x, viewCorners[3].y);
        maskPath.close();
        canvas.drawPath(maskPath, dimPaint);

        quadPath.reset();
        quadPath.moveTo(viewCorners[0].x, viewCorners[0].y);
        quadPath.lineTo(viewCorners[1].x, viewCorners[1].y);
        quadPath.lineTo(viewCorners[2].x, viewCorners[2].y);
        quadPath.lineTo(viewCorners[3].x, viewCorners[3].y);
        quadPath.close();
        canvas.drawPath(quadPath, linePaint);
        drawEdgeGrid(canvas, viewCorners);

        float radius = HANDLE_RADIUS_DP * density;
        for (int i = 0; i < 4; i++) {
            PointF p = viewCorners[i];
            canvas.drawCircle(p.x, p.y, radius, handlePaint);
            canvas.drawCircle(p.x, p.y, radius, handleStrokePaint);
        }
    }

    /** 选区内部的三分参考线，便于判断文档是否拍正。 */
    private void drawEdgeGrid(Canvas canvas, PointF[] corners) {
        for (int ratio = 1; ratio <= 2; ratio++) {
            float t = ratio / 3f;
            PointF top = lerp(corners[0], corners[1], t);
            PointF bottom = lerp(corners[3], corners[2], t);
            canvas.drawLine(top.x, top.y, bottom.x, bottom.y, gridPaint);
            PointF left = lerp(corners[0], corners[3], t);
            PointF right = lerp(corners[1], corners[2], t);
            canvas.drawLine(left.x, left.y, right.x, right.y, gridPaint);
        }
    }

    private PointF lerp(PointF a, PointF b, float t) {
        return new PointF(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!hasImage() || corners == null) return false;
        RectF drawn = imageRect();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                draggingIndex = findCornerIndex(event.getX(), event.getY());
                if (draggingIndex < 0) return false;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (draggingIndex < 0) return false;
                moveCorner(draggingIndex, event.getX(), event.getY(), drawn);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (draggingIndex < 0) return false;
                draggingIndex = -1;
                getParent().requestDisallowInterceptTouchEvent(false);
                if (listener != null) listener.onCornersChanged(QuadGeometry.copy(corners));
                return true;
            default:
                return false;
        }
    }

    private int findCornerIndex(float x, float y) {
        float slop = TOUCH_SLOP_DP * density;
        PointF[] points = cornerPoints();
        if (points == null) return -1;
        int best = -1;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < points.length; i++) {
            PointF p = points[i];
            float distance = (float) Math.hypot(p.x - x, p.y - y);
            if (distance <= slop && distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    private void moveCorner(int index, float x, float y, RectF drawn) {
        // 拖动过程中保持角点槽位不变，否则越过相邻角时手柄会「跳走」；
        // 顺序约定在 getCorners()/确认时统一重排
        corners[index] = ImageGeom.toImage(x, y, drawn, bitmap.getWidth(), bitmap.getHeight());
        invalidate();
        if (listener != null) listener.onCornersChanged(QuadGeometry.copy(corners));
    }
}
