package com.documentscanner.scanner.ui;

import android.app.Activity;
import android.util.Log;

import com.documentscanner.scanner.cv.ImageBudget;
import com.documentscanner.scanner.cv.PageRenderer;
import com.documentscanner.scanner.model.ScanPage;
import com.documentscanner.scanner.model.ScanSession;

import java.util.ArrayList;
import java.util.List;

/**
 * 导出前的补齐：把还没确认过裁剪的页面按自动识别的选区渲染出来。
 * 页面管理和取景页两条导出入口共用这一份定义，否则同一份会话从两边导出会拿到不同的页。
 *
 * <p>只能在主线程调用：{@link PageRenderer} 的回调落在主线程，逐页串行的下一步也在主线程发起。
 */
final class PendingRenderer {

    private static final String TAG = "PendingRenderer";

    interface Listener {
        /** 真的有待补页时开始补齐，调用方借此进入忙态。 */
        void begin();

        /** 补齐结束（含页面中途被收尾的情况），调用方借此退出忙态。 */
        void end();

        /** 其中一页没补上；{@code remaining} 是含这一页在内还剩几页。 */
        void onPageFailed(int remaining);

        /** 全部处理完。没有待补页时也会回调，且不经 {@link #begin}/{@link #end}。 */
        void ready();
    }

    private PendingRenderer() {
    }

    static void prepare(Activity host, ScanSession session, ImageBudget budget, Listener listener) {
        final List<ScanPage> pending = new ArrayList<>();
        for (ScanPage page : session.pages()) {
            if (!page.edited || !page.hasResult()) pending.add(page);
        }
        if (pending.isEmpty()) {
            listener.ready();
            return;
        }
        listener.begin();
        pump(host, pending, 0, budget, listener);
    }

    /** 一次只渲染一页：并行会把内存压满。 */
    private static void pump(Activity host, List<ScanPage> pending, int index,
                             ImageBudget budget, Listener listener) {
        if (host.isFinishing() || host.isDestroyed()) {
            listener.end();
            return;
        }
        if (index >= pending.size()) {
            listener.end();
            listener.ready();
            return;
        }
        ScanPage page = pending.get(index);
        PageRenderer.render(page, page.quad, budget, error -> {
            if (error != null) {
                Log.w(TAG, "页面补齐失败 " + page.id, error);
                listener.onPageFailed(pending.size() - index);
            }
            pump(host, pending, index + 1, budget, listener);
        });
    }
}
