package com.documentscanner.util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 文本文件的原子写入与带备份读取。
 * 直接覆写会在进程崩溃或磁盘写满时留下半截文件，而这份文件往往是恢复数据的唯一依据；
 * 因此先写临时文件并 fsync，再改名替换，同时把上一版留成 .bak 供回退。
 */
public final class AtomicTextFile {

    private static final String TMP_SUFFIX = ".tmp";
    private static final String BAK_SUFFIX = ".bak";

    private AtomicTextFile() {
    }

    /** 原子写入。失败时抛出 IOException，且不会破坏 target 原有的内容。 */
    public static void write(File target, String content) throws IOException {
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建目录 " + dir);
        }
        File tmp = sibling(target, TMP_SUFFIX);
        try (FileOutputStream os = new FileOutputStream(tmp)) {
            os.write(content.getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.getFD().sync();
        } catch (IOException | RuntimeException e) {
            deleteQuietly(tmp);
            throw e;
        }

        File bak = sibling(target, BAK_SUFFIX);
        boolean hadTarget = target.exists();
        if (hadTarget && !target.renameTo(bak)) {
            deleteQuietly(tmp);
            throw new IOException("无法备份 " + target);
        }
        if (!tmp.renameTo(target)) {
            if (hadTarget && !bak.renameTo(target)) {
                throw new IOException("无法写入 " + target + "，上一版留在 " + bak);
            }
            deleteQuietly(tmp);
            throw new IOException("无法写入 " + target);
        }
    }

    /** 读不到（不存在或 IO 异常）时返回 null，交由调用方决定回退顺序。 */
    public static String readOrNull(File file) {
        if (file == null || !file.isFile()) return null;
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) file.length());
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) > 0) {
                out.write(chunk, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    public static File backupOf(File file) {
        return sibling(file, BAK_SUFFIX);
    }

    private static File sibling(File file, String suffix) {
        return new File(file.getAbsolutePath() + suffix);
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            file.deleteOnExit();
        }
    }
}
