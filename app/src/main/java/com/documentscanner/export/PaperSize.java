package com.documentscanner.export;

import android.graphics.Bitmap;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;

/**
 * PDF 页面尺寸。固定纸张时图片等比内接并居中，四周留白；
 * 跟随图片比例时页面就是图片本身，不留白，但每页尺寸可能不同。
 */
public enum PaperSize {
    A4(595f, 842f),
    LETTER(612f, 792f),
    /** 长边固定 842pt，短边按图片比例换算。 */
    FOLLOW_IMAGE(0f, 0f);

    /** 固定纸张时的页边距，约 6.35mm。 */
    private static final float MARGIN_PT = 18f;
    /** 跟随图片时统一让长边等于 A4 长边。 */
    private static final float LONG_EDGE_PT = 842f;

    public final float widthPt;
    public final float heightPt;

    PaperSize(float widthPt, float heightPt) {
        this.widthPt = widthPt;
        this.heightPt = heightPt;
    }

    public boolean isFixed() {
        return this != FOLLOW_IMAGE;
    }

    /** 页面方向跟随图片：横图配横向纸张，不把横版内容挤成竖排。 */
    public PdfDocument.PageInfo pageInfoFor(Bitmap bitmap, int pageNumber) {
        boolean landscape = bitmap.getWidth() >= bitmap.getHeight();
        if (!isFixed()) {
            float ratio = bitmap.getWidth() / (float) Math.max(1, bitmap.getHeight());
            int width;
            int height;
            if (ratio >= 1) {
                width = Math.round(LONG_EDGE_PT);
                height = Math.max(1, Math.round(LONG_EDGE_PT / ratio));
            } else {
                height = Math.round(LONG_EDGE_PT);
                width = Math.max(1, Math.round(LONG_EDGE_PT * ratio));
            }
            return new PdfDocument.PageInfo.Builder(width, height, pageNumber).create();
        }
        float longEdge = Math.max(widthPt, heightPt);
        float shortEdge = Math.min(widthPt, heightPt);
        int width = Math.round(landscape ? longEdge : shortEdge);
        int height = Math.round(landscape ? shortEdge : longEdge);
        return new PdfDocument.PageInfo.Builder(width, height, pageNumber).create();
    }

    /** 图片在页面上的绘制区域。 */
    public RectF contentRectFor(Bitmap bitmap, PdfDocument.PageInfo info) {
        int pageWidth = info.getPageWidth();
        int pageHeight = info.getPageHeight();
        if (!isFixed()) return new RectF(0, 0, pageWidth, pageHeight);
        float availableW = pageWidth - 2 * MARGIN_PT;
        float availableH = pageHeight - 2 * MARGIN_PT;
        if (availableW <= 0 || availableH <= 0) return new RectF(0, 0, pageWidth, pageHeight);
        float scale = Math.min(availableW / bitmap.getWidth(), availableH / bitmap.getHeight());
        float drawW = bitmap.getWidth() * scale;
        float drawH = bitmap.getHeight() * scale;
        float left = (pageWidth - drawW) / 2f;
        float top = (pageHeight - drawH) / 2f;
        return new RectF(left, top, left + drawW, top + drawH);
    }
}
