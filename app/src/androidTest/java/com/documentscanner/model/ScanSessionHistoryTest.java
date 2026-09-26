package com.documentscanner.model;

import static com.documentscanner.model.SessionFixtures.QUAD;
import static com.documentscanner.model.SessionFixtures.addPage;
import static com.documentscanner.model.SessionFixtures.context;
import static com.documentscanner.model.SessionFixtures.freshSession;
import static com.documentscanner.model.SessionFixtures.read;
import static com.documentscanner.model.SessionFixtures.resetWorld;
import static com.documentscanner.model.SessionFixtures.root;
import static com.documentscanner.model.SessionFixtures.write;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 多会话：每份会话各占一个目录，开始新会话不再丢掉上一份，历史列表能读回摘要并随时切回去。
 */
@RunWith(AndroidJUnit4.class)
public class ScanSessionHistoryTest {

    @Before
    public void setUp() throws Exception {
        resetWorld();
    }

    @After
    public void tearDown() throws Exception {
        resetWorld();
    }

    // ---- 新会话与历史 -------------------------------------------------------

    @Test
    public void startingANewSessionKeepsTheOldOneInHistory() throws Exception {
        ScanSession first = freshSession();
        first.setTitle("第一份");
        addPage(first, true);

        ScanSession second = ScanSession.startNew(context());

        assertNotSame("当前会话有页面时必须另起一份", first, second);
        assertTrue(second.isEmpty());
        List<ScanSession.Info> history = ScanSession.history(context());
        assertEquals(1, history.size());
        assertEquals(first.getSessionId(), history.get(0).sessionId);
        assertEquals("第一份", history.get(0).title);
        assertEquals(1, history.get(0).pages);
    }

    @Test
    public void anEmptyCurrentSessionIsReusedInsteadOfPilingUp() throws Exception {
        ScanSession empty = freshSession();

        assertSame(empty, ScanSession.startNew(context()));
        assertTrue(ScanSession.history(context()).isEmpty());
    }

    @Test
    public void historyIsOrderedNewestFirst() throws Exception {
        ScanSession older = freshSession();
        older.setTitle("旧");
        addPage(older, false);
        ScanSession newer = ScanSession.startNew(context());
        newer.setTitle("新");
        addPage(newer, false);

        List<ScanSession.Info> history = ScanSession.history(context());

        assertEquals(2, history.size());
        assertEquals("新", history.get(0).title);
        assertEquals("旧", history.get(1).title);
        assertTrue(history.get(0).createdAt >= history.get(1).createdAt);
    }

    @Test
    public void aSessionDrainedOfItsLastPageDropsOutOfHistory() throws Exception {
        ScanSession session = freshSession();
        ScanPage page = addPage(session, false);
        session.remove(page.id);

        assertTrue(ScanSession.history(context()).isEmpty());
    }

    @Test
    public void openingAHistorySessionSwapsToItsPages() throws Exception {
        ScanSession first = freshSession();
        ScanPage firstPage = addPage(first, false);
        ScanSession second = ScanSession.startNew(context());
        ScanPage secondPage = addPage(second, false);

        ScanSession back = ScanSession.open(context(), first.getSessionId());

        assertNotSame(second, back);
        assertEquals(1, back.size());
        assertNotNull(back.byId(firstPage.id));
        assertNull("另一份会话的页面不能串进来", back.byId(secondPage.id));
        assertTrue(read(new File(root(), "index.json")).contains(first.getSessionId()));
    }

    @Test
    public void aNewSessionNeverLandsOnTopOfAnExistingDirectory() throws Exception {
        ScanSession first = freshSession();
        addPage(first, false);
        // 把从现在起一段时间内的目录名全占住，逼 startNew 往后找空位：
        // 会话 id 只有毫秒精度，同一毫秒内另起一份会把别人的目录接过来
        Set<String> occupied = new HashSet<>();
        long now = System.currentTimeMillis();
        for (long stamp = now; stamp <= now + 300; stamp++) {
            String name = "s-" + stamp;
            assertTrue(new File(root(), name).mkdirs());
            occupied.add(name);
        }

        ScanSession second = ScanSession.startNew(context());

        assertFalse("新会话占用了别人的目录", occupied.contains(second.getSessionId()));
        addPage(second, false);
        assertEquals(1, second.size());
        assertEquals(1, first.size());
    }

