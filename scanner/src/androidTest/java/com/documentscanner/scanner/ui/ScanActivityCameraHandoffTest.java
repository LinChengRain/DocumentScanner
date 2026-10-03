package com.documentscanner.scanner.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.documentscanner.scanner.R;
import com.documentscanner.scanner.cv.Cv;
import com.documentscanner.scanner.cv.DocPipeline;
import com.documentscanner.scanner.model.ScanPage;
import com.documentscanner.scanner.model.ScanSession;
import com.documentscanner.scanner.model.SessionFixtures;
import com.documentscanner.scanner.util.ImageIO;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * 两台取景页之间的交接。「重拍」是在用户原来那台之上再开一台，而 ProcessCameraProvider 是
 * 进程单例：新开的这一台一绑相机就把原来那台的预览流与拍照流一并摘走，回到原取景页时画面灰屏
 * 卡住、快门报「Not bound to a valid Camera」，只能退回首页重开。
 * 所以两边都用真实镜头按快门：重拍那台要拍得出来（它自己抢到了相机），原来那台回到前景后
 * 也得拍得出来（它没被抢走）。绑定与拍照都是异步的，因此一律等状态，不等睡眠。
 */
@RunWith(AndroidJUnit4.class)
public class ScanActivityCameraHandoffTest {

    private static final int SEED_WIDTH = 900;
    private static final int SEED_HEIGHT = 1200;

    private Context context;
    private Instrumentation instrumentation;
    private ScanSession session;
    private Instrumentation.ActivityMonitor cropMonitor;

    @Before
    public void setUp() throws Exception {
        context = SessionFixtures.context();
        instrumentation = InstrumentationRegistry.getInstrumentation();
        SessionFixtures.resetWorld();
        assertTrue("OpenCV native 库加载失败", Cv.ensure());
        session = ScanSession.get(context);
        session.setTitle("取景页相机交接");
        cropMonitor = instrumentation.addMonitor(CropActivity.class.getName(), null, false);
    }

    @After
    public void tearDown() throws Exception {
        instrumentation.removeMonitor(cropMonitor);
        SessionFixtures.resetWorld();
    }

    @Test
    public void theOriginalScannerStillShootsAfterARetake() throws Exception {
        ScanPage target = seededPage();

        try (ActivityScenario<ScanActivity> scenario =
                     ActivityScenario.launch(new Intent(context, ScanActivity.class))) {
            final ScanActivity[] holder = new ScanActivity[1];
            scenario.onActivity(activity -> {
                holder[0] = activity;
                // 手动模式：镜头不该自己往会话里加页，页数就全是这条用例的账
                activity.findViewById(R.id.btn_mode).performClick();
            });
            ScanActivity original = holder[0];
            assertNotNull("取景页没有起来", original);
            awaitReady(original, "原来那台取景页");

            awaitNewShot(pressShutter(original, "重拍之前"), "重拍之前");
            assertEquals("基准实拍没多出页", 2, pageCount());
            // 照片落盘不等于这一张收尾完成（选区与 busy 还在相机线程那边），等它彻底交回快门，
            // 后面的「让位」断言才有基准。
            awaitReady(original, "基准实拍之后交回快门");

            // 「重拍」：在原来那台之上再开一台，让它把这一页重做完再收掉自己。
            // 监视器只圈这一段——原来那台早已 resume 过，共用一个监视器会把它的实例当成
            // 新起的那台还回来。
            Instrumentation.ActivityMonitor second = instrumentation
                    .addMonitor(ScanActivity.class.getName(), null, false);
            ScanActivity retaker;
            try {
                onMain(() -> {
                    original.startActivity(ScanActivity.replaceIntent(original, target.id));
                    return null;
                });
                retaker = awaitScanner(second);
            } finally {
                instrumentation.removeMonitor(second);
            }
            assertEquals("重拍开的不是这一页的替换模式",
                    target.id, retaker.getIntent().getStringExtra(ScanActivity.EXTRA_REPLACE_PAGE));
            onMain(() -> {
                retaker.findViewById(R.id.btn_mode).performClick();
                return null;
            });
            awaitReady(retaker, "重拍那台取景页");
            // 交接的另一半：让位的那台必须把相机交出去，不然两台同时挂在进程单例上，
            // 换到配置更严的机器上就是这一台绑不上。
            assertFalse("让位的那台还占着相机", onMain(original::readyForCapture));

            pressShutter(retaker, "重拍");
            awaitCropLaunch();
            assertTrue("重拍没换上新的那张照片", looksReShot(target));
            waitUntil("重拍那台让位给裁剪页", () -> retaker.isFinishing());
            finishActivity(cropMonitor.waitForActivity());
            assertEquals("重拍多出一页", 2, pageCount());

            awaitReady(original, "重拍之后回到原来那台取景页");
            awaitNewShot(pressShutter(original, "重拍之后"), "重拍之后");
            assertEquals("两台取景页交接完的页数不对", 3, pageCount());
            assertNotNull("重拍把原来那一页弄丢了", session.byId(target.id));
            assertTrue("回到前景后的这一次快门改写了重拍那一页", looksReShot(target));
        }
    }

