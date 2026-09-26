package com.documentscanner.cv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Looper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.documentscanner.model.ScanPage;
import com.documentscanner.model.ScanSession;
import com.documentscanner.util.ImageIO;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.core.Point;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 渲染链路在真实设备上的行为：OpenCV 原生库确实被加载、边缘检测能找到合成文档、
 * 三份产物各自落盘，而 PageRenderer 的回调约定是「主线程回调」。
 */
@RunWith(AndroidJUnit4.class)
public class PageRendererTest {

    private static final long TIMEOUT_SECONDS = 60;
    private static final float[] CORNERS = new float[]{
            0.06f, 0.06f, 0.94f, 0.05f, 0.95f, 0.95f, 0.05f, 0.94f};

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
    public void renderWritesArtifactsAndCallsBackOnMainThread() throws Exception {
        ScanPage page = newPageWithPhoto();

        RenderResult result = render(page, CORNERS);

        assertNull("渲染失败: " + result.error, result.error);
        assertTrue("回调必须落在主线程", result.onMainThread);
        assertTrue(page.warp.exists());
        assertTrue(page.result.exists());
        assertTrue(page.thumb.exists());
        assertTrue("warp 文件为空", page.warp.length() > 0);
        assertTrue("result 文件为空", page.result.length() > 0);
        assertTrue(page.edited);
        assertArrayLength8(page.quad);
        // 矫正后的页面应接近传入的选区比例（1200x1600 -> 约 0.88 x 0.89 的边长）
        Bitmap warped = ImageIO.load(page.warp, DocPipeline.EDGE_OUTPUT);
        try {
            assertTrue("矫正结果尺寸异常: " + warped.getWidth() + "x" + warped.getHeight(),
                    warped.getWidth() > 400 && warped.getHeight() > 400);
        } finally {
            warped.recycle();
        }
    }

    @Test
    public void refilterReusesWarpAndOnlyReplacesResult() throws Exception {
        ScanPage page = newPageWithPhoto();
        assertNull(render(page, CORNERS).error);
        byte[] warpBefore = readBytes(page.warp);
        byte[] resultBefore = readBytes(page.result);

        page.filter = FilterType.BINARY;
        RenderResult result = refilter(page);

        assertNull("重新应用滤镜失败: " + result.error, result.error);
        assertTrue("回调必须落在主线程", result.onMainThread);
        // warp 是「矫正但无滤镜」的那一份，换滤镜时不该被重写
        assertTrue(java.util.Arrays.equals(warpBefore, readBytes(page.warp)));
        assertFalse(java.util.Arrays.equals(resultBefore, readBytes(page.result)));
        assertTrue(page.result.length() > 0);
    }

    @Test
    public void detectQuadFindsSyntheticDocument() throws Exception {
        Bitmap photo = documentBitmap(1200, 1600);
        try {
            Point[] quad = DocPipeline.detectQuad(photo, true);
            assertNotNull("未能在合成文档上检出四角", quad);
            assertEquals(4, quad.length);
            assertTrue("检出的选区不是凸四边形", QuadGeometry.isConvex(quad));
            double ratio = QuadGeometry.area(quad) / (double) (photo.getWidth() * photo.getHeight());
            assertTrue("检出面积占比异常: " + ratio, ratio > 0.4 && ratio < 0.98);
        } finally {
            photo.recycle();
        }
    }

    @Test
    public void renderWithoutDetectedQuadFallsBackToWholeFrame() throws Exception {
        // 纯色图检不出边框，此时必须退化成整幅而不是失败或崩溃
        Bitmap blank = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888);
        blank.eraseColor(Color.rgb(250, 250, 250));
        ScanPage page = session.createPage();
        ImageIO.saveJpeg(blank, page.original, DocPipeline.JPEG_QUALITY);
        blank.recycle();

        assertNull(render(page, null).error);

        assertTrue(page.result.exists() && page.result.length() > 0);
        assertNotNull(page.quad);
        assertArrayLength8(page.quad);
    }

    // ---- 辅助 --------------------------------------------------------------

    private static void assertArrayLength8(float[] quad) {
        assertNotNull(quad);
        assertEquals(8, quad.length);
    }

    private ScanPage newPageWithPhoto() throws IOException {
        ScanPage page = session.createPage();
        Bitmap photo = documentBitmap(1200, 1600);
        try {
            ImageIO.saveJpeg(photo, page.original, DocPipeline.JPEG_QUALITY);
        } finally {
            photo.recycle();
        }
        return page;
    }

    /** 深色桌面上放一张带几行「文字」的白纸，四角略微歪斜。 */
    private static Bitmap documentBitmap(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.rgb(40, 42, 46));

        float insetX = width * 0.08f;
        float insetY = height * 0.06f;
        Path page = new Path();
        page.moveTo(insetX, insetY + height * 0.02f);
        page.lineTo(width - insetX * 0.9f, insetY);
        page.lineTo(width - insetX, height - insetY);
        page.lineTo(insetX * 1.1f, height - insetY * 0.9f);
        page.close();
        Paint paper = new Paint(Paint.ANTI_ALIAS_FLAG);
        paper.setColor(Color.rgb(246, 246, 244));
        paper.setStyle(Paint.Style.FILL);
        canvas.drawPath(page, paper);

        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.rgb(25, 25, 25));
        for (int i = 0; i < 14; i++) {
            float top = insetY + height * 0.10f + i * height * 0.05f;
            canvas.drawRect(insetX + width * 0.06f, top,
                    width - insetX - width * 0.06f, top + height * 0.018f, text);
        }
        return bitmap;
    }

    private static RenderResult render(ScanPage page, float[] corners) throws InterruptedException {
        RenderResult result = new RenderResult();
        CountDownLatch latch = new CountDownLatch(1);
        PageRenderer.render(page, corners, error -> {
            result.error = error;
            result.onMainThread = Looper.myLooper() == Looper.getMainLooper();
            latch.countDown();
        });
        result.finished = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue("渲染超时未回调", result.finished);
        return result;
    }

    private static RenderResult refilter(ScanPage page) throws InterruptedException {
        RenderResult result = new RenderResult();
        CountDownLatch latch = new CountDownLatch(1);
        PageRenderer.refilter(page, error -> {
            result.error = error;
            result.onMainThread = Looper.myLooper() == Looper.getMainLooper();
            latch.countDown();
        });
        result.finished = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue("重跑滤镜超时未回调", result.finished);
        return result;
    }

    private static byte[] readBytes(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while ((count = in.read(chunk)) > 0) {
                out.write(chunk, 0, count);
            }
            return out.toByteArray();
        }
    }

    private static void resetInstance() throws Exception {
        Field instance = ScanSession.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    private static class RenderResult {
        volatile boolean finished;
        volatile boolean onMainThread;
        volatile Throwable error;
    }
}
