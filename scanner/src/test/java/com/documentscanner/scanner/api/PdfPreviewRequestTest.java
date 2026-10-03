package com.documentscanner.scanner.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.documentscanner.scanner.export.PaperSize;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 预览请求的容错与不可变性：这几个 null 分支就是四个 extra 原先的隐式约定。 */
public class PdfPreviewRequestTest {

    @Test
    public void missingPageIdsBecomeAnEmptyList() {
        PdfPreviewRequest request =
                new PdfPreviewRequest(new File("/tmp/a.pdf"), null, null, null);

        assertTrue(request.pageIds().isEmpty());
        assertEquals(PaperSize.FOLLOW_IMAGE, request.paper());
        assertEquals(null, request.title());
    }

    @Test
    public void thePageIdListIsACopyTheCallerCannotChangeLater() {
        List<String> source = new ArrayList<>(Arrays.asList("p1", "p2"));
        PdfPreviewRequest request =
                new PdfPreviewRequest(new File("/tmp/a.pdf"), "标题", source, PaperSize.A4);

        source.clear();

        assertEquals(Arrays.asList("p1", "p2"), request.pageIds());
        assertThrows(UnsupportedOperationException.class, () -> request.pageIds().add("p3"));
    }

    @Test
    public void aMissingPdfFileIsRejectedAtConstruction() {
        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () ->
                        new PdfPreviewRequest(null, "标题", null, PaperSize.A4));

        assertEquals("pdf == null", error.getMessage());
    }
}
