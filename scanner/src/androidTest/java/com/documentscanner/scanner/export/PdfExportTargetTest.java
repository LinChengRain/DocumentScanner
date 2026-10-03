package com.documentscanner.scanner.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

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
 * 体积上限对合成 PDF 的实际作用：写完一份、量真实字节、按指数缩边再写。
 *
 * <p>要证的不是「变小了」（那太容易），而是三件各自会单独坏掉的事：
 * 上限够用时一律不多写；上限卡在两档之间时给出的是<b>连续</b>边长而不是查表；
 * 上限根本够不着时交的是最小那份而不是一个异常。
 */
@RunWith(AndroidJUnit4.class)
public class PdfExportTargetTest {

    /** 与 {@link PdfExportBudgetTest} 同一份确定性图样，基线字节才可比。 */
    private static final int PAGE_WIDTH = 1700;
    private static final int PAGE_HEIGHT = 2400;
    private static final long NOISE_SEED = 42L;

    /** 文档图样的种子：换了它，下面那条用例里的「离下限还留着余量」就得重新量。 */
    private static final long DOCUMENT_SEED = 7L;

    /** 1700x2400 一页 → A4：长边 1800 时 2,333,347 B。 */
    private static final long BASELINE_STANDARD_PDF_BYTES = 2_333_347L;

    /** 卡在 DRAFT 与 STANDARD 的字节之间、离两边都很远的一个上限。 */
    private static final long BETWEEN_TIERS = 1_600_000L;

