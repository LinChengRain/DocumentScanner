# 宿主 :app 统一 minify 时随模块带出的 keep 规则。
# 注意：涉及模块自己类的规则，目标包名必须写 com.documentscanner.scanner.*，
# 照抄 app 的老包名不会报错，只会静默失效。
# 「静默」到什么程度要实测才知道：B4 把下面 ui.widget 的包名写回老包名，
# release 包进取景页依旧不崩——因为 aapt2 会扫合并后的 layout，自己为 XML 里的
# 自定义 View 补一条 `-keep class X { <init>(Context, AttributeSet); }`
# （证据：app/build/intermediates/aapt_proguard_file/release/aapt_rules.txt）。
# 所以「装上去没崩」证明不了 keep 生效，只能去查 aapt_rules 和 mapping.txt。

# OpenCV：org.opencv.* 里有 JNI 回调 Java 侧构造器/方法的入口，
# 类名与方法名都不能被改，否则 System.loadLibrary 成功后仍是 NoSuchMethodError。
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# 自定义 View：XML 里写的是全限定类名，LayoutInflater 运行期按字符串反射查类。
# 名字本身由上面说的 aapt 规则兜底，这条多出来的价值是 `{ *; }` 连构造器重载和
# 公开方法一起保住——只被反射/动态构造的 View 不在 aapt 的扫描范围里，
# 模块发布出去之后宿主怎么用不受我们控制。
-keep class com.documentscanner.scanner.ui.widget.** { *; }

# 会话模型：B3 之前这条写在宿主里（com.documentscanner.model.**），搬进来只改了包名，
# 语义保持原样——宿主 minify 时整份模型不裁不混。
-keep class com.documentscanner.scanner.model.** { *; }
