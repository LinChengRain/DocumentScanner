package com.documentscanner.scanner.cv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.opencv.core.Point;

/**
 * 选区几何的纯逻辑测试。重点验证裁剪页依赖的不变量：
 * 「归一化坐标 + (x,y)->(1-y,x)」与「像素坐标 + 实际位图旋转」得到同一个四边形。
 */
public class QuadGeometryTest {

    private static final double PIXEL_TOLERANCE = 2.0;

    @Test
    public void sortCorners_isOrderIndependent() {
        Point[] expected = new Point[]{
                new Point(10, 20), new Point(300, 25), new Point(290, 400), new Point(15, 390)};
        Point[] shuffled = new Point[]{expected[2], expected[0], expected[3], expected[1]};

        Point[] sorted = QuadGeometry.sortCorners(shuffled);
        for (int i = 0; i < 4; i++) {
            assertEquals("slot " + i, expected[i].x, sorted[i].x, 1e-9);
            assertEquals("slot " + i, expected[i].y, sorted[i].y, 1e-9);
        }
    }

    @Test
    public void normalize_denormalize_roundTrips() {
        int width = 1200;
        int height = 1600;
        Point[] quad = new Point[]{
                new Point(12, 34), new Point(1188, 40), new Point(1150, 1560), new Point(20, 1570)};

        Point[] back = QuadGeometry.denormalize(
                QuadGeometry.normalize(quad, width, height), width, height);

        for (int i = 0; i < 4; i++) {
            assertEquals(quad[i].x, back[i].x, 0.05);
            assertEquals(quad[i].y, back[i].y, 0.05);
        }
    }

    @Test
    public void rotatingNormalizedQuad_matchesRotatingPixelQuad() {
        int width = 1200;
        int height = 1600;
        Point[] quad = new Point[]{
                new Point(12, 34), new Point(1188, 40), new Point(1150, 1560), new Point(20, 1570)};

        Point[] byPixels = QuadGeometry.rotate90Clockwise(quad, width, height);
        Point[] byNormalized = QuadGeometry.denormalize(
                QuadGeometry.rotateNormalized90Clockwise(QuadGeometry.normalize(quad, width, height)),
                height, width);

        for (int i = 0; i < 4; i++) {
            assertEquals(byPixels[i].x, byNormalized[i].x, PIXEL_TOLERANCE);
            assertEquals(byPixels[i].y, byNormalized[i].y, PIXEL_TOLERANCE);
        }
    }

    @Test
    public void insetFrame_isAUsableQuad() {
        Point[] frame = QuadGeometry.insetFrame(1000, 800, 0.02);
        assertTrue(QuadGeometry.isConvex(frame));
        assertTrue(QuadGeometry.area(frame) > 0);
        assertTrue(QuadGeometry.minInteriorAngleDeg(frame) > 80);
    }

    @Test
    public void isValidNormalized_rejectsOutOfFrameSelection() {
        assertTrue(QuadGeometry.isValidNormalized(new float[]{
                0.02f, 0.02f, 0.98f, 0.02f, 0.98f, 0.98f, 0.02f, 0.98f}));
        assertFalse(QuadGeometry.isValidNormalized(new float[]{
                1.5f, 0f, 1f, 0f, 1f, 1f, 0f, 1f}));
        assertFalse(QuadGeometry.isValidNormalized(new float[]{
                -0.5f, 0f, 1f, 0f, 1f, 1f, 0f, 1f}));
        assertFalse(QuadGeometry.isValidNormalized(null));
    }

    @Test
    public void isUsableQuad_acceptsNormalSelection() {
        assertTrue(QuadGeometry.isUsableQuad(new Point[]{
                new Point(12, 34), new Point(1188, 40), new Point(1150, 1560), new Point(20, 1570)},
                1200, 1600));
    }

    @Test
    public void isUsableQuad_rejectsCrossedSelection() {
        // 槽位仍是 TL/TR/BR/BL，但把右上拖到了右下、右下拖到了右上 —— 连线自交
        assertFalse(QuadGeometry.isUsableQuad(new Point[]{
                new Point(10, 10), new Point(90, 90), new Point(10, 90), new Point(90, 10)},
                100, 100));
    }