    private Context context;
    private File dir;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        dir = new File(context.getCacheDir(), "pdf-target-test");
        deleteRecursively(dir);
        assertTrue(dir.mkdirs());
    }

    @After
    public void tearDown() {
        ScannerConfig.resetForTesting();
        deleteRecursively(dir);
    }

    /** 没设上限时必须是原来那份字节：这个功能不该动任何人的默认产物。 */
    @Test
    public void noTargetWritesThePdfThePlainExporterWrites() throws Exception {
        PdfExporter.Outcome outcome = fit(1, 0);

        assertEquals("不设上限就顺带改了所有人已导出的 PDF 字节",
                BASELINE_STANDARD_PDF_BYTES, outcome.bytes);
        assertEquals(1, outcome.writes);
        assertEquals(ImageBudget.STANDARD.pdfDecodeEdge, outcome.decodeEdge);
    }

    /** 第一份就装得进去时，一次都不该多写。 */
    @Test
    public void aTargetTheFirstPdfAlreadyMeetsCostsNoExtraWrite() throws Exception {
        PdfExporter.Outcome outcome = fit(1, BASELINE_STANDARD_PDF_BYTES + 1);

        assertEquals(1, outcome.writes);
        assertEquals(BASELINE_STANDARD_PDF_BYTES, outcome.bytes);
        assertEquals(ImageBudget.STANDARD.pdfDecodeEdge, outcome.decodeEdge);
    }

    /**
     * 上限卡在两档之间时，落点也必须卡在两档之间——这是「连续缩边」与「查表降档」唯一的区别。
     * 写成查表的话这里会一步跳到 DRAFT 甚至 TINY，清晰度白丢一截。
     */
    @Test
    public void aTargetBetweenTiersShrinksToAnEdgeNoTierOwns() throws Exception {
        PdfExporter.Outcome outcome = fit(1, BETWEEN_TIERS);

        assertTrue("超出上限：" + outcome.bytes, outcome.bytes <= BETWEEN_TIERS);
        assertTrue("缩过就得真的多写一轮：" + outcome.writes, outcome.writes > 1);
        assertTrue("边长该严格低于上限档：" + outcome.decodeEdge,
                outcome.decodeEdge < ImageBudget.STANDARD.pdfDecodeEdge);
        assertTrue("留了余量，不该一步踩到 TINY：" + outcome.decodeEdge,
                outcome.decodeEdge > ImageBudget.TINY.pdfDecodeEdge);
        for (ImageBudget budget : ImageBudget.values()) {
            assertTrue("连续缩边不该正好落在某一档上：" + outcome.decodeEdge,
                    outcome.decodeEdge != budget.pdfDecodeEdge);
        }
        assertTrue("写数该远在上限之内（余量的意义）：" + outcome.bytes,
                outcome.bytes <= BETWEEN_TIERS * 0.97);
        assertTrue("也不能一路砍到底浪费清晰度：" + outcome.bytes,
                outcome.bytes >= BETWEEN_TIERS * 0.8);
    }

    /**
     * 够不着的上限不是失败：交最小那份，把超出量写进返回值让调用方看得见。
     * 「因为装不进 10KB 所以一份扫描件没了」是不能接受的行为。
     */
    @Test
    public void anUnreachableTargetShipsTheSmallestPdfInsteadOfAnError() throws Exception {
        PdfExporter.Outcome outcome = fit(1, 10_000L);

        assertEquals("该一步踩到下限就停，不该一像素一像素地磨",
                PdfSizeFit.MIN_DECODE_EDGE, outcome.decodeEdge);
        assertEquals(2, outcome.writes);
        assertTrue(outcome.bytes > 10_000L);
        assertTrue("落到下限后必须比原来小得多：" + outcome.bytes,
                outcome.bytes < BASELINE_STANDARD_PDF_BYTES / 4);
        assertEquals("成品还是那份能打开的 PDF", 1, pageCountOf(outcome.pdf));
        assertTrue(outcome.pdf.isFile());
    }

    /** 同一个上限，页多的那次必须降得更低——证明它量的是真实字节，不是按档猜的数。 */
    @Test
    public void morePagesPushTheEdgeDownForTheSameTarget() throws Exception {
        PdfExporter.Outcome one = fit(1, BETWEEN_TIERS);
        PdfExporter.Outcome three = fit(3, BETWEEN_TIERS);

        assertTrue("三页也该装进同一个上限：" + three.bytes, three.bytes <= BETWEEN_TIERS);
        assertTrue("同上限下三页的边长该更低：" + one.decodeEdge + " / " + three.decodeEdge,
                three.decodeEdge < one.decodeEdge);
    }

    /** 每一轮都覆写同一个目标名，目录里不该多出任何中间产物。 */
    @Test
    public void theFittedExportLeavesNothingElseBehind() throws Exception {
        fit(1, BETWEEN_TIERS);

        String[] names = dir.list();
        assertEquals("除了一张页面图和最终 PDF 还剩文件：" + java.util.Arrays.toString(names),
                2, names == null ? 0 : names.length);
    }

    /**
     * 上限是起导出时取好传进来的，不是逐页现读的：进度回调里翻配置不该影响这一份。
     * 与 {@link PdfExportBudgetTest} 那条同构，只是这次翻的是新旋钮。
     */
    @Test
    public void theTargetIsHandedOnceNotReadMidway() throws Exception {
        List<File> images = pages(1);
        PdfExporter.Outcome steady = PdfExporter.exportFitting(images, new File(dir, "steady.pdf"),
                PaperSize.A4, ImageBudget.STANDARD, BETWEEN_TIERS, null);

        ScannerConfig.setMaxPdfSizeKb(1);
        PdfExporter.Outcome flipped = PdfExporter.exportFitting(images,
                new File(dir, "flipped.pdf"), PaperSize.A4, ImageBudget.STANDARD,
                BETWEEN_TIERS, (index, total) -> ScannerConfig.setMaxPdfSizeKb(900));

        assertEquals("中途改配置不该影响这一份 PDF 的字节", steady.bytes, flipped.bytes);
        assertEquals(steady.decodeEdge, flipped.decodeEdge);
        assertEquals(steady.writes, flipped.writes);
    }

    /**
     * 文档图样上的目标必须三轮内收敛——上面那批用例全用噪声图样，量不到这个。
     *
     * <p>噪声图样的字节斜率是 2.15（先验的来源），真扫描页这边同一份图样只量到 0.73 与 1.45
     * （就是下面那条轨迹的两段）：白纸占了大半面积，
     * 缩边时每像素反而更贵。先验对这种内容乐观了近一倍，release 走查那次真会话就是
     * 1800→1311→1102→993 四轮打完、要 512,000 B 却交了 521,829 B（那条失败由
     * {@code PdfSizeFitTest} 拿实测四点在 JVM 上复现）。
     *
     * <p>这台机上这条图样的实测轨迹：1800 是 469,664 B，第二枪按先验到 1278（365,242 B），
     * 标定斜率之后第三枪落到 697 的 151,352 B 就收工；把标定下掉（永远用先验）的话它要到
     * 第四枪才进目标——上限之内但把预算全花光了，所以下面红的正是「三轮内收敛」那一条。
     */
    @Test
    public void aDocumentLikePdfMeetsAReachableTargetWithoutUsingEveryWrite() throws Exception {
        List<File> images = documentPages();
        long target = 250_000L;

        PdfExporter.Outcome full = PdfExporter.exportFitting(images,
                new File(dir, "document-full.pdf"), PaperSize.A4, ImageBudget.STANDARD, 0L, null);
        assertTrue("图样要是小到不用缩，这条用例就成空转了：" + full.bytes,
                full.bytes > target * 1.5);

        PdfExporter.Outcome outcome = PdfExporter.exportFitting(images,
                new File(dir, "document.pdf"), PaperSize.A4, ImageBudget.STANDARD, target, null);

        assertTrue("超出上限：" + outcome.bytes, outcome.bytes <= target);
        assertTrue("每多一轮就是用户多等一秒，该三轮内收敛：" + outcome.writes,
                outcome.writes <= 3);
        assertTrue("离下限还远着，不该把清晰度直接踩穿：" + outcome.decodeEdge,
                outcome.decodeEdge > PdfSizeFit.MIN_DECODE_EDGE);
    }

    // ---- 辅助 --------------------------------------------------------------

    /** 两页同一张「文档」：多尺度笔画，字节斜率贴近真扫描页。 */
    private List<File> documentPages() throws IOException {
        File file = new File(dir, "document.jpg");
        Bitmap page = Bitmap.createBitmap(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(page);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint();
        paint.setColor(Color.rgb(30, 30, 40));
        Random random = new Random(DOCUMENT_SEED);
        float y = 120f;
        while (y < PAGE_HEIGHT - 80f) {
            int thickness = 6 + random.nextInt(26);
            float x = 110f;
            while (x < PAGE_WIDTH - 140f) {
                float width = 18f + random.nextInt(120);
                canvas.drawRect(x, y, x + width, y + thickness, paint);
                x += width + 8 + random.nextInt(40);
            }
            y += thickness + 10 + random.nextInt(34);
        }
        try {
            ImageIO.saveJpeg(page, file, ImageBudget.STANDARD.jpegQuality);
        } finally {
            page.recycle();
        }
        List<File> images = new ArrayList<>();
        images.add(file);
        images.add(file);
        return images;
    }

    private PdfExporter.Outcome fit(int pageCount, long maxBytes) throws IOException {
        return PdfExporter.exportFitting(pages(pageCount),
                new File(dir, "fit-" + pageCount + "-" + maxBytes + ".pdf"),
                PaperSize.A4, ImageBudget.STANDARD, maxBytes, null);
    }

    private List<File> pages(int count) throws IOException {
        List<File> images = new ArrayList<>();
        for (int i = 0; i < count; i++) images.add(page());
        return images;
    }

    /** 同一份确定性图样反复用：三页那次只是把同一个文件交进去三遍。 */
    private File page() throws IOException {
        File file = new File(dir, "page.jpg");
        if (file.isFile()) return file;
        Bitmap page = Bitmap.createBitmap(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.Config.ARGB_8888);
        Random random = new Random(NOISE_SEED);
        int[] pixels = new int[PAGE_WIDTH * PAGE_HEIGHT];
        for (int i = 0; i < pixels.length; i++) {
            int v = 190 + random.nextInt(60);
            pixels[i] = (0xFF << 24) | (v << 16) | (v << 8) | v;
        }
        page.setPixels(pixels, 0, PAGE_WIDTH, 0, 0, PAGE_WIDTH, PAGE_HEIGHT);
        try {
            ImageIO.saveJpeg(page, file, ImageBudget.STANDARD.jpegQuality);
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
