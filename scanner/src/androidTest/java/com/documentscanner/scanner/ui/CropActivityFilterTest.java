package com.documentscanner.scanner.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.documentscanner.scanner.R;
import com.documentscanner.scanner.cv.DocPipeline;
import com.documentscanner.scanner.cv.FilterType;
import com.documentscanner.scanner.model.ScanPage;
import com.documentscanner.scanner.model.ScanSession;
import com.documentscanner.scanner.model.SessionFixtures;
import com.documentscanner.scanner.util.ImageIO;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.Callable;

/**
 * 裁剪页滤镜条上「默认落在哪一档」。
 *
 * <p>默认值本身在 {@code FilterTypeTest} 里钉，这里要的是它在屏幕上真的成立：
 * 一页新扫描进来，选中的必须是「原图」那一格——而不是代码里恰好排第一。
 * 第二条反过来钉：页面自己带着滤镜上场时不许被默认值盖掉，
 * 否则「永远选中原图」这种写法也能蒙过第一条。
 */
@RunWith(AndroidJUnit4.class)
public class CropActivityFilterTest {

    private static final int PHOTO_WIDTH = 600;
    private static final int PHOTO_HEIGHT = 800;

    private Context context;
    private ScanSession session;

    @Before
    public void setUp() throws Exception {
        context = SessionFixtures.context();
        SessionFixtures.resetWorld();
        session = ScanSession.get(context);
        session.setTitle("滤镜用例");
    }

    @After
    public void tearDown() throws Exception {
        SessionFixtures.resetWorld();
    }

    @Test
    public void aFreshPageOpensOnTheOriginalEffect() throws Exception {
        ScanPage page = newPage();
        assertEquals("用例前提：这页没被挑过滤镜", FilterType.ORIGINAL, page.filter);

        withCrop(page, strip -> {
            assertEquals("默认选中的必须是「原图」那一格", 0, selectedIndex(strip));
            assertEquals("原图", labelOf(strip, 0));
        });
    }

    @Test
    public void aPageThatAlreadyPickedAFilterOpensOnIt() throws Exception {
        ScanPage page = newPage();
        page.filter = FilterType.COLOR_MAGIC;
        session.save();

        withCrop(page, strip -> assertEquals(FilterType.COLOR_MAGIC.ordinal(),
                selectedIndex(strip)));
    }

    // ---- 辅助 --------------------------------------------------------------

    /**
     * 一页「刚扫出来」的页面：真实可解码的原图 + 自动检出的选区，滤镜保持出厂默认。
     * 不能用 {@code SessionFixtures.addPage}——它给已确认页写死 BINARY，
     * 而这一档正是本用例要排除的。
     */
    private ScanPage newPage() throws Exception {
        ScanPage page = session.createPage();
        Bitmap photo = Bitmap.createBitmap(PHOTO_WIDTH, PHOTO_HEIGHT, Bitmap.Config.ARGB_8888);
        photo.eraseColor(Color.WHITE);
        try {
            ImageIO.saveJpeg(photo, page.original, DocPipeline.JPEG_QUALITY_ORIGINAL);
        } finally {
            photo.recycle();
        }
        page.quad = SessionFixtures.QUAD.clone();
        session.save();
        return page;
    }

    private interface StripCheck {
        void check(RecyclerView strip) throws Exception;
    }

    /** 拉起真实裁剪页，等滤镜条排好版（五格都在），再把整棵树交给断言。 */
    private void withCrop(ScanPage page, StripCheck check) throws Exception {
        try (ActivityScenario<CropActivity> scenario =
                     ActivityScenario.launch(CropActivity.intentFor(context, page.id))) {
            final RecyclerView[] holder = new RecyclerView[1];
            scenario.onActivity(activity -> holder[0] = activity.findViewById(R.id.filter_strip));
            final RecyclerView strip = holder[0];
            waitUntil("滤镜条排好版", () -> strip.getChildCount() == FilterType.values().length);
            onMain(() -> {
                check.check(strip);
                return null;
            });
        }
    }

    private static int selectedIndex(RecyclerView strip) {
        for (int i = 0; i < strip.getChildCount(); i++) {
            if (viewAt(strip, i).isSelected()) return i;
        }
        return -1;
    }

    private static String labelOf(RecyclerView strip, int index) {
        return viewAt(strip, index).getText().toString();
    }

    /** item 布局的根节点就是那格标签本身。 */
    private static TextView viewAt(RecyclerView strip, int index) {
        View child = strip.getChildAt(index);
        assertTrue("第 " + index + " 格还没排版", child != null);
        return (TextView) child;
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

    private void waitUntil(String what, Callable<Boolean> condition) {
        long deadline = SystemClock.uptimeMillis() + 20_000;
        while (SystemClock.uptimeMillis() < deadline) {
            if (Boolean.TRUE.equals(onMain(condition))) return;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail("等待超时：" + what);
    }
}
