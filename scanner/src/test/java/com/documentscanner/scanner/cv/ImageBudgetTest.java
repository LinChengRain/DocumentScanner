package com.documentscanner.scanner.cv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 体积档的数值契约。
 *
 * <p>这一层要钉的不是「档存在」，而是两件很容易被顺手改坏的事：默认档必须逐字等于本次改动前
 * 散在各处的常量（否则加一个设置会顺带改了每一份历史产物的字节），以及四个数之间的方向关系
 * （解码长边小于输出长边就是在放大，导出长边小于输出长边就是在白丢像素）。
 */
public class ImageBudgetTest {

    /**
     * 改动前的常量是 EDGE_OUTPUT=1800、SOURCE_EDGE=2200、JPEG_QUALITY=92、DECODE_EDGE=1600。
     * 第四个之所以记成 1800 而不是 1600：1600 那个上限从来没能生效——降采样按 2 的幂向下取整，
     * 1800 的页面配 1600 会原样通过。所以「不改行为」的正确写法是让导出长边等于页面长边，
     * 直接抄 1600 反而会把默认档真的缩小一档。
     */
    @Test
    public void theDefaultBudgetIsWhatTheAppShippedWith() {
        assertSame(ImageBudget.STANDARD, ImageBudget.DEFAULT);
        assertEquals(1800, ImageBudget.STANDARD.outputEdge);
        assertEquals(2200, ImageBudget.STANDARD.sourceDecodeEdge);
        assertEquals(92, ImageBudget.STANDARD.jpegQuality);
        assertEquals(1800, ImageBudget.STANDARD.pdfDecodeEdge);
    }

    @Test
    public void noBudgetDecodesLessThanItOutputs() {
        for (ImageBudget budget : ImageBudget.values()) {
            assertTrue(budget + " 解码的原图长边不得低于输出长边，否则矫正就是在放大",
                    budget.sourceDecodeEdge >= budget.outputEdge);
            assertTrue(budget + " 导出时不得再缩页面产物，否则白丢像素",
                    budget.pdfDecodeEdge >= budget.outputEdge);
        }
    }

    @Test
    public void theTiersOrderThemselves() {
        ImageBudget[] ladder = {ImageBudget.TINY, ImageBudget.DRAFT,
                ImageBudget.STANDARD, ImageBudget.FINE};
        for (int i = 1; i < ladder.length; i++) {
            ImageBudget lower = ladder[i - 1];
            ImageBudget higher = ladder[i];
            assertTrue(higher + " 的长边要大于 " + lower, higher.outputEdge > lower.outputEdge);
            assertTrue(higher + " 的质量要高于 " + lower, higher.jpegQuality > lower.jpegQuality);
            assertTrue(higher + " 的页面内存要更大",
                    higher.maxPageBytes() > lower.maxPageBytes());
        }
    }

    /** 内存这一量纲是给设置方的说话对象：它得和长边的平方一致，不能是拍出来的数。 */
    @Test
    public void pageMemoryIsTheSquareEdgeTimesFourBytes() {
        assertEquals(1800L * 1800 * 4, ImageBudget.STANDARD.maxPageBytes());
    }

    @Test
    public void anUnreadableNameFallsBackToTheDefault() {
        assertSame(ImageBudget.DEFAULT, ImageBudget.byName(null));
        assertSame(ImageBudget.DEFAULT, ImageBudget.byName(""));
        assertSame(ImageBudget.DEFAULT, ImageBudget.byName("PIXEL_PEA"));
        assertSame(ImageBudget.DRAFT, ImageBudget.byName("DRAFT"));
    }
}
