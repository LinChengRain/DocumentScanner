package com.documentscanner.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.viewpager2.widget.ViewPager2;

import com.documentscanner.R;
import com.documentscanner.export.PaperSize;
import com.documentscanner.export.PdfExporter;
import com.documentscanner.model.ScanPage;
import com.documentscanner.model.ScanSession;
import com.documentscanner.model.SessionFixtures;
import com.documentscanner.util.ImageIO;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * PDF 预览页的真实装配：抽屉开关、翻页联动、删页后重新导出。
 * 页面的打开与导出都在后台线程完成，因此断言前统一等条件成立。
 */
@RunWith(AndroidJUnit4.class)
public class PdfPreviewActivityTest {

    private Context context;
    private File dir;
    private ScanSession session;
    private final List<String> pageIds = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        context = SessionFixtures.context();
        SessionFixtures.resetWorld();
        dir = new File(context.getCacheDir(), "pdf-preview-test");
        deleteRecursively(dir);
        assertTrue(dir.mkdirs());

        session = ScanSession.get(context);
        session.setTitle("预览用例");
        for (int i = 0; i < 2; i++) {
            ScanPage page = session.createPage();
            SessionFixtures.write(page.original, "photo");
            ImageIO.saveJpeg(pageOfColor(i == 0 ? Color.BLUE : Color.GREEN), page.result, 90);
            page.edited = true;
            pageIds.add(page.id);
        }
        session.save();
    }

    @After
    public void tearDown() throws Exception {
        deleteRecursively(dir);
        SessionFixtures.resetWorld();
    }

    @Test
    public void thePagerShowsEveryPageAndTheIndicatorFollows() throws Exception {
        File pdf = exportAll();

        try (ActivityScenario<PdfPreviewActivity> scenario =
                     ActivityScenario.launch(PdfPreviewActivity.intentFor(
                             context, pdf, "预览用例", pageIds, PaperSize.A4))) {
            final PdfPreviewActivity[] holder = new PdfPreviewActivity[1];
            scenario.onActivity(activity -> holder[0] = activity);
            final PdfPreviewActivity activity = holder[0];

            waitUntil("PDF 装载完成", () -> pagerAdapter(activity) != null);
            assertEquals(2, itemCount(pagerAdapter(activity)));
            assertEquals("1 / 2", indicator(activity));
            assertEquals(View.VISIBLE, view(activity, R.id.btn_delete_page).getVisibility());

            setPagerIndex(activity, 1);
            waitUntil("页码跟随翻页", () -> "2 / 2".equals(indicator(activity)));
        }
    }

    @Test
    public void theIndicatorTogglesTheThumbnailDrawer() throws Exception {
        File pdf = exportAll();

        try (ActivityScenario<PdfPreviewActivity> scenario =
                     ActivityScenario.launch(PdfPreviewActivity.intentFor(
                             context, pdf, "预览用例", pageIds, PaperSize.A4))) {
            final PdfPreviewActivity[] holder = new PdfPreviewActivity[1];
            scenario.onActivity(activity -> holder[0] = activity);
            final PdfPreviewActivity activity = holder[0];
            waitUntil("PDF 装载完成", () -> pagerAdapter(activity) != null);

            assertEquals(View.GONE, view(activity, R.id.rv_pdf_thumbs).getVisibility());
            click(view(activity, R.id.tv_page_indicator));
            assertEquals(View.VISIBLE, view(activity, R.id.rv_pdf_thumbs).getVisibility());
            assertEquals(2, itemCount(thumbAdapter(activity)));

            click(view(activity, R.id.tv_page_indicator));
            assertEquals(View.GONE, view(activity, R.id.rv_pdf_thumbs).getVisibility());
        }
    }

    @Test
    public void deletingAPageRewritesThePdfAndDropsItFromTheSession() throws Exception {
        File pdf = exportAll();
        assertEquals(2, pageCount(pdf));
        String doomed = pageIds.get(0);

        try (ActivityScenario<PdfPreviewActivity> scenario =
                     ActivityScenario.launch(PdfPreviewActivity.intentFor(
                             context, pdf, "预览用例", pageIds, PaperSize.A4))) {
            final PdfPreviewActivity[] holder = new PdfPreviewActivity[1];
            scenario.onActivity(activity -> holder[0] = activity);
            final PdfPreviewActivity activity = holder[0];
            waitUntil("PDF 装载完成", () -> pagerAdapter(activity) != null);

            onMain(activity, () -> {
                activity.removePage(0);
                return null;
            });

            waitUntil("重新导出一页版", () -> "1 / 1".equals(indicator(activity)));
            assertEquals(1, session.size());
            assertNull("会话里那一页要一起消失", session.byId(doomed));
            assertEquals("PDF 必须被重写成 1 页", 1, pageCount(pdf));
            assertFalse("重新导出不能留下临时文件",
                    new File(pdf.getParentFile(), pdf.getName() + ".tmp").exists());
        }
    }

    @Test
    public void aPdfWithoutPageIdsCannotDeletePages() throws Exception {
        File pdf = exportAll();

        try (ActivityScenario<PdfPreviewActivity> scenario =
                     ActivityScenario.launch(PdfPreviewActivity.intentFor(
                             context, pdf, "外部文件", new ArrayList<String>(), PaperSize.A4))) {
            final PdfPreviewActivity[] holder = new PdfPreviewActivity[1];
            scenario.onActivity(activity -> holder[0] = activity);
            final PdfPreviewActivity activity = holder[0];
            waitUntil("PDF 装载完成", () -> pagerAdapter(activity) != null);

            assertEquals(2, itemCount(pagerAdapter(activity)));
            assertEquals(View.GONE, view(activity, R.id.btn_delete_page).getVisibility());
        }
    }

    // ---- 辅助 --------------------------------------------------------------

    private File exportAll() throws IOException {
        List<File> images = new ArrayList<>();
        for (ScanPage page : session.pages()) {
            images.add(page.bestOutput());
        }
        return PdfExporter.export(images, new File(dir, "预览.pdf"), PaperSize.A4, null);
    }

    private static Bitmap pageOfColor(int color) {
        Bitmap bitmap = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint();
        paint.setColor(color);
        canvas.drawRect(0, 0, 150, 400, paint);
        return bitmap;
    }

    private static void click(View view) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(view::performClick);
    }

    private static View view(Activity activity, int id) {
        return onMain(activity, () -> activity.findViewById(id));
    }

    private static String indicator(Activity activity) {
        return onMain(activity, () ->
                ((TextView) activity.findViewById(R.id.tv_page_indicator)).getText().toString());
    }

    private static RecyclerView.Adapter<?> pagerAdapter(Activity activity) {
        return onMain(activity, () -> ((ViewPager2) activity.findViewById(R.id.pager_pdf)).getAdapter());
    }

    private static RecyclerView.Adapter<?> thumbAdapter(Activity activity) {
        return onMain(activity, () ->
                ((RecyclerView) activity.findViewById(R.id.rv_pdf_thumbs)).getAdapter());
    }

    private static void setPagerIndex(Activity activity, int index) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                ((ViewPager2) activity.findViewById(R.id.pager_pdf)).setCurrentItem(index, false));
    }

    private static int itemCount(RecyclerView.Adapter<?> adapter) {
        return adapter == null ? -1 : adapter.getItemCount();
    }

    private static int pageCount(File pdf) throws IOException {
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
        long deadline = SystemClock.uptimeMillis() + 15_000;
        while (SystemClock.uptimeMillis() < deadline) {
            if (Boolean.TRUE.equals(condition.call())) return;
            Thread.sleep(50);
        }
        fail("等待超时：" + what);
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
