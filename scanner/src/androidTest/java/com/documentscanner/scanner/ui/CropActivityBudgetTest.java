package com.documentscanner.scanner.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.documentscanner.scanner.R;
import com.documentscanner.scanner.api.ScannerConfig;
import com.documentscanner.scanner.cv.DocPipeline;
import com.documentscanner.scanner.cv.ImageBudget;
import com.documentscanner.scanner.model.ScanPage;
import com.documentscanner.scanner.model.ScanSession;
import com.documentscanner.scanner.model.SessionFixtures;
import com.documentscanner.scanner.ui.widget.CropQuadView;
import com.documentscanner.scanner.util.ImageIO;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Callable;

/**
 * 宿主档位要能真的穿过这一屏：onCreate 读一次配置，档位随参数落到 PageRenderer。
 *
 * <p>挑「确认」这条路径是因为它是四屏里最短的可观察链路——一次点击直接产出 result.jpg，
 * 它的长边就是档位的长边，谁都看得出来。PDF 那一头的档位验证在 export/PdfExportBudgetTest，
 * 那里能直接量到合成的字节。
 */
@RunWith(AndroidJUnit4.class)
public class CropActivityBudgetTest {

    /** 比最高档的产物短、比最低档的产物长：两个方向都能看出档位有没有生效。 */
    private static final int PHOTO_WIDTH = 1200;
    private static final int PHOTO_HEIGHT = 1600;
    private static final float[] FULL_FRAME =
            {0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f};

    private Context context;
    private ScanSession session;

    @Before
    public void setUp() throws Exception {
        ScannerConfig.resetForTesting();
        context = SessionFixtures.context();
        SessionFixtures.resetWorld();
        session = ScanSession.get(context);
        session.setTitle("档位用例");
    }

    @After
    public void tearDown() throws Exception {
        SessionFixtures.resetWorld();
        ScannerConfig.resetForTesting();
    }

    /** 宿主调小了就得出小图。少了这条，四屏各自硬编码出厂档也能全绿。 */
    @Test
    public void theScreenRendersAtTheTierTheHostSet() throws Exception {
        ScanPage page = newPage();
        ScannerConfig.setImageBudget(ImageBudget.TINY);

        confirm(page);

        assertEquals("裁剪页没按宿主的档位出图",
                ImageBudget.TINY.outputEdge, longEdgeOf(page.result), 2);
    }

    /** 一次都没调时的样子，顺带钉住「矫正只缩不放」：1600 的照片在 1800 的档上原样出来。 */
    @Test
    public void theUntouchedConfigRendersAtTheFactoryTier() throws Exception {
        ScanPage page = newPage();

        confirm(page);

        // 满幅选区过一遍透视变换会有几个像素的取整损失，所以这里留容差；
        // 容差与最近的另一档差着 700 像素，不会把「读错档」放过去。
        assertEquals("默认档不该把不够大的照片放大",
                PHOTO_HEIGHT, longEdgeOf(page.result), 8);
    }

    // ---- 装配与辅助 --------------------------------------------------------

    private ScanPage newPage() throws IOException {
        ScanPage page = session.createPage();
        Bitmap photo = Bitmap.createBitmap(PHOTO_WIDTH, PHOTO_HEIGHT, Bitmap.Config.ARGB_8888);
        photo.eraseColor(Color.WHITE);
        try {
            ImageIO.saveJpeg(photo, page.original, DocPipeline.JPEG_QUALITY_ORIGINAL);
        } finally {
            photo.recycle();
        }
        page.quad = FULL_FRAME.clone();
        session.save();
        return page;
    }

    /** 拉起真实裁剪页，等照片解码完成（选区没换算到视图上就点确认，拿到的是屏幕尺寸而不是照片尺寸）。 */
    private void confirm(ScanPage page) throws Exception {
        try (ActivityScenario<CropActivity> scenario =
                     ActivityScenario.launch(CropActivity.intentFor(context, page.id))) {
            CropQuadView[] holder = new CropQuadView[1];
            scenario.onActivity(activity -> holder[0] = activity.findViewById(R.id.crop_view));
            CropQuadView view = holder[0];
            assertNotNull("裁剪视图没找到", view);
            waitUntil("照片解码完成", () -> view.hasImage() && view.getCorners() != null);

            scenario.onActivity(activity ->
                    activity.findViewById(R.id.btn_confirm).performClick());

            waitUntil("渲染回调落盘", () -> page.edited && page.result.exists());
        }
    }

    private static int longEdgeOf(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        assertTrue("读不出尺寸: " + file, bounds.outWidth > 0 && bounds.outHeight > 0);
        return Math.max(bounds.outWidth, bounds.outHeight);
    }

    private static <T> T onMain(Callable<T> body) {
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

    private void waitUntil(String what, Callable<Boolean> condition) throws Exception {
        long deadline = android.os.SystemClock.uptimeMillis() + 60_000;
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (Boolean.TRUE.equals(onMain(condition))) return;
            Thread.sleep(50);
        }
        fail("等待超时：" + what);
    }
}
