package com.documentscanner.export;

import java.io.File;

/**
 * 导出文件名分配。会话标题可以重复，同名会话也常常要反复导出，
 * 直接以标题命名会让后一次导出静默覆盖前一次的成品。
 */
public final class ExportNames {

    private ExportNames() {
    }

    /** 返回目录下第一个不存在的 {@code base (n).ext}，首个候选就是 base.ext。 */
    public static File unique(File dir, String base, String extension) {
        String safe = sanitize(base);
        File candidate = new File(dir, safe + extension);
        int index = 2;
        while (candidate.exists()) {
            candidate = new File(dir, safe + " (" + index + ")" + extension);
            index++;
        }
        return candidate;
    }

    /** 与 ScanSession.getSafeTitle 一致的字符集处理，标题里的路径分隔符不能带进文件名。 */
    public static String sanitize(String base) {
        String value = base == null ? "" : base.replaceAll("[\\\\/:*?\"<>|\\n\\r]", "_").trim();
        return value.isEmpty() ? "scan" : value;
    }
}
