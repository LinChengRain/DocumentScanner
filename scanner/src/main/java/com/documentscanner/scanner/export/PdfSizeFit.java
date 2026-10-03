package com.documentscanner.scanner.export;

/**
 * 「按体积目标决定下一枪用多长解码边」的算术。
 *
 * <p>为什么单独立一个类而不是写在 {@link PdfExporter} 里：那一边满手 {@code android.graphics.*}，
 * 纯函数住在里面就只能上真机才能验证，而这里最容易写错恰恰是这几个数——指数取多少、下限卡在哪、
 * 什么时候该停。它们必须是 JVM 能钉住的。
 *
 * <p>为什么是「写一次量一次再缩」而不是一次算到位：成品字节只能写出来才知道，而指数在内容之间差得远。
 * 同一台机器上量过的几条曲线（长边→字节，每段都是相邻两枪实测）：
 * <ul>
 *   <li>噪声图样（F5 的四档基线）：2.06~2.22；</li>
 *   <li>多尺度「字」的文档图样（两页 A4，1800→1278→697）：0.73、1.45；</li>
 *   <li>真机走查那次真会话（2 页，1800→1311→1102→993）：0.97、0.85、0.96；</li>
 *   <li>单一粗细线条的探针图样（12 个边长铺到 600~2400）：600→1800 整段只有 0.25，
 *       1500→2400 那一段甚至是负的——字节几乎不跟着边动，还会不降反升。</li>
 * </ul>
 * 也就是说 2.15 这个先验对**真实扫描件**大约乐观了两倍：按它算出的下一枪只缩到该缩的一半，
 * 于是四轮（上限）打完还在目标外面——走查那次要 512,000 B，交出来 521,829 B。
 *
 * <p>所以指数只当**第一枪的起点**，从第二枪起改用「上一枪和这一枪量出来的斜率」。先验必须偏大：
 * 边只降不升，砍过头了没法回头，指数取大一点最多是多写一轮。
 */
final class PdfSizeFit {

    /**
     * 没有实测点可用时的先验：{@code bytes ≈ 长边^2.15}，来自 F5 四档实测
     * （537,865 / 1,010,445 / 2,333,347 / 4,422,958 B 对 900 / 1200 / 1800 / 2400）。
     */
    static final double SIZE_EXPONENT = 2.15;

    /**
     * 斜率下限。真文档能低到 0.73（两页多尺度「字」的图样，1800→1278 那一段实测），
     * 单一粗细线条的探针图样更极端：600→1800 整段只有 0.25，1500→2400 那一段甚至是负的——
     * 字节几乎不跟着边动了（重采样共振）。再往下取就是一枪踩到 {@link #MIN_DECODE_EDGE}，
     * 宁可多写一轮慢慢磨。
     */
    static final double MIN_EXPONENT = 0.8;

    /** 斜率上限：比最陡的实测段（2.22）再松一点，防的是一次量出个假陡、结果几乎不缩。 */
    static final double MAX_EXPONENT = 3.0;

    /**
     * 往目标里留的余量：真正的指数只在实测点附近局部成立（同一份噪声图样 900→1200 段量出 2.19，
     * 1200→1800 段只有 2.06），一刀砍到刚好贴线的话下一枪多半还差一点点，于是来回磨。
     * 按 0.9 的目标缩，第一枪就落在上限之内，多数会话两轮写完。
     */
    static final double TARGET_MARGIN = 0.9;

    /** 再小就不是扫描件了，是带几个灰点的白纸。宁可承认超目标也不交这种东西。 */
    static final int MIN_DECODE_EDGE = 600;

    /** 最多写几轮。走查那次按先验走是 1800→1311→1102→993 四轮，标定之后同样内容三轮就到。 */
    static final int MAX_ATTEMPTS = 4;

    private PdfSizeFit() {
    }

