package com.documentscanner.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.documentscanner.R;
import com.documentscanner.scanner.model.ScanPage;
import com.documentscanner.scanner.model.ScanSession;
import com.documentscanner.scanner.model.SessionFixtures;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 首页历史列表的真实渲染：布局能否 inflate、行文本与可见性对不对。
 * RecyclerView 在 ScrollView 里不会自己走完 layout，测试里手动量一次。
 */
@RunWith(AndroidJUnit4.class)
public class MainActivityHistoryTest {

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
    public void aFreshHomeHidesTheHistorySection() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                assertEquals(View.GONE, visibilityOf(activity, R.id.tv_history_title));
                assertEquals(View.GONE, visibilityOf(activity, R.id.rv_sessions));
            });
        }
    }

    @Test
    public void sessionsAreListedNewestFirstWithPageCounts() throws Exception {
        seedSession("第一份");
        seedSession("第二份");

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                RecyclerView list = activity.findViewById(R.id.rv_sessions);
                assertEquals(View.VISIBLE, visibilityOf(activity, R.id.tv_history_title));
                layout(list);

                assertEquals(2, list.getAdapter().getItemCount());
                View newest = list.getChildAt(0);
                View oldest = list.getChildAt(1);
                assertEquals("第二份", titleOf(newest));
                assertEquals("第一份", titleOf(oldest));
                assertTrue("行数里要带页数与日期：" + metaOf(newest),
                        metaOf(newest).startsWith("1 页 · "));
            });
        }
    }

    @Test
    public void tappingARowContinuesThatSession() throws Exception {
        String older = seedSession("旧会话");
        seedSession("新会话");

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                RecyclerView list = activity.findViewById(R.id.rv_sessions);
                layout(list);

                list.getChildAt(1).performClick();

                assertEquals(older, ScanSession.get(context).getSessionId());
            });
        }
    }

    @Test
    public void aSessionWithoutFilesStillGetsARowWithThePlaceholderCover() throws Exception {
        ScanSession session = ScanSession.get(context);
        session.setTitle("空壳");
        session.createPage();          // 只登记路径，一个文件都不写
        SessionFixtures.resetInstance();

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                RecyclerView list = activity.findViewById(R.id.rv_sessions);
                layout(list);

                assertEquals(1, list.getAdapter().getItemCount());
                assertEquals("空壳", titleOf(list.getChildAt(0)));
            });
        }
    }

    // ---- 辅助 --------------------------------------------------------------

    /** 造一份有一页产物的会话，另起一份空的接着用，返回刚造好那份的 id。 */
    private String seedSession(String title) throws Exception {
        ScanSession session = ScanSession.get(context);
        session.setTitle(title);
        ScanPage page = session.createPage();
        SessionFixtures.write(page.original, "photo");
        SessionFixtures.write(page.thumb, "thumb");
        session.save();
        String seeded = session.getSessionId();
        ScanSession.startNew(context);
        SessionFixtures.resetInstance();
        return seeded;
    }

    private static int visibilityOf(MainActivity activity, int id) {
        return activity.findViewById(id).getVisibility();
    }

    private static void layout(RecyclerView list) {
        list.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        list.layout(0, 0, list.getMeasuredWidth(), list.getMeasuredHeight());
    }

    private static String titleOf(View row) {
        return textOf(row, R.id.tv_session_title);
    }

    private static String metaOf(View row) {
        return textOf(row, R.id.tv_session_meta);
    }

    private static String textOf(View row, int id) {
        return ((TextView) row.findViewById(id)).getText().toString();
    }
}
