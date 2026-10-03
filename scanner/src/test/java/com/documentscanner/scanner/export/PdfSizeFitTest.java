package com.documentscanner.scanner.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.documentscanner.scanner.cv.ImageBudget;

import org.junit.Test;

/**
 * 缩边算术。
 *
 * <p>这几条只能也应当在 JVM 上钉住：真机能证明「最后那份确实变小了」，证明不了「变小的方式
 * 是按实测字节连续缩的，而不是照着四档查表、或者一刀砍到底」。而后者才是这个功能的全部风险——
 * 指数写成 2、下限忘了卡、允许往上涨，真机上看着都「变小了」。
 *
 * <p>斜率的来源同样是算术而不是玄学：{@link #WALKTHROUGH_EDGES}/{@link #WALKTHROUGH_BYTES}
 * 是真机 release 走查那一次 logcat 里量到的四个点，照着它跑一遍多轮拟合，就能在没有手机的
 * 情况下复现「四轮打完还差 10 KB」这个已经发生过的失败。
 */
public class PdfSizeFitTest {

    /** F5 在同一台机上量出来的三个真实点，用来把指数钉死而不是让它跟着实现漂。 */
    private static final long A4_AT_1200 = 1_010_445L;
    private static final long A4_AT_1800 = 2_333_347L;
    private static final long A4_AT_900 = 537_865L;

    /** 真机走查那次的两点：1800→909,516、1311→668,330，局部斜率约 0.97。 */
    private static final int WALK_EDGE_1 = 1800;
    private static final long WALK_BYTES_1 = 909_516L;
    private static final int WALK_EDGE_2 = 1311;
    private static final long WALK_BYTES_2 = 668_330L;
    private static final int WALK_EDGE_3 = 1102;
    private static final long WALK_BYTES_3 = 576_395L;
    private static final int WALK_EDGE_4 = 993;
    private static final long WALK_BYTES_4 = 521_829L;
    private static final long WALK_TARGET = 512_000L;

    @Test
    public void noTargetMeansEverythingFits() {
        assertTrue(PdfSizeFit.fits(A4_AT_1800, 0));
        assertTrue("负数也是「不表态」", PdfSizeFit.fits(A4_AT_1800, -1));
    }

    /** 等号算装得进去：宿主说 500KB，出 512,000 B 就是 500KB，不该为此多写一轮。 */
    @Test
    public void landingExactlyOnTheTargetCountsAsFitting() {
        assertTrue(PdfSizeFit.fits(1000L, 1000L));
        assertFalse(PdfSizeFit.fits(1001L, 1000L));
    }

    @Test
    public void aPdfThatAlreadyFitsNeedsNoSecondGuess() {
        assertEquals(0, PdfSizeFit.nextEdge(1800, 4_000_000L, A4_AT_1800,
                PdfSizeFit.SIZE_EXPONENT));
    }

    /**
     * 从 1800 的实测字节出发，缩到「TINY 那档的大小」应当落在 866 上下（再按余量收一点）。
     *
     * <p>这个区间就是 {@link PdfSizeFit#SIZE_EXPONENT} 本身：指数写成 2 会得到 820，写成 2.3
     * 会得到 908。四档之间那些数不是枚举里的常量，是拟合出来的，所以得有区间守着它。
     */
    @Test
    public void theShrinkTracksThePriorExponent() {
        int edge = PdfSizeFit.nextEdge(ImageBudget.STANDARD.pdfDecodeEdge, A4_AT_900, A4_AT_1800,
                PdfSizeFit.SIZE_EXPONENT);

        assertTrue("按 2.15 的指数该落在 866 上下：" + edge, edge >= 860 && edge <= 872);
    }

    /** 同一个量数换个斜率就该给个不一样的边——斜率参数不是装饰。 */
    @Test
    public void theGuessFollowsTheExponentItIsGiven() {
        int byPrior = PdfSizeFit.nextEdge(1800, WALK_TARGET, WALK_BYTES_1,
                PdfSizeFit.SIZE_EXPONENT);
        int byMeasurement = PdfSizeFit.nextEdge(1800, WALK_TARGET, WALK_BYTES_1, 0.97);

        assertTrue("真文档的斜率比先验缓，同一枪要缩得更狠：" + byMeasurement + " vs " + byPrior,
                byMeasurement < byPrior * 0.72);
    }

