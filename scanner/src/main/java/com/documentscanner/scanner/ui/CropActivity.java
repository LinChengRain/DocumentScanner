package com.documentscanner.scanner.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.documentscanner.scanner.R;
import com.documentscanner.scanner.api.Scanner;
import com.documentscanner.scanner.api.ScannerConfig;
import com.documentscanner.scanner.cv.Cv;
import com.documentscanner.scanner.cv.DocPipeline;
import com.documentscanner.scanner.cv.FilterType;
import com.documentscanner.scanner.cv.ImageBudget;
import com.documentscanner.scanner.cv.PageRenderer;
import com.documentscanner.scanner.cv.QuadGeometry;
import com.documentscanner.scanner.databinding.ScannerActivityCropBinding;
import com.documentscanner.scanner.model.ScanPage;
import com.documentscanner.scanner.model.ScanSession;
import com.documentscanner.scanner.ui.adapter.FilterAdapter;
import com.documentscanner.scanner.util.ImageIO;
import com.documentscanner.scanner.util.Io;
import com.documentscanner.scanner.util.Ui;

import org.opencv.core.Point;

import java.util.ArrayList;
import java.util.List;

/**
 * 裁剪与增强编辑页。
 * 选区角点始终以「当前显示位图」的像素坐标保存在 {@link com.documentscanner.scanner.ui.widget.CropQuadView}，
 * 确认时才归一化写入会话，因此渲染用的导出尺寸与这里编辑的预览尺寸互不牵制。
 */
public class CropActivity extends AppCompatActivity {

    private static final String TAG = "CropActivity";
    private static final String EXTRA_PAGE_ID = "page_id";
    /** 效果预览缩略图的长边，够看清滤镜差异即可。 */
    private static final int PREVIEW_EDGE = 560;
    /** 拖动选区时合并刷新请求的间隔。 */
    private static final long PREVIEW_DEBOUNCE_MS = 320L;

    private ScannerActivityCropBinding binding;
    private ScanSession session;
    private ScanPage page;
    /** 起屏时取一次体积档：这一屏里的渲染与「应用到全部」必须出同一尺寸。 */
    private ImageBudget budget = ImageBudget.DEFAULT;

    /** 编辑用位图，长边不超过 {@link DocPipeline#EDGE_DETECT_SOURCE}，仅在 onDestroy 回收。 */
    private Bitmap display;
    private FilterAdapter filterAdapter;
    private Bitmap previewBitmap;
    private Runnable pendingPreview;
    private int generation;
    private boolean submitting;

    public static Intent intentFor(Context context, String pageId) {
        return new Intent(context, CropActivity.class).putExtra(EXTRA_PAGE_ID, pageId);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ScannerActivityCropBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        String pageId = getIntent().getStringExtra(EXTRA_PAGE_ID);
        session = ScanSession.get(this);
        page = pageId == null ? null : session.byId(pageId);
        if (page == null) {
            finish();
            return;
        }
        budget = ScannerConfig.imageBudget();

        binding.tvPageOf.setText(getString(R.string.scanner_crop_page_of,
                session.indexOf(page.id) + 1, session.size()));

        filterAdapter = new FilterAdapter(this, page.filter, this::onFilterPicked);
        binding.filterStrip.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        binding.filterStrip.setAdapter(filterAdapter);

        binding.cropView.setOnCornersChanged(corners -> {
            // 选区变了，旧预览就不再可信；拖动过程里不逐帧重算，松手后再刷新
            generation++;
            scheduleFilterPreview();
        });
        binding.btnClose.setOnClickListener(v -> finish());
        binding.btnRotate.setOnClickListener(v -> rotateClockwise());
        binding.btnRecheck.setOnClickListener(v -> redetect());
        binding.btnFullArea.setOnClickListener(v -> selectWholeImage());
        binding.btnRetake.setOnClickListener(v -> retake());
        binding.btnConfirm.setOnClickListener(v -> confirm());

        loadImage();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        generation++;
        cancelScheduledPreview();
        recycleQuietly(display);
        recycleQuietly(previewBitmap);
        display = null;
        previewBitmap = null;
    }

    // ---- 初始化 -----------------------------------------------------------

