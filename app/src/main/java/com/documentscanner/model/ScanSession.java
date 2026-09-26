package com.documentscanner.model;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.documentscanner.cv.FilterType;
import com.documentscanner.cv.QuadGeometry;
import com.documentscanner.util.AtomicTextFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 一次扫描会话（多页集合）的内存索引 + 磁盘落盘。
 * 每个会话独占一个目录（{@code cache/scan_session/<id>/}），进程被回收后可从该目录里的
 * session.json 恢复；哪一个是「当前会话」记在 {@code scan_session/index.json} 里。
 * 页面产物存的是相对会话根目录的路径，因此整个目录搬动后仍能读回。
 */
public class ScanSession {

    private static final String TAG = "ScanSession";
    private static final String ROOT_DIR = "scan_session";
    private static final String STATE_FILE = "session.json";
    private static final String INDEX_FILE = "index.json";
    private static final String LEGACY_PREFIX = "/" + ROOT_DIR + "/";
    private static final String[] LEGACY_DIRS = {"originals", "warps", "results", "thumbs"};
    /** 会话 id 由本类生成，读回来时也要能挡住被改坏的路径。 */
    private static final java.util.regex.Pattern ID_PATTERN =
            java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private static ScanSession instance;

    private final File root;
    private final File originalsDir;
    private final File warpsDir;
    private final File resultsDir;
    private final File thumbsDir;
    private final File exportDir;
    private final List<ScanPage> pages = new ArrayList<>();

    private String id;
    private String title;
    private long createdAt;
    private int sequence;

    /** 当前活动会话；进程内第一次调用时按 index.json 决定打开哪一份。 */
    public static synchronized ScanSession get(Context context) {
        Context app = context.getApplicationContext();
        if (instance == null) {
            instance = new ScanSession(app, ensureActiveId(app));
        }
        return instance;
    }

    /** 切换到某个历史会话；id 非法或目录已不在时退回一份新会话。 */
    public static synchronized ScanSession open(Context context, String sessionId) {
        Context app = context.getApplicationContext();
        File dir = sessionDir(app, sessionId);
        String target = dir != null && dir.isDirectory() ? sessionId : ensureActiveId(app);
        writeActiveId(app, target);
        instance = new ScanSession(app, target);
        return instance;
    }

    /** 开始一份新会话；当前会话还没落任何页面时直接复用，不攒空壳。 */
    public static synchronized ScanSession startNew(Context context) {
        Context app = context.getApplicationContext();
        ScanSession current = get(app);
        if (current.isEmpty()) return current;
        String fresh = newId(app);
        writeActiveId(app, fresh);
        instance = new ScanSession(app, fresh);
        return instance;
    }

    /** 历史会话摘要，按创建时间从新到旧；空会话不进列表。 */
    public static synchronized List<Info> history(Context context) {
        Context app = context.getApplicationContext();
        migrateLegacyIfNeeded(app);
        List<Info> result = new ArrayList<>();
        File[] dirs = rootDir(app).listFiles(File::isDirectory);
        if (dirs == null) return result;
        for (File dir : dirs) {
            Info info = peek(dir);
            if (info != null && info.pages > 0) result.add(info);
        }
        Collections.sort(result, new Comparator<Info>() {
            @Override
            public int compare(Info left, Info right) {
                int byTime = Long.compare(right.createdAt, left.createdAt);
                // 同一毫秒内创建的两份会话按 id 倒序，id 里的时间戳保证单调
                return byTime != 0 ? byTime : right.sessionId.compareTo(left.sessionId);
            }
        });
        return result;
    }

    /** 删除一个会话目录。删掉的正是当前会话时丢弃单例，下次 get() 会另起一份。 */
    public static synchronized void deleteSession(Context context, String sessionId) {
        Context app = context.getApplicationContext();
        File dir = sessionDir(app, sessionId);
        if (dir == null) return;
        deleteRecursively(dir);
        if (sessionId.equals(readActiveId(app))) {
            writeActiveId(app, null);
            instance = null;
        }
    }

    private ScanSession(Context context, String sessionId) {
        Context app = context.getApplicationContext();
        id = sessionId;
        root = new File(rootDir(app), sessionId);
        originalsDir = mkdirs(new File(root, "originals"));
        warpsDir = mkdirs(new File(root, "warps"));
        resultsDir = mkdirs(new File(root, "results"));
        thumbsDir = mkdirs(new File(root, "thumbs"));
        exportDir = mkdirs(new File(app.getFilesDir(), "export"));
        restore();
        if (TextUtils.isEmpty(title)) {
            title = defaultTitle();
        }
        if (createdAt <= 0) {
            createdAt = System.currentTimeMillis();
            persist();
        }
    }

