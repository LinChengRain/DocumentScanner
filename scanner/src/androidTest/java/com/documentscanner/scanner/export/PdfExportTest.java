package com.documentscanner.scanner.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

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

/**
 * PDF 导出的尺寸与页数：纸张归一后每页必须是所选纸张，图片等比内接；
 * 顺带确认真用 PdfRenderer 打得开这份文件。
 */
@RunWith(AndroidJUnit4.class)
public class PdfExportTest {

    private Context context;
    private File dir;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        dir = new File(context.getCacheDir(), "pdf-export-test");
        deleteRecursively(dir);
        assertTrue(dir.mkdirs());
    }

    @After
    public void tearDown() {
        deleteRecursively(dir);
    }

    // ---- 纸张尺寸 -----------------------------------------------------------

    @Test
    public void fixedPaperFollowsTheImageOrientation() {
        PdfDocument.PageInfo portrait = PaperSize.A4.pageInfoFor(bitmap(100, 200), 1);
        assertEquals(595, portrait.getPageWidth());
        assertEquals(842, portrait.getPageHeight());

        PdfDocument.PageInfo landscape = PaperSize.A4.pageInfoFor(bitmap(200, 100), 1);
        assertEquals("横图要配横向纸张", 842, landscape.getPageWidth());
        assertEquals(595, landscape.getPageHeight());

        PdfDocument.PageInfo letter = PaperSize.LETTER.pageInfoFor(bitmap(300, 100), 1);
        assertEquals(792, letter.getPageWidth());
        assertEquals(612, letter.getPageHeight());
    }

    @Test
    public void fixedPaperInscribesTheImageAndCentersIt() {
        Bitmap image = bitmap(500, 1000);
        PdfDocument.PageInfo info = PaperSize.A4.pageInfoFor(image, 1);

        RectF rect = PaperSize.A4.contentRectFor(image, info);

        // 可用高度 842-36=806 是短板，宽按比例 403，再在 595 宽里居中
        assertEquals(806f, rect.height(), 1f);
        assertEquals(403f, rect.width(), 1f);
        assertEquals(18f, rect.top, 1f);
        assertEquals(96f, rect.left, 1f);
        assertEquals(image.getWidth() / (float) image.getHeight(),
                rect.width() / rect.height(), 0.02f);
    }

    @Test
    public void aWideImageIsScaledByWidthAndLetterboxedVertically() {
        Bitmap image = bitmap(900, 1000);
        PdfDocument.PageInfo info = PaperSize.A4.pageInfoFor(image, 1);

        RectF rect = PaperSize.A4.contentRectFor(image, info);

        assertEquals(559f, rect.width(), 1f);   // 宽卡住：595 - 2*18
        assertEquals(621f, rect.height(), 1f);
        assertEquals(18f, rect.left, 1f);
        assertTrue("上下要留出空白", rect.top > 100f);
    }

    @Test
    public void followingTheImageUsesOneLongEdgeAndNoMargin() {
        Bitmap image = bitmap(100, 200);
        PdfDocument.PageInfo info = PaperSize.FOLLOW_IMAGE.pageInfoFor(image, 1);

        assertEquals(842, info.getPageHeight());
        assertEquals(421, info.getPageWidth());
        RectF rect = PaperSize.FOLLOW_IMAGE.contentRectFor(image, info);
        assertEquals(0f, rect.left, 0f);
        assertEquals(0f, rect.top, 0f);
        assertEquals(info.getPageWidth(), rect.right, 0f);
        assertEquals(info.getPageHeight(), rect.bottom, 0f);
    }

    // ---- 真实文件 -----------------------------------------------------------

    @Test
    public void exportedPdfHasEveryPageOnTheChosenPaper() throws Exception {
        List<File> images = new ArrayList<>();
        images.add(writePage("a.jpg", 300, 420));
        images.add(writePage("b.jpg", 420, 300));
        File pdf = new File(dir, "out.pdf");

        PdfExporter.export(images, pdf, PaperSize.A4, ImageBudget.DEFAULT, null);

        assertTrue(pdf.isFile() && pdf.length() > 0);
        assertEquals(2, pageCountAndCheckSizes(pdf, 595, 842, 842, 595));
    }

    @Test
    public void followImagePdfKeepsPerAspectRatio() throws Exception {
        List<File> images = new ArrayList<>();
        images.add(writePage("square.jpg", 400, 400));
        File pdf = new File(dir, "follow.pdf");

        PdfExporter.export(images, pdf, PaperSize.FOLLOW_IMAGE, ImageBudget.DEFAULT, null);

        assertEquals(1, pageCountAndCheckSizes(pdf, 842, 842));
    }

    @Test
    public void missingPagesAreSkippedWhileTheRestStillExports() throws Exception {
        List<File> images = new ArrayList<>();
        images.add(writePage("ok.jpg", 300, 400));
        images.add(new File(dir, "gone.jpg"));
        File pdf = new File(dir, "partial.pdf");

        PdfExporter.export(images, pdf, PaperSize.A4, ImageBudget.DEFAULT, null);

        assertEquals(1, pageCountAndCheckSizes(pdf, 595, 842));
    }

    @Test
    public void nothingExportableIsAnErrorNotAnEmptyFile() throws Exception {
        List<File> images = new ArrayList<>();
        images.add(new File(dir, "gone.jpg"));
        File pdf = new File(dir, "empty.pdf");

        try {
            PdfExporter.export(images, pdf, PaperSize.A4, ImageBudget.DEFAULT, null);
            fail("全页都不可用时应当报错，而不是产出一份空 PDF");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("页面"));
        }
    }

    // ---- 辅助 --------------------------------------------------------------

    /** 打开导出的 PDF，校验页数与每页尺寸（pt），返回页数。 */
    private static int pageCountAndCheckSizes(File pdf, int... expectedwhPairs) throws IOException {
        ParcelFileDescriptor descriptor =
                ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY);
        PdfRenderer renderer = new PdfRenderer(descriptor);
        try {
            assertEquals(expectedwhPairs.length / 2, renderer.getPageCount());
            for (int i = 0; i < renderer.getPageCount(); i++) {
                PdfRenderer.Page page = renderer.openPage(i);
                try {
                    assertEquals("第 " + (i + 1) + " 页宽度",
                            expectedwhPairs[i * 2], page.getWidth(), 2);
                    assertEquals("第 " + (i + 1) + " 页高度",
                            expectedwhPairs[i * 2 + 1], page.getHeight(), 2);
                } finally {
                    page.close();
                }
            }
            return renderer.getPageCount();
        } finally {
            renderer.close();
            descriptor.close();
        }
    }

    private File writePage(String name, int width, int height) throws IOException {
        Bitmap bitmap = bitmap(width, height);
        File file = new File(dir, name);
        ImageIO.saveJpeg(bitmap, file, 90);
        bitmap.recycle();
        return file;
    }

    private static Bitmap bitmap(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint();
        paint.setColor(Color.DKGRAY);
        canvas.drawRect(0, 0, width / 4f, height, paint);
        return bitmap;
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
