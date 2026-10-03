package com.documentscanner.scanner.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.content.Intent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.documentscanner.scanner.export.PaperSize;
import com.documentscanner.scanner.ui.CropActivity;
import com.documentscanner.scanner.ui.PageListActivity;
import com.documentscanner.scanner.ui.PdfPreviewActivity;
import com.documentscanner.scanner.ui.ScanActivity;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * 门面产出的 Intent 契约：跳转到哪一屏、extra 用什么 key、值对不对。
 *
 * <p>这里故意把 key 写成字面量（{@code "page_id"} 这一批字符串在门面之外没有第二份定义，
 * 想核对契约只能这么写）。它们是跨屏 wire format 的锁：模块内部把常量改了名、
 * 或某处漏传一个 key，编译期都不会报错，只有这类断言能当场变红。
 */
@RunWith(AndroidJUnit4.class)
public class ScannerIntentTest {

    private Context context;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @Test
    public void scanIntentTargetsTheCameraWithoutAReplaceKey() {
        Intent intent = Scanner.scanIntent(context);

        assertEquals(ScanActivity.class.getName(), intent.getComponent().getClassName());
        assertNull(intent.getStringExtra("replace_page"));
    }

    @Test
    public void replacingScanCarriesThePageToOverwrite() {
        Intent intent = Scanner.scanReplacingIntent(context, "p7");

        assertEquals(ScanActivity.class.getName(), intent.getComponent().getClassName());
        assertEquals("p7", intent.getStringExtra("replace_page"));
    }

    @Test
    public void cropIntentCarriesThePageToEdit() {
        Intent intent = Scanner.cropIntent(context, "p7");

        assertEquals(CropActivity.class.getName(), intent.getComponent().getClassName());
        assertEquals("p7", intent.getStringExtra("page_id"));
    }

    @Test
    public void pagesIntentTargetsThePageList() {
        Intent intent = Scanner.pagesIntent(context);

        assertEquals(PageListActivity.class.getName(), intent.getComponent().getClassName());
    }

    @Test
    public void pdfPreviewIntentRoundTripsTheRequestThroughTheExtras() {
        File pdf = new File("/data/data/com.documentscanner/files/export/扫描件.pdf");
        PdfPreviewRequest request = new PdfPreviewRequest(
                pdf, "扫描件", Arrays.asList("p1", "p2"), PaperSize.A4);

        Intent intent = Scanner.pdfPreviewIntent(context, request);

        assertEquals(PdfPreviewActivity.class.getName(), intent.getComponent().getClassName());
        assertEquals(pdf.getAbsolutePath(), intent.getStringExtra("pdf_path"));
        assertEquals("扫描件", intent.getStringExtra("pdf_title"));
        assertEquals(Arrays.asList("p1", "p2"), intent.getStringArrayListExtra("page_ids"));
        assertEquals("A4", intent.getStringExtra("paper_size"));
    }

    @Test
    public void pdfPreviewIntentCarriesAnEmptyPageListRatherThanNothing() {
        Intent intent = Scanner.pdfPreviewIntent(context, new PdfPreviewRequest(
                new File("/tmp/a.pdf"), null, new ArrayList<String>(), PaperSize.FOLLOW_IMAGE));

        assertEquals(new ArrayList<String>(), intent.getStringArrayListExtra("page_ids"));
        assertEquals("FOLLOW_IMAGE", intent.getStringExtra("paper_size"));
        assertNull(intent.getStringExtra("pdf_title"));
    }
}
