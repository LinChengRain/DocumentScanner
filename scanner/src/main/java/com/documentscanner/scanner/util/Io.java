package com.documentscanner.scanner.util;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 极简线程调度：所有 OpenCV / IO 工作走后台，UI 回调走主线程。 */
public final class Io {

    private static final ExecutorService BG = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "documentscanner-bg");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Io() {
    }

    public static void bg(Runnable r) {
        BG.execute(r);
    }

    public static void main(Runnable r) {
        MAIN.post(r);
    }
}
