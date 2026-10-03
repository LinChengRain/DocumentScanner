package com.documentscanner.scanner.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.content.Context;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.documentscanner.scanner.R;
import com.documentscanner.scanner.api.ScannerConfig;
import com.documentscanner.scanner.model.ScanSession;
import com.documentscanner.scanner.model.SessionFixtures;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.Callable;

/** 页面管理网格的真实渲染：行数、页码标签、菜单入口与「未确认」角标。 */
@RunWith(AndroidJUnit4.class)
public class PageListActivityTest {

    private Context context;
    private ScanSession session;

    @Before
    public void setUp() throws Exception {
        context = SessionFixtures.context();
        SessionFixtures.resetWorld();
        session = ScanSession.get(context);
        session.setTitle("网格用例");
    }

    @After
    public void tearDown() throws Exception {
        ScannerConfig.resetForTesting();
        SessionFixtures.resetWorld();
    }

    @Test
    public void theGridListsEveryPageInOrder() throws Exception {
        SessionFixtures.addPage(session, true);
        SessionFixtures.addPage(session, true);

        withPageList(activity -> {
            RecyclerView grid = grid(activity);
            assertEquals(2, grid.getAdapter().getItemCount());
            assertEquals("1", labelOf(row(grid, 0)));
            assertEquals("2", labelOf(row(grid, 1)));
            assertEquals(View.VISIBLE, row(grid, 0).findViewById(R.id.btn_page_menu).getVisibility());
            assertTrue(headerOf(activity).contains("网格用例"));
            assertTrue(headerOf(activity).contains("2 页"));
        });
    }

    @Test
    public void anUnconfirmedPageCarriesThePendingTag() throws Exception {
        SessionFixtures.addPage(session, true);
        SessionFixtures.addPage(session, false);

        withPageList(activity -> {
            RecyclerView grid = grid(activity);

            assertEquals(View.GONE, row(grid, 0).findViewById(R.id.tv_pending).getVisibility());
            assertEquals(View.VISIBLE, row(grid, 1).findViewById(R.id.tv_pending).getVisibility());
        });
    }

    /** 宿主关掉「保存」这一类动作时，只有那一个按钮消失，分享照常留在原位。 */
    @Test
    public void theSaveButtonFollowsTheConfigWithoutTouchingShare() throws Exception {
        SessionFixtures.addPage(session, true);
        SessionFixtures.addPage(session, true);
        ScannerConfig.setSaveActionVisible(false);

        withPageList(activity -> {
            assertEquals(View.GONE, view(activity, R.id.btn_save_gallery).getVisibility());
            assertEquals(View.VISIBLE, view(activity, R.id.btn_share_images).getVisibility());
            assertEquals(View.VISIBLE, view(activity, R.id.action_row).getVisibility());
        });
    }

    /** 两个都关掉时那一行跟着收起，底栏不该留一条 48dp 的空带。 */
    @Test
    public void hidingBothActionsCollapsesTheRow() throws Exception {
        SessionFixtures.addPage(session, true);
        SessionFixtures.addPage(session, true);
        ScannerConfig.setSaveActionVisible(false);
        ScannerConfig.setShareActionVisible(false);

        withPageList(activity -> {
            assertEquals(View.GONE, view(activity, R.id.btn_save_gallery).getVisibility());
            assertEquals(View.GONE, view(activity, R.id.btn_share_images).getVisibility());
            assertEquals(View.GONE, view(activity, R.id.action_row).getVisibility());
        });
    }

    /** 默认状态是「都给」：宿主一次都不调 ScannerConfig 时，行为与分模块之前一致。 */
    @Test
    public void bothActionsAreVisibleWhenTheHostSaysNothing() throws Exception {
        SessionFixtures.addPage(session, true);
        SessionFixtures.addPage(session, true);

        withPageList(activity -> {
            assertEquals(View.VISIBLE, view(activity, R.id.btn_save_gallery).getVisibility());
            assertEquals(View.VISIBLE, view(activity, R.id.btn_share_images).getVisibility());
            assertEquals(View.VISIBLE, view(activity, R.id.action_row).getVisibility());
        });
    }

    // ---- 辅助 --------------------------------------------------------------

    private static View view(Activity activity, int id) {
        return activity.findViewById(id);
    }
    private void withPageList(TestBody body) {
        try (ActivityScenario<PageListActivity> scenario =
                     ActivityScenario.launch(PageListActivity.class)) {
            final PageListActivity[] holder = new PageListActivity[1];
            scenario.onActivity(activity -> holder[0] = activity);
            waitUntilMain(holder[0], "网格完成排版", () -> {
                RecyclerView grid = grid(holder[0]);
                grid.measure(
                        View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY));
                grid.layout(0, 0, grid.getMeasuredWidth(), grid.getMeasuredHeight());
                return grid.getChildAt(1) != null;
            });
            onMain(holder[0], () -> {
                body.run(holder[0]);
                return null;
            });
        }
    }

    private interface TestBody {
        void run(PageListActivity activity);
    }

    private static RecyclerView grid(Activity activity) {
        return activity.findViewById(R.id.page_grid);
    }

    private static View row(RecyclerView grid, int index) {
        return grid.getChildAt(index);
    }

    private static String labelOf(View row) {
        return ((TextView) row.findViewById(R.id.tv_page_index)).getText().toString();
    }

    private static String headerOf(PageListActivity activity) {
        return ((TextView) activity.findViewById(R.id.tv_title)).getText().toString();
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

    private static void waitUntilMain(Activity activity, String what, Callable<Boolean> condition) {
        long deadline = SystemClock.uptimeMillis() + 15_000;
        while (SystemClock.uptimeMillis() < deadline) {
            if (Boolean.TRUE.equals(onMain(activity, condition))) return;
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
