package com.documentscanner.scanner.api;

import android.content.Context;

import com.documentscanner.scanner.model.ScanSession;

import java.util.List;

/**
 * 会话入口：列历史、切会话、开新会话、删会话。
 *
 * <p><b>一个进程只有一份「当前会话」。</b>底层 {@link ScanSession} 是进程单例，
 * {@link #open} 与 {@link Scanner#startNewScan} 都会替换它，模块内所有屏幕共享同一份。
 * 这是 B3 定下的语义（见 docs/scanner-module-plan.md §9），宿主若要在同一进程里
 * 并行跑两份扫描会话，得先给单例改成显式句柄——目前没有这个需求，所以没改。
 *
 * <p>会话中间文件落在 {@code getCacheDir()/scan_session/}，系统可清，不是长期存储；
 * 已导出的 PDF 由 {@code getFilesDir()/export/} 承载。
 */
public final class ScannerSession {

    private ScannerSession() {
    }

    /** 历史会话，按时间倒序。不含当前会话未落盘的那部分改动。 */
    public static List<ScanSession.Info> list(Context context) {
        return ScanSession.history(context);
    }

    /** 把某份历史会话设为当前会话。之后各屏读写都指向它。 */
    public static void open(Context context, String sessionId) {
        ScanSession.open(context, sessionId);
    }

    /** 当前活动会话的 id。宿主用它判断「现在在编辑哪一份」，不需要拿到会话对象。 */
    public static String activeId(Context context) {
        return ScanSession.get(context).getSessionId();
    }

    /** 清空当前会话，开一份新的。历史那份不会被删。 */
    public static void startNew(Context context) {
        ScanSession.startNew(context);
    }

    /** 删除一份历史会话的中间文件；已导出的 PDF 保留。 */
    public static void delete(Context context, String sessionId) {
        ScanSession.deleteSession(context, sessionId);
    }

    /** 进程启动时预热单例（读一次索引），免得第一屏卡在 IO 上。 */
    public static void prepare(Context context) {
        ScanSession.get(context);
    }
}
