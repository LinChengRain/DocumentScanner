package com.documentscanner.scanner.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.documentscanner.scanner.R;
import com.documentscanner.scanner.cv.DocPipeline;
import com.documentscanner.scanner.cv.QuadGeometry;
import com.documentscanner.scanner.model.ScanPage;
import com.documentscanner.scanner.model.ScanSession;
import com.documentscanner.scanner.model.SessionFixtures;
import com.documentscanner.scanner.ui.widget.CropQuadView;
import com.documentscanner.scanner.util.ImageIO;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.core.Point;

import java.io.IOException;
import java.util.concurrent.Callable;

/**
 * 裁剪页左右留白的真实行为：选区贴到照片边缘时，角点手柄必须留在屏幕内、而且要按得住。
 * 设备息屏时真实排版不会跑，所以照片解码完成后由用例自己给裁剪视图量一个固定尺寸。
 */
@RunWith(AndroidJUnit4.class)
public class CropActivityEdgeTest {

    /** 自量的视图尺寸，与机型无关，于是留白断言只取决于 padding 与手柄半径的比例。 */
    private static final int VIEW_WIDTH = 1080;
    private static final int VIEW_HEIGHT = 1500;
    private static final int PHOTO_WIDTH = 1200;
    private static final int PHOTO_HEIGHT = 1600;
    /** 贴满整帧：四个角点分别落在照片的最外侧，也就是用户抱怨的那种极端情况。 */
    private static final float[] FULL_FRAME = new float[]{
            0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f};

    private Context context;
    private ScanSession session;
    private ScanPage page;
    private View fullAreaButton;

    @Before
    public void setUp() throws Exception {
        context = SessionFixtures.context();
        SessionFixtures.resetWorld();
        session = ScanSession.get(context);
        session.setTitle("留白用例");
        page = session.createPage();
        writePhoto(page);
        page.quad = FULL_FRAME.clone();
        session.save();
    }

    @After
    public void tearDown() throws Exception {
        SessionFixtures.resetWorld();
    }

    @Test
    public void aFullFrameSelectionKeepsEveryHandleInsideTheScreen() throws Exception {
        withCropView((view, rect) -> {
            float radius = handleRadius(view);
            assertTrue("左侧手柄被屏幕边缘切掉：rect.left=" + rect.left + " radius=" + radius,
                    rect.left >= radius);
            assertTrue("右侧手柄被屏幕边缘切掉：rect.right=" + rect.right
                            + " width=" + view.getWidth() + " radius=" + radius,
                    view.getWidth() - rect.right >= radius);
            assertTrue("上下留白放不下手柄：" + rect,
                    rect.top >= radius && view.getHeight() - rect.bottom >= radius);
            assertTrue("照片被推出视图：" + rect,
                    rect.left >= 0f && rect.top >= 0f
                            && rect.right <= view.getWidth() && rect.bottom <= view.getHeight());

            // 满幅选区的角点就在照片边缘上，所以它们在视图上正好落在矩形四角
            PointF[] corners = requirePoints(view);
            assertEquals(rect.left, corners[QuadGeometry.TOP_LEFT].x, 1f);
            assertEquals(rect.top, corners[QuadGeometry.TOP_LEFT].y, 1f);
            assertEquals(rect.right, corners[QuadGeometry.TOP_RIGHT].x, 1f);
            assertEquals(rect.left, corners[QuadGeometry.BOTTOM_LEFT].x, 1f);
            assertEquals(rect.bottom, corners[QuadGeometry.BOTTOM_LEFT].y, 1f);
        });
    }

    @Test
    public void anEdgeCornerIsGrabbableFromTheGutter() throws Exception {
        withCropView((view, rect) -> {
            float radius = handleRadius(view);
            // 指尖压在贴屏那一侧的手柄外沿上：这正是用户抱怨按不到的位置
            PointF corner = requirePoints(view)[QuadGeometry.TOP_LEFT];
            float x = corner.x - radius;
            float y = corner.y;
            assertTrue("按点落在视图外：" + x, x >= 0f);

            assertTrue("贴边的角点按不住", touch(view, MotionEvent.ACTION_DOWN, x, y));
            assertTrue("角点没跟着手指走",
                    touch(view, MotionEvent.ACTION_MOVE, x + 200f, y + 160f));
            touch(view, MotionEvent.ACTION_UP, x + 200f, y + 160f);

            Point moved = view.getCorners()[QuadGeometry.TOP_LEFT];
            assertTrue("左上角点没离开照片左边界：" + moved.x, moved.x > 1.0);
            assertTrue("左上角点没离开照片上边界：" + moved.y, moved.y > 1.0);
        });
    }

    @Test
    public void draggingAnEdgeCornerBackStopsAtThePhotoEdge() throws Exception {
        withCropView((view, rect) -> {
            dragCornerTo(view, rect.left + 240f, rect.top + 240f);
            dragCornerTo(view, 0f, 0f);        // 甩到留白之外

            Point clamped = view.getCorners()[QuadGeometry.TOP_LEFT];
            assertEquals("角点越过了照片左边界", 0.0, clamped.x, 0.5);
            assertEquals("角点越过了照片上边界", 0.0, clamped.y, 0.5);
            // 角点停在照片边缘，也就停在留白内侧，不会被拖进黑边
            assertEquals(rect.left, requirePoints(view)[QuadGeometry.TOP_LEFT].x, 1f);
        });
    }

