package com.documentscanner.scanner.cv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Looper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.documentscanner.scanner.model.ScanPage;
import com.documentscanner.scanner.model.ScanSession;
import com.documentscanner.scanner.util.ImageIO;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 体积档真的落到像素数上：渲染与重跑滤镜都要按当下这一档出图，而不是继续吃出厂尺寸。
 *
 * <p>选一张 3000×2400 的源图是有原因的：矫正只在「原始像素比目标长边大」时才缩小，
 * 从不放大。源图不够大时四档会写出同一个尺寸，用例就变成空断言。
 */
@RunWith(AndroidJUnit4.class)
public class PageRendererBudgetTest {

    private static final long TIMEOUT_SECONDS = 60;
    private static final float[] CORNERS = new float[]{
            0.02f, 0.02f, 0.98f, 0.02f, 0.98f, 0.98f, 0.02f, 0.98f};

    private Context context;
    private ScanSession session;

    @Before
    public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        resetInstance();
        session = ScanSession.get(context);
        assertTrue("OpenCV native 库加载失败", Cv.ensure());
    }

    @After
    public void tearDown() throws Exception {
        session.clear();
        resetInstance();
    }

    @Test
    public void everyTierWritesThePageAtItsOwnEdge() throws Exception {
        for (ImageBudget budget : ImageBudget.values()) {
            ScanPage page = newPageWithBigPhoto();

            assertNull(budget + " 渲染失败", render(page, budget));

            assertEquals(budget + " 的 result 长边", budget.outputEdge,
                    longEdgeOf(page.result), 2);
            assertEquals(budget + " 的 warp 长边", budget.outputEdge,
                    longEdgeOf(page.warp), 2);
        }
    }

    /**
     * 换档后的重跑滤镜必须重出 result，但不该顺手改写 warp：
     * warp 是「无滤镜的矫正底片」，它的尺寸属于当初那次渲染，动它就不是重跑滤镜而是重渲染。
     */
    @Test
    public void refilterRewritesTheResultButLeavesTheWarpAlone() throws Exception {
        ScanPage page = newPageWithBigPhoto();
        assertNull(render(page, ImageBudget.STANDARD));
        assertEquals(ImageBudget.STANDARD.outputEdge, longEdgeOf(page.warp), 2);
        long warpBytes = page.warp.length();

        page.filter = FilterType.BINARY;
        assertNull(refilter(page, ImageBudget.TINY));

        assertEquals("result 要按新档重出", ImageBudget.TINY.outputEdge,
                longEdgeOf(page.result), 2);
        assertEquals("warp 不该被重跑滤镜改写", ImageBudget.STANDARD.outputEdge,
                longEdgeOf(page.warp), 2);
        assertEquals("warp 的字节一个都不该动", warpBytes, page.warp.length());
    }

    /**
     * 档位管的是产物，不是唯一的那张源图。TINY 一旦顺手把 original 也按 78 的质量重编码，
     * 用户下次换档就再也拿不回细节了——这条是「省空间不该毁掉源图」这个决定的落地处。
     */
    @Test
    public void thePhotoKeepsItsOwnResolutionRegardlessOfTheTier() throws Exception {
        ScanPage page = newPageWithBigPhoto();
        long photoBytes = page.original.length();

        assertNull(render(page, ImageBudget.TINY));

        assertEquals("原图字节一个都不该动", photoBytes, page.original.length());
        assertEquals("原图仍是拍下来的那张", 3000, longEdgeOf(page.original));
    }

    // ---- 辅助 --------------------------------------------------------------

    private ScanPage newPageWithBigPhoto() throws Exception {
        ScanPage page = session.createPage();
        Bitmap photo = Bitmap.createBitmap(3000, 2400, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(photo);
        canvas.drawColor(Color.rgb(40, 42, 46));
        Paint paper = new Paint();
        paper.setColor(Color.rgb(250, 250, 250));
        canvas.drawRect(300, 240, 2700, 2160, paper);
        try {
            ImageIO.saveJpeg(photo, page.original, DocPipeline.JPEG_QUALITY_ORIGINAL);
        } finally {
            photo.recycle();
        }
        return page;
    }

    private static Throwable render(ScanPage page, ImageBudget budget) throws Exception {
        return run(callback -> PageRenderer.render(page, CORNERS, budget, callback));
    }

    private static Throwable refilter(ScanPage page, ImageBudget budget) throws Exception {
        return run(callback -> PageRenderer.refilter(page, budget, callback));
    }

    private interface Call {
        void go(PageRenderer.Callback callback);
    }

    /** 等到回调为止，顺带核对 PageRenderer 的约定：回调必须落在主线程。 */
    private static Throwable run(Call call) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Throwable[] failure = new Throwable[1];
        boolean[] onMainThread = new boolean[1];
        call.go(error -> {
            failure[0] = error;
            onMainThread[0] = Looper.myLooper() == Looper.getMainLooper();
            done.countDown();
        });
        assertTrue("渲染超时未回调", done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue("回调必须落在主线程", onMainThread[0]);
        return failure[0];
    }

    private static int longEdgeOf(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        assertTrue("读不出尺寸: " + file, bounds.outWidth > 0 && bounds.outHeight > 0);
        return Math.max(bounds.outWidth, bounds.outHeight);
    }

    private static void resetInstance() throws Exception {
        Field instance = ScanSession.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }
}
