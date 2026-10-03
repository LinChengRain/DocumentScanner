package com.documentscanner.scanner.cv;

import static org.junit.Assert.assertEquals;

import com.documentscanner.scanner.model.ScanPage;

import org.junit.Test;

/**
 * 「画面效果默认是哪一档」这条契约。
 *
 * <p>默认值散在四处（新页面的字段初值、选择条的 null 兜底、会话恢复的缺键兜底、序号越界兜底），
 * 全都指向 {@link FilterType#DEFAULT}，所以这一档被改回去时四处会一起变。这里钉的是两件事：
 * 默认那一档确实是「原图」（唯一不改像素的一档），以及新建的页面真的带着它上场。
 */
public class FilterTypeTest {

    @Test
    public void theDefaultEffectLeavesThePictureAlone() {
        assertEquals(FilterType.ORIGINAL, FilterType.DEFAULT);
    }

    @Test
    public void aFreshPageStartsOnTheDefaultEffect() {
        ScanPage page = new ScanPage("p1", null, null, null, null);
        assertEquals(FilterType.DEFAULT, page.filter);
    }

    @Test
    public void anUnreadableOrdinalFallsBackToTheDefault() {
        assertEquals(FilterType.ORIGINAL, FilterType.byOrdinal(-1));
        assertEquals(FilterType.ORIGINAL, FilterType.byOrdinal(FilterType.values().length));
        // 兜底只兜越界的，认得出的序号不能被它动到
        assertEquals(FilterType.ENHANCED, FilterType.byOrdinal(FilterType.ENHANCED.ordinal()));
    }
}
