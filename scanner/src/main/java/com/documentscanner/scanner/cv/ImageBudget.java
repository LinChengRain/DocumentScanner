package com.documentscanner.scanner.cv;

/**
 * 图片体积档：一次决定「页面产物长边 + 解码原图长边 + JPEG 质量 + 导出解码长边」四个数。
 *
 * <p>为什么做成档位而不是两个自由数字：这四个数互相牵制。解码长边低于输出长边就是在放大
 * （更大但不更清），输出长边高于解码长边就是白丢像素；把它们拆开暴露出去，宿主能配出
 * 「又大又糊」这种谁都没想要的组合。
 *
 * <p>为什么这一档能控住 PDF 大小：框架的 PdfDocument 不吃页面 JPEG 的字节，它把交进去的
 * 位图按接近无损的质量重编码（实测 2.25~7.5 倍），所以能控的只有像素数，而像素数由这里的
 * 长边决定，字节大致随长边的平方走。也正因如此，导出侧的解码长边必须精确缩放而不是 2 的
 * 幂次降采样——后者会让「调到 1600」对一张 1800 的页面完全不起作用。
 */
public enum ImageBudget {

    /** 只为发出去能用：邮件、微信里的正文。 */
    TINY(900, 1100, 78, 900),
    /** 屏幕阅读够用，比默认档省一半以上。 */
    DRAFT(1200, 1500, 82, 1200),
    /** 出厂档，数值等于本次改动前散在各处的常量，不改任何历史产物字节。 */
    STANDARD(1800, 2200, 92, 1800),
    /** 打小纸面、要留档的用；页面位图约 17 MB，低内存机器上要留意。 */
    FINE(2400, 2800, 95, 2400);

    public static final ImageBudget DEFAULT = STANDARD;

    /** 矫正产物（warp/result）的长边上限。 */
    public final int outputEdge;
    /** 渲染时解码原始照片的长边上限，必须不低于 outputEdge，否则矫正就是在放大。 */
    public final int sourceDecodeEdge;
    /** 页面产物 JPEG 的编码质量。 */
    public final int jpegQuality;
    /** 合成 PDF 时解码页面产物的长边上限。 */
    public final int pdfDecodeEdge;

    ImageBudget(int outputEdge, int sourceDecodeEdge, int jpegQuality, int pdfDecodeEdge) {
        this.outputEdge = outputEdge;
        this.sourceDecodeEdge = sourceDecodeEdge;
        this.jpegQuality = jpegQuality;
        this.pdfDecodeEdge = pdfDecodeEdge;
    }

    /** 单页位图的峰值内存（ARGB_8888，正方形上界）；给设置方一个可读的量纲。 */
    public long maxPageBytes() {
        return (long) outputEdge * outputEdge * 4;
    }

    /** 名字读不出（null、脏值、被删掉的档）时退到出厂档，而不是抛异常。 */
    public static ImageBudget byName(String name) {
        if (name == null) return DEFAULT;
        for (ImageBudget budget : values()) {
            if (budget.name().equals(name)) return budget;
        }
        return DEFAULT;
    }
}
