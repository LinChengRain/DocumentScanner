package com.documentscanner.scanner.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCase;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.documentscanner.scanner.R;
import com.documentscanner.scanner.api.PdfPreviewRequest;
import com.documentscanner.scanner.api.Scanner;
import com.documentscanner.scanner.api.ScannerConfig;
import com.documentscanner.scanner.camera.LiveDocAnalyzer;
import com.documentscanner.scanner.cv.Cv;
import com.documentscanner.scanner.cv.DocPipeline;
import com.documentscanner.scanner.cv.ImageBudget;
import com.documentscanner.scanner.cv.QuadGeometry;
import com.documentscanner.scanner.databinding.ScannerActivityScanBinding;
import com.documentscanner.scanner.export.ExportNames;
import com.documentscanner.scanner.export.PaperSize;
import com.documentscanner.scanner.export.PdfExporter;
import com.documentscanner.scanner.model.ScanPage;
import com.documentscanner.scanner.model.ScanSession;
import com.documentscanner.scanner.util.ImageIO;
import com.documentscanner.scanner.util.Io;
import com.documentscanner.scanner.util.Ui;
import com.google.common.util.concurrent.ListenableFuture;

import org.opencv.core.Point;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 扫描取景页。CameraX 的 Preview / ImageAnalysis / ImageCapture 三条流并行：
 * 预览等比居中显示，分析流只取亮度平面做边框检测，拍照流按需输出全分辨率 JPEG。
 * 三条流统一 4:3，因此检测坐标到预览坐标只需一次等比居中映射，不必处理裁剪偏移。
 */
public class ScanActivity extends AppCompatActivity implements LiveDocAnalyzer.Callback {

    public static final int MAX_PAGES = 20;
    /** 「查看」直接按这一档导出，不再问纸张；要换档走页面管理里的「导出 PDF」。 */
    private static final PaperSize PREVIEW_PAPER = PaperSize.A4;
    /** 传入页面 id 即进入「重拍」模式：这一页拍完就退出取景页，位置保持不变。 */
    public static final String EXTRA_REPLACE_PAGE = "replace_page";

    public static Intent replaceIntent(Context context, String pageId) {
        return new Intent(context, ScanActivity.class).putExtra(EXTRA_REPLACE_PAGE, pageId);
    }

    private static final String TAG = "ScanActivity";
    /** 自动快门冷却，避免同一份文档被连拍。 */
    private static final long AUTO_COOLDOWN_MS = 1800L;
    /** 选区角点相对画面宽高的位移超过这个值，才认为文档被挪动过。 */
    private static final float AUTO_REARM_DISTANCE = 0.06f;

    private ScannerActivityScanBinding binding;
    private LiveDocAnalyzer analyzer;
    private ImageCapture imageCapture;
    private ProcessCameraProvider cameraProvider;
    private ExecutorService cameraExecutor;
    /**
     * 本实例自己绑上的那组用例，解绑只针对它们（见 {@link #unbindCamera()}）。
     * 空数组表示当前没有持有绑定。
     */
    private UseCase[] boundUseCases = new UseCase[0];
    /** 是否应当持有相机绑定：onResume 置真、onPause 置假，绑定的异步回调据此收敛。 */
    private boolean cameraWanted;

    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    private int flashMode = ImageCapture.FLASH_MODE_OFF;
    private boolean autoMode = true;
    /** 非空即「重拍」模式：下一次快门覆盖这一页而不是新增一页。 */
    private String replacePageId;
    private boolean busy;
    private long lastAutoCaptureAt;
    private float[] currentQuad;
    private float[] capturedQuad;
    private boolean autoArmed = true;

    private final PageImageLoader thumbLoader = new PageImageLoader();
    private ActivityResultLauncher<Intent> galleryLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ScannerActivityScanBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        cameraExecutor = Executors.newSingleThreadExecutor();
        analyzer = new LiveDocAnalyzer(this);
        binding.previewView.setScaleType(PreviewView.ScaleType.FIT_CENTER);

        galleryLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    Intent data = result.getData();
                    Uri picked = data == null ? null : data.getData();
                    if (result.getResultCode() == RESULT_OK && picked != null) {
                        importFromGallery(picked);
                    }
                });

        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnShutter.setOnClickListener(v -> capture());
        binding.btnFlash.setOnClickListener(v -> cycleFlash());
        binding.btnFlip.setOnClickListener(v -> switchLens());
        binding.btnMode.setOnClickListener(v -> toggleMode());
        binding.btnImport.setOnClickListener(v -> pickFromGallery());
        binding.btnFinish.setOnClickListener(v -> previewAsPdf());
        binding.btnThumbnail.setOnClickListener(v -> openPageList());

        replacePageId = getIntent().getStringExtra(EXTRA_REPLACE_PAGE);
        updateModeUi();
        updateFlashUi();
        updatePagesUi();
        if (replacePageId != null) {
            binding.tvHint.setText(R.string.scanner_scan_retake_hint);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        cameraWanted = true;
        analyzer.setPaused(busy);
        updatePagesUi();
        // 一台取景页只在前台时持有相机：「重拍」会在旧的这一台之上再开一台，让位时先解绑、
        // 回来时重新绑，两台才不会再抢同一份相机配置。绑定是异步的，所以这里顺带新建
        // imageCapture，不必再单独补 targetRotation。
        bindCamera();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 离开页面即停止检测，避免从裁剪页返回前又触发一次自动快门
        analyzer.setPaused(true);
        cameraWanted = false;
        unbindCamera();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) cameraExecutor.shutdown();
    }

    // ---- CameraX ----------------------------------------------------------

    private void bindCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            // 提供商是现成的，回调却可能迟到：等到时页面也许已经让位出去了，
            // 这时再绑就会把相机压在后台页手里，等下一次 onResume 重新绑。
            if (!cameraWanted || isFinishing() || isDestroyed()) return;
            try {
                cameraProvider = future.get();
                startPreview();
            } catch (Exception e) {
                Log.e(TAG, "相机初始化失败", e);
                binding.tvHint.setText(R.string.scanner_scan_no_camera);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void startPreview() {
        if (cameraProvider == null) return;
        // 换镜头与重新绑定都走这里：先让出旧的这组用例，再拿新的一组去绑
        unbindCamera();

        // CameraX 1.1：分辨率只给「比例 + 目标尺寸」，由内部挑最接近的流。
        // 1.3 的 ResolutionSelector 那套要 compileSdk ≥34，降到 API 31 基线后不再可用。
        Preview preview = new Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build();
        preview.setSurfaceProvider(binding.previewView.getSurfaceProvider());

        // 分析流刻意用小分辨率：检测在 480 宽上做已足够，帧率优先
        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build();
        analysis.setAnalyzer(cameraExecutor, analyzer);

        imageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setFlashMode(flashMode)
                .build();
        imageCapture.setTargetRotation(currentDisplayRotation());

        try {
            cameraProvider.bindToLifecycle(this,
                    new CameraSelector.Builder().requireLensFacing(lensFacing).build(),
                    preview, analysis, imageCapture);
            boundUseCases = new UseCase[] {preview, analysis, imageCapture};
            binding.tvHint.setText(R.string.scanner_scan_hint_idle);
        } catch (Exception e) {
            Log.e(TAG, "绑定相机失败", e);
            boundUseCases = new UseCase[0];
            imageCapture = null;
            binding.tvHint.setText(R.string.scanner_scan_no_camera);
        }
    }

    /**
     * 只摘掉本实例自己绑上的那一组用例。ProcessCameraProvider 是进程单例，
     * 一次 {@code unbindAll()} 会把另一台取景页（「重拍」正是在旧的这一台之上再开一台）的
     * 预览、分析与拍照流一并解掉，于是回到那一页时预览灰屏、快门报
     * 「Not bound to a valid Camera」，只能退回首页重开。
     */
    private void unbindCamera() {
        if (cameraProvider != null && boundUseCases.length > 0) {
            try {
                cameraProvider.unbind(boundUseCases);
            } catch (Exception e) {
                Log.w(TAG, "解绑相机失败", e);
            }
        }
        boundUseCases = new UseCase[0];
        imageCapture = null;
    }

    /** 页面锁定竖屏，因此这里取到的旋转在整次会话中是稳定的。 */
    @SuppressWarnings("deprecation") // API 24-29 只能走 getDefaultDisplay()
    private int currentDisplayRotation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return getDisplay() != null ? getDisplay().getRotation() : Surface.ROTATION_0;
        }
        return getWindowManager().getDefaultDisplay().getRotation();
    }

    private void switchLens() {
        lensFacing = lensFacing == CameraSelector.LENS_FACING_BACK
                ? CameraSelector.LENS_FACING_FRONT
                : CameraSelector.LENS_FACING_BACK;
        startPreview();
    }

    private void cycleFlash() {
        if (flashMode == ImageCapture.FLASH_MODE_OFF) {
            flashMode = ImageCapture.FLASH_MODE_AUTO;
        } else if (flashMode == ImageCapture.FLASH_MODE_AUTO) {
            flashMode = ImageCapture.FLASH_MODE_ON;
        } else {
            flashMode = ImageCapture.FLASH_MODE_OFF;
        }
        if (imageCapture != null) imageCapture.setFlashMode(flashMode);
        updateFlashUi();
    }

    private void updateFlashUi() {
        int icon;
        int label;
        switch (flashMode) {
            case ImageCapture.FLASH_MODE_ON:
                icon = R.drawable.scanner_ic_flash_on;
                label = R.string.scanner_scan_flash_on;
                break;
            case ImageCapture.FLASH_MODE_AUTO:
                icon = R.drawable.scanner_ic_flash_auto;
                label = R.string.scanner_scan_flash_auto;
                break;
            default:
                icon = R.drawable.scanner_ic_flash_off;
                label = R.string.scanner_scan_flash_off;
        }
        binding.btnFlash.setImageResource(icon);
        binding.btnFlash.setContentDescription(getString(label));
    }

    private void toggleMode() {
        autoMode = !autoMode;
        updateModeUi();
    }

    private void updateModeUi() {
        binding.btnMode.setText(autoMode ? R.string.scanner_scan_mode_auto : R.string.scanner_scan_mode_manual);
        binding.btnMode.setCompoundDrawablesRelativeWithIntrinsicBounds(
                autoMode ? R.drawable.scanner_ic_auto_detect : R.drawable.scanner_ic_manual, 0, 0, 0);
        if (!autoMode) binding.quadOverlay.setStable(false);
    }

    // ---- 实时检测回调 ------------------------------------------------------

    @Override
    public void onDetected(Point[] quad, int imageWidth, int imageHeight, boolean stable) {
        if (isFinishing() || isDestroyed()) return;
        binding.quadOverlay.setImageSize(imageWidth, imageHeight);
        binding.quadOverlay.setQuad(quad);
        binding.quadOverlay.setStable(stable && autoMode);

        currentQuad = quad == null ? null
                : QuadGeometry.normalize(quad, imageWidth, imageHeight);
        if (quad == null || rearmAutoCapture()) {
            // 文档移出画面，或者选区已经挪开：下一次自动快门重新解锁
            autoArmed = true;
        }

        if (quad == null) {
            binding.tvHint.setText(busy ? R.string.scanner_scan_capturing : R.string.scanner_scan_hint_manual);
        } else if (stable && autoMode) {
            binding.tvHint.setText(autoArmed
                    ? R.string.scanner_scan_hint_stable : R.string.scanner_scan_hint_detected);
            maybeAutoCapture();
        } else {
            binding.tvHint.setText(R.string.scanner_scan_hint_detected);
        }
    }

    private boolean rearmAutoCapture() {
        float[] shot = capturedQuad;
        float[] now = currentQuad;
        if (shot == null || now == null) return true;
        for (int i = 0; i < shot.length; i++) {
            if (Math.abs(shot[i] - now[i]) > AUTO_REARM_DISTANCE) return true;
        }
        return false;
    }

    private void maybeAutoCapture() {
        if (!autoMode || busy || !autoArmed) return;
        if (SystemClock.uptimeMillis() - lastAutoCaptureAt < AUTO_COOLDOWN_MS) return;
        capture();
    }

    // ---- 拍摄 ------------------------------------------------------------

    private void capture() {
        if (busy) return;
        if (!Cv.ensure()) {
            Ui.toast(this, R.string.scanner_cv_not_ready);
            return;
        }
        if (imageCapture == null) {
            Ui.toast(this, R.string.scanner_scan_no_camera);
            return;
        }
        ScanSession session = ScanSession.get(this);
        final boolean replacing = replacePageId != null;
        if (!replacing && session.size() >= MAX_PAGES) {
            Ui.toast(this, getString(R.string.scanner_scan_pages_full, MAX_PAGES));
            return;
        }

        beginWork();
        capturedQuad = currentQuad;
        autoArmed = false;
        final ScanPage page = replacing
                ? retakeTarget(session, replacePageId) : session.createPage();
        ImageCapture.OutputFileOptions options =
                new ImageCapture.OutputFileOptions.Builder(page.original).build();

        imageCapture.takePicture(options, cameraExecutor, new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults results) {
                try {
                    final float[] detected = preparePage(page);
                    Io.main(() -> pageReady(page, detected, session, replacing));
                } catch (Exception e) {
                    Log.e(TAG, "处理照片失败", e);
                    failAndRecover(page, e);
                }
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                Log.e(TAG, "拍照失败", exception);
                failAndRecover(page, exception);
            }
        });
    }

    /**
     * 相机是否已经接上、并且不在处理上一张。绑定是异步的，快门与自动检测都得等它，
     * 所以这里等的是真状态而不是睡眠——设备测试按第一下快门之前先用它对齐。
     */
    boolean readyForCapture() {
        return imageCapture != null && !busy;
    }

    private void beginWork() {
        busy = true;
        lastAutoCaptureAt = SystemClock.uptimeMillis();
        analyzer.setPaused(true);
        binding.progress.setVisibility(View.VISIBLE);
        animateShutter();
    }

    private void endWork(boolean resumeAnalyzer) {
        busy = false;
        binding.progress.setVisibility(View.GONE);
        if (resumeAnalyzer) analyzer.setPaused(false);
    }

    private void failAndRecover(ScanPage page, Throwable error) {
        Io.main(() -> {
            endWork(true);
            autoArmed = true;
            ScanSession.get(this).remove(page.id);
            updatePagesUi();
            Ui.toast(this, getString(R.string.scanner_error_generic, String.valueOf(error.getMessage())));
        });
    }

    /**
     * 解码照片（EXIF 方向已烘焙）→ 自动检测四角 → 写一张临时缩略图。
     * 在相机线程执行，避免占住主线程。检测到的选区作为返回值交给调用方，
     * 由主线程写入页面模型（后台线程不碰会话状态）。
     */
    private float[] preparePage(ScanPage page) throws IOException {
        Bitmap source = ImageIO.load(page.original, DocPipeline.EDGE_DETECT_SOURCE);
        try {
            Point[] quad = DocPipeline.detectQuad(source, true);
            float[] normalized = quad == null ? null
                    : QuadGeometry.normalize(quad, source.getWidth(), source.getHeight());
            Bitmap thumb = ImageIO.scaledCopy(source, DocPipeline.EDGE_THUMB);
            ImageIO.saveJpeg(thumb, page.thumb, DocPipeline.JPEG_QUALITY_THUMB);
            if (thumb != source) thumb.recycle();
            return normalized;
        } finally {
            source.recycle();
        }
    }

    private void animateShutter() {
        binding.btnShutter.setScaleX(0.86f);
        binding.btnShutter.setScaleY(0.86f);
        binding.btnShutter.animate().scaleX(1f).scaleY(1f).setDuration(150L).start();
    }

    // ---- 相册导入 ---------------------------------------------------------

    private void pickFromGallery() {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT)
                .setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE);
        try {
            galleryLauncher.launch(intent);
        } catch (Exception e) {
            Ui.toast(this, R.string.scanner_pdf_no_app);
        }
    }

    /**
     * 把相册里的一张图变成一页。包内可见是为了让设备测试绕过系统选择器直接喂图，
     * 从而在没有镜头的环境下走完「一页就绪」这条收尾路径。
     */
    void importFromGallery(Uri uri) {
        if (busy) return;
        ScanSession session = ScanSession.get(this);
        final boolean replacing = replacePageId != null;
        if (!replacing && session.size() >= MAX_PAGES) {
            Ui.toast(this, getString(R.string.scanner_scan_pages_full, MAX_PAGES));
            return;
        }

        beginWork();
        final ScanPage page = replacing
                ? retakeTarget(session, replacePageId) : session.createPage();
        Io.bg(() -> {
            Bitmap bitmap = null;
            try {
                bitmap = ImageIO.load(this, uri, DocPipeline.EDGE_DETECT_SOURCE);
                ImageIO.saveJpeg(bitmap, page.original, DocPipeline.JPEG_QUALITY_ORIGINAL);
                final float[] detected = preparePage(page);
                Io.main(() -> pageReady(page, detected, session, replacing));
            } catch (Exception e) {
                Log.e(TAG, "导入图片失败", e);
                failAndRecover(page, e);
            } finally {
                if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            }
        });
    }

    // ---- 页面状态 ---------------------------------------------------------

    private void updatePagesUi() {
        ScanSession session = ScanSession.get(this);
        int count = session.size();
        binding.btnFinish.setText(getString(R.string.scanner_scan_finish, count));
        binding.btnFinish.setEnabled(count > 0);
        if (count == 0) {
            binding.imgThumbnail.setImageDrawable(null);
            binding.tvPageBadge.setVisibility(View.GONE);
            return;
        }
        thumbLoader.loadInto(binding.imgThumbnail, session.pages().get(count - 1));
        binding.tvPageBadge.setVisibility(View.VISIBLE);
        binding.tvPageBadge.setText(String.valueOf(count));
    }

    /**
     * 一页已落盘并检出选区后的收尾。快门只负责攒页：留在取景页并把分析流重新打开，
     * 要编辑由用户点缩略图或「完成」进列表再挑，不必每拍一张都被弹出取景页。
     * 「重拍」是唯一例外，它本身就是「回去改这一页」这条路径上的一步，所以直接落回裁剪页。
     */
    private void pageReady(ScanPage page, float[] detected, ScanSession session, boolean replacing) {
        page.quad = detected;
        replacePageId = null;
        endWork(!replacing);
        updatePagesUi();
        if (replacing) {
            openCrop(page.id);
            finish();
            return;
        }
        Ui.toast(this, getString(R.string.scanner_scan_page_added, session.size()));
    }

    /** 重拍复用原页面（id 与位置都不变）；原页已被删除时退化成新增一页。 */
    private static ScanPage retakeTarget(ScanSession session, String pageId) {
        if (session.byId(pageId) == null) return session.createPage();
        session.resetForRetake(pageId);
        return session.byId(pageId);
    }

    private void openCrop(String pageId) {
        Scanner.openCrop(this, pageId);
    }

    // ---- 「查看」：直接给成品 ------------------------------------------------

    /**
     * 「查看」走的是「我想看看扫成什么样了」这条路径：补齐没确认过的页 → 按
     * {@link #PREVIEW_PAPER} 导出 → 进 PDF 预览页。排序、换滤镜、改标题仍然从缩略图
     * 进页面管理，那里才有「导出 PDF」的三档纸张可选。
     */
    private void previewAsPdf() {
        if (busy) return;
        final ScanSession session = ScanSession.get(this);
        if (session.isEmpty()) return;
        // 一次「查看」取一次档：补齐页面与合成 PDF 用的是同一份像素预算
        final ImageBudget budget = ScannerConfig.imageBudget();
        ScannerConfig.setMaxPdfSizeKb(500*session.size());
        // 上限也在这儿取，不在导出线程里现读——中途改配置不该影响这一份
        final long maxPdfBytes = ScannerConfig.maxPdfBytes();
        PendingRenderer.prepare(this, session, budget, new PendingRenderer.Listener() {
            @Override
            public void begin() {
                beginExport();
            }

            @Override
            public void end() {
                endWork(true);
            }

            @Override
            public void onPageFailed(int remaining) {
                Ui.toast(ScanActivity.this,
                        getString(R.string.scanner_pages_unedited_warning, remaining));
            }

            @Override
            public void ready() {
                exportForPreview(session, budget, maxPdfBytes);
            }
        });
    }

    private void exportForPreview(ScanSession session, ImageBudget budget, long maxPdfBytes) {
        List<File> images = new ArrayList<>();
        ArrayList<String> pageIds = new ArrayList<>();
        for (ScanPage page : session.pages()) {
            File output = page.bestOutput();
            if (output != null && output.exists()) {
                images.add(output);
                pageIds.add(page.id);
            }
        }
        if (images.isEmpty()) {
            endWork(true);
            Ui.toast(this, R.string.scanner_pages_empty);
            return;
        }
        beginExport();
        final File target = ExportNames.unique(session.exportDir(), session.getSafeTitle(), ".pdf");
        Io.bg(() -> {
            File written = null;
            Throwable error = null;
            try {
                written = PdfExporter.exportFitting(
                        images, target, PREVIEW_PAPER, budget, maxPdfBytes, null).pdf;
            } catch (Exception e) {
                Log.e(TAG, "导出 PDF 失败", e);
                error = e;
            }
            final File pdf = written;
            final Throwable failure = error;
            Io.main(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (pdf == null) {
                    endWork(true);
                    Ui.toast(this, getString(R.string.scanner_pdf_export_failed) + "："
                            + String.valueOf(failure == null ? "" : failure.getMessage()));
                    return;
                }
                // 这一屏要退到后台了，分析流保持暂停：回来时 onResume 会按 busy 重新放行，
                // 中间不再吃一次自动快门，凭空多出一页。
                endWork(false);
                Scanner.openPdfPreview(this, new PdfPreviewRequest(
                        pdf, session.getTitle(), pageIds, PREVIEW_PAPER));
            });
        });
    }

    /** 导出用的忙态：与快门共用 busy 和进度条，但不做快门动画、也不动自动冷却。 */
    private void beginExport() {
        busy = true;
        analyzer.setPaused(true);
        binding.progress.setVisibility(View.VISIBLE);
    }

    private void openPageList() {
        if (ScanSession.get(this).isEmpty()) return;
        Scanner.openPages(this);
    }
}