    private void loadImage() {
        binding.progress.setVisibility(View.VISIBLE);
        final int gen = ++generation;
        Io.bg(() -> {
            Bitmap loaded = null;
            Throwable error = null;
            try {
                loaded = ImageIO.load(page.original, DocPipeline.EDGE_DETECT_SOURCE);
            } catch (Exception e) {
                Log.e(TAG, "读取照片失败", e);
                error = e;
            }
            final Bitmap bitmap = loaded;
            final Throwable failure = error;
            Io.main(() -> {
                if (gen != generation || isFinishing() || isDestroyed()) {
                    recycleQuietly(bitmap);
                    return;
                }
                if (bitmap == null) {
                    binding.progress.setVisibility(View.GONE);
                    Ui.toast(this, getString(R.string.scanner_error_generic, String.valueOf(failure == null
                            ? getString(R.string.scanner_cv_not_ready) : failure.getMessage())));
                    finish();
                    return;
                }
                display = bitmap;
                binding.cropView.setBitmap(display);
                binding.cropView.setCorners(initialCorners());
                binding.progress.setVisibility(View.GONE);
                refreshFilterPreview();
            });
        });
    }

    private Point[] initialCorners() {
        Point[] fromPage = page.quad == null ? null
                : QuadGeometry.denormalize(page.quad, display.getWidth(), display.getHeight());
        if (fromPage != null && QuadGeometry.area(fromPage) > 0) return fromPage;
        return QuadGeometry.insetFrame(display.getWidth(), display.getHeight(), 0.02);
    }

    // ---- 工具条 -----------------------------------------------------------

    private void selectWholeImage() {
        if (display == null) return;
        binding.cropView.setCorners(
                QuadGeometry.insetFrame(display.getWidth(), display.getHeight(), 0.004));
        refreshFilterPreview();
    }

    private void redetect() {
        if (display == null) return;
        binding.progress.setVisibility(View.VISIBLE);
        PageRenderer.detectQuad(display, quad -> {
            if (isFinishing() || isDestroyed()) return;
            binding.progress.setVisibility(View.GONE);
            if (quad == null) {
                Ui.toast(this, R.string.scanner_crop_detect_failed);
            } else {
                binding.cropView.setCorners(
                        QuadGeometry.clamp(quad, display.getWidth(), display.getHeight(), 1.0));
                refreshFilterPreview();
            }
        });
    }

    /**
     * 旋转同时改写 original 文件：下游（渲染、导出）永远按正立位图工作，
     * 不必再携带一个旋转角度字段。
     */
    private void rotateClockwise() {
        if (display == null) return;
        final int gen = ++generation;
        final int oldWidth = display.getWidth();
        final int oldHeight = display.getHeight();
        final Point[] oldCorners = binding.cropView.getCorners();
        final Bitmap rotated = ImageIO.rotatedCopy(display, 90);
        binding.progress.setVisibility(View.VISIBLE);
        Io.bg(() -> {
            Throwable error = null;
            try {
                ImageIO.saveJpeg(rotated, page.original, DocPipeline.JPEG_QUALITY_ORIGINAL);
            } catch (Exception e) {
                Log.e(TAG, "保存旋转结果失败", e);
                error = e;
            }
            final Throwable failure = error;
            Io.main(() -> {
                binding.progress.setVisibility(View.GONE);
                if (isFinishing() || isDestroyed()) return;
                if (gen != generation || failure != null) {
                    recycleQuietly(rotated);
                    Ui.toast(this, failure == null
                            ? getString(R.string.scanner_crop_rotate_failed)
                            : getString(R.string.scanner_error_generic, failure.getMessage()));
                    return;
                }
                display = rotated;
                if (page.quad != null) {
                    page.quad = QuadGeometry.rotateNormalized90Clockwise(page.quad);
                }
                binding.cropView.setBitmap(rotated);
                if (oldCorners != null) {
                    // 选区跟着图片一起转，否则角点会停在旧坐标系里
                    binding.cropView.setCorners(QuadGeometry.rotate90Clockwise(
                            oldCorners, oldWidth, oldHeight));
                }
                session.save();
                refreshFilterPreview();
            });
        });
    }

    /**
     * 「重拍」是回去把这一页重新拍一张：交给取景页的替换模式，页的位置与产物都原样留着，
     * 真要作废是在取景页拍下新页那一刻。所以这里不能先删页——删了就没有「改这一页」可言。
     */
    private void retake() {
        Scanner.openScanReplacing(this, page.id);
        finish();
    }

    private void onFilterPicked(FilterType type) {
        refreshFilterPreview();
    }

