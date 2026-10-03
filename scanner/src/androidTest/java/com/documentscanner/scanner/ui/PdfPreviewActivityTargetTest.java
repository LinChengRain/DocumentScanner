package com.documentscanner.scanner.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.documentscanner.scanner.R;
import com.documentscanner.scanner.api.PdfPreviewRequest;
import com.documentscanner.scanner.api.ScannerConfig;
import com.documentscanner.scanner.cv.ImageBudget;
import com.documentscanner.scanner.export.PaperSize;
import com.documentscanner.scanner.export.PdfExporter;
import com.documentscanner.scanner.model.ScanPage;
import com.documentscanner.scanner.model.ScanSession;
import com.documentscanner.scanner.model.SessionFixtures;
import com.documentscanner.scanner.util.ImageIO;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;

/**
 * 宿主设的体积上限真的走到了 PDF 预览页的重新合成里。
 *
 * <p>导出器那层有用例直接调 {@code exportFitting}，可那证明不了三块界面读到了旋钮——
 * 少读一处就是「配置页里改了、界面上照旧」。这里走真实一屏：起屏 → 删页 → 等它重写成品。
 */
@RunWith(AndroidJUnit4.class)
public class PdfPreviewActivityTargetTest {

    private static final int PAGE_WIDTH = 1700;
    private static final int PAGE_HEIGHT = 2400;
    private static final long NOISE_SEED = 42L;

    /** 一页按出厂档合成是 2,333,347 B；800KB 的上限必须把它砍下来。 */
    private static final int TARGET_KB = 800;
    private static final long TARGET_BYTES = TARGET_KB * 1024L;

    private Context context;
    private File dir;
    private ScanSession session;
    private final List<String> pageIds = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        context = SessionFixtures.context();
        SessionFixtures.resetWorld();
        dir = new File(context.getCacheDir(), "pdf-preview-target-test");
        deleteRecursively(dir);
        assertTrue(dir.mkdirs());

