package com.documentscanner;

import android.app.Application;

import com.documentscanner.cv.Cv;
import com.documentscanner.model.ScanSession;
import com.documentscanner.util.Io;

public class DocumentScannerApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // native 库较大，放后台预热；各处 Cv.ensure() 是幂等的，会等它加载完
        Io.bg(Cv::ensure);
        ScanSession.get(this);
    }
}
