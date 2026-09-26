# OpenCV 的 Java 绑定通过 JNI 反射访问，不能被混淆或裁剪
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# 本项目的算法回调与自定义 View 都在 XML / JNI 边界上被引用
-keep class com.documentscanner.model.** { *; }
-keep class com.documentscanner.ui.widget.** { *; }