        session = ScanSession.get(context);
        session.setTitle("上限用例");
        for (int i = 0; i < 2; i++) {
            ScanPage page = session.createPage();
            SessionFixtures.write(page.original, "photo");
            writeNoisePage(page.result);
            page.edited = true;
            pageIds.add(page.id);
        }
        session.save();
    }

    @After
    public void tearDown() throws Exception {
        ScannerConfig.resetForTesting();
        deleteRecursively(dir);
        SessionFixtures.resetWorld();
    }

    @Test
    public void theRewriteHonoursTheHostsSizeTarget() throws Exception {
        ScannerConfig.setMaxPdfSizeKb(TARGET_KB);
        File pdf = exportAll();
        assertTrue("图样本身要大于上限，否则这条用例是空话：" + pdf.length(),
                pdf.length() > TARGET_BYTES * 2);

        deleteFirstPageAndAwaitRewrite(pdf);

        assertTrue("重写后的成品该装进上限：" + pdf.length() + " B > " + TARGET_BYTES,
                pdf.length() <= TARGET_BYTES);
        assertEquals(1, pageCountOf(pdf));
    }

    /** 对照组：同一条路径不设上限时保持原样，说明上一条里变小确实是上限带来的。 */
    @Test
    public void theSameRewriteWithoutATargetKeepsTheFullSize() throws Exception {
        File pdf = exportAll();

        deleteFirstPageAndAwaitRewrite(pdf);

        assertTrue("没设上限就不该缩：" + pdf.length(), pdf.length() > TARGET_BYTES * 2);
    }

    /**
     * 上限是起屏时取的：屏开着的时候宿主改配置，不该影响这一屏接下来的重写。
     * 与 {@code ScannerConfig} 顶上那句「读到的是起那一屏时的值」是同一条约定。
     */
    @Test
    public void theTargetIsTakenWhenTheScreenOpens() throws Exception {
        ScannerConfig.setMaxPdfSizeKb(TARGET_KB);
        File pdf = exportAll();
        long before = pdf.length();

        try (ActivityScenario<PdfPreviewActivity> scenario =
                     ActivityScenario.launch(PdfPreviewActivity.intentFor(
                             context, new PdfPreviewRequest(pdf, "上限用例", pageIds,
                                     PaperSize.A4)))) {
            final PdfPreviewActivity[] holder = new PdfPreviewActivity[1];
            scenario.onActivity(activity -> holder[0] = activity);
            final PdfPreviewActivity activity = holder[0];
            waitUntil("PDF 装载完成", () -> pagesRendered(activity) > 0);

            ScannerConfig.setMaxPdfSizeKb(0);
            onMain(activity, () -> {
                activity.removePage(0);
                return null;
            });
            waitUntil("重写完成（字节变了）", () -> pdf.length() != before);
        }

        assertTrue("起屏时取到的上限该管到这次重写：" + pdf.length(),
                pdf.length() <= TARGET_BYTES);
        assertEquals(1, pageCountOf(pdf));
    }

    // ---- 辅助 --------------------------------------------------------------

    private void deleteFirstPageAndAwaitRewrite(File pdf) throws Exception {
        long before = pdf.length();
        try (ActivityScenario<PdfPreviewActivity> scenario =
                     ActivityScenario.launch(PdfPreviewActivity.intentFor(
                             context, new PdfPreviewRequest(pdf, "上限用例", pageIds,
                                     PaperSize.A4)))) {
            final PdfPreviewActivity[] holder = new PdfPreviewActivity[1];
            scenario.onActivity(activity -> holder[0] = activity);
            final PdfPreviewActivity activity = holder[0];
            waitUntil("PDF 装载完成", () -> pagesRendered(activity) > 0);

            onMain(activity, () -> {
                activity.removePage(0);
                return null;
            });
            // 重写走的是临时名 + 改名的路子，所以字节数只在最后一轮成品落地那一刻变
            waitUntil("重写完成（字节变了）", () -> pdf.length() != before);
        }
    }

    /** 连续排布的列表项数量——装载完成前一直是 0。 */
    private static int pagesRendered(Activity activity) {
        return onMain(activity, () ->
                ((RecyclerView) activity.findViewById(R.id.rv_pdf_pages)).getChildCount());
    }

    private File exportAll() throws IOException {
        List<File> images = new ArrayList<>();
        for (ScanPage page : session.pages()) images.add(page.bestOutput());
        return PdfExporter.export(images, new File(dir, "上限.pdf"),
                PaperSize.A4, ImageBudget.DEFAULT, null);
    }

    /** 与导出器那层同一份确定性图样，两边量出来的字节才对得上。 */
    private void writeNoisePage(File target) throws IOException {
        Bitmap page = Bitmap.createBitmap(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.Config.ARGB_8888);
        try {
            Random random = new Random(NOISE_SEED);
            int[] pixels = new int[PAGE_WIDTH * PAGE_HEIGHT];
            for (int i = 0; i < pixels.length; i++) {
                int v = 190 + random.nextInt(60);
                pixels[i] = (0xFF << 24) | (v << 16) | (v << 8) | v;
            }
            page.setPixels(pixels, 0, PAGE_WIDTH, 0, 0, PAGE_WIDTH, PAGE_HEIGHT);
            ImageIO.saveJpeg(page, target, ImageBudget.STANDARD.jpegQuality);
        } finally {
            page.recycle();
        }
    }

    private int pageCountOf(File pdf) throws IOException {
        ParcelFileDescriptor descriptor =
                ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY);
        PdfRenderer renderer = new PdfRenderer(descriptor);
        try {
            return renderer.getPageCount();
        } finally {
            renderer.close();
            descriptor.close();
        }
    }

    private static <T> T onMain(Activity activity, Callable<T> body) {
        final Object[] box = new Object[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                box[0] = body.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        @SuppressWarnings("unchecked")
        T value = (T) box[0];
        return value;
    }

    private static void waitUntil(String what, Callable<Boolean> condition) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 20_000;
        while (SystemClock.uptimeMillis() < deadline) {
            if (Boolean.TRUE.equals(condition.call())) return;
            Thread.sleep(50);
        }
        fail("等待超时：" + what);
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }
}
