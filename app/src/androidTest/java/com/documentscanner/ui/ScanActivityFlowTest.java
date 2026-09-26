package com.documentscanner.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.documentscanner.R;
import com.documentscanner.cv.Cv;
import com.documentscanner.cv.DocPipeline;
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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * 「快门只攒页」这条契约：一页就绪后必须留在取景页，编辑由用户主动进入，唯一的例外是「重拍」。
 * 镜头不在自动化范围内，所以走「相册导入」这条同源路径喂合成文档——它和快门共用同一个收尾。
 * 每张照片都带一个专属尺寸当作身份标记，真实相机拍出的页不可能撞上它。
 */
@RunWith(AndroidJUnit4.class)
public class ScanActivityFlowTest {

    /** 每张照片一个专属尺寸，认领页面时不必和真实相机拍出的页争抢。 */
    private static final int SEED_WIDTH = 800;
    private static final int SEED_HEIGHT = 1100;
    private static final int FIRST_WIDTH = 900;
    private static final int FIRST_HEIGHT = 1200;
    private static final int SECOND_WIDTH = 1000;
    private static final int SECOND_HEIGHT = 1300;
    private static final int RETAKE_WIDTH = 1100;
    private static final int RETAKE_HEIGHT = 1400;

    private Context context;
    private ScanSession session;
    private Instrumentation.ActivityMonitor cropMonitor;
    private final ScanActivity[] opened = new ScanActivity[1];

    @Before
    public void setUp() throws Exception {
        context = SessionFixtures.context();
        SessionFixtures.resetWorld();
        assertTrue("OpenCV native 库加载失败", Cv.ensure());
        session = ScanSession.get(context);
        session.setTitle("取景页流程");
        cropMonitor = InstrumentationRegistry.getInstrumentation()
                .addMonitor(CropActivity.class.getName(), null, false);
    }

    @After
    public void tearDown() throws Exception {
        InstrumentationRegistry.getInstrumentation().removeMonitor(cropMonitor);
        SessionFixtures.resetWorld();
    }

    @Test
    public void anImportedPageStaysOnTheScanner() throws Exception {
        try (ActivityScenario<ScanActivity> scenario = launchScanner()) {
            ScanPage page = importPhoto(scenario, "flow_first.jpg", FIRST_WIDTH, FIRST_HEIGHT);

            assertDetectedDocument(page.quad);
            assertTrue("缩略图没落盘", page.thumb.length() > 0);
            assertEquals("攒一页不该弹裁剪页", 0, cropMonitor.getHits());
            assertStillScanning();
        }
    }

    @Test
    public void successivePagesAccumulateWithoutLeavingTheScanner() throws Exception {
        try (ActivityScenario<ScanActivity> scenario = launchScanner()) {
            ScanPage first = importPhoto(scenario, "flow_a.jpg", FIRST_WIDTH, FIRST_HEIGHT);
            ScanPage second = importPhoto(scenario, "flow_b.jpg", SECOND_WIDTH, SECOND_HEIGHT);

            List<String> ids = pageIds();
            assertTrue("两页没有按顺序攒下：" + ids,
                    ids.indexOf(first.id) < ids.indexOf(second.id));
            assertEquals("每页都只该出现一次：" + ids, 2, ids.size());
            assertEquals("攒两页不该弹裁剪页", 0, cropMonitor.getHits());
            assertStillScanning();
        }
    }

    @Test
    public void retakeIsTheOnePathThatFallsBackIntoTheEditor() throws Exception {
        ScanPage target = seededPage();

        try (ActivityScenario<ScanActivity> scenario = ActivityScenario
                .launch(ScanActivity.replaceIntent(context, target.id))) {
            captureActivity(scenario);
            ScanActivity activity = opened[0];
            importPhoto(scenario, "flow_retake.jpg", RETAKE_WIDTH, RETAKE_HEIGHT);
            awaitCropLaunch();

            assertTrue("重拍必须把取景页收掉", activity.isFinishing());
        }
        // 裁剪页就此停在栈顶：它是被真实拉起来的，instrumentation 结束时统一收掉

        List<String> ids = pageIds();
        assertEquals("重拍不该多出一页：" + ids, 1, ids.size());
        assertEquals("重拍必须原位替换这一页", target.id, ids.get(0));
        assertEquals("重拍的内容没落到这一页上", target.id, page(RETAKE_WIDTH, RETAKE_HEIGHT).id);
    }

    // ---- 装配与辅助 --------------------------------------------------------

    /** 拉起取景页并立刻切到手动：相机一旦绑定就可能自动快门，页码断言会混进无关的页。 */
    private ActivityScenario<ScanActivity> launchScanner() {
        ActivityScenario<ScanActivity> scenario =
                ActivityScenario.launch(new Intent(context, ScanActivity.class));
        captureActivity(scenario);
        return scenario;
    }

    private void captureActivity(ActivityScenario<ScanActivity> scenario) {
        scenario.onActivity(activity -> {
            opened[0] = activity;
            activity.findViewById(R.id.btn_mode).performClick();
        });
        assertNotNull("取景页没有起来", opened[0]);
    }

    private ScanPage importPhoto(ActivityScenario<ScanActivity> scenario, String name,
                             int width, int height) throws Exception {
        Uri uri = writePhoto(name, width, height);
        scenario.onActivity(activity -> activity.importFromGallery(uri));
        return awaitPage(width, height);
    }

