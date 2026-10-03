package com.documentscanner.scanner.api;

import com.documentscanner.scanner.export.PaperSize;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次 PDF 预览所需的全部输入，替代原先摊成四个 Intent extra 的写法
 * （{@code pdf_path} / {@code pdf_title} / {@code page_ids} / {@code paper_size}）。
 *
 * <p>四个字符串 key 一旦跨屏就只能靠约定对齐：改一个名字、少传一个 key，编译期都不报错，
 * 预览页会静默按「跟随图片比例」渲染。对象化之后 key 的读写收进
 * {@link com.documentscanner.scanner.ui.PdfPreviewActivity#intentFor}，外面只见类型。
 */
public final class PdfPreviewRequest {

    private final File pdf;
    private final String title;
    private final List<String> pageIds;
    private final PaperSize paper;

    /**
     * @param pdf     已导出的 PDF 文件，必须存在且可读
     * @param title   会话标题，可为 null（预览页会退回默认标题）
     * @param pageIds 参与这份 PDF 的页面 id，顺序与 PDF 页序一致；可为 null
     * @param paper   纸张尺寸，null 视为 {@link PaperSize#FOLLOW_IMAGE}
     */
    public PdfPreviewRequest(File pdf, String title, List<String> pageIds, PaperSize paper) {
        if (pdf == null) throw new IllegalArgumentException("pdf == null");
        this.pdf = pdf;
        this.title = title;
        this.pageIds = pageIds == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(pageIds));
        this.paper = paper == null ? PaperSize.FOLLOW_IMAGE : paper;
    }

    public File pdf() {
        return pdf;
    }

    public String title() {
        return title;
    }

    public List<String> pageIds() {
        return pageIds;
    }

    public PaperSize paper() {
        return paper;
    }
}