    /** 目标卡在两档之间时给的是连续边长，不是四档之一——查表做不到这件事。 */
    @Test
    public void theShrinkIsContinuousRatherThanTierStepped() {
        int edge = PdfSizeFit.nextEdge(1800, 1_258_291L, A4_AT_1800, PdfSizeFit.SIZE_EXPONENT);

        assertTrue("落在 DRAFT 与 STANDARD 之间才对：" + edge,
                edge > ImageBudget.DRAFT.pdfDecodeEdge && edge < ImageBudget.STANDARD.pdfDecodeEdge);
    }

    /**
     * 余量得真的管用：按最悲观的那段局部指数（1200→1800 实测只有 2.064）回推第一枪的字节，
     * 仍然要落在上限之内。少了这个余量，第一枪贴着线、下一枪又贴着线，四轮都收敛不完。
     */
    @Test
    public void theFirstGuessKeepsHeadroomEvenOnThePessimisticLocalExponent() {
        long maxBytes = 1_600_000L;
        int edge = PdfSizeFit.nextEdge(1800, maxBytes, A4_AT_1800, PdfSizeFit.SIZE_EXPONENT);
        double localExponent = Math.log((double) A4_AT_1800 / A4_AT_1200)
                / Math.log(1800.0 / ImageBudget.DRAFT.pdfDecodeEdge);

        double predicted = A4_AT_1800 * Math.pow((double) edge / 1800.0, localExponent);

        assertTrue("局部指数 " + localExponent + " 下第一枪算出 " + (long) predicted
                + " B，该留出余量而不只是贴线", predicted <= maxBytes * 0.97);
    }

    /** 贴着线的目标也整个余量缩，不多写那几轮磨回来的字。 */
    @Test
    public void aHairOverTheTargetTakesTheWholeMargin() {
        int next = PdfSizeFit.nextEdge(1800, 1_799_999L, 1_800_000L, PdfSizeFit.SIZE_EXPONENT);

        assertTrue("该按余量缩约 5% 边长，而不是只降一格：" + next, next < 1790 && next > 1700);
    }

    /** 只会往下：任何超标的组合都不该给出更长或同长的边，也不该给出 0（除以下限）。 */
    @Test
    public void theEdgeOnlyEverComesDown() {
        long[][] overTarget = {
                {1_799_999L, 1_800_000L},
                {10_000L, A4_AT_1800},
                {1L, A4_AT_1800},
                {1L, Long.MAX_VALUE},
        };

        for (long[] pair : overTarget) {
            int next = PdfSizeFit.nextEdge(ImageBudget.FINE.pdfDecodeEdge, pair[0], pair[1],
                    PdfSizeFit.SIZE_EXPONENT);

            assertTrue("目标 " + pair[0] + " / 实测 " + pair[1] + " 给了走不通的边 " + next,
                    next < ImageBudget.FINE.pdfDecodeEdge && next >= PdfSizeFit.MIN_DECODE_EDGE);
        }
        assertEquals("到下限就停", 0,
                PdfSizeFit.nextEdge(PdfSizeFit.MIN_DECODE_EDGE, 1L, A4_AT_1800,
                        PdfSizeFit.SIZE_EXPONENT));
    }

    @Test
    public void theFloorIsTheLastResort() {
        assertEquals(PdfSizeFit.MIN_DECODE_EDGE,
                PdfSizeFit.nextEdge(1800, 1_000L, 4_000_000L, PdfSizeFit.SIZE_EXPONENT));
    }

    /** 到了下限就停：一像素一像素地磨四轮，只是把同一份糊图再写三遍。 */
    @Test
    public void theFloorStopsTheDescent() {
        assertEquals(0, PdfSizeFit.nextEdge(PdfSizeFit.MIN_DECODE_EDGE, 1_000L, 200_000L,
                PdfSizeFit.SIZE_EXPONENT));
    }

    /** 脏字节数（0 或负）不该把算术炸成除零，也不该编出一个更长的边。 */
    @Test
    public void aDirtyMeasurementGuessesNothing() {
        assertEquals(0, PdfSizeFit.nextEdge(1800, 1_000L, 0L, PdfSizeFit.SIZE_EXPONENT));
        assertEquals(0, PdfSizeFit.nextEdge(1800, 1_000L, -1L, PdfSizeFit.SIZE_EXPONENT));
    }

