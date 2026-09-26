package com.documentscanner.cv;

/** 文档增强滤镜，顺序与 res/values/arrays.xml 的 filter_names 一一对应。 */
public enum FilterType {
    /** 不做处理 */
    ORIGINAL,
    /** 自动增强：背景归一化去阴影 + 锐化，最接近 iOS「文档」模式 */
    ENHANCED,
    /** 灰度：保留层次，去色 */
    GRAYSCALE,
    /** 黑白：局部自适应阈值二值化，适合纯文本 */
    BINARY,
    /** 魔法色彩：亮度均衡 + 饱和度提升，适合彩色图表 */
    COLOR_MAGIC;

    public static FilterType byOrdinal(int ordinal) {
        FilterType[] all = values();
        if (ordinal < 0 || ordinal >= all.length) return ENHANCED;
        return all[ordinal];
    }
}
