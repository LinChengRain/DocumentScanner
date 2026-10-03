package com.documentscanner.scanner.export;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import android.content.Context;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.documentscanner.scanner.model.ScanSession;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 分享出口的三条边界：provider 声明的目录内必须读得通，声明外的文件必须拒绝。
 * authority 由模块 manifest 的 {@code ${applicationId}} 占位符与 {@code ShareFiles.authority()}
 * 两边各算一份，对不上时 getUriForFile 直接抛异常——这条只能运行期抓。
 */
@RunWith(AndroidJUnit4.class)
public class ShareFilesTest {

    private Context context;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @Test
    public void exportedPdfIsReadableThroughProvider() throws IOException {
        File dir = new File(context.getFilesDir(), "export");
        ensureDir(dir);
        File pdf = new File(dir, "share-check.pdf");
        byte[] payload = "%PDF-1.4 fake bytes".getBytes("UTF-8");
        write(pdf, payload);

        Uri uri = ShareFiles.uriForFile(context, pdf);

        assertEquals("content", uri.getScheme());
        assertEquals(ShareFiles.authority(context), uri.getAuthority());
        assertArrayEquals(payload, read(uri));
    }

    @Test
    public void sessionArtifactIsReadableThroughProvider() throws IOException {
        File dir = new File(new File(context.getCacheDir(), ScanSession.ROOT_DIR), "s-check/originals");
        ensureDir(dir);
        File photo = new File(dir, "p001.jpg");
        byte[] payload = "photo".getBytes("UTF-8");
        write(photo, payload);

        assertArrayEquals(payload, read(ShareFiles.uriForFile(context, photo)));
    }

    /** 声明之外的文件不能被换成交给别的应用的 URI，否则整个私有目录跟着暴露。 */
    @Test
    public void fileOutsideDeclaredDirsHasNoUri() throws IOException {
        File loose = new File(context.getCacheDir(), "not-declared.txt");
        write(loose, "secret".getBytes("UTF-8"));
        try {
            ShareFiles.uriForFile(context, loose);
            fail("cache 根目录不在 scanner_file_paths.xml 里，必须拒绝");
        } catch (IllegalArgumentException expected) {
            // 正是 FileProvider 找不到匹配 root 时抛的
        } finally {
            loose.delete();
        }
    }

    private static void ensureDir(File dir) {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("无法创建 " + dir);
    }

    private static void write(File file, byte[] payload) throws IOException {
        ensureDir(file.getParentFile());
        try (FileOutputStream os = new FileOutputStream(file)) {
            os.write(payload);
        }
    }

    private static byte[] read(Uri uri) throws IOException {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (InputStream in = app.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("打不开 " + uri);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) > 0) out.write(buffer, 0, count);
            return out.toByteArray();
        }
    }
}