    /** 用「矫正后 + 滤镜」的小图做预览，所见即所得，成本远低于整页渲染。 */
    private void refreshFilterPreview() {
        cancelScheduledPreview();
        final Bitmap base = display;
        final Point[] quad = binding.cropView.getCorners();
        final FilterType type = filterAdapter.getSelected();
        if (base == null || quad == null || type == null) return;
        final int gen = ++generation;
        Io.bg(() -> {
            Bitmap warped = null;
            Bitmap filtered;
            try {
                if (!Cv.ensure()) throw new IllegalStateException("OpenCV 未就绪");
                warped = DocPipeline.warp(base, quad, PREVIEW_EDGE);
                filtered = DocPipeline.applyFilter(warped, type);
            } catch (Throwable t) {
                Log.w(TAG, "滤镜预览失败", t);
                recycleQuietly(warped);
                return;
            }
            final Bitmap recycle = warped == filtered ? null : warped;
            Io.main(() -> {
                if (recycle != null) recycle.recycle();
                if (gen != generation || isFinishing() || isDestroyed()) {
                    recycleQuietly(filtered);
                    return;
                }
                Bitmap previous = previewBitmap;
                previewBitmap = filtered;
                binding.imgFilterPreview.setImageBitmap(filtered);
                recycleQuietly(previous);
            });
        });
    }

    private void scheduleFilterPreview() {
        cancelScheduledPreview();
        pendingPreview = () -> {
            pendingPreview = null;
            if (!submitting && !isFinishing() && !isDestroyed()) refreshFilterPreview();
        };
        binding.cropView.postDelayed(pendingPreview, PREVIEW_DEBOUNCE_MS);
    }

    private void cancelScheduledPreview() {
        if (pendingPreview == null) return;
        binding.cropView.removeCallbacks(pendingPreview);
        pendingPreview = null;
    }

    // ---- 确认 -------------------------------------------------------------

    private void confirm() {
        if (display == null || submitting) return;
        final int width = display.getWidth();
        final int height = display.getHeight();
        Point[] corners = binding.cropView.getCorners();
        if (corners == null) {
            corners = QuadGeometry.insetFrame(width, height, 0.02);
        }
        // 角点槽位在拖动过程中不变，因此交叉的选区会在这里被识别出来，
        // 而不是等到渲染时被静默重排成另一个形状
        if (!QuadGeometry.isUsableQuad(corners, width, height)) {
            Ui.toast(this, R.string.scanner_crop_quad_invalid);
            return;
        }
        float[] normalized = QuadGeometry.normalize(
                QuadGeometry.sanitize(corners, width, height), width, height);

        page.filter = filterAdapter.getSelected();
        submitting = true;
        setPanelEnabled(false);
        binding.progress.setVisibility(View.VISIBLE);

        PageRenderer.render(page, normalized, budget, error -> {
            if (isFinishing() || isDestroyed()) return;
            if (error != null) {
                submitting = false;
                setPanelEnabled(true);
                binding.progress.setVisibility(View.GONE);
                Ui.toast(this, getString(R.string.scanner_error_generic, error.getMessage()));
                return;
            }
            session.save();
            if (binding.cbApplyAll.isChecked()) {
                applyFilterToAll();
            } else {
                finish();
            }
        });
    }

    /** 「应用到全部」：其余已矫正页面只重跑滤镜这一步，未确认页面留待渲染时套用。 */
    private void applyFilterToAll() {
        FilterType chosen = page.filter;
        List<ScanPage> others = new ArrayList<>();
        for (ScanPage other : session.pages()) {
            if (other.id.equals(page.id)) continue;
            other.filter = chosen;
            if (other.hasWarp()) others.add(other);
        }
        session.save();
        if (others.isEmpty()) {
            finish();
            return;
        }
        PageRenderer.refilterAll(others, budget, error -> {
            if (isFinishing() || isDestroyed()) return;
            Ui.toast(this, getString(error == null
                    ? R.string.scanner_pages_filter_applied : R.string.scanner_pdf_export_failed));
            finish();
        });
    }

    private void setPanelEnabled(boolean enabled) {
        binding.btnRotate.setEnabled(enabled);
        binding.btnRecheck.setEnabled(enabled);
        binding.btnFullArea.setEnabled(enabled);
        binding.btnRetake.setEnabled(enabled);
        binding.btnConfirm.setEnabled(enabled);
        binding.filterStrip.setEnabled(enabled);
    }

    private static void recycleQuietly(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }
}