    // ---- 兜底与删除 ---------------------------------------------------------

    @Test
    public void openingAnUnknownOrUnsafeSessionFallsBackToAFreshOne() throws Exception {
        ScanSession fresh = ScanSession.open(context(), "s-does-not-exist");

        assertTrue(fresh.isEmpty());
        assertNotEquals("s-does-not-exist", fresh.getSessionId());

        ScanSession escaped = ScanSession.open(context(), "../escape");

        assertTrue(escaped.isEmpty());
        assertFalse("非法 id 不能在缓存里建目录",
                new File(context().getCacheDir(), "escape").exists());
    }

    @Test
    public void deletingTheCurrentSessionStartsOverWithAnEmptyOne() throws Exception {
        ScanSession session = freshSession();
        addPage(session, false);
        String id = session.getSessionId();

        ScanSession.deleteSession(context(), id);

        assertFalse(new File(root(), id).exists());
        ScanSession next = ScanSession.get(context());
        assertNotSame(session, next);
        assertTrue(next.isEmpty());
        assertTrue(ScanSession.history(context()).isEmpty());
    }

    @Test
    public void deletingAnotherSessionLeavesTheCurrentOneAlone() throws Exception {
        ScanSession older = freshSession();
        addPage(older, false);
        ScanSession current = ScanSession.startNew(context());
        addPage(current, false);

        ScanSession.deleteSession(context(), older.getSessionId());

        assertFalse(new File(root(), older.getSessionId()).exists());
        assertSame(current, ScanSession.get(context()));
        assertEquals(1, current.size());
        assertEquals(1, ScanSession.history(context()).size());
    }

    // ---- 页面复制与重拍 -----------------------------------------------------

    @Test
    public void duplicateCopiesProductsRightAfterTheSource() throws Exception {
        ScanSession session = freshSession();
        ScanPage source = addPage(session, true);
        write(source.thumb, "thumb-bytes");
        addPage(session, false);

        ScanPage copy = session.duplicate(source.id);

        assertNotNull(copy);
        assertEquals(3, session.size());
        assertEquals("复制页要紧跟原页", 1, session.indexOf(copy.id));
        assertNotEquals(source.id, copy.id);
        assertEquals(source.filter, copy.filter);
        assertTrue(copy.edited);
        assertArrayEquals(QUAD, copy.quad, 0f);
        assertEquals(read(source.original), read(copy.original));
        assertEquals("result", read(copy.result));
        assertEquals("thumb-bytes", read(copy.thumb));
    }

    @Test
    public void theCopyGetsItsOwnSelectionArray() throws Exception {
        ScanSession session = freshSession();
        ScanPage source = addPage(session, true);

        ScanPage copy = session.duplicate(source.id);

        assertNotNull(copy);
        assertNotSame(source.quad, copy.quad);
        copy.quad[0] = 0.5f;
        assertEquals(0.02f, source.quad[0], 0f);
    }

    @Test
    public void duplicatingAnUnknownPageIsIgnored() throws Exception {
        ScanSession session = freshSession();
        addPage(session, false);

        assertNull(session.duplicate("p999-missing"));
        assertEquals(1, session.size());
    }

    @Test
    public void retakeClearsDerivedFilesButKeepsThePhotoAndItsSlot() throws Exception {
        ScanSession session = freshSession();
        addPage(session, false);
        ScanPage page = addPage(session, true);
        write(page.warp, "warp");
        session.save();

        session.resetForRetake(page.id);

        assertFalse(page.edited);
        assertNull(page.quad);
        assertFalse(page.warp.exists());
        assertFalse(page.result.exists());
        assertTrue("重拍只作废产物，原始照片必须留下", page.original.isFile());
        assertEquals(1, session.indexOf(page.id));

        ScanSession revived = freshSession();
        assertEquals(2, revived.size());
        assertNotNull(revived.byId(page.id));
    }

