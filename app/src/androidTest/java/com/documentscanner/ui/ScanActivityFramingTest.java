package com.documentscanner.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.documentscanner.R;
import com.documentscanner.model.SessionFixtures;
import com.documentscanner.ui.widget.ImageGeom;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.Callable;

/**
 * 取景页的构图布局：预览与实时边框必须落在顶栏和提示条之间的空档里。
 * 铺满整屏时 fitCenter 按整屏居中，画面（连同检出的文档边框）就沉到提示条与快门那一排下面，
 * 也就是用户说的「绿框偏下」。叠加层还得与预览逐像素对齐，否则边框会画歪。
 */
@RunWith(AndroidJUnit4.class)
public class ScanActivityFramingTest {

    /** 相机分析帧是 3:4 竖版：fitCenter 之后按宽铺满，上下各留一条黑边。 */
    private static final int FRAME_WIDTH = 1200;
    private static final int FRAME_HEIGHT = 1600;
    /** 居中只允许差一个像素舍入。 */
    private static final float CENTER_TOLERANCE_PX = 3f;

    private Context context;

    @Before
    public void setUp() throws Exception {
        context = SessionFixtures.context();
        SessionFixtures.resetWorld();
    }

    @After
    public void tearDown() throws Exception {
        SessionFixtures.resetWorld();
    }

    @Test
    public void theLiveFrameSitsCenteredBetweenTheTopBarAndTheHint() throws Exception {
        try (ActivityScenario<ScanActivity> scenario =
                     ActivityScenario.launch(new Intent(context, ScanActivity.class))) {
            final ScanActivity[] holder = new ScanActivity[1];
            scenario.onActivity(activity -> {
                holder[0] = activity;
                // 手动模式：真镜头几帧之内就会自动快门，构图用例不该被卷进拍摄流程
                activity.findViewById(R.id.btn_mode).performClick();
            });
            Geometry geo = onMain(() -> measureAndRead(holder[0]));

            assertEquals("叠加层与预览不对齐，边框会画歪",
                    geo.preview.toString(), geo.overlay.toString());

            // 用户抱怨的那一条：整块画面必须留在顶栏与提示条之间，而且落在空档正中
            Rect drawn = geo.drawnFrame();
            assertTrue("画面下沿压到了提示条：" + drawn + " " + geo, drawn.bottom <= geo.hint.top);
            assertTrue("画面上沿顶进了顶栏：" + drawn + " " + geo, drawn.top >= geo.topBar.bottom);
            float gapAbove = drawn.top - geo.topBar.bottom;
            float gapBelow = geo.hint.top - drawn.bottom;
            assertTrue("画面没有落在空档正中，偏下了：上留白=" + gapAbove + " 下留白=" + gapBelow,
                    Math.abs(gapAbove - gapBelow) <= CENTER_TOLERANCE_PX);

            assertTrue("预览被顶栏压住：" + geo, geo.preview.top >= geo.topBar.bottom);
            assertTrue("预览掉到提示条下面：" + geo, geo.preview.bottom <= geo.hint.top);
            assertTrue("取景区被挤扁了：" + geo,
                    geo.preview.width() > 0 && geo.preview.height() * 2 > geo.freeHeight());
        }
    }

    // ---- 装配与辅助 --------------------------------------------------------

    /** 设备息屏时真实排版不会跑，所以用例自己按屏幕尺寸量一遍整棵树。 */
    private Geometry measureAndRead(ScanActivity activity) {
        View decor = activity.getWindow().getDecorView();
        int width = activity.getResources().getDisplayMetrics().widthPixels;
        int height = activity.getResources().getDisplayMetrics().heightPixels;
        decor.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, width, height);

        Geometry geo = new Geometry();
        geo.preview = boundsOf(activity, R.id.preview_view);
        geo.overlay = boundsOf(activity, R.id.quad_overlay);
        geo.topBar = boundsOf(activity, R.id.top_bar);
        geo.hint = boundsOf(activity, R.id.tv_hint);
        geo.controls = boundsOf(activity, R.id.controls);
        return geo;
    }

    /** 这几个视图都是根 ConstraintLayout 的直接子节点，left/top 已经在同一个坐标系里。 */
    private static Rect boundsOf(Activity root, int id) {
        View view = root.findViewById(id);
        assertNotNull("取景页缺少视图：" + root.getResources().getResourceEntryName(id), view);
        return new Rect(view.getLeft(), view.getTop(), view.getRight(), view.getBottom());
    }

    private static final class Geometry {
        Rect preview;
        Rect overlay;
        Rect topBar;
        Rect hint;
        Rect controls;

        int freeHeight() {
            return hint.top - topBar.bottom;
        }

        /** 一帧 3:4 画面按 fitCenter 落进预览之后的实际矩形。 */
        Rect drawnFrame() {
            RectF rect = ImageGeom.fitCenterRect(FRAME_WIDTH, FRAME_HEIGHT,
                    preview.width(), preview.height());
            return new Rect(preview.left + Math.round(rect.left), preview.top + Math.round(rect.top),
                    preview.left + Math.round(rect.right), preview.top + Math.round(rect.bottom));
        }

        @Override
        public String toString() {
            return "预览" + preview + " 叠加" + overlay + " 顶栏" + topBar
                    + " 提示" + hint + " 控件" + controls;
        }
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
