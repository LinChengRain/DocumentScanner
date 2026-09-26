package com.documentscanner.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

/** 导出文件名分配：同名会话反复导出绝不能互相覆盖。 */
public class ExportNamesTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void theFirstExportKeepsThePlainTitle() {
        File target = ExportNames.unique(folder.getRoot(), "合同", ".pdf");

        assertEquals("合同.pdf", target.getName());
        assertFalse(target.exists());
    }

    @Test
    public void aRepeatedExportMovesToTheNextFreeName() throws IOException {
        File dir = folder.getRoot();
        File first = ExportNames.unique(dir, "合同", ".pdf");
        touch(first);
        File second = ExportNames.unique(dir, "合同", ".pdf");
        touch(second);

        assertEquals("合同 (2).pdf", second.getName());
        assertEquals("合同 (3).pdf", ExportNames.unique(dir, "合同", ".pdf").getName());
    }

    @Test
    public void differentTitlesNeverShareAName() throws IOException {
        File dir = folder.getRoot();
        File invoices = ExportNames.unique(dir, "发票", ".pdf");
        touch(invoices);

        assertEquals("合同.pdf", ExportNames.unique(dir, "合同", ".pdf").getName());
    }

    @Test
    public void separatorsInTheTitleCannotEscapeTheExportDir() {
        File target = ExportNames.unique(folder.getRoot(), "../../etc/passwd", ".pdf");

        assertEquals(folder.getRoot(), target.getParentFile());
        assertEquals(".._.._etc_passwd.pdf", target.getName());
    }

    @Test
    public void blankOrMissingTitlesFallBackToScan() {
        assertEquals("scan", ExportNames.sanitize(null));
        assertEquals("scan", ExportNames.sanitize("   "));
        assertEquals("scan.pdf", ExportNames.unique(folder.getRoot(), "", ".pdf").getName());
    }

    @Test
    public void forbiddenCharactersAreReplacedRatherThanDropped() {
        assertEquals("a_b_c_d", ExportNames.sanitize("a/b:c*d"));
        assertEquals("___", ExportNames.sanitize("***"));
    }

    @Test
    public void numberingIsPartOfTheNameNotTheExtension() {
        File dir = folder.getRoot();
        File image = ExportNames.unique(dir, "页面", ".jpg");

        assertEquals("页面.jpg", image.getName());
        assertTrue(image.getAbsolutePath().endsWith("页面.jpg"));
    }

    private static void touch(File file) throws IOException {
        assertTrue(file.createNewFile());
    }
}
