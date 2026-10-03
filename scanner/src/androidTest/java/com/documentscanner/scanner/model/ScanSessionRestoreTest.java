package com.documentscanner.scanner.model;

import static com.documentscanner.scanner.model.SessionFixtures.QUAD;
import static com.documentscanner.scanner.model.SessionFixtures.addPage;
import static com.documentscanner.scanner.model.SessionFixtures.backupFile;
import static com.documentscanner.scanner.model.SessionFixtures.deleteRecursively;
import static com.documentscanner.scanner.model.SessionFixtures.freshSession;
import static com.documentscanner.scanner.model.SessionFixtures.read;
import static com.documentscanner.scanner.model.SessionFixtures.resetWorld;
import static com.documentscanner.scanner.model.SessionFixtures.stateFile;
import static com.documentscanner.scanner.model.SessionFixtures.tmpFile;
import static com.documentscanner.scanner.model.SessionFixtures.write;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.documentscanner.scanner.cv.FilterType;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * 会话状态的落盘与恢复。这里测的是「进程重启之后」的行为：单例被重置、
 * 磁盘上可能留下半截 JSON、也可能被系统清掉一部分缓存文件。
 */
@RunWith(AndroidJUnit4.class)
public class ScanSessionRestoreTest {

    @Before
    public void setUp() throws Exception {
        resetWorld();
    }

    @After
    public void tearDown() throws Exception {
        resetWorld();
    }

    @Test
    public void persistsAndRestoresPageState() throws Exception {
        ScanSession session = freshSession();
        session.setTitle("合同扫描件");
        ScanPage page = addPage(session, true);

        ScanSession revived = freshSession();

        assertEquals(1, revived.size());
        assertEquals("合同扫描件", revived.getTitle());
        ScanPage restored = revived.byId(page.id);
        assertNotNull("按 id 找不回恢复的页面", restored);
        assertEquals(FilterType.BINARY, restored.filter);
        assertTrue(restored.edited);
        assertArrayEquals(QUAD, restored.quad, 0f);
        assertEquals(page.original.getAbsolutePath(), restored.original.getAbsolutePath());
    }

    @Test
    public void relativeProductPathsSurviveAWholeDirectoryMove() throws Exception {
        ScanSession session = freshSession();
        ScanPage page = addPage(session, false);
        String sessionId = session.getSessionId();

        // 把整个会话目录换名挪走并把索引指过去：产物存的是相对路径，挪动后必须还能找回文件
        File moved = new File(SessionFixtures.root(), "moved-" + sessionId);
        assertTrue(new File(SessionFixtures.root(), sessionId).renameTo(moved));
        write(new File(SessionFixtures.root(), "index.json"),
                "{\"active\":\"moved-" + sessionId + "\"}");

        ScanSession revived = freshSession();

        assertEquals(1, revived.size());
        ScanPage restored = revived.pages().get(0);
        assertEquals(page.id, restored.id);
        assertTrue("相对路径没能在新目录下定位到原始照片", restored.original.isFile());
        assertEquals(moved.getAbsolutePath(),
                restored.original.getParentFile().getParentFile().getAbsolutePath());
    }

    @Test
    public void restoredSequenceKeepsIdsUnique() throws Exception {
        ScanSession session = freshSession();
        ScanPage first = addPage(session, false);
        addPage(session, false);

        ScanSession revived = freshSession();
        assertEquals(2, revived.size());
        ScanPage third = revived.createPage();
        ScanPage fourth = revived.createPage();

        assertEquals(4, revived.size());
        assertFalse(third.id.equals(first.id));
        assertEquals(4, new HashSet<>(idsOf(revived)).size());
        assertEquals(2, revived.indexOf(third.id));
        assertEquals(3, revived.indexOf(fourth.id));
    }

    private static List<String> idsOf(ScanSession session) {
        List<String> ids = new ArrayList<>();
        for (ScanPage page : session.pages()) {
            ids.add(page.id);
        }
        return ids;
    }

