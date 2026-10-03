package com.documentscanner.scanner.model;

import com.documentscanner.scanner.cv.FilterType;

import java.io.File;

/**
 * 一页扫描件。三份产物文件把「矫正」和「滤镜」分开保存，
 * 这样换滤镜时无需回头解码原始大图，重排/预览也都直接读结果文件。
 */
public class ScanPage {

    public final String id;
    public final File original;
    public final File warp;
    public final File result;
    public final File thumb;

    public FilterType filter = FilterType.DEFAULT;
    /** 是否已经完成过一次裁剪确认；未完成时不允许进入导出。 */
    public boolean edited;
    /**
     * 归一化（0..1）四角，顺序 TL/TR/BR/BL，坐标基于「正立显示」的 original。
     * 存归一值而不是像素值，渲染时才能套用到任意导出尺寸。
     */
    public float[] quad;

    public ScanPage(String id, File original, File warp, File result, File thumb) {
        this.id = id;
        this.original = original;
        this.warp = warp;
        this.result = result;
        this.thumb = thumb;
    }

    public boolean hasResult() {
        return result != null && result.exists() && result.length() > 0;
    }

    public boolean hasWarp() {
        return warp != null && warp.exists() && warp.length() > 0;
    }

    /** 当前可用于导出/预览的最佳产物。 */
    public File bestOutput() {
        return hasResult() ? result : hasWarp() ? warp : original;
    }
}
