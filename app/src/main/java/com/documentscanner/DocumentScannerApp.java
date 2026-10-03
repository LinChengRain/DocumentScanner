package com.documentscanner;

import android.app.Application;

import com.documentscanner.scanner.api.ScannerEngine;
import com.documentscanner.scanner.api.ScannerSession;

public class DocumentScannerApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // native 库较大，放后台预热；各处 Cv.ensure() 是幂等的，会等它加载完
        ScannerEngine.warmUp();
        ScannerSession.prepare(this);
    }
}
