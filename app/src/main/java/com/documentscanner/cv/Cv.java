package com.documentscanner.cv;

import android.util.Log;

import org.opencv.android.OpenCVLoader;

/** OpenCV native 库只需在进程内加载一次。 */
public final class Cv {

    private static final String TAG = "Cv";
    private static volatile boolean loaded;

    private Cv() {
    }

    public static synchronized boolean ensure() {
        if (!loaded) {
            try {
                loaded = OpenCVLoader.initLocal();
            } catch (Throwable t) {
                Log.e(TAG, "加载 OpenCV native 库失败", t);
                loaded = false;
            }
            Log.i(TAG, "OpenCV initLocal=" + loaded + " version=" + OpenCVLoader.OPENCV_VERSION);
        }
        return loaded;
    }

    public static String version() {
        return OpenCVLoader.OPENCV_VERSION;
    }
}