    /** 从最高档一路缩到停，最多也就这几轮——循环的上限不是随手写的数。 */
    @Test
    public void theDescentTerminatesWithinTheAttemptCap() {
        int edge = ImageBudget.FINE.pdfDecodeEdge;
        int rounds = 0;

        while (edge > 0 && rounds < 100) {
            long pretendBytes = (long) (Math.pow(edge, PdfSizeFit.SIZE_EXPONENT) / 400.0);
            edge = PdfSizeFit.nextEdge(edge, 1L, pretendBytes, PdfSizeFit.SIZE_EXPONENT);
            rounds++;
        }

        assertEquals("缩不到下限说明有边涨回来", 0, edge);
        assertTrue("轮数该远低于上限：" + rounds, rounds <= PdfSizeFit.MAX_ATTEMPTS);
    }

    /**
     * 下限与轮数是被定下来的两个数，不是实现顺手起的名字。
     *
     * <p>上面那批用例断言的都是「到下限就停」「不超过上限」这类<b>行为</b>，判据里写的就是常量本身
     * ——把 {@code MIN_DECODE_EDGE} 从 600 改成 300，它们照旧全绿（F6 实测过这条等价变种并在文档里认下来了），
     * 可 A4 短边 300px 已经不是能看的扫描件；把 {@code MAX_ATTEMPTS} 改成 5，红字也不会出现，
     * 而那是「用户最多为此多等一轮」的承诺被改口。
     *
     * <p>所以这里把数钉成字面量：想动它，就得连这一条一起改，改动于是变成一次明示而不是一次漂移。
     */
    @Test
    public void theFloorAndTheCapAreTheShippedNumbers() {
        assertEquals(600, PdfSizeFit.MIN_DECODE_EDGE);
        assertEquals(4, PdfSizeFit.MAX_ATTEMPTS);
    }

    // ---- 斜率标定 ----------------------------------------------------------

    /** 第一枪没有可比的对，只能用先验——先验偏大是有意的，边只能降不能升。 */
    @Test
    public void theFirstShotHasNothingToCalibrateAgainst() {
        assertEquals(PdfSizeFit.SIZE_EXPONENT, PdfSizeFit.exponent(1800, WALK_BYTES_1, 0, 0), 0.0);
        assertEquals("上一枪的边不比这一枪长就是喂错了序", PdfSizeFit.SIZE_EXPONENT,
                PdfSizeFit.exponent(1800, WALK_BYTES_1, 1311, WALK_BYTES_2), 0.0);
        assertEquals(PdfSizeFit.SIZE_EXPONENT, PdfSizeFit.exponent(1311, WALK_BYTES_2, 1800, 0), 0.0);
        assertEquals(PdfSizeFit.SIZE_EXPONENT, PdfSizeFit.exponent(1311, 0, 1800, WALK_BYTES_1), 0.0);
    }

    /** 走查那次的真实两点量出 0.97，不是先验的 2.15——这一条就是这次改动存在的理由。 */
    @Test
    public void theSlopeComesFromTwoRealShots() {
        double p = PdfSizeFit.exponent(WALK_EDGE_2, WALK_BYTES_2, WALK_EDGE_1, WALK_BYTES_1);

        assertTrue("1800→1311 这段该量到约 0.97：" + p, p > 0.95 && p < 0.99);
        assertTrue("比先验缓得多，照先验走就是四轮打完还在目标外", p < PdfSizeFit.SIZE_EXPONENT / 2);
    }

    /** 字节没跟着边一起降（重采样共振会这样），钳到最缓那一档继续往下，而不是一脚踩穿下限。 */
    @Test
    public void aFlatOrRisingMeasurementIsClampedToTheShallowestSlope() {
        assertEquals(PdfSizeFit.MIN_EXPONENT,
                PdfSizeFit.exponent(900, 200_000L, 1200, 200_000L), 0.0);
        assertEquals("缩了边反而更大份", PdfSizeFit.MIN_EXPONENT,
                PdfSizeFit.exponent(900, 260_000L, 1200, 200_000L), 0.0);
    }