    /** 攒页的落点：留在取景页，而且「查看」已经可以点。 */
    private void assertStillScanning() {
        assertFalse("取景页把自己收尾了", opened[0].isFinishing());
        View finish = onMain(() -> opened[0].findViewById(R.id.btn_finish));
        assertTrue("攒页之后「查看」应该可以点", finish.isEnabled());
    }

    private void awaitCropLaunch() throws Exception {
        waitUntil("裁剪页被拉起", () -> cropMonitor.getHits() > 0);
        assertEquals("只该弹一次裁剪页", 1, cropMonitor.getHits());
    }

    private ScanPage seededPage() throws Exception {
        ScanPage page = session.createPage();
        Bitmap bitmap = ImageIO.load(context,
                writePhoto("flow_seed.jpg", SEED_WIDTH, SEED_HEIGHT), DocPipeline.EDGE_DETECT_SOURCE);
        try {
            ImageIO.saveJpeg(bitmap, page.original, DocPipeline.JPEG_QUALITY);
        } finally {
            bitmap.recycle();
        }
        page.quad = new float[]{0.05f, 0.05f, 0.95f, 0.05f, 0.95f, 0.95f, 0.05f, 0.95f};
        session.save();
        return page;
    }

    /** 造一张合成文档，返回它的 file:// 地址；尺寸就是这一页的身份标记。 */
    private Uri writePhoto(String name, int width, int height) throws IOException {
        File file = new File(context.getCacheDir(), name);
        Bitmap bitmap = documentPhoto(width, height);
        try {
            ImageIO.saveJpeg(bitmap, file, DocPipeline.JPEG_QUALITY);
        } finally {
            bitmap.recycle();
        }
        return Uri.fromFile(file);
    }

    /** 深色桌上一张带几行「文字」的白纸，四角略微歪斜，让自动检测真的能检出选区。 */
    private static Bitmap documentPhoto(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.rgb(40, 42, 46));

        float insetX = width * 0.08f;
        float insetY = height * 0.06f;
        Path paper = new Path();
        paper.moveTo(insetX, insetY + height * 0.02f);
        paper.lineTo(width - insetX * 0.9f, insetY);
        paper.lineTo(width - insetX, height - insetY);
        paper.lineTo(insetX * 1.1f, height - insetY * 0.9f);
        paper.close();
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(Color.rgb(246, 246, 244));
        fill.setStyle(Paint.Style.FILL);
        canvas.drawPath(paper, fill);

        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.rgb(25, 25, 25));
        for (int i = 0; i < 12; i++) {
            float top = insetY + height * 0.10f + i * height * 0.05f;
            canvas.drawRect(insetX + width * 0.06f, top,
                    width - insetX - width * 0.06f, top + height * 0.018f, text);
        }
        return bitmap;
    }

    /**
     * 等这一页真的收尾完成。文件一落盘就能按尺寸认领，但那时快门流程还没走完
     * （选区是收尾时在主线写上的，之前 busy 仍为真），紧接着再导一页就会被丢弃。
     */
    private ScanPage awaitPage(int width, int height) throws Exception {
        final ScanPage[] holder = new ScanPage[1];
        waitUntil(width + "x" + height + " 那一页收尾完成", () -> {
            int index = indexOfPage(width, height);
            if (index < 0 || session.pages().get(index).quad == null) return false;
            holder[0] = session.pages().get(index);
            return true;
        });
        return holder[0];
    }

    /** 选区确实是那张合成文档：归一化后落在画面内，又占掉大半幅。 */
    private static void assertDetectedDocument(float[] quad) {
        assertNotNull("攒下的页没带上自动识别的选区", quad);
        assertEquals(8, quad.length);
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int i = 0; i < 8; i += 2) {
            assertTrue("选区跑出画面外：" + Arrays.toString(quad),
                    quad[i] >= 0f && quad[i] <= 1f && quad[i + 1] >= 0f && quad[i + 1] <= 1f);
            minX = Math.min(minX, quad[i]);
            maxX = Math.max(maxX, quad[i]);
            minY = Math.min(minY, quad[i + 1]);
            maxY = Math.max(maxY, quad[i + 1]);
        }
        double coverage = (maxX - minX) * (maxY - minY);
        assertTrue("选区不像是那张合成文档：" + coverage, coverage > 0.4 && coverage < 0.99);
    }

    private ScanPage page(int width, int height) {
        int index = indexOfPage(width, height);
        assertTrue("会话里没有这张页：" + width + "x" + height, index >= 0);
        return session.pages().get(index);
    }

    /** 按解码尺寸认领页面：导入流会把照片原样落到 page.original，尺寸因此可辨。 */
    private int indexOfPage(int width, int height) {
        List<ScanPage> pages = session.pages();
        for (int i = 0; i < pages.size(); i++) {
            ScanPage candidate = pages.get(i);
            Rect bounds = boundsOf(candidate.original);
            if (bounds != null && bounds.width() == width && bounds.height() == height) return i;
        }
        return -1;
    }

    private static Rect boundsOf(File file) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        return new Rect(0, 0, options.outWidth, options.outHeight);
    }

    private List<String> pageIds() {
        List<String> ids = new ArrayList<>();
        for (ScanPage page : session.pages()) ids.add(page.id);
        return ids;
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
        long deadline = SystemClock.uptimeMillis() + 30_000;
        while (SystemClock.uptimeMillis() < deadline) {
            if (Boolean.TRUE.equals(onMain(condition))) return;
            Thread.sleep(50);
        }
        fail("等待超时：" + what);
    }
}