    // ---- 页面管理 ----------------------------------------------------------

    public List<ScanPage> pages() {
        return pages;
    }

    public int size() {
        return pages.size();
    }

    public boolean isEmpty() {
        return pages.isEmpty();
    }

    public ScanPage byId(String pageId) {
        for (ScanPage page : pages) {
            if (page.id.equals(pageId)) return page;
        }
        return null;
    }

    public int indexOf(String pageId) {
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i).id.equals(pageId)) return i;
        }
        return -1;
    }

    /** 新建一页并登记四个产物文件路径，调用方负责写入内容。 */
    public ScanPage createPage() {
        sequence++;
        String pageId = String.format(Locale.US, "p%03d-%d", sequence, System.currentTimeMillis());
        ScanPage page = new ScanPage(pageId,
                new File(originalsDir, pageId + ".jpg"),
                new File(warpsDir, pageId + ".jpg"),
                new File(resultsDir, pageId + ".jpg"),
                new File(thumbsDir, pageId + ".jpg"));
        pages.add(page);
        persist();
        return page;
    }

    public ScanPage remove(String pageId) {
        ScanPage page = byId(pageId);
        if (page == null) return null;
        pages.remove(page);
        deleteFilesOf(page);
        persist();
        return page;
    }

    /** 重拍本页前的清理：旧产物作废，页面位置与 id 保持不变。 */
    public void resetForRetake(String pageId) {
        ScanPage page = byId(pageId);
        if (page == null) return;
        page.edited = false;
        page.quad = null;
        deleteQuietly(page.warp);
        deleteQuietly(page.result);
        persist();
    }

    /** 复制一页（连同已生成的产物），新页紧跟在原页之后。 */
    public ScanPage duplicate(String pageId) {
        int at = indexOf(pageId);
        if (at < 0) return null;
        ScanPage source = pages.get(at);
        ScanPage copy = createPage();
        copy.filter = source.filter;
        copy.edited = source.edited;
        copy.quad = source.quad == null ? null : source.quad.clone();
        copyInto(source.original, copy.original);
        copyInto(source.warp, copy.warp);
        copyInto(source.result, copy.result);
        copyInto(source.thumb, copy.thumb);
        // createPage 只能追加在末尾，这里挪回原页之后，符合「复制自第几页」的直觉
        pages.remove(copy);
        pages.add(at + 1, copy);
        persist();
        return copy;
    }

    public void move(int from, int to) {
        if (from < 0 || to < 0 || from >= pages.size() || to >= pages.size() || from == to) return;
        ScanPage page = pages.remove(from);
        pages.add(to, page);
        persist();
    }

    /** 页面字段（滤镜/角点/edited）被就地修改后调用，把状态落盘。 */
    public void save() {
        persist();
    }

    public String getSessionId() {
        return id;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String newTitle) {
        if (!TextUtils.isEmpty(newTitle)) {
            this.title = newTitle;
            persist();
        }
    }

    /** 会话标题对应的安全文件名，用于 PDF 与相册命名。 */
    public String getSafeTitle() {
        String base = TextUtils.isEmpty(title) ? "scan"
                : title.replaceAll("[\\\\/:*?\"<>|\\n\\r]", "_").trim();
        return TextUtils.isEmpty(base) ? "scan" : base;
    }

    public File exportDir() {
        return exportDir;
    }

    /** 清空当前会话：删除所有页面与产物文件，但保留导出目录里的成品。 */
    public void clear() {
        for (ScanPage page : new ArrayList<>(pages)) {
            deleteFilesOf(page);
        }
        pages.clear();
        sequence = 0;
        persist();
    }

    private void deleteFilesOf(ScanPage page) {
        deleteQuietly(page.original);
        deleteQuietly(page.warp);
        deleteQuietly(page.result);
        deleteQuietly(page.thumb);
    }

    // ---- 持久化 ------------------------------------------------------------

    private void persist() {
        try {
            JSONObject state = new JSONObject();
            state.put("title", title);
            state.put("createdAt", createdAt);
            state.put("sequence", sequence);
            JSONArray array = new JSONArray();
            for (ScanPage page : pages) {
                JSONObject item = new JSONObject();
                item.put("id", page.id);
                item.put("original", pathOf(page.original));
                item.put("warp", pathOf(page.warp));
                item.put("result", pathOf(page.result));
                item.put("thumb", pathOf(page.thumb));
                item.put("filter", page.filter.name());
                item.put("edited", page.edited);
                if (page.quad != null && page.quad.length == 8) {
                    JSONArray quad = new JSONArray();
                    for (float value : page.quad) {
                        quad.put(value);
                    }
                    item.put("quad", quad);
                }
                array.put(item);
            }
            state.put("pages", array);

            AtomicTextFile.write(new File(root, STATE_FILE), state.toString());
        } catch (IOException | JSONException e) {
            Log.w(TAG, "会话状态写入失败", e);
        }
    }

    /** 主状态文件不可用（半截、被清理）时退回上一版备份，两版都读不出就保持空会话而不删文件。 */
    private void restore() {
        File stateFile = new File(root, STATE_FILE);
        if (applyState(AtomicTextFile.readOrNull(stateFile))) return;
        String backup = AtomicTextFile.readOrNull(AtomicTextFile.backupOf(stateFile));
        if (applyState(backup)) {
            Log.i(TAG, "主状态文件不可用，已从备份恢复");
            persist();
        }
    }

    /** 解析成功才把页面提交进列表，中途失败不能留下半份状态。 */
    private boolean applyState(String json) {
        if (json == null) return false;
        List<ScanPage> restored = new ArrayList<>();
        try {
            JSONObject state = new JSONObject(json);
            String parsedTitle = state.optString("title", null);
            long parsedCreatedAt = state.optLong("createdAt", 0);
            int parsedSequence = state.optInt("sequence", 0);
            JSONArray array = state.optJSONArray("pages");
            if (array == null) return false;
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                ScanPage page = new ScanPage(item.getString("id"),
                        resolvePath(item.optString("original", null)),
                        resolvePath(item.optString("warp", null)),
                        resolvePath(item.optString("result", null)),
                        resolvePath(item.optString("thumb", null)));
                page.filter = FilterType.valueOf(item.optString("filter", FilterType.ENHANCED.name()));
                page.edited = item.optBoolean("edited", false);
                page.quad = readQuad(item);
                // 缓存被系统清理时丢弃残缺页面，而不是在后续步骤里崩溃
                boolean usable = page.original != null && page.original.exists()
                        && (!page.edited || page.bestOutput().exists());
                if (usable) {
                    restored.add(page);
                } else {
                    deleteFilesOf(page);
                }
            }
            title = parsedTitle;
            createdAt = parsedCreatedAt;
            sequence = parsedSequence;
            pages.addAll(restored);
            Log.i(TAG, "恢复会话 " + id + ": " + pages.size() + " 页");
            return true;
        } catch (JSONException | IllegalArgumentException e) {
            Log.w(TAG, "会话状态解析失败", e);
            return false;
        }
    }

    private static float[] readQuad(JSONObject item) {
        JSONArray array = item.optJSONArray("quad");
        if (array == null || array.length() != 8) return null;
        float[] quad = new float[8];
        for (int i = 0; i < 8; i++) {
            quad[i] = (float) array.optDouble(i, 0);
        }
        return QuadGeometry.isValidNormalized(quad) ? quad : null;
    }

    /** 存相对路径，会话目录整体挪动（含老版本单会话迁移）后路径依然指向自己那份文件。 */
    private String pathOf(File file) {
        if (file == null) return "";
        String prefix = root.getAbsolutePath() + "/";
        String absolute = file.getAbsolutePath();
        return absolute.startsWith(prefix) ? absolute.substring(prefix.length()) : absolute;
    }

    private File resolvePath(String stored) {
        return resolve(root, stored);
    }

    private static File resolve(File root, String stored) {
        if (TextUtils.isEmpty(stored)) return null;
        File asAbsolute = new File(stored);
        if (!asAbsolute.isAbsolute()) return new File(root, stored);
        if (asAbsolute.isFile()) return asAbsolute;
        // 旧版单会话布局存的是绝对路径，文件已随目录迁移，取尾段重新落到本会话目录下
        int at = stored.indexOf(LEGACY_PREFIX);
        return at >= 0 ? new File(root, stored.substring(at + LEGACY_PREFIX.length())) : asAbsolute;
    }

    // ---- 会话目录与索引 ------------------------------------------------------

    private static File rootDir(Context app) {
        return new File(app.getCacheDir(), ROOT_DIR);
    }

    /** 校验过的会话目录；id 不合法时返回 null，避免被拼成目录之外的路径。 */
    private static File sessionDir(Context app, String sessionId) {
        if (sessionId == null || !ID_PATTERN.matcher(sessionId).matches()) return null;
        return new File(rootDir(app), sessionId);
    }

    private static String newId(Context app) {
        // 同一毫秒内连续开两份会话时也要各占一个目录，否则新会话会接上刚那份的页面
        File root = rootDir(app);
        long stamp = System.currentTimeMillis();
        String candidate;
        do {
            candidate = String.format(Locale.US, "s-%d", stamp++);
        } while (new File(root, candidate).exists());
        return candidate;
    }

    private static synchronized String readActiveId(Context app) {
        try {
            String json = AtomicTextFile.readOrNull(new File(rootDir(app), INDEX_FILE));
            if (json == null) return null;
            return new JSONObject(json).optString("active", null);
        } catch (JSONException e) {
            return null;
        }
    }

    private static synchronized void writeActiveId(Context app, String sessionId) {
        try {
            JSONObject index = new JSONObject();
            index.put("active", sessionId == null ? "" : sessionId);
            AtomicTextFile.write(new File(rootDir(app), INDEX_FILE), index.toString());
        } catch (IOException | JSONException e) {
            Log.w(TAG, "会话索引写入失败", e);
        }
    }

    /** 当前活动会话 id；索引缺失或指向已不存在的目录时另起一份并记下来。 */
    private static synchronized String ensureActiveId(Context app) {
        migrateLegacyIfNeeded(app);
        String active = readActiveId(app);
        File dir = sessionDir(app, active);
        if (dir != null && dir.isDirectory()) return active;
        String fresh = newId(app);
        writeActiveId(app, fresh);
        return fresh;
    }

    /**
     * 单会话时代的布局是 {@code scan_session/session.json} 加四个产物目录，
     * 整体搬进一个会话目录即可；页面里存的绝对路径由 resolvePath 重新落回本目录。
     */
    private static synchronized void migrateLegacyIfNeeded(Context app) {
        File root = rootDir(app);
        File legacyState = new File(root, STATE_FILE);
        if (!legacyState.isFile()) return;
        String id = "s-legacy-" + legacyState.lastModified();
        File target = sessionDir(app, id);
        if (target == null || target.isDirectory()) return;
        if (!target.mkdirs()) return;
        if (!legacyState.renameTo(new File(target, STATE_FILE))) {
            Log.w(TAG, "旧会话状态迁移失败，保持原样");
            return;
        }
        for (String name : LEGACY_DIRS) {
            File from = new File(root, name);
            if (from.isDirectory()) from.renameTo(new File(target, name));
        }
        writeActiveId(app, id);
        Log.i(TAG, "已把单会话布局迁移到 " + id);
    }

    /** 只读地看一眼某个会话目录，用于历史列表。 */
    private static Info peek(File dir) {
        String json = AtomicTextFile.readOrNull(new File(dir, STATE_FILE));
        if (json == null) json = AtomicTextFile.readOrNull(AtomicTextFile.backupOf(new File(dir, STATE_FILE)));
        if (json == null) return null;
        try {
            JSONObject state = new JSONObject(json);
            JSONArray array = state.optJSONArray("pages");
            String title = state.optString("title", null);
            return new Info(dir.getName(), TextUtils.isEmpty(title) ? null : title,
                    state.optLong("createdAt", dir.lastModified()),
                    array == null ? 0 : array.length(),
                    coverOf(dir, array));
        } catch (JSONException e) {
            return null;
        }
    }

    /** 历史列表封面：取第一页还留在磁盘上的产物，全被清掉则没有封面。 */
    private static File coverOf(File dir, JSONArray pages) {
        JSONObject first = pages == null ? null : pages.optJSONObject(0);
        if (first == null) return null;
        for (String key : new String[]{"thumb", "result", "original"}) {
            File candidate = resolve(dir, first.optString(key, null));
            if (candidate != null && candidate.isFile()) return candidate;
        }
        return null;
    }

    // ---- 小工具 ------------------------------------------------------------

    private static String defaultTitle() {
        return new SimpleDateFormat("'扫描件' yyyy-MM-dd HH.mm", Locale.CHINA).format(new Date());
    }

    private static File mkdirs(File dir) {
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "无法创建目录 " + dir);
        }
        return dir;
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            Log.w(TAG, "删除失败 " + file);
        }
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        if (!file.delete()) {
            Log.w(TAG, "删除失败 " + file);
        }
    }

    private static void copyInto(File from, File to) {
        if (from == null || to == null || !from.isFile()) return;
        try (InputStream in = new FileInputStream(from);
             OutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            Log.w(TAG, "复制页面文件失败 " + from + " -> " + to, e);
        }
    }

    /** 历史列表用的一行摘要。 */
    public static class Info {
        public final String sessionId;
        /** 可能为 null：状态文件里没写标题时交给界面兜底。 */
        public final String title;
        public final long createdAt;
        public final int pages;
        public final File cover;

        Info(String sessionId, String title, long createdAt, int pages, File cover) {
            this.sessionId = sessionId;
            this.title = title;
            this.createdAt = createdAt;
            this.pages = pages;
            this.cover = cover;
        }
    }
}
