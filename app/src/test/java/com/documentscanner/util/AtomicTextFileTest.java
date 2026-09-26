package com.documentscanner.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 会话状态落盘依赖的原子性：写失败不能留下半截文件，上一版要能被找回来。
 */
public class AtomicTextFileTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void write_thenReadsBack() throws IOException {
        File target = file("state.json");
        AtomicTextFile.write(target, "第一版");
        assertEquals("第一版", AtomicTextFile.readOrNull(target));
        assertFalse("成功后不应留下临时文件", new File(target + ".tmp").exists());
    }

    @Test
    public void write_createsMissingParentDirs() throws IOException {
        File target = new File(folder.getRoot(), "nested/deep/state.json");
        AtomicTextFile.write(target, "{}");
        assertEquals("{}", AtomicTextFile.readOrNull(target));
    }

    @Test
    public void rewrite_keepsPreviousVersionAsBackup() throws IOException {
        File target = file("state.json");
        AtomicTextFile.write(target, "v1");
        AtomicTextFile.write(target, "v2");

        assertEquals("v2", AtomicTextFile.readOrNull(target));
        assertEquals("v1", AtomicTextFile.readOrNull(AtomicTextFile.backupOf(target)));
    }

    @Test
    public void failedWrite_leavesPreviousContentIntact() throws IOException {
        File target = file("state.json");
        AtomicTextFile.write(target, "好数据");

        // 临时文件不可写，模拟写入中途失败（磁盘满 / 权限异常）
        File tmp = new File(target.getAbsolutePath() + ".tmp");
        assertTrue(tmp.createNewFile());
        assertTrue(tmp.setWritable(false));
        try {
            AtomicTextFile.write(target, "半截数据");
            fail("写入应当失败");
        } catch (IOException expected) {
            assertEquals("好数据", AtomicTextFile.readOrNull(target));
            assertFalse("失败后不应留下临时文件", tmp.exists());
        } finally {
            tmp.setWritable(true);
        }
    }

    @Test
    public void readOrNull_returnsNullForMissingFile() {
        assertNull(AtomicTextFile.readOrNull(file("does-not-exist.json")));
        assertNull(AtomicTextFile.readOrNull(null));
    }

    @Test
    public void backupSurvivesNonAtomicTruncation() throws IOException {
        File target = file("state.json");
        AtomicTextFile.write(target, "v1");
        AtomicTextFile.write(target, "v2");
        truncateWith(target, "半截的 js");

        // 主文件被直接覆写坏掉时，上一版仍在，调用方可以按 主→备 的顺序重试
        assertEquals("半截的 js", AtomicTextFile.readOrNull(target));
        assertEquals("v1", AtomicTextFile.readOrNull(AtomicTextFile.backupOf(target)));
    }

    /** 绕开原子写入，模拟崩溃在旧版覆写过程里留下的半截文件。 */
    private static void truncateWith(File target, String content) throws IOException {
        try (FileOutputStream os = new FileOutputStream(target)) {
            os.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private File file(String name) {
        return new File(folder.getRoot(), name);
    }
}