    /** 没设目标（0 或负数）就是永远满足；设了就按字节比，等号算装得进去。 */
    static boolean fits(long pdfBytes, long maxBytes) {
        return maxBytes <= 0 || pdfBytes <= maxBytes;
    }

    /**
     * 用相邻两枪的实测点标定指数：{@code p = ln(字节比) / ln(边比)}，钳进
     * {@link #MIN_EXPONENT}~{@link #MAX_EXPONENT}。
     *
     * <p>比较的是「上一枪（更大边）」和「这一枪（更小边）」，所以 {@code priorEdge <= edge}
     * 说明没有可用的对（第一枪、或者外部把边喂乱了），回落到先验。字节不降反升也算不出斜率，
     * 那种图样是重采样共振（单一粗细的线条碰上整数缩放比），钳到下限继续往下缩。
     */
    static double exponent(int edge, long bytes, int priorEdge, long priorBytes) {
        if (priorEdge <= 0 || priorBytes <= 0 || bytes <= 0 || priorEdge <= edge) {
            return SIZE_EXPONENT;
        }
        double measured = Math.log(bytes / (double) priorBytes)
                / Math.log(edge / (double) priorEdge);
        return Math.max(MIN_EXPONENT, Math.min(MAX_EXPONENT, measured));
    }

    /**
     * 下一枪的解码长边；返回 0 表示再缩也无益——装得进去了，或者已经到下限。
     *
     * <p>只会缩不会升是硬约束，也是这里唯一不需要额外钳位的原因：走到这一步说明
     * {@code actualBytes > maxBytes}，于是比值带着余量之后必然小于 1，取 floor 之后严格低于
     * 当前边。往上涨在数学上就不可达——页面产物只有渲染档那 {@code outputEdge} 个像素，
     * 把解码边抬到它之上是空操作（{@code ImageIO.scaleMaxEdge} 原样返回），
     * 多写一轮只会把同一份 PDF 再来一遍。
     *
     * <p>贴着线的目标也会整个余量（约 5% 边长、10% 字节）缩下去，而不是只降一格：
     * 斜率本身就随段落摆（同一份噪声图样 900→1200 是 2.20、1200→1800 只有 2.06），
     * 一格一格地磨要多写好几轮才收敛，不如第一枪就留出余量。
     * 代价是成品通常比上限小 10% 左右——上限是上限，不是精确值。
     */
    static int nextEdge(int edge, long maxBytes, long actualBytes, double exponent) {
        if (fits(actualBytes, maxBytes) || edge <= MIN_DECODE_EDGE) return 0;
        double ratio = maxBytes * TARGET_MARGIN / (double) actualBytes;
        return Math.max(MIN_DECODE_EDGE,
                (int) Math.floor(edge * Math.pow(ratio, 1.0 / exponent)));
    }

    /**
     * 逐轮拟合的状态：记住上一枪量出来的点，好让 {@link #exponent} 有意义。
     *
     * <p>它单独拎出来是为了让「四枪怎么收敛」这件事能在 JVM 上跑：真机上量到的一条字节曲线
     * 喂进 {@link PdfExporter#exportFitting} 就再也复现不了，而按目标缩边这套逻辑最容易错的
     * 正是第 2、3 枪用的斜率。导出方只管写文件、量字节、问它要下一枪。
     *
     * <p>一次导出一个实例，别复用：复用的是斜率，不是目标。
     */
    static final class Search {

        private int priorEdge;
        private long priorBytes;

        /**
         * 报告这一枪（{@code edge}）量出了 {@code bytes}，换取下一枪的解码边；0 表示收工
         * （装得进去了，或已经踩到下限），守卫在 {@link PdfSizeFit#nextEdge} 里。
         */
        int nextEdge(int edge, long bytes, long maxBytes) {
            double exponent = PdfSizeFit.exponent(edge, bytes, priorEdge, priorBytes);
            priorEdge = edge;
            priorBytes = bytes;
            return PdfSizeFit.nextEdge(edge, maxBytes, bytes, exponent);
        }
    }
}