    /** 一次离谱的陡（两枪之间字节掉了四个数量级）不该让下一枪几乎不动。 */
    @Test
    public void anImplausiblySteepSlopeIsClampedAtTheTop() {
        assertEquals(PdfSizeFit.MAX_EXPONENT,
                PdfSizeFit.exponent(1000, 1_000L, 2400, 4_000_000L), 0.0);
    }

    /** 噪声图样那两条真实段落落在先验附近，标定不会把它们越改越差。 */
    @Test
    public void thePriorStillDescribesTheNoisyFixture() {
        double p = PdfSizeFit.exponent(ImageBudget.DRAFT.pdfDecodeEdge, A4_AT_1200,
                ImageBudget.STANDARD.pdfDecodeEdge, A4_AT_1800);

        assertTrue("1200→1800 段实测 2.06：" + p, p > 2.0 && p < 2.1);
        assertTrue("先验和它差不到 6%，所以对噪声图样的既有断言不受标定影响",
                Math.abs(p - PdfSizeFit.SIZE_EXPONENT) / PdfSizeFit.SIZE_EXPONENT < 0.06);
    }

    // ---- 逐轮状态 ----------------------------------------------------------

    /** 没设目标、或者已经装得进去，都不该再要下一枪。 */
    @Test
    public void theSearchStopsTheMomentThePdfFits() {
        PdfSizeFit.Search search = new PdfSizeFit.Search();

        assertEquals(0, search.nextEdge(WALK_EDGE_1, WALK_BYTES_1, 0L));
        assertEquals("等号也算装得进去", 0, search.nextEdge(WALK_EDGE_1, WALK_BYTES_1, WALK_BYTES_1));
        assertEquals(0, search.nextEdge(WALK_EDGE_2, WALK_BYTES_2, WALK_BYTES_2 + 1));
    }

    /**
     * 走查那条曲线上的两次决策：第一枪按先验落在 1311，第二枪按实测斜率落在 894。
     *
     * <p>1102 是按先验继续磨会得到的边——正是那次四轮打完还差 9,689 B 的走法。
     */
    @Test
    public void theFirstShotFollowsThePriorAndTheSecondRecalibrates() {
        PdfSizeFit.Search search = new PdfSizeFit.Search();

        int first = search.nextEdge(WALK_EDGE_1, WALK_BYTES_1, WALK_TARGET);
        assertEquals("没有可比的对，只能按先验", 1311, first);

        int second = search.nextEdge(WALK_EDGE_2, WALK_BYTES_2, WALK_TARGET);

        assertTrue("标定后该一步到 894 上下，而不是按先验磨到 1102：" + second,
                second >= 880 && second <= 905);
    }

    /** 斜率要用最近的两枪：记住了第一枪不放，第三枪的斜率就是整段的，比局部的缓。 */
    @Test
    public void theSearchCarriesForwardTheMostRecentPoint() {
        PdfSizeFit.Search search = new PdfSizeFit.Search();
        search.nextEdge(WALK_EDGE_1, WALK_BYTES_1, WALK_TARGET);
        search.nextEdge(WALK_EDGE_2, WALK_BYTES_2, WALK_TARGET);

        int given = search.nextEdge(WALK_EDGE_3, 550_000L, WALK_TARGET);

        int fromLastPair = PdfSizeFit.nextEdge(WALK_EDGE_3, WALK_TARGET, 550_000L,
                PdfSizeFit.exponent(WALK_EDGE_3, 550_000L, WALK_EDGE_2, WALK_BYTES_2));
        int fromFirstPair = PdfSizeFit.nextEdge(WALK_EDGE_3, WALK_TARGET, 550_000L,
                PdfSizeFit.exponent(WALK_EDGE_3, 550_000L, WALK_EDGE_1, WALK_BYTES_1));
        assertEquals(fromLastPair, given);
        assertTrue("两对算出来必须不一样，这条断言才有意义", fromFirstPair != fromLastPair);
    }

    /** 到了下限就没什么可缩的了，哪怕状态里还留着上一枪。 */
    @Test
    public void theSearchGivesUpAtTheFloor() {
        PdfSizeFit.Search search = new PdfSizeFit.Search();
        search.nextEdge(WALK_EDGE_1, WALK_BYTES_1, WALK_TARGET);

        assertEquals(0, search.nextEdge(PdfSizeFit.MIN_DECODE_EDGE, 900_000L, WALK_TARGET));
    }

