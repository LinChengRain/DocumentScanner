package com.documentscanner.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.documentscanner.R;
import com.documentscanner.cv.FilterType;
import com.documentscanner.cv.PageRenderer;
import com.documentscanner.databinding.ActivityPageListBinding;
import com.documentscanner.export.ExportNames;
import com.documentscanner.export.GallerySaver;
import com.documentscanner.export.PaperSize;
import com.documentscanner.export.PdfExporter;
import com.documentscanner.export.ShareFiles;
import com.documentscanner.model.ScanPage;
import com.documentscanner.model.ScanSession;
import com.documentscanner.ui.adapter.PageAdapter;
import com.documentscanner.util.Io;
import com.documentscanner.util.Ui;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 页面管理：排序、删除、重命名，并把整份会话导出为 PDF / 图片。
 * 拖拽排序直接改的是 {@link ScanSession#pages()} 这个列表本身，缩略图与导出顺序共用同一份数据。
 */
public class PageListActivity extends AppCompatActivity implements PageAdapter.Listener {

    private static final String TAG = "PageListActivity";
    private static final int GRID_SPAN = 3;

    private ActivityPageListBinding binding;
    private ScanSession session;
    private PageAdapter adapter;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityPageListBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        session = ScanSession.get(this);
        adapter = new PageAdapter(session.pages(), this);
        binding.pageGrid.setLayoutManager(new GridLayoutManager(this, GRID_SPAN));
        binding.pageGrid.setAdapter(adapter);
        attachDragReorder();

        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnRename.setOnClickListener(v -> showRenameDialog());
        binding.btnClear.setOnClickListener(v -> confirmNewSession());
        binding.btnAddPage.setOnClickListener(v ->
                startActivity(new Intent(this, ScanActivity.class)));
        binding.btnExportPdf.setOnClickListener(v -> exportPdf());
        binding.btnSaveGallery.setOnClickListener(v -> saveToGallery());
        binding.btnShareImages.setOnClickListener(v -> shareImages());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        binding.tvTitle.setText(
                getString(R.string.pages_header, session.getTitle(), session.size()));
        boolean empty = session.isEmpty();
        binding.tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.bottomPanel.setVisibility(empty ? View.GONE : View.VISIBLE);
        adapter.notifyDataSetChanged();
    }

    // ---- 网格交互 ----------------------------------------------------------

    private void attachDragReorder() {
        // 三列网格：横向拖动换列内位置，纵向拖动换行，四个方向都要开
        ItemTouchHelper helper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN
                        | ItemTouchHelper.START | ItemTouchHelper.END, 0) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView,
                                  @NonNull RecyclerView.ViewHolder from,
                                  @NonNull RecyclerView.ViewHolder to) {
                int fromPosition = from.getBindingAdapterPosition();
                int toPosition = to.getBindingAdapterPosition();
                session.move(fromPosition, toPosition);
                adapter.notifyItemMoved(fromPosition, toPosition);
                adapter.notifyItemChanged(fromPosition);
                adapter.notifyItemChanged(toPosition);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder holder, int direction) {
                // 只支持拖拽，不支持滑动删除
            }

            @Override
            public boolean isLongPressDragEnabled() {
                return !busy;
            }
        });
        helper.attachToRecyclerView(binding.pageGrid);
    }

    @Override
    public void onPageClick(ScanPage page, int position) {
        if (busy) return;
        startActivity(CropActivity.intentFor(this, page.id));
    }

    @Override
    public void onPageDelete(ScanPage page, int position) {
        if (busy) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pages_delete_title)
                .setMessage(getString(R.string.pages_delete_body, position + 1))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    session.remove(page.id);
                    refresh();
                })
                .show();
    }

    @Override
    public void onPageMenu(ScanPage page, int position) {
        if (busy) return;
        String[] actions = {
                getString(R.string.pages_duplicate),
                getString(R.string.pages_page_filter),
                getString(R.string.pages_retake)};
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.pages_menu_title, position + 1))
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) duplicatePage(page);
                    else if (which == 1) showFilterPicker(page);
                    else retakePage(page);
                })
                .show();
    }

    private void duplicatePage(ScanPage page) {
        if (session.size() >= ScanActivity.MAX_PAGES) {
            Ui.toast(this, getString(R.string.scan_pages_full, ScanActivity.MAX_PAGES));
            return;
        }
        ScanPage copy = session.duplicate(page.id);
        if (copy == null) return;
        refresh();
        Ui.toast(this, getString(R.string.pages_duplicated, session.indexOf(copy.id) + 1));
    }

    private void showFilterPicker(ScanPage page) {
        String[] names = getResources().getStringArray(R.array.filter_names);
        FilterType[] types = FilterType.values();
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pages_filter_title)
                .setSingleChoiceItems(names, page.filter.ordinal(), (dialog, which) -> {
                    dialog.dismiss();
                    if (which >= 0 && which < types.length) applyPageFilter(page, types[which]);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void applyPageFilter(ScanPage page, FilterType type) {
        if (busy || page.filter == type) return;
        page.filter = type;
        if (!page.hasWarp()) {
            // 还没确认过裁剪，没有 warp 可复用；只记下选择，渲染时会带上
            session.save();
            refresh();
            return;
        }
        beginBusy();
        PageRenderer.refilter(page, error -> {
            endBusy();
            if (isFinishing() || isDestroyed()) return;
            if (error != null) {
                Ui.toast(this, R.string.pages_filter_failed);
                return;
            }
            session.save();
            refresh();
        });
    }

    private void retakePage(ScanPage page) {
        startActivity(ScanActivity.replaceIntent(this, page.id));
    }

    private void showRenameDialog() {
        final EditText input = new EditText(this);
        input.setText(session.getTitle());
        input.setSelection(input.getText().length());
        input.setHint(R.string.pages_rename_hint);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pages_rename)
                .setView(wrap(input))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    String value = String.valueOf(input.getText()).trim();
                    if (!TextUtils.isEmpty(value)) {
                        session.setTitle(value);
                        refresh();
                    }
                })
                .show();
    }

    private View wrap(View child) {
        FrameLayout container = new FrameLayout(this);
        int margin = (int) Ui.dp(this, 20f);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(margin, margin / 2, margin, 0);
        container.addView(child, params);
        return container;
    }

    private void confirmNewSession() {
        if (busy) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pages_clear_title)
                .setMessage(R.string.pages_new_session_body)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.pages_new_session, (dialog, which) -> {
                    // 当前会话整份留在历史里，回到首页换一份干净的目录
                    ScanSession.startNew(PageListActivity.this);
                    finish();
                })
                .show();
    }

    // ---- 导出前的补齐 ------------------------------------------------------

    /** 把还没确认过裁剪的页面按自动识别结果补渲染，逐个串行以免同时压满内存。 */
    private void preparePendingThen(Runnable whenReady) {
        final List<ScanPage> pending = new ArrayList<>();
        for (ScanPage page : session.pages()) {
            if (!page.edited || !page.hasResult()) pending.add(page);
        }
        if (pending.isEmpty()) {
            whenReady.run();
            return;
        }
        beginBusy();
        new Runnable() {
            int index;

            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) {
                    endBusy();
                    return;
                }
                if (index >= pending.size()) {
                    endBusy();
                    adapter.notifyDataSetChanged();
                    whenReady.run();
                    return;
                }
                ScanPage page = pending.get(index++);
                PageRenderer.render(page, page.quad, error -> {
                    if (error != null) {
                        Log.w(TAG, "页面补齐失败 " + page.id, error);
                        Ui.toast(PageListActivity.this,
                                getString(R.string.pages_unedited_warning, pending.size() - index + 1));
                    }
                    run();
                });
            }
        }.run();
    }

    // ---- 导出 ------------------------------------------------------------

    private static final PaperSize[] PAPER_SIZES = {
            PaperSize.A4, PaperSize.LETTER, PaperSize.FOLLOW_IMAGE};

    private void exportPdf() {
        if (busy) return;
        preparePendingThen(this::askPaperSize);
    }

    /** 纸张尺寸在导出前问一次：A4 归一会让每页都留白边，跟随图片则完全按扫描比例。 */
    private void askPaperSize() {
        String[] labels = {
                getString(R.string.paper_a4),
                getString(R.string.paper_letter),
                getString(R.string.paper_follow)};
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pdf_paper_title)
                .setItems(labels, (dialog, which) -> exportPdfWith(PAPER_SIZES[which]))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void exportPdfWith(PaperSize paper) {
        final List<ScanPage> ready = exportablePages();
        if (ready.isEmpty()) {
            Ui.toast(this, R.string.pages_empty);
            return;
        }
        beginBusy();
        List<File> images = new ArrayList<>();
        final ArrayList<String> pageIds = new ArrayList<>();
        for (ScanPage page : ready) {
            images.add(page.bestOutput());
            pageIds.add(page.id);
        }
        final File target = ExportNames.unique(session.exportDir(), session.getSafeTitle(), ".pdf");
        Io.bg(() -> {
            File written = null;
            Throwable error = null;
            try {
                written = PdfExporter.export(images, target, paper, null);
            } catch (Exception e) {
                Log.e(TAG, "导出 PDF 失败", e);
                error = e;
            }
            final File pdf = written;
            final Throwable failure = error;
            Io.main(() -> {
                endBusy();
                if (isFinishing() || isDestroyed()) return;
                if (pdf == null) {
                    Ui.toast(this, getString(R.string.pdf_export_failed) + "："
                            + String.valueOf(failure == null ? "" : failure.getMessage()));
                    return;
                }
                startActivity(PdfPreviewActivity.intentFor(
                        this, pdf, session.getTitle(), pageIds, paper));
            });
        });
    }

    private void saveToGallery() {
        if (busy) return;
        final List<File> images = outputFiles();
        if (images.isEmpty()) {
            Ui.toast(this, R.string.pages_empty);
            return;
        }
        beginBusy();
        final String prefix = session.getSafeTitle();
        Io.bg(() -> {
            int saved = GallerySaver.saveImages(this, images, prefix, null);
            Io.main(() -> {
                endBusy();
                if (isFinishing() || isDestroyed()) return;
                Ui.toast(this, saved > 0
                        ? getString(R.string.pdf_saved_to, GallerySaver.ALBUM)
                        : getString(R.string.pdf_export_failed));
            });
        });
    }

    private void shareImages() {
        if (busy) return;
        List<File> images = outputFiles();
        if (images.isEmpty()) {
            Ui.toast(this, R.string.pages_empty);
            return;
        }
        Intent chooser = Intent.createChooser(
                ShareFiles.multiple(this, images, "image/jpeg",
                        getString(R.string.share_subject, session.getTitle())),
                getString(R.string.pages_share));
        try {
            startActivity(chooser);
        } catch (Exception e) {
            Ui.toast(this, R.string.pdf_no_app);
        }
    }

    /** 有可用产物的页面，顺序即导出顺序。 */
    private List<ScanPage> exportablePages() {
        List<ScanPage> ready = new ArrayList<>();
        for (ScanPage page : session.pages()) {
            File output = page.bestOutput();
            if (output != null && output.exists()) ready.add(page);
        }
        return ready;
    }

    private List<File> outputFiles() {
        List<File> files = new ArrayList<>();
        for (ScanPage page : exportablePages()) {
            files.add(page.bestOutput());
        }
        return files;
    }

    private void beginBusy() {
        busy = true;
        binding.progress.setVisibility(View.VISIBLE);
        setActionsEnabled(false);
    }

    private void endBusy() {
        busy = false;
        if (isFinishing() || isDestroyed()) return;
        binding.progress.setVisibility(View.GONE);
        setActionsEnabled(true);
    }

    private void setActionsEnabled(boolean enabled) {
        binding.btnAddPage.setEnabled(enabled);
        binding.btnExportPdf.setEnabled(enabled);
        binding.btnSaveGallery.setEnabled(enabled);
        binding.btnShareImages.setEnabled(enabled);
        binding.btnClear.setEnabled(enabled);
    }
}
