package com.documentscanner.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.documentscanner.R;
import com.documentscanner.databinding.ActivityPdfPreviewBinding;
import com.documentscanner.databinding.ItemPdfPageBinding;
import com.documentscanner.databinding.ItemPdfThumbBinding;
import com.documentscanner.export.ExportNames;
import com.documentscanner.export.PaperSize;
import com.documentscanner.export.PdfExporter;
import com.documentscanner.export.ShareFiles;
import com.documentscanner.model.ScanPage;
import com.documentscanner.model.ScanSession;
import com.documentscanner.util.Io;
import com.documentscanner.util.Ui;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 导出结果预览：用框架自带的 PdfRenderer 逐页渲染，避免引入解码库。
 * PdfRenderer 不是线程安全的，因此所有渲染都在 {@link #renderLock} 下串行进行。
 * 删页走「从会话移除 + 重新导出」，所以预览里的 PDF 与页面管理始终是同一份来源。
 */
public class PdfPreviewActivity extends AppCompatActivity {

    private static final String TAG = "PdfPreview";
    private static final String EXTRA_PATH = "pdf_path";
    private static final String EXTRA_TITLE = "pdf_title";
    private static final String EXTRA_PAGE_IDS = "page_ids";
    private static final String EXTRA_PAPER = "paper_size";
    /** 单页渲染长边：够看清正文，又不至于让内存吃紧。 */
    private static final int RENDER_EDGE = 1280;
    private static final int THUMB_EDGE = 220;
    private static final int CACHE_BYTES = 24 * 1024 * 1024;

    private ActivityPdfPreviewBinding binding;
    private File pdfFile;
    private String title;
    private PaperSize paper = PaperSize.FOLLOW_IMAGE;
    private final List<String> pageIds = new ArrayList<>();
    private ScanSession session;

    private ParcelFileDescriptor descriptor;
    private PdfRenderer renderer;
    private final Object renderLock = new Object();
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(CACHE_BYTES) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private ActivityResultLauncher<Intent> createDocumentLauncher;

    public static Intent intentFor(Context context, File pdf, String title,
                                   List<String> pageIds, PaperSize paper) {
        ArrayList<String> ids = pageIds == null ? new ArrayList<>() : new ArrayList<>(pageIds);
        return new Intent(context, PdfPreviewActivity.class)
                .putExtra(EXTRA_PATH, pdf.getAbsolutePath())
                .putExtra(EXTRA_TITLE, title)
                .putStringArrayListExtra(EXTRA_PAGE_IDS, ids)
                .putExtra(EXTRA_PAPER, paper == null ? PaperSize.FOLLOW_IMAGE.name() : paper.name());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityPdfPreviewBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        String path = getIntent().getStringExtra(EXTRA_PATH);
        pdfFile = path == null ? null : new File(path);
        title = getIntent().getStringExtra(EXTRA_TITLE);
        List<String> ids = getIntent().getStringArrayListExtra(EXTRA_PAGE_IDS);
        if (ids != null) pageIds.addAll(ids);
        paper = readPaper();
        if (title == null) title = getString(R.string.pdf_title);
        if (pdfFile == null || !pdfFile.exists()) {
            Ui.toast(this, R.string.pdf_export_failed);
            finish();
            return;
        }
        session = ScanSession.get(this);

        binding.tvTitle.setText(title);
        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnShare.setOnClickListener(v -> sharePdf());
        binding.btnSaveDevice.setOnClickListener(v -> saveToDevice());
        binding.btnDeletePage.setVisibility(pageIds.isEmpty() ? View.GONE : View.VISIBLE);
        binding.btnDeletePage.setOnClickListener(v -> confirmDeleteCurrentPage());
        binding.tvPageIndicator.setOnClickListener(v -> toggleThumbs());
        binding.rvPdfThumbs.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        binding.pagerPdf.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateIndicator(position);
                highlightThumb(position);
            }
        });

        createDocumentLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        copyTo(result.getData().getData());
                    }
                });

        populate(0);
    }

    private PaperSize readPaper() {
        String name = getIntent().getStringExtra(EXTRA_PAPER);
        if (name == null) return PaperSize.FOLLOW_IMAGE;
        try {
            return PaperSize.valueOf(name);
        } catch (IllegalArgumentException e) {
            return PaperSize.FOLLOW_IMAGE;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseRenderer();
    }

    // ---- 打开与渲染 --------------------------------------------------------

    private void populate(int startIndex) {
        cache.evictAll();
        binding.progress.setVisibility(View.VISIBLE);
        Io.bg(() -> {
            int pageCount = 0;
            Throwable error = null;
            try {
                descriptor = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY);
                renderer = new PdfRenderer(descriptor);
                pageCount = renderer.getPageCount();
            } catch (Exception e) {
                Log.e(TAG, "打开 PDF 失败", e);
                error = e;
                releaseRenderer();
            }
            final int total = pageCount;
            final Throwable failure = error;
            Io.main(() -> {
                binding.progress.setVisibility(View.GONE);
                if (isFinishing() || isDestroyed()) return;
                if (total <= 0) {
                    Ui.toast(this, getString(R.string.pdf_export_failed) + "："
                            + String.valueOf(failure == null ? "" : failure.getMessage()));
                    finish();
                    return;
                }
                binding.tvSubtitle.setText(
                        getString(R.string.pdf_size, total, humanSize(pdfFile.length())));
                binding.pagerPdf.setAdapter(new PageAdapter(total));
                binding.rvPdfThumbs.setAdapter(new ThumbAdapter(total));
                int at = Math.max(0, Math.min(startIndex, total - 1));
                binding.pagerPdf.setCurrentItem(at, false);
                binding.rvPdfThumbs.scrollToPosition(at);
                updateIndicator(at);
                highlightThumb(at);
            });
        });
    }

    private void releaseRenderer() {
        synchronized (renderLock) {
            if (renderer != null) {
                renderer.close();
                renderer = null;
            }
            closeDescriptor();
        }
    }

    private void closeDescriptor() {
        if (descriptor != null) {
            try {
                descriptor.close();
            } catch (IOException ignored) {
                // 忽略
            }
            descriptor = null;
        }
    }

    private Bitmap renderPage(int index, int edge) {
        String key = keyOf(index, edge);
        Bitmap cached = cache.get(key);
        if (cached != null && !cached.isRecycled()) return cached;
        synchronized (renderLock) {
            if (renderer == null || index < 0 || index >= renderer.getPageCount()) return null;
            try (PdfRenderer.Page page = renderer.openPage(index)) {
                float ratio = page.getWidth() / (float) Math.max(1, page.getHeight());
                int width;
                int height;
                if (ratio >= 1f) {
                    width = edge;
                    height = Math.max(1, Math.round(edge / ratio));
                } else {
                    height = edge;
                    width = Math.max(1, Math.round(edge * ratio));
                }
                Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                out.eraseColor(Color.WHITE);
                page.render(out, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                cache.put(key, out);
                return out;
            } catch (Exception e) {
                Log.w(TAG, "渲染第 " + (index + 1) + " 页失败", e);
                return null;
            }
        }
    }

    /** 文件名与尺寸参与键值，重新导出或换清晰度后不会命中旧缓存。 */
    private String keyOf(int index, int edge) {
        return pdfFile.getName() + "-" + pdfFile.length() + "#" + edge + "-" + index;
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024f);
        return String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f));
    }

    // ---- 缩略图抽屉 --------------------------------------------------------

    private void toggleThumbs() {
        boolean show = binding.rvPdfThumbs.getVisibility() != View.VISIBLE;
        binding.rvPdfThumbs.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) binding.rvPdfThumbs.scrollToPosition(binding.pagerPdf.getCurrentItem());
    }

    private void highlightThumb(int position) {
        RecyclerView.Adapter<?> adapter = binding.rvPdfThumbs.getAdapter();
        if (adapter != null) {
            adapter.notifyItemRangeChanged(0, adapter.getItemCount(), SELECTION);
        }
    }

    private static final String SELECTION = "selection";

    // ---- 删页 --------------------------------------------------------------

    private void confirmDeleteCurrentPage() {
        int index = binding.pagerPdf.getCurrentItem();
        if (index < 0 || index >= pageIds.size()) return;
        if (pageIds.size() <= 1) {
            Ui.toast(this, R.string.pdf_last_page);
            return;
        }
        final int page = index + 1;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pdf_delete_title)
                .setMessage(getString(R.string.pdf_delete_body, page))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> removePage(index))
                .show();
    }

    /** 删页即「这份文档少一页」，所以同步删掉会话里的页面，两边不会分叉。确认弹窗的回调走这里。 */
    void removePage(int index) {
        String doomed = pageIds.remove(index);
        session.remove(doomed);
        Ui.toast(this, getString(R.string.pdf_page_deleted, index + 1));
        rebuild();
    }

    private void rebuild() {
        final List<File> images = new ArrayList<>();
        for (String id : pageIds) {
            ScanPage page = session.byId(id);
            File output = page == null ? null : page.bestOutput();
            if (output != null && output.exists()) images.add(output);
        }
        if (images.isEmpty()) {
            Ui.toast(this, R.string.pages_empty);
            finish();
            return;
        }
        binding.progress.setVisibility(View.VISIBLE);
        final File previous = pdfFile;
        final int keepIndex = Math.min(binding.pagerPdf.getCurrentItem(), images.size() - 1);
        Io.bg(() -> {
            // 先写到临时名，成功后再替换，失败时旧的成品还在
            File staging = new File(previous.getParentFile(), previous.getName() + ".tmp");
            File written = null;
            Throwable error = null;
            try {
                written = PdfExporter.export(images, staging, paper, null);
            } catch (Exception e) {
                Log.e(TAG, "重新生成 PDF 失败", e);
                error = e;
            }
            final File source = written;
            final Throwable failure = error;
            Io.main(() -> {
                if (isFinishing() || isDestroyed()) {
                    if (source != null) source.delete();
                    return;
                }
                binding.progress.setVisibility(View.GONE);
                if (source == null) {
                    Ui.toast(this, getString(R.string.pdf_rebuild_failed) + "："
                            + String.valueOf(failure == null ? "" : failure.getMessage()));
                    return;
                }
                releaseRenderer();
                boolean replaced = !previous.exists()
                        || (previous.delete() && source.renameTo(previous));
                if (!replaced) {
                    Log.w(TAG, "替换 PDF 失败，使用新文件名 " + source);
                    previous.delete();
                }
                pdfFile = replaced ? previous : source;
                populate(Math.max(0, keepIndex));
            });
        });
    }

    // ---- 分享与另存 --------------------------------------------------------

    private void sharePdf() {
        Intent chooser = Intent.createChooser(
                ShareFiles.single(this, pdfFile, "application/pdf",
                        getString(R.string.share_subject, title)),
                getString(R.string.pdf_share));
        try {
            startActivity(chooser);
        } catch (Exception e) {
            Ui.toast(this, R.string.pdf_no_app);
        }
    }

    private void saveToDevice() {
        String safeName = ExportNames.sanitize(title);
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/pdf")
                .putExtra(Intent.EXTRA_TITLE, safeName + ".pdf");
        try {
            createDocumentLauncher.launch(intent);
        } catch (Exception e) {
            Ui.toast(this, R.string.pdf_no_app);
        }
    }

    private void copyTo(Uri target) {
        if (target == null) return;
        binding.progress.setVisibility(View.VISIBLE);
        Io.bg(() -> {
            Throwable error = null;
            try (InputStream in = new FileInputStream(pdfFile);
                 OutputStream out = getContentResolver().openOutputStream(target, "w")) {
                if (out == null) throw new IOException("无法写入目标文件");
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
                out.flush();
            } catch (Exception e) {
                Log.e(TAG, "另存为失败", e);
                error = e;
            }
            final Throwable failure = error;
            Io.main(() -> {
                binding.progress.setVisibility(View.GONE);
                if (isFinishing() || isDestroyed()) return;
                Ui.toast(this, failure == null
                        ? getString(R.string.pdf_saved_to, title)
                        : getString(R.string.pdf_export_failed));
            });
        });
    }

    private void updateIndicator(int index) {
        RecyclerView.Adapter<?> adapter = binding.pagerPdf.getAdapter();
        int total = adapter == null ? 0 : adapter.getItemCount();
        binding.tvPageIndicator.setText(getString(R.string.pdf_page_indicator, index + 1, total));
    }

    /** 每页一张 ViewPager2 页面；渲染在后台完成后再回填，避免滑动时卡住主线程。 */
    private class PageAdapter extends RecyclerView.Adapter<PageHolder> {

        private final int total;

        PageAdapter(int total) {
            this.total = total;
        }

        @NonNull
        @Override
        public PageHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new PageHolder(ItemPdfPageBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull PageHolder holder, int position) {
            holder.bind(holder.getBindingAdapterPosition());
        }

        @Override
        public int getItemCount() {
            return total;
        }
    }

    private class PageHolder extends RecyclerView.ViewHolder {

        private final ItemPdfPageBinding itemBinding;
        private int boundIndex = -1;

        PageHolder(ItemPdfPageBinding itemBinding) {
            super(itemBinding.getRoot());
            this.itemBinding = itemBinding;
        }

        void bind(int index) {
            boundIndex = index;
            itemBinding.imgPdfPage.setImageDrawable(null);
            Bitmap ready = cache.get(keyOf(index, RENDER_EDGE));
            if (ready != null && !ready.isRecycled()) {
                itemBinding.progressPage.setVisibility(View.GONE);
                itemBinding.imgPdfPage.setImageBitmap(ready);
                return;
            }
            itemBinding.progressPage.setVisibility(View.VISIBLE);
            Io.bg(() -> {
                Bitmap bitmap = renderPage(index, RENDER_EDGE);
                Io.main(() -> {
                    itemBinding.progressPage.setVisibility(View.GONE);
                    if (boundIndex != index || isFinishing() || isDestroyed() || bitmap == null) return;
                    itemBinding.imgPdfPage.setImageBitmap(bitmap);
                });
            });
        }
    }

    /** 底部抽屉：一次渲染出小尺寸页面，点哪页就跳到哪页。 */
    private class ThumbAdapter extends RecyclerView.Adapter<ThumbHolder> {

        private final int total;

        ThumbAdapter(int total) {
            this.total = total;
        }

        @NonNull
        @Override
        public ThumbHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ThumbHolder(ItemPdfThumbBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ThumbHolder holder, int position) {
            holder.bind(holder.getBindingAdapterPosition());
        }

        @Override
        public void onBindViewHolder(@NonNull ThumbHolder holder, int position, @NonNull List<Object> payloads) {
            if (payloads.contains(SELECTION)) {
                holder.setSelected(binding.pagerPdf.getCurrentItem() == holder.getBindingAdapterPosition());
                return;
            }
            super.onBindViewHolder(holder, position, payloads);
        }

        @Override
        public int getItemCount() {
            return total;
        }
    }

    private class ThumbHolder extends RecyclerView.ViewHolder {

        private final ItemPdfThumbBinding itemBinding;
        private int boundIndex = -1;

        ThumbHolder(ItemPdfThumbBinding itemBinding) {
            super(itemBinding.getRoot());
            this.itemBinding = itemBinding;
            itemView.setOnClickListener(v -> {
                int at = getBindingAdapterPosition();
                if (at != RecyclerView.NO_POSITION) binding.pagerPdf.setCurrentItem(at, true);
            });
        }

        void bind(int index) {
            boundIndex = index;
            itemBinding.tvThumbIndex.setText(String.valueOf(index + 1));
            setSelected(index == binding.pagerPdf.getCurrentItem());
            itemBinding.imgPdfThumb.setImageDrawable(null);
            Bitmap ready = cache.get(keyOf(index, THUMB_EDGE));
            if (ready != null && !ready.isRecycled()) {
                itemBinding.imgPdfThumb.setImageBitmap(ready);
                return;
            }
            Io.bg(() -> {
                Bitmap bitmap = renderPage(index, THUMB_EDGE);
                Io.main(() -> {
                    if (boundIndex != index || isFinishing() || isDestroyed() || bitmap == null) return;
                    itemBinding.imgPdfThumb.setImageBitmap(bitmap);
                });
            });
        }

        void setSelected(boolean selected) {
            itemView.setSelected(selected);
        }
    }
}