    @Test
    public void isUsableQuad_rejectsTinySelection() {
        assertFalse(QuadGeometry.isUsableQuad(new Point[]{
                new Point(10, 10), new Point(22, 10), new Point(22, 22), new Point(10, 22)},
                1000, 1000));
    }

    /**
     * 面积门限钉在画面 1%。上面那条用的是 12×12，占画面 0.0144%，任何高于它的门限都会让它通过——
     * 它钉的是「极小要拒」，钉不住门限本身：实测把 MIN_USABLE_AREA_RATIO 从 0.01 降到 0.001，
     * 整轮用例一条不红。这里成对给 1.21% 与 0.9025%，两边其余条件（凸、内角 90°）都一样，
     * 所以动门限必定当场红。
     */
    @Test
    public void isUsableQuad_gatesTheAreaAtOnePercentOfTheFrame() {
        assertTrue("画面 1.21% 的选区该放行",
                QuadGeometry.isUsableQuad(square(110), 1000, 1000));
        assertFalse("画面 0.9025% 的选区该拒",
                QuadGeometry.isUsableQuad(square(95), 1000, 1000));
    }

    /**
     * 内角门限钉在 8°，同样成对。两个四边形都凸、面积都占画面 6.8% 以上（面积那条不构成干扰），
     * 差别只在最尖那个角：9.48° 与 6.50°。
     */
    @Test
    public void isUsableQuad_gatesTheSharpestCornerAtEightDegrees() {
        Point[] eightPointNine = new Point[]{
                new Point(0, 0), new Point(1000, 0), new Point(1200, 70), new Point(1000, 167)};
        Point[] sixPointFive = new Point[]{
                new Point(0, 0), new Point(1000, 0), new Point(1200, 70), new Point(1000, 114)};

        assertTrue("最小内角 9.48° 该放行", QuadGeometry.isUsableQuad(eightPointNine, 1000, 1000));
        assertFalse("最小内角 6.50° 该拒", QuadGeometry.isUsableQuad(sixPointFive, 1000, 1000));
    }

    private static Point[] square(int side) {
        return new Point[]{
                new Point(0, 0), new Point(side, 0),
                new Point(side, side), new Point(0, side)};
    }

    @Test
    public void isUsableQuad_rejectsCollapsedCorner() {
        assertFalse(QuadGeometry.isUsableQuad(new Point[]{
                new Point(0, 0), new Point(0, 0), new Point(500, 500), new Point(0, 500)},
                1000, 1000));
    }

    @Test
    public void sanitize_ordersAndKeepsSelectionInsideFrame() {
        Point[] dragged = new Point[]{
                new Point(1200, -30), new Point(-40, 1020), new Point(30, 40), new Point(980, 960)};

        Point[] sane = QuadGeometry.sanitize(dragged, 1000, 1000);

        assertEquals(30, sane[QuadGeometry.TOP_LEFT].x, 1e-9);
        assertEquals(40, sane[QuadGeometry.TOP_LEFT].y, 1e-9);
        assertEquals(998, sane[QuadGeometry.TOP_RIGHT].x, 1e-9);
        assertEquals(1, sane[QuadGeometry.TOP_RIGHT].y, 1e-9);
        assertEquals(980, sane[QuadGeometry.BOTTOM_RIGHT].x, 1e-9);
        assertEquals(960, sane[QuadGeometry.BOTTOM_RIGHT].y, 1e-9);
        assertEquals(1, sane[QuadGeometry.BOTTOM_LEFT].x, 1e-9);
        assertEquals(998, sane[QuadGeometry.BOTTOM_LEFT].y, 1e-9);
    }

    @Test
    public void sanitize_doesNotMutateInput() {
        Point[] input = new Point[]{
                new Point(1200, -30), new Point(-40, 1020), new Point(30, 40), new Point(980, 960)};
        QuadGeometry.sanitize(input, 1000, 1000);

        assertEquals(1200, input[0].x, 1e-9);
        assertEquals(-30, input[0].y, 1e-9);
    }
}