    /**
     * 复现那次走查：真会话两页，目标 512,000 B，实测曲线就是上面四个点。
     *
     * <p>照先验走是 1800→1311→1102→993 四轮、交 521,829 B（超标近 10 KB）；改成相邻两枪标定斜率
     * 之后第三枪就落在 906，稳稳进目标。
     */
    @Test
    public void theWalkthroughCurveMeetsItsTargetInThreeShots() {
        long[] outcome = fitFromStandard(WALK_TARGET);

        long writes = outcome[0];
        int edge = (int) outcome[1];
        long bytes = outcome[2];

        assertTrue("目标 512,000 B 在这条曲线上是可达的（下限 600 都有 "
                + walkthroughBytes(600) + " B），却交了 " + bytes + " B 于长边 " + edge,
                bytes <= WALK_TARGET);
        assertTrue("每多一轮就是用户多等一秒，该三轮收敛：" + writes, writes <= 3);
        assertTrue("不该踩到下限：" + edge, edge > PdfSizeFit.MIN_DECODE_EDGE);
    }

    /** 同一曲线的第二个目标：先验走法在这里同样是四轮打完还差一点（422,815 > 400,000）。 */
    @Test
    public void theWalkthroughCurveMeetsASecondTargetToo() {
        long target = 400_000L;
        long[] outcome = fitFromStandard(target);

        assertTrue("下限 600 都有 " + walkthroughBytes(600) + " B，400,000 是可达的，却交了 "
                + outcome[2] + " B 于长边 " + outcome[1], outcome[2] <= target);
        assertTrue("该三轮收敛：" + outcome[0], outcome[0] <= 3);
    }

    /**
     * 从出厂档（长边 1800）起，照着走查那条曲线跑一遍 {@link PdfSizeFit.Search}。
     *
     * <p>驱动循环和 {@code PdfExporter.exportFitting} 一致（写→量→问下一枪），被验的是 Search
     * 的决策序列，不是这个循环本身。返回 {@code {轮数, 最终边, 最终字节}}。
     */
    private static long[] fitFromStandard(long maxBytes) {
        PdfSizeFit.Search search = new PdfSizeFit.Search();
        int edge = ImageBudget.STANDARD.pdfDecodeEdge;
        long writes = 0;
        long bytes = 0;

        for (writes = 1; writes <= PdfSizeFit.MAX_ATTEMPTS; writes++) {
            bytes = walkthroughBytes(edge);
            if (PdfSizeFit.fits(bytes, maxBytes)) break;
            int next = search.nextEdge(edge, bytes, maxBytes);
            if (next == 0 || writes == PdfSizeFit.MAX_ATTEMPTS) break;
            edge = next;
        }
        return new long[]{writes, edge, bytes};
    }

    /**
     * 走查那四个点连成的曲线：分段按 log-log 插值，993 以下沿最底段的实测斜率外推。
     *
     * <p>这是对设备实测的复现，不是模型——段与段之间（0.85~0.97）本来就是抖的，
     * 拟合的正是「局部斜率」这件事。
     */
    private static long walkthroughBytes(int edge) {
        int[] edges = {WALK_EDGE_1, WALK_EDGE_2, WALK_EDGE_3, WALK_EDGE_4, 600};
        long[] values = {WALK_BYTES_1, WALK_BYTES_2, WALK_BYTES_3, WALK_BYTES_4, 0};
        values[4] = (long) (WALK_BYTES_4
                * Math.pow(600.0 / WALK_EDGE_4, bottomSlope()));
        for (int i = 0; i < edges.length - 1; i++) {
            if (edge >= edges[i + 1]) {
                double p = Math.log((double) values[i] / values[i + 1])
                        / Math.log((double) edges[i] / edges[i + 1]);
                return (long) (values[i + 1] * Math.pow((double) edge / edges[i + 1], p));
            }
        }
        return values[4];
    }

    private static double bottomSlope() {
        return Math.log((double) WALK_BYTES_3 / WALK_BYTES_4)
                / Math.log((double) WALK_EDGE_3 / WALK_EDGE_4);
    }
}
