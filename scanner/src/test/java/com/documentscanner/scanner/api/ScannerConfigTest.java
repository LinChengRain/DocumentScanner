package com.documentscanner.scanner.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.documentscanner.scanner.cv.ImageBudget;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * 开关的默认值与互相独立性。
 *
 * <p>这一层最要紧的是「宿主一次都没调时是什么」——默认值一旦被改成 false，页面管理和
 * PDF 预览页上的保存、分享会同时消失，而设备侧用例全在显隐断言里才看得见。
 * 这里没有 Android 依赖，纯 JVM 就能把契约钉住。
 */
public class ScannerConfigTest {

    @Before
    public void setUp() {
        ScannerConfig.resetForTesting();
    }

    @After
    public void tearDown() {
        ScannerConfig.resetForTesting();
    }

    @Test
    public void bothActionsDefaultToVisible() {
        assertTrue(ScannerConfig.saveActionVisible());
        assertTrue(ScannerConfig.shareActionVisible());
    }

    @Test
    public void turningOneActionOffLeavesTheOtherAlone() {
        ScannerConfig.setSaveActionVisible(false);

        assertFalse(ScannerConfig.saveActionVisible());
        assertTrue("分享不该被保存开关带走", ScannerConfig.shareActionVisible());

        ScannerConfig.setShareActionVisible(false);

        assertFalse(ScannerConfig.shareActionVisible());
        assertFalse(ScannerConfig.saveActionVisible());
    }

    @Test
    public void turningAnActionBackOnRestoresIt() {
        ScannerConfig.setSaveActionVisible(false);
        ScannerConfig.setSaveActionVisible(true);

        assertTrue(ScannerConfig.saveActionVisible());
    }

    @Test
    public void resetPutsTheDefaultsBack() {
        ScannerConfig.setSaveActionVisible(false);
        ScannerConfig.setShareActionVisible(false);
        ScannerConfig.setImageBudget(ImageBudget.TINY);

        ScannerConfig.resetForTesting();

        assertTrue(ScannerConfig.saveActionVisible());
        assertTrue(ScannerConfig.shareActionVisible());
        assertSame("恢复默认要把体积档一起清掉，否则用例之间会串一条「谁都比谁小」的隐式约定",
                ImageBudget.DEFAULT, ScannerConfig.imageBudget());
    }

    /** 宿主一次都没调时必须是出厂档：这一条塌了，所有人的 PDF 会集体变小或变大。 */
    @Test
    public void theImageBudgetDefaultsToTheShippedTier() {
        assertSame(ImageBudget.STANDARD, ScannerConfig.imageBudget());
    }

    @Test
    public void anUnsetBudgetDoesNotLeakThePreviousOne() {
        ScannerConfig.setImageBudget(ImageBudget.FINE);
        assertSame(ImageBudget.FINE, ScannerConfig.imageBudget());

        ScannerConfig.setImageBudget(null);

        assertSame("传 null 是「不表态」，不是「沿用上一档」", ImageBudget.DEFAULT,
                ScannerConfig.imageBudget());
    }

    /** 显隐开关与体积档是两种语义，互不该干扰对方。 */
    @Test
    public void theImageBudgetDoesNotTouchTheActionSwitches() {
        ScannerConfig.setImageBudget(ImageBudget.DRAFT);

        assertTrue(ScannerConfig.saveActionVisible());
        assertTrue(ScannerConfig.shareActionVisible());
    }

    /** 宿主一次都没调时不该有任何体积约束：这一条塌了，所有人的 PDF 会集体悄悄变小。 */
    @Test
    public void thePdfSizeTargetDefaultsToNone() {
        assertEquals(0L, ScannerConfig.maxPdfBytes());
    }

    @Test
    public void thePdfSizeTargetIsInKibibytes() {
        ScannerConfig.setMaxPdfSizeKb(500);

        assertEquals("界面上写的 500 KB 与传给导出器的字节必须是同一个数",
                500L * 1024L, ScannerConfig.maxPdfBytes());
    }

    @Test
    public void aNonPositivePdfSizeTargetMeansNoTarget() {
        ScannerConfig.setMaxPdfSizeKb(800);
        ScannerConfig.setMaxPdfSizeKb(0);
        assertEquals(0L, ScannerConfig.maxPdfBytes());

        ScannerConfig.setMaxPdfSizeKb(800);
        ScannerConfig.setMaxPdfSizeKb(-42);

        assertEquals("负数是脏值，不该变成一个「谁都装不进去」的负目标", 0L,
                ScannerConfig.maxPdfBytes());
    }

    @Test
    public void thePdfSizeTargetDoesNotTouchTheOtherThreeKnobs() {
        ScannerConfig.setMaxPdfSizeKb(300);

        assertTrue(ScannerConfig.saveActionVisible());
        assertTrue(ScannerConfig.shareActionVisible());
        assertSame(ImageBudget.DEFAULT, ScannerConfig.imageBudget());
    }

    @Test
    public void resetClearsThePdfSizeTargetToo() {
        ScannerConfig.setMaxPdfSizeKb(500);

        ScannerConfig.resetForTesting();

        assertEquals("残留的上限会让后面每一条用例都在缩边重导", 0L, ScannerConfig.maxPdfBytes());
    }
}
