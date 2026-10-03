package com.documentscanner.scanner.api;

import android.content.Context;
import android.content.Intent;

import com.documentscanner.scanner.model.ScanSession;
import com.documentscanner.scanner.ui.CropActivity;
import com.documentscanner.scanner.ui.PageListActivity;
import com.documentscanner.scanner.ui.PdfPreviewActivity;
import com.documentscanner.scanner.ui.ScanActivity;

/**
 * 起屏入口：宿主与模块内部都只通过这里跳扫描的几屏。
 *
 * <p>每个 {@code openXxx} 都是「{@code xxxIntent} + {@code startActivity}」的薄封装，
 * Intent 工厂单独露出来是为了能在不启动 Activity 的情况下断言跳转目标与 extra 契约
 * （见 {@code scanner/src/androidTest/.../api/ScannerIntentTest}）。
 * 四屏的 key 各由对应 Activity 的工厂方法持有（{@code ScanActivity.replaceIntent}、
 * {@code CropActivity.intentFor}、{@code PdfPreviewActivity.intentFor}），本类只负责
 * 「谁来起、要不要先切会话」。
 *
 * <p>之所以要有这一层：裸 {@code new Intent(ctx, ScanActivity.class)} 与
 * {@code ScanSession.startNew(ctx)} 的配对原先散在宿主里，任何一处漏了前半句，
 * 屏幕就会接着上一份会话写——编译期查不出来，真机上才表现为「历史会话被串改」。
 */
public final class Scanner {

    private Scanner() {
    }

    /** 取景页。追加模式：不动会话。 */
    public static Intent scanIntent(Context context) {
        return new Intent(context, ScanActivity.class);
    }

    /** 取景页的重拍模式：拍完替换 {@code pageId} 那一页并退出，位置不变。 */
    public static Intent scanReplacingIntent(Context context, String pageId) {
        return ScanActivity.replaceIntent(context, pageId);
    }

    /** 某一页的裁剪/滤镜编辑页。 */
    public static Intent cropIntent(Context context, String pageId) {
        return CropActivity.intentFor(context, pageId);
    }

    /** 当前会话的页面管理。 */
    public static Intent pagesIntent(Context context) {
        return new Intent(context, PageListActivity.class);
    }

    /** 一份已导出 PDF 的预览页。 */
    public static Intent pdfPreviewIntent(Context context, PdfPreviewRequest request) {
        return PdfPreviewActivity.intentFor(context, request);
    }

    /** 开一份新会话进取景页。历史会话不会被清空，见 {@link #continueScan}。 */
    public static void startNewScan(Context context) {
        ScanSession.startNew(context);
        context.startActivity(scanIntent(context));
    }

    /** 沿用当前会话进取景页补页（页面管理里的「添加页面」）。 */
    public static void continueScan(Context context) {
        context.startActivity(scanIntent(context));
    }

    /** 重拍某一页：拍完替换该页并退出取景页，位置不变。 */
    public static void openScanReplacing(Context context, String pageId) {
        context.startActivity(scanReplacingIntent(context, pageId));
    }

    /** 打开当前会话的页面管理，不动会话指针。 */
    public static void openPages(Context context) {
        context.startActivity(pagesIntent(context));
    }

    /** 打开某份历史会话的页面管理。这一步会切会话，之后的编辑都写进这份会话。 */
    public static void openPages(Context context, String sessionId) {
        ScanSession.open(context, sessionId);
        openPages(context);
    }

    /** 编辑某一页的裁剪范围与滤镜。 */
    public static void openCrop(Context context, String pageId) {
        context.startActivity(cropIntent(context, pageId));
    }

    /** 预览一份已经导出的 PDF。 */
    public static void openPdfPreview(Context context, PdfPreviewRequest request) {
        context.startActivity(pdfPreviewIntent(context, request));
    }
}