    // ---- 历史摘要的封面 -----------------------------------------------------

    @Test
    public void historyCoverPrefersThePageThumb() throws Exception {
        ScanSession session = freshSession();
        ScanPage page = addPage(session, false);
        write(page.thumb, "thumb");

        List<ScanSession.Info> history = ScanSession.history(context());

        assertEquals(1, history.size());
        assertEquals(page.thumb.getAbsolutePath(), history.get(0).cover.getAbsolutePath());
    }

    @Test
    public void historyCoverFallsBackToTheOriginalPhoto() throws Exception {
        ScanSession session = freshSession();
        ScanPage page = addPage(session, false);

        ScanSession other = ScanSession.startNew(context());
        other.createPage();   // 这份一个文件都没写

        List<ScanSession.Info> history = ScanSession.history(context());

        assertEquals(2, history.size());
        assertNull(history.get(0).cover);
        assertEquals(page.original.getAbsolutePath(), history.get(1).cover.getAbsolutePath());
    }

    @Test
    public void aSessionWithoutAnyFilesStillListsButWithoutACover() throws Exception {
        ScanSession session = freshSession();
        ScanPage page = session.createPage();   // 只登记路径，文件一个都没写
        assertFalse(page.original.exists());

        List<ScanSession.Info> history = ScanSession.history(context());

        assertEquals(1, history.size());
        assertEquals(1, history.get(0).pages);
        assertNull(history.get(0).cover);
    }

    // ---- 旧版单会话布局迁移 -------------------------------------------------

    @Test
    public void legacySingleSessionLayoutIsMovedIntoHistory() throws Exception {
        File legacyDir = root();
        File legacyPhoto = new File(legacyDir, "originals/old.jpg");
        write(legacyPhoto, "old-photo");
        write(new File(legacyDir, "session.json"), "{\"title\":\"老会话\",\"createdAt\":1234,"
                + "\"sequence\":1,\"pages\":[{\"id\":\"p001-1\",\"original\":\""
                + legacyPhoto.getAbsolutePath() + "\",\"warp\":\"\",\"result\":\"\","
                + "\"thumb\":\"\",\"filter\":\"ENHANCED\",\"edited\":false}]}");

        List<ScanSession.Info> history = ScanSession.history(context());

        assertEquals(1, history.size());
        assertTrue(history.get(0).sessionId.startsWith("s-legacy-"));
        assertEquals("老会话", history.get(0).title);
        assertEquals(1, history.get(0).pages);
        assertFalse("旧状态文件不能留在原位", new File(legacyDir, "session.json").exists());

        ScanSession session = freshSession();
        assertEquals(1, session.size());
        ScanPage restored = session.pages().get(0);
        assertEquals("p001-1", restored.id);
        assertTrue("旧绝对路径要重新落到迁移后的会话目录里", restored.original.isFile());
        assertEquals(history.get(0).sessionId,
                restored.original.getParentFile().getParentFile().getName());
    }

    @Test
    public void legacyMigrationIsIdempotent() throws Exception {
        File legacyDir = root();
        write(new File(legacyDir, "originals/old.jpg"), "old-photo");
        write(new File(legacyDir, "session.json"), "{\"title\":\"老会话\",\"sequence\":1,"
                + "\"pages\":[{\"id\":\"p001-1\",\"original\":\"originals/old.jpg\"}]}");
        String firstId = ScanSession.history(context()).get(0).sessionId;

        ScanSession.history(context());
        List<ScanSession.Info> history = ScanSession.history(context());

        assertEquals(1, history.size());
        assertEquals(firstId, history.get(0).sessionId);
        assertEquals("old-photo",
                read(new File(new File(legacyDir, firstId), "originals/old.jpg")));
    }
}
