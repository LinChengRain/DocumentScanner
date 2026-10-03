package com.documentscanner.scanner.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.documentscanner.scanner.R;
import com.documentscanner.scanner.cv.DocPipeline;
import com.documentscanner.scanner.model.ScanPage;
import com.documentscanner.scanner.model.ScanSession;
import com.documentscanner.scanner.model.SessionFixtures;
import com.documentscanner.scanner.util.ImageIO;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.concurrent.Callable;

/**
 * 裁剪页的「重拍」必须把这一页交回取景页去替换，而不是当场删掉：用户点它是为了「这页没拍好，
 * 重来一张」，删页等于把这一页连同它在多页里的位置一起丢了，而且新页还没拍就先断了退路。
 */
@RunWith(AndroidJUnit4.class)
public class CropActivityRetakeTest {

    private static final int PHOTO_WIDTH = 1200;
    private static final int PHOTO_HEIGHT = 1600;
    private static final float[] QUAD = new float[]{
            0.04f, 0.05f, 0.96f, 0.04f, 0.95f, 0.96f, 0.05f, 0.95f};

    private Context context;
    private ScanSession session;
    private ScanPage page;
    private long originalBytes;

    @Before
    public void setUp() throws Exception {
        context = SessionFixtures.context();
        SessionFixtures.resetWorld();
        session = ScanSession.get(context);
        session.setTitle("重拍用例");
        page = session.createPage();
        Bitmap photo = Bitmap.createBitmap(PHOTO_WIDTH, PHOTO_HEIGHT, Bitmap.Config.ARGB_8888);
        photo.eraseColor(Color.WHITE);
        try {
            ImageIO.saveJpeg(photo, page.original, DocPipeline.JPEG_QUALITY_ORIGINAL);
        } finally {
            photo.recycle();
        }
        page.quad = QUAD.clone();
        page.edited = true;
        session.save();
        originalBytes = page.original.length();
        assertTrue("测试照片没写进去", originalBytes > 0);
    }

    @After
    public void tearDown() throws Exception {
        SessionFixtures.resetWorld();
    }

    @Test
    public void retakeHandsThePageBackToTheCameraInsteadOfDeletingIt() throws Exception {
        Instrumentation.ActivityMonitor scanner = InstrumentationRegistry.getInstrumentation()
                .addMonitor(ScanActivity.class.getName(), null, false);
        Activity camera = null;
        try (ActivityScenario<CropActivity> scenario =
                     ActivityScenario.launch(CropActivity.intentFor(context, page.id))) {
            final CropActivity[] holder = new CropActivity[1];
            scenario.onActivity(activity -> holder[0] = activity);
            final CropActivity crop = holder[0];

            onMain(() -> {
                View retake = crop.findViewById(R.id.btn_retake);
                assertNotNull("裁剪页没有重拍入口", retake);
                assertTrue("重拍按钮点不动", retake.performClick());
                return null;
            });
            camera = awaitCamera(scanner);

            assertNotNull("重拍没有把用户送回取景页", camera);
            assertEquals("取景页没在替换这一页的模式上",
                    page.id, camera.getIntent().getStringExtra(ScanActivity.EXTRA_REPLACE_PAGE));
            assertTrue("裁剪页该让位给取景页", crop.isFinishing());
        } finally {
            InstrumentationRegistry.getInstrumentation().removeMonitor(scanner);
            if (camera != null) releaseCamera(camera);
        }

        assertEquals("重拍把那一页删掉了", 1, session.size());
        assertNotNull("重拍后这一页就不在了", session.byId(page.id));
        assertEquals("新页还没拍就先作废了原图", originalBytes, page.original.length());
        assertTrue("重拍提前清掉了选区", Arrays.equals(QUAD, page.quad));
    }

    // ---- 装配与辅助 --------------------------------------------------------

    /** 计数命中之后 monitor 已经记下那个实例，所以这里能立刻拿到被拉起来的取景页读它的 Intent。 */
    private Activity awaitCamera(Instrumentation.ActivityMonitor scanner) throws Exception {
        waitUntil("取景页被重拍拉起", () -> scanner.getHits() > 0);
        return scanner.waitForActivity();
    }

    /**
     * 收掉被拉起来的取景页。先点一下「自动/手动」切到手动：真实镜头几帧之内就会自动快门，
     * 那一脚下去就会替换掉这一页，后面的断言就没有基准了。
     */
    private void releaseCamera(Activity camera) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            camera.findViewById(R.id.btn_mode).performClick();
            camera.finish();
        });
    }

    private void waitUntil(String what, Callable<Boolean> condition) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 15_000;
        while (SystemClock.uptimeMillis() < deadline) {
            if (Boolean.TRUE.equals(onMain(condition))) return;
            Thread.sleep(50);
        }
        fail("等待超时：" + what);
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
}
