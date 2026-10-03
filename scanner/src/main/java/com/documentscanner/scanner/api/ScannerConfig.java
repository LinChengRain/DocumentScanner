package com.documentscanner.scanner.api;

import com.documentscanner.scanner.cv.ImageBudget;

/**
 * 进程级配置：宿主在 Application（或起屏之前）调一次，模块内所有屏与导出按同一份配置走。
 *
 * <p>这里有<b>两种语义</b>共存，读这个类时别混：
 * <ul>
 *   <li>{@link #setSaveActionVisible} / {@link #setShareActionVisible} 是<b>能力开关</b>——
 *       宿主的产品决定「这个 App 要不要露出保存/分享」。布尔直值，不给就是不给。
 *       前者对应页面管理的「存到相册」与 PDF 预览的「另存为」，后者对应两处「分享」。
 *       关掉只是不给按钮，不动导出能力本身——PDF 照旧落在应用私有目录里。</li>
 *   <li>{@link #setImageBudget} 是<b>产品档</b>——同一份扫描件的清晰度与体积之争，
 *       没有「能不能」只有「要不要」。所以它收成一个枚举而不是两个自由数字，
 *       理由见 {@link ImageBudget}。</li>
 *   <li>{@link #setMaxPdfSizeKb} 是<b>成品上限</b>——它不选档，只在合成 PDF 时按需把像素
 *       再缩一点，直到装得进目标。和前两个都不一样：它改的是那一份 PDF，不改任何页面产物，
 *       也改不动已经导出的那份。上限够不着时照常出成品，不报错。</li>
 * </ul>
 *
 * <p>为什么都做成静态而不是起屏参数：这些都是与「起哪一屏」无关的产品决定。做成参数就得每次
 * 起屏都带一遍，漏一次那一屏照常按默认走，而这正是编译期查不出来的不一致。
 *
 * <p>读到的是「起那一屏 / 起那一次导出」时的值，过程中改动不动当前。
 */
public final class ScannerConfig {

    private static volatile boolean saveActionVisible = true;
    private static volatile boolean shareActionVisible = true;
    private static volatile ImageBudget imageBudget = ImageBudget.DEFAULT;
    private static volatile long maxPdfBytes;

    private ScannerConfig() {
    }

    /** 「保存」类动作是否出现在界面上，默认显示。 */
    public static void setSaveActionVisible(boolean visible) {
        saveActionVisible = visible;
    }

    public static boolean saveActionVisible() {
        return saveActionVisible;
    }

    /** 「分享」是否出现在界面上，默认显示。 */
    public static void setShareActionVisible(boolean visible) {
        shareActionVisible = visible;
    }

    public static boolean shareActionVisible() {
        return shareActionVisible;
    }

    /**
     * 图片体积档：决定页面产物长边、原图解码长边、JPEG 质量与合成 PDF 时的解码长边。
     * 默认 {@link ImageBudget#STANDARD}，即本次改动前的行为。传 null 视为默认档。
     *
     * <p>生效范围：此后新渲染的页面，以及此后合成的每一份 PDF。已渲染好的页面产物不会被重写，
     * 已导出的 PDF 也不会自动重生成——要按新档出图就重新导出一次。
     */
    public static void setImageBudget(ImageBudget budget) {
        imageBudget = budget == null ? ImageBudget.DEFAULT : budget;
    }

    public static ImageBudget imageBudget() {
        return imageBudget;
    }

    /**
     * 成品 PDF 的体积上限，单位 KB（与界面上那个「共 N 页 · X KB」同一口径，1 KB = 1024 B）。
     * 默认 0 表示不设上限，导出行为与有这个功能之前逐字节相同。0 或负数等同于不设上限。
     *
     * <p>它是<b>整份 PDF</b> 的上限，不是每页的：模块不知道也不该猜这次导出几页。
     * 要「每页不超过 500KB」就把上限写成 {@code 500 * 页数}。
     *
     * <p>生效范围：此后合成的每一份 PDF，且只在 {@code setImageBudget} 那档允许的范围内往下缩
     * ——上限再宽也不会出比这档更大的成品。够不着目标时交最小那份，不失败。
     *
     * @see com.documentscanner.scanner.export.PdfExporter#exportFitting
     */
    public static void setMaxPdfSizeKb(int kb) {
        maxPdfBytes = kb <= 0 ? 0 : kb * 1024L;
    }

    /** 体积上限的字节数；0 表示不设。 */
    public static long maxPdfBytes() {
        return maxPdfBytes;
    }

    /** 用例之间不互相串状态；宿主不该调这个。 */
    public static void resetForTesting() {
        saveActionVisible = true;
        shareActionVisible = true;
        imageBudget = ImageBudget.DEFAULT;
        maxPdfBytes = 0;
    }
}
