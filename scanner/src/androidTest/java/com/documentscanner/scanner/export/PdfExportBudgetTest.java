package com.documentscanner.scanner.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.documentscanner.scanner.api.ScannerConfig;
import com.documentscanner.scanner.cv.ImageBudget;
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

/**
 * 体积档对合成 PDF 的实际作用。
 *
 * <p>这份用例存在的主要理由是：框架的 PdfDocument 不吃页面 JPEG 的字节，它把画上去的位图
 * 按接近无损的质量重编码（本机实测膨胀 2.25~7.5 倍），所以「PDF 多大」只能由交进去的像素数
 * 决定。而这个杠杆很容易被实现弄丢——导出侧原先只按 2 的幂向下取整，1800 的页面配任何
 * 大于 900 的上限都会原样通过，调档于是变成prefs里的一个字符串。
 */
@RunWith(AndroidJUnit4.class)
public class PdfExportBudgetTest {

    /** 长边取最高档的解码边：比它短的图样会让最高档和次高档写出同一个字节数，单调性断言就成了空话。 */
    private static final int PAGE_WIDTH = 1700;
    private static final int PAGE_HEIGHT = 2400;
    private static final long NOISE_SEED = 42L;

    /** 本机（PTP-AN00）上按这份确定性图样量出来的基线，见 docs/scanner-module-plan.md §10。 */
    private static final long BASELINE_PAGE_BYTES = 1970078L;

    /** 1700x2400 图样 → A4 PDF：TINY 537865 / DRAFT 1010445 / STANDARD 2333347 / FINE 4422958。 */
    private static final long BASELINE_STANDARD_PDF_BYTES = 2333347L;

    private Context context;
    private File dir;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        dir = new File(context.getCacheDir(), "pdf-budget-test");
        deleteRecursively(dir);
        assertTrue(dir.mkdirs());
    }

    @After
    public void tearDown() {
        ScannerConfig.resetForTesting();
        deleteRecursively(dir);
    }

    /** 先钉住图样本身：图样不能逐字节重现的话，下面那条 PDF 基线就没有意义。 */
    @Test
    public void theFixtureIsTheOneTheBaselineWasTakenOn() throws Exception {
        File page = writePageAt(ImageBudget.STANDARD);

        assertEquals("页面 JPEG 与取基线时不一致，编码器或图样变了",
                BASELINE_PAGE_BYTES, page.length());
    }

    @Test
    public void theStandardTierStillWritesThePdfItUsedTo() throws Exception {
        List<File> images = new ArrayList<>();
        images.add(writePageAt(ImageBudget.STANDARD));

        File pdf = PdfExporter.export(images, new File(dir, "standard.pdf"),
                PaperSize.A4, ImageBudget.STANDARD, null);

        assertEquals("默认档顺带改了所有人已导出的 PDF 字节",
                BASELINE_STANDARD_PDF_BYTES, pdf.length());
    }

    /** 单调性是唯一能承诺的关系：具体膨胀多少由框架决定，钉死数字只会天天红。 */
    @Test
    public void aBiggerTierWritesABiggerPdf() throws Exception {
        List<File> images = new ArrayList<>();
        images.add(writePageAt(ImageBudget.STANDARD));

        long tiny = export(images, ImageBudget.TINY).length();
        long draft = export(images, ImageBudget.DRAFT).length();
        long standard = export(images, ImageBudget.STANDARD).length();
        long fine = export(images, ImageBudget.FINE).length();

        assertTrue("TINY 应当比 DRAFT 小：" + tiny + " / " + draft, tiny < draft);
        assertTrue("DRAFT 应当比 STANDARD 小：" + draft + " / " + standard, draft < standard);
        assertTrue("FINE 不应比 STANDARD 小：" + fine + " / " + standard, fine > standard);
    }

    /** 每一档都得还是份打得开的 PDF——缩像素很容易顺手把文件写坏。 */
    @Test
    public void everyTierStillProducesAPdfTheRendererOpens() throws Exception {
        List<File> images = new ArrayList<>();
        images.add(writePageAt(ImageBudget.STANDARD));

        for (ImageBudget budget : ImageBudget.values()) {
            File pdf = export(images, budget);
            assertTrue(budget + " 写出空文件", pdf.length() > 0);
            assertEquals(budget + " 页数", 1, pageCountOf(pdf));
        }
    }

    /**
     * 一次导出用一次档位：档位是起导出时取好、随参数传进来的，不是逐页现读的。
     * 否则宿主在进度回调里改一下配置，同一份 PDF 就会前半 1800 后半 900。
     */
    @Test
    public void theExportUsesTheBudgetItWasHandedNotWhateverIsSetMidway() throws Exception {
        List<File> images = new ArrayList<>();
        images.add(writePageAt(ImageBudget.STANDARD));

        File pdf = PdfExporter.export(images, new File(dir, "flip.pdf"), PaperSize.A4,
                ImageBudget.STANDARD, (index, total) -> ScannerConfig.setImageBudget(ImageBudget.TINY));

        assertEquals("导出中途改配置不该影响这一份 PDF",
                BASELINE_STANDARD_PDF_BYTES, pdf.length());
    }

    // ---- 辅助 --------------------------------------------------------------

    private File export(List<File> images, ImageBudget budget) throws IOException {
        return PdfExporter.export(images, new File(dir, budget.name() + ".pdf"),
                PaperSize.A4, budget, null);
    }

    /** 确定性图样：同一台机上每次跑出的位图逐像素一致，字节才可比。 */
    private File writePageAt(ImageBudget budget) throws IOException {
        Bitmap page = Bitmap.createBitmap(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.Config.ARGB_8888);
        Random random = new Random(NOISE_SEED);
        int[] pixels = new int[PAGE_WIDTH * PAGE_HEIGHT];
        for (int i = 0; i < pixels.length; i++) {
            int v = 190 + random.nextInt(60);
            pixels[i] = (0xFF << 24) | (v << 16) | (v << 8) | v;
        }
        page.setPixels(pixels, 0, PAGE_WIDTH, 0, 0, PAGE_WIDTH, PAGE_HEIGHT);
        File file = new File(dir, "page-" + budget.name() + ".jpg");
        try {
            ImageIO.saveJpeg(page, file, budget.jpegQuality);
        } finally {
            page.recycle();
        }
        return file;
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

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }
}