    @Test
    public void theGrabbedCornerTracksTheFinger() throws Exception {
        withCropView((view, rect) -> {
            // 命中测试与绘制必须用同一个矩形，否则角点会偏离指尖，留白也就白留了
            PointF start = requirePoints(view)[QuadGeometry.TOP_LEFT];
            float x = start.x + 300f;
            float y = start.y + 200f;
            assertTrue("拖动起点落在视图外：" + x + "," + y,
                    x < view.getWidth() && y < view.getHeight());
            dragCornerTo(view, x, y);

            PointF after = requirePoints(view)[QuadGeometry.TOP_LEFT];
            assertEquals("角点没有停在指尖上", x, after.x, 2f);
            assertEquals("角点没有停在指尖上", y, after.y, 2f);
        });
    }

    @Test
    public void wholePageSelectionKeepsItsCornersInsideThePhoto() throws Exception {
        withCropView((view, rect) -> {
            Point[] before = view.getCorners();
            double beforeMinX = before[QuadGeometry.TOP_LEFT].x;
            double beforeMaxX = before[QuadGeometry.TOP_RIGHT].x;
            double beforeMinY = before[QuadGeometry.TOP_LEFT].y;
            double beforeMaxY = before[QuadGeometry.BOTTOM_LEFT].y;

            onMain(() -> {
                fullAreaButton.performClick();
                return null;
            });

            Point[] after = view.getCorners();
            double minX = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double minY = Double.MAX_VALUE;
            double maxY = -Double.MAX_VALUE;
            for (Point corner : after) {
                minX = Math.min(minX, corner.x);
                maxX = Math.max(maxX, corner.x);
                minY = Math.min(minY, corner.y);
                maxY = Math.max(maxY, corner.y);
            }
            // 「全选整页」在四个方向都要往里收一点：角点严格落在照片内，
            // 视图上就必然比矩形边缘更靠里，配合上面的矩形留白，手柄不可能出屏
            assertTrue("全选后左边界没有内收：" + minX + " >= " + beforeMinX,
                    minX > beforeMinX);
            assertTrue("全选后右边界没有内收：" + maxX + " <= " + beforeMaxX,
                    maxX < beforeMaxX);
            assertTrue("全选后上边界没有内收：" + minY, minY > beforeMinY);
            assertTrue("全选后下边界没有内收：" + maxY, maxY < beforeMaxY);
            assertTrue("全选后左上角点出了屏：" + requirePoints(view)[0].x,
                    requirePoints(view)[QuadGeometry.TOP_LEFT].x >= handleRadius(view));
        });
    }

    // ---- 装配与辅助 --------------------------------------------------------

    private interface CropViewCheck {
        void check(CropQuadView view, RectF rect) throws Exception;
    }

    /** 拉起真实裁剪页，等照片解码完成，再按固定尺寸量一遍布局让几何成立。 */
    private void withCropView(CropViewCheck check) throws Exception {
        try (ActivityScenario<CropActivity> scenario =
                     ActivityScenario.launch(CropActivity.intentFor(context, page.id))) {
            final CropQuadView[] holder = new CropQuadView[1];
            scenario.onActivity(activity -> {
                holder[0] = activity.findViewById(R.id.crop_view);
                // 「全选整页」在底部面板里，是裁剪视图的兄弟节点，只能从页面根上找
                fullAreaButton = activity.findViewById(R.id.btn_full_area);
            });
            final CropQuadView view = holder[0];
            waitUntil("照片解码完成", () -> view.hasImage() && view.getCorners() != null);
            onMain(() -> {
                view.measure(
                        View.MeasureSpec.makeMeasureSpec(VIEW_WIDTH, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(VIEW_HEIGHT, View.MeasureSpec.EXACTLY));
                view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
                return null;
            });
            check.check(view, onMain(view::imageRect));
        }
    }

    private static PointF[] requirePoints(CropQuadView view) {
        PointF[] points = onMain(view::cornerPoints);
        assertTrue("角点还没有换算到视图上", points != null);
        return points;
    }

    /** 从左上角点当前所在位置按下，拖到指定视图坐标后松手。 */
    private static void dragCornerTo(CropQuadView view, float x, float y) {
        PointF start = requirePoints(view)[QuadGeometry.TOP_LEFT];
        touch(view, MotionEvent.ACTION_DOWN, start.x, start.y);
        touch(view, MotionEvent.ACTION_MOVE, x, y);
        touch(view, MotionEvent.ACTION_UP, x, y);
    }

    private static float handleRadius(View view) {
        return CropQuadView.HANDLE_RADIUS_DP * view.getResources().getDisplayMetrics().density;
    }

    /** 直接 dispatchTouchEvent，走手指落到屏幕上的同一条路径。 */
    private static boolean touch(View view, int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
        try {
            return view.dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    private void writePhoto(ScanPage target) throws IOException {
        Bitmap photo = Bitmap.createBitmap(PHOTO_WIDTH, PHOTO_HEIGHT, Bitmap.Config.ARGB_8888);
        photo.eraseColor(Color.WHITE);
        try {
            ImageIO.saveJpeg(photo, target.original, DocPipeline.JPEG_QUALITY_ORIGINAL);
        } finally {
            photo.recycle();
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

    private void waitUntil(String what, Callable<Boolean> condition) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 20_000;
        while (SystemClock.uptimeMillis() < deadline) {
            if (Boolean.TRUE.equals(onMain(condition))) return;
            Thread.sleep(50);
        }
        fail("等待超时：" + what);
    }
}
