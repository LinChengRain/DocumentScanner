package com.documentscanner.model;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

import com.documentscanner.cv.FilterType;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

/** 会话测试的公共脚手架：把缓存目录清干净、丢弃单例、按当前活动会话定位状态文件。 */
public final class SessionFixtures {

    static final String ROOT_DIR = "scan_session";
    static final String STATE_FILE = "session.json";
    public static final float[] QUAD = new float[]{
            0.02f, 0.02f, 0.98f, 0.02f, 0.98f, 0.98f, 0.02f, 0.98f};

    private SessionFixtures() {
    }

    public static Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    public static File root() {
        return new File(context().getCacheDir(), ROOT_DIR);
    }

    /** 每个用例前后都要一份干净的世界：磁盘清空 + 单例丢弃。 */
    public static void resetWorld() throws Exception {
        resetInstance();
        deleteRecursively(root());
    }

    /** ScanSession 是进程级单例，测试里必须手动丢弃上一个实例。 */
    public static void resetInstance() throws Exception {
        Field instance = ScanSession.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    public static ScanSession freshSession() throws Exception {
        resetInstance();
        return ScanSession.get(context());
    }

    /** index.json 记录的当前活动会话 id。 */
    public static String activeId() throws Exception {
        String json = readOrNull(new File(root(), "index.json"));
        return json == null ? null : new JSONObject(json).optString("active", null);
    }

    public static File sessionDir() throws Exception {
        return new File(root(), activeId());
    }

    public static File stateFile() throws Exception {
        return new File(sessionDir(), STATE_FILE);
    }

    public static File backupFile() throws Exception {
        return new File(sessionDir(), STATE_FILE + ".bak");
    }

    public static File tmpFile() throws Exception {
        return new File(sessionDir(), STATE_FILE + ".tmp");
    }

    /** 走真实 API 造一页，并把它依赖的文件建出来。 */
    public static ScanPage addPage(ScanSession session, boolean edited) throws IOException {
        ScanPage page = session.createPage();
        write(page.original, "photo");
        if (edited) {
            page.edited = true;
            page.filter = FilterType.BINARY;
            page.quad = QUAD;
            write(page.result, "result");
            session.save();
        }
        return page;
    }

    public static void write(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileOutputStream os = new FileOutputStream(file)) {
            os.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static String read(File file) throws IOException {
        String value = readOrNull(file);
        return value == null ? "" : value;
    }

    public static String readOrNull(File file) throws IOException {
        if (!file.isFile()) return null;
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) > 0) {
                out.write(buffer, 0, count);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    public static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
