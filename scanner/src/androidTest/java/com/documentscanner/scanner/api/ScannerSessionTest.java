package com.documentscanner.scanner.api;

import static com.documentscanner.scanner.model.SessionFixtures.addPage;
import static com.documentscanner.scanner.model.SessionFixtures.context;
import static com.documentscanner.scanner.model.SessionFixtures.freshSession;
import static com.documentscanner.scanner.model.SessionFixtures.resetWorld;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.documentscanner.scanner.model.ScanSession;
import com.documentscanner.scanner.model.SessionFixtures;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * 会话门面的语义核对：宿主能看到的四个动作，是否真的对应「一份进程级当前会话」的规则。
 *
 * <p>{@link ScanSession} 自己的落盘/恢复由 model 那批用例覆盖（27 例），这里只验 api 层：
 * 门面一旦接错方法（例如 {@link ScannerSession#list} 改成返回当前会话的页面），
 * model 的用例全都还是绿的，宿主首页却会看到空列表。
 */
@RunWith(AndroidJUnit4.class)
public class ScannerSessionTest {

    @Before
    public void setUp() throws Exception {
        resetWorld();
    }

    @After
    public void tearDown() throws Exception {
        resetWorld();
    }

    @Test
    public void listReturnsPersistedSessionsNewestFirst() throws Exception {
        ScanSession older = freshSession();
        older.setTitle("旧的一份");
        addPage(older, true);
        ScannerSession.startNew(context());
        ScanSession newer = ScanSession.get(context());
        newer.setTitle("新的一份");
        addPage(newer, true);

        List<ScanSession.Info> history = ScannerSession.list(context());

        assertEquals(2, history.size());
        assertEquals(newer.getSessionId(), history.get(0).sessionId);
        assertEquals(older.getSessionId(), history.get(1).sessionId);
        assertEquals("旧的一份", history.get(1).title);
    }

    @Test
    public void openMovesTheActivePointerAndStartNewLeavesItAlone() throws Exception {
        ScanSession first = freshSession();
        addPage(first, true);
        String firstId = first.getSessionId();
        ScannerSession.startNew(context());
        String secondId = ScannerSession.activeId(context());
        assertNotEquals(firstId, secondId);

        ScannerSession.open(context(), firstId);

        assertEquals(firstId, SessionFixtures.activeId());
        assertEquals(firstId, ScannerSession.activeId(context()));
    }

    @Test
    public void openingAnUnknownSessionIdFallsBackToAValidOne() throws Exception {
        ScanSession current = freshSession();
        addPage(current, true);

        ScannerSession.open(context(), "no-such-session");

        assertNotEquals("no-such-session", SessionFixtures.activeId());
        assertEquals(current.getSessionId(), SessionFixtures.activeId());
    }

    @Test
    public void preparePutsAValidActiveSessionOnDisk() throws Exception {
        ScannerSession.prepare(context());

        // activeId 必须在 prepare 这一步就已经落到 index.json：
        // 若 prepare 是空实现，这里读到 null（后面那次 get() 也会写盘，所以只能先读）。
        String active = SessionFixtures.activeId();
        assertNotNull(active);
        assertEquals(ScanSession.get(context()).getSessionId(), active);
    }

    @Test
    public void deleteRemovesOnlyThatSessionFromHistory() throws Exception {
        ScanSession doomed = freshSession();
        doomed.setTitle("要删的");
        addPage(doomed, true);
        ScannerSession.startNew(context());
        ScanSession kept = ScanSession.get(context());
        addPage(kept, true);
        String doomedId = doomed.getSessionId();

        ScannerSession.delete(context(), doomedId);

        List<ScanSession.Info> history = ScannerSession.list(context());
        assertEquals(1, history.size());
        assertEquals(kept.getSessionId(), history.get(0).sessionId);
    }
}
