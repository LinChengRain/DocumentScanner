package com.documentscanner.scanner.api;

import com.documentscanner.scanner.cv.Cv;
import com.documentscanner.scanner.util.Io;

/**
 * 识别引擎（OpenCV）的对外可见信息。
 *
 * <p>这里刻意不出现任何 {@code org.opencv.*} 类型：模块对宿主是
 * {@code implementation} 依赖，宿主编译期看不到 OpenCV，也不该看到——
 * 一旦签名里出现 {@code Mat}，整条依赖就得开成 {@code api}，
 * 21MB 的 native 库和它的版本约束（4.10.0 被钉，见 README §1）会外泄给所有接入方。
 */
public final class ScannerEngine {

    private ScannerEngine() {
    }

    /** 编译进包的 OpenCV 版本号，用于「识别引擎 OpenCV x.y.z」这类展示。 */
    public static String version() {
        return Cv.version();
    }

    /** 后台预热 native 库；幂等，各屏自己也会等它加载完。 */
    public static void warmUp() {
        Io.bg(Cv::ensure);
    }
}