    @Test
    public void truncatedStateFallsBackToBackup() throws Exception {
        ScanSession session = freshSession();
        ScanPage first = addPage(session, false);
        addPage(session, false);
        assertTrue("第二次落盘后应留下上一版备份", backupFile().exists());

        write(stateFile(), "{\"title\":\"半截\",\"sequence\":2,\"pages\":[{\"id\"");

        ScanSession revived = freshSession();

        assertEquals(1, revived.size());
        assertEquals(first.id, revived.pages().get(0).id);
        // 恢复成功后立刻重新落盘，主文件重新可用，且不留临时文件
        assertTrue(read(stateFile()).contains(first.id));
        assertFalse(tmpFile().exists());
    }

    @Test
    public void partiallyBrokenStateNeverCommitsHalfAList() throws Exception {
        ScanSession session = freshSession();
        ScanPage good = addPage(session, false);
        // 断掉备份回退，这里只考察主文件解析中途失败时的原子性
        deleteRecursively(backupFile());
        String valid = read(stateFile());
        assertTrue(valid.endsWith("]}"));

        // 真落盘的状态 + 一个缺 id 的尾部条目：解析必须在尾部整体失败，第一条也不能留在内存里
        write(stateFile(), valid.substring(0, valid.length() - 2) + ",{\"original\":\"\"}]}");

        ScanSession revived = freshSession();

        assertEquals("半截状态被提交了一部分", 0, revived.size());
        assertNull(revived.byId(good.id));
    }

    @Test
    public void anAbsolutePathLeftInPlaceStillResolves() throws Exception {
        ScanSession session = freshSession();
        ScanPage page = addPage(session, false);
        deleteRecursively(backupFile());

        // 迁移中途失败时产物可能还留在原处，绝对路径 + 文件仍在原位必须认得
        write(stateFile(), "{\"title\":\"绝对\",\"sequence\":1,\"pages\":[{\"id\":\""
                + page.id + "\",\"original\":\"" + page.original.getAbsolutePath() + "\"}]}");

        ScanSession revived = freshSession();

        assertEquals(1, revived.size());
        assertTrue(revived.byId(page.id).original.isFile());
    }

    @Test
    public void dropsPagesWhoseFilesWereCleanedUp() throws Exception {
        ScanSession session = freshSession();
        ScanPage kept = addPage(session, false);
        ScanPage gone = addPage(session, false);
        assertTrue(gone.original.delete());

        ScanSession revived = freshSession();

        assertEquals(1, revived.size());
        assertEquals(kept.id, revived.pages().get(0).id);
    }

    @Test
    public void editedPageSurvivesWhenResultWasCleanedUp() throws Exception {
        ScanSession session = freshSession();
        addPage(session, false);
        ScanPage edited = session.createPage();
        write(edited.original, "photo");
        edited.edited = true;
        edited.filter = FilterType.GRAYSCALE;
        edited.quad = QUAD;
        write(edited.result, "result");
        session.save();
        assertTrue(edited.result.delete());

        ScanSession revived = freshSession();

        // 原始照片还在，编辑结果可以重新渲染，因此这一页必须留下并保留其选区与滤镜
        assertEquals(2, revived.size());
        ScanPage restored = revived.byId(edited.id);
        assertNotNull(restored);
        assertTrue(restored.edited);
        assertEquals(FilterType.GRAYSCALE, restored.filter);
        assertArrayEquals(QUAD, restored.quad, 0f);
    }

    @Test
    public void unusableBothVersionsStartsEmpty() throws Exception {
        ScanSession session = freshSession();
        addPage(session, false);

        write(stateFile(), "not json at all");
        write(backupFile(), "also not json");

        ScanSession revived = freshSession();

        assertTrue(revived.isEmpty());
        assertFalse(revived.getTitle().isEmpty());
    }

    @Test
    public void outOfRangeQuadIsDiscardedButPageKept() throws Exception {
        ScanSession session = freshSession();
        ScanPage page = session.createPage();
        write(page.original, "photo");
        page.edited = true;
        page.quad = new float[]{1.5f, 0f, 1f, 0f, 1f, 1f, 0f, 1f};
        write(page.result, "out");
        session.save();

        ScanSession revived = freshSession();

        assertEquals(1, revived.size());
        assertNull(revived.byId(page.id).quad);
    }
}
