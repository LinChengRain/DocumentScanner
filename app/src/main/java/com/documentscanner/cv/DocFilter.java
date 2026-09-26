package com.documentscanner.cv;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.CLAHE;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

/** 文档增强滤镜组，输入输出均为 CV_8UC3（BGR）。 */
public final class DocFilter {

    private DocFilter() {
    }

    /** 返回新 Mat，调用方负责 release；失败或库未就绪时返回源图克隆。 */
    public static Mat apply(Mat bgr, FilterType type) {
        if (bgr == null || bgr.empty()) return new Mat();
        if (!Cv.ensure() || type == null || type == FilterType.ORIGINAL) return bgr.clone();
        switch (type) {
            case ENHANCED:
                return enhanced(bgr);
            case GRAYSCALE:
                return grayscale(bgr);
            case BINARY:
                return binary(bgr);
            case COLOR_MAGIC:
                return colorMagic(bgr);
            default:
                return bgr.clone();
        }
    }

    /**
     * 自动增强：用大核模糊估计光照背景，再做除法归一化，
     * 一步消除阴影和桌面底色，效果比简单直方图均衡自然得多。
     */
    private static Mat enhanced(Mat bgr) {
        Mat background = estimateBackground(bgr);
        Mat sourceF = new Mat();
        Mat backgroundF = new Mat();
        Mat normalizedF = new Mat();
        Mat normalized = new Mat();
        try {
            bgr.convertTo(sourceF, CvType.CV_32FC3);
            background.convertTo(backgroundF, CvType.CV_32FC3);
            Core.divide(sourceF, backgroundF, normalizedF, 246.0);
            normalizedF.convertTo(normalized, CvType.CV_8UC3);
            return unsharp(normalized, 0.45);
        } finally {
            background.release();
            sourceF.release();
            backgroundF.release();
            normalizedF.release();
            normalized.release();
        }
    }

    private static Mat grayscale(Mat bgr) {
        Mat gray = new Mat();
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY);
        Mat equalized = clahe(gray, 2.4);
        gray.release();
        Mat bgrOut = new Mat();
        Imgproc.cvtColor(equalized, bgrOut, Imgproc.COLOR_GRAY2BGR);
        equalized.release();
        return unsharp(bgrOut, 0.3);
    }

    /** 黑白：局部自适应阈值，抗光照不均能力远强于全局阈值。 */
    private static Mat binary(Mat bgr) {
        Mat gray = new Mat();
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY);
        Mat blurred = new Mat();
        Imgproc.GaussianBlur(gray, blurred, new Size(3, 3), 0);

        int blockSize = odd(Math.max(15, (int) (Math.min(blurred.cols(), blurred.rows()) * 0.11)));
        Mat thresholded = new Mat();
        Imgproc.adaptiveThreshold(blurred, thresholded, 255,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, blockSize, 12);

        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
        Mat cleaned = new Mat();
        Imgproc.morphologyEx(thresholded, cleaned, Imgproc.MORPH_OPEN, kernel,
                new org.opencv.core.Point(-1, -1), 1);

        Mat out = new Mat();
        Imgproc.cvtColor(cleaned, out, Imgproc.COLOR_GRAY2BGR);

        gray.release();
        blurred.release();
        thresholded.release();
        cleaned.release();
        kernel.release();
        return out;
    }

    /** 魔法色彩：只在 L 通道做对比度受限均衡，再单独抬饱和度，避免色彩失真。 */
    private static Mat colorMagic(Mat bgr) {
        Mat lab = new Mat();
        Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab);
        List<Mat> channels = new ArrayList<>(3);
        Core.split(lab, channels);
        Mat luminance = channels.get(0);
        Mat equalizedLuminance = clahe(luminance, 3.0);
        luminance.release();
        channels.set(0, equalizedLuminance);
        Mat equalizedLab = new Mat();
        Core.merge(channels, equalizedLab);
        for (Mat channel : channels) channel.release();
        lab.release();

        Mat boosted = new Mat();
        Imgproc.cvtColor(equalizedLab, boosted, Imgproc.COLOR_Lab2BGR);
        equalizedLab.release();

        Mat hsv = new Mat();
        Imgproc.cvtColor(boosted, hsv, Imgproc.COLOR_BGR2HSV);
        List<Mat> hsvChannels = new ArrayList<>(3);
        Core.split(hsv, hsvChannels);
        Core.multiply(hsvChannels.get(1), new org.opencv.core.Scalar(1, 1, 1), hsvChannels.get(1), 1.22);
        Mat mergedHsv = new Mat();
        Core.merge(hsvChannels, mergedHsv);
        for (Mat channel : hsvChannels) channel.release();
        hsv.release();

        Mat out = new Mat();
        Imgproc.cvtColor(mergedHsv, out, Imgproc.COLOR_HSV2BGR);
        mergedHsv.release();
        boosted.release();
        return out;
    }

    private static Mat clahe(Mat gray, double clipLimit) {
        // CLAHE 的 Java 包装没有 release()，交给 finalize 释放 native 对象
        CLAHE clahe = Imgproc.createCLAHE(clipLimit, new Size(8, 8));
        Mat dst = new Mat();
        clahe.apply(gray, dst);
        return dst;
    }

    /** 非锐化掩模，让文字边缘更利落。 */
    private static Mat unsharp(Mat src, double amount) {
        Mat blurred = new Mat();
        Imgproc.GaussianBlur(src, blurred, new Size(0, 0), 2.2);
        Mat dst = new Mat();
        Core.addWeighted(src, 1 + amount, blurred, -amount, 0, dst);
        blurred.release();
        return dst;
    }

    /** 降采样后做大核模糊再放回原尺寸，成本恒定。 */
    private static Mat estimateBackground(Mat src) {
        Mat small = new Mat();
        Imgproc.resize(src, small, new Size(Math.max(1, src.cols() / 6), Math.max(1, src.rows() / 6)),
                0, 0, Imgproc.INTER_AREA);
        int kernel = odd(Math.max(9, (int) (Math.min(small.cols(), small.rows()) * 0.3)));
        Mat blurredSmall = new Mat();
        Imgproc.GaussianBlur(small, blurredSmall, new Size(kernel, kernel), 0);
        Mat background = new Mat();
        Imgproc.resize(blurredSmall, background, new Size(src.cols(), src.rows()), 0, 0, Imgproc.INTER_LINEAR);
        small.release();
        blurredSmall.release();
        return background;
    }

    private static int odd(int value) {
        return value < 3 ? 3 : (value % 2 == 0 ? value + 1 : value);
    }
}