    // ---- 装配与辅助 --------------------------------------------------------

    /** 一页早就拍好的旧照片，充当被重拍的对象；它的尺寸是这一页的身份证。 */
    private ScanPage seededPage() throws IOException {
        ScanPage page = session.createPage();
        saveSolid(page.original, SEED_WIDTH, SEED_HEIGHT, Color.rgb(250, 250, 248));
        page.quad = new float[]{0.05f, 0.05f, 0.95f, 0.05f, 0.95f, 0.95f, 0.05f, 0.95f};
        session.save();
        return page;
    }

    /** 重拍之后这一页该是镜头拍出来的那张，不再是测试自己写的合成图。 */
    private static boolean looksReShot(ScanPage page) {
        Rect bounds = boundsOf(page.original);
        return bounds != null && page.original.length() > 0
                && (bounds.width() != SEED_WIDTH || bounds.height() != SEED_HEIGHT);
    }

    /** 按下快门，返回按下之前已有的页 id——新的一页是在这一刻就建出来的，事后就认不出了。 */
    private Set<String> pressShutter(final Activity activity, final String label) throws Exception {
        final Set<String> before = onMain(this::pageIds);
        onMain(() -> {
            View shutter = activity.findViewById(R.id.btn_shutter);
            assertNotNull(label + "找不到快门", shutter);
            shutter.performClick();
            return null;
        });
        return before;
    }

    /** 等快门真的拍出一页：新出现的那一页，照片已经落盘。 */
    private void awaitNewShot(final Set<String> before, final String label) throws Exception {
        waitUntil(label + "拍出了一页", () -> {
            for (ScanPage page : session.pages()) {
                if (!before.contains(page.id) && page.original.length() > 0) return true;
            }
            return false;
        });
    }

    /** 相机接上且不在处理上一张，才容得下下一次快门。 */
    private void awaitReady(final ScanActivity activity, final String label) throws Exception {
        waitUntil(label + "的相机接上", activity::readyForCapture);
    }

    /** 新拉起的那台取景页：命中之后 monitor 已经记下它，所以能立刻拿到实例。 */
    private ScanActivity awaitScanner(Instrumentation.ActivityMonitor monitor) throws Exception {
        waitUntil("重拍的取景页被拉起", () -> monitor.getHits() > 0);
        return (ScanActivity) monitor.waitForActivity();
    }

    private void awaitCropLaunch() throws Exception {
        waitUntil("裁剪页被拉起", () -> cropMonitor.getHits() > 0);
        assertEquals("重拍只该弹一次裁剪页", 1, cropMonitor.getHits());
    }

    private void finishActivity(final Activity activity) {
        instrumentation.runOnMainSync(activity::finish);
    }

    private int pageCount() throws Exception {
        return onMain(() -> session.size());
    }

    private Set<String> pageIds() {
        Set<String> ids = new HashSet<>();
        for (ScanPage page : session.pages()) ids.add(page.id);
        return ids;
    }

    private static void saveSolid(File file, int width, int height, int color) throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            bitmap.eraseColor(color);
            ImageIO.saveJpeg(bitmap, file, DocPipeline.JPEG_QUALITY_ORIGINAL);
        } finally {
            bitmap.recycle();
        }
    }

    private static Rect boundsOf(File file) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        return new Rect(0, 0, options.outWidth, options.outHeight);
    }

    private <T> T onMain(Callable<T> body) {
        final Object[] box = new Object[1];
        instrumentation.runOnMainSync(() -> {
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
        long deadline = SystemClock.uptimeMillis() + 40_000;
        while (SystemClock.uptimeMillis() < deadline) {
            if (Boolean.TRUE.equals(onMain(condition))) return;
            Thread.sleep(50);
        }
        fail("等待超时：" + what);
    }
}
