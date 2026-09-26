# DocumentScanner · 类 iOS「扫描文稿」的开源安卓文档扫描 App

纯 Java 实现，OpenCV 做文档边缘检测与透视矫正，CameraX 负责取景与拍照，
端到端覆盖「实时识别 → 自动/手动快门 → 四角手动调整 → 滤镜增强 → 多页管理 → PDF/相册导出」。
以 Apache License 2.0 开源，可自由用于学习与商业项目。

```
首页（历史会话）→ 扫描取景（实时绿框 · 快门攒页）→ 裁剪编辑（四角 + 滤镜）→ 页面管理（排序/删除/复制/换滤镜/重拍）→ PDF 预览 · 分享
          ↘ 相册导入                                                                                              ↘ 存到相册
```

## 1. 环境与构建

| 项 | 版本 |
| --- | --- |
| JDK | 17+（实测 Microsoft OpenJDK 17.0.19） |
| Gradle | 7.6.4（wrapper 已生成，走腾讯镜像） |
| AGP | 4.1.3 |
| compileSdk / targetSdk | 34 |
| minSdk | 24（Android 7.0） |
| OpenCV | 4.10.0（Maven Central 官方 AAR，无需手动 ndk-build；版本被钉住，原因见下） |
| CameraX | 1.3.1 |

首次使用请确认 `local.properties` 里的 `sdk.dir` 指向本机 Android SDK。命令行构建必须把
`JAVA_HOME` 指到 JDK 17（例：`JAVA_HOME=<你的 jdk-17 路径> ./gradlew :app:assembleDebug`）；
用 JDK 20 跑 wrapper 会在解析 `settings.gradle` 时就炸成 `Unsupported class file major version 64`。

> **AGP 4.1.3 为什么要配 Gradle 7.6.4**：AGP 4.1.x 官方只测到 Gradle 6.x，而 Gradle 6.x 内置的
> Groovy 读不了 JDK 17 的 class（`Unsupported class file major version 61`），本机又没有 JDK ≤ 14。
> 7.6.4 是「JDK 17 能跑 + AGP 4.1.3 能配起来」的最小跨度过渡，属于未受支持组合；
> 若日后补上 JDK 11，把 `distributionUrl` 换回 `gradle-6.5-bin.zip` 即可回到官方支持区间。

```bash
./gradlew :app:assembleDebug      # 产物 app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug       # 连接手机后直接安装
./gradlew :app:assembleRelease    # R8 压缩版，用 debug 签名，仅用于本地验证
./gradlew :app:testDebugUnitTest  # 纯逻辑测试（选区几何 + 原子落盘，无需设备）
./gradlew :app:connectedDebugAndroidTest  # 真机/模拟器测试（会话恢复 + 渲染链路）
```

测试分两层，共 85 例。`testDebugUnitTest`（24 例）覆盖不依赖 Android 运行时的几何与文件语义：
`QuadGeometryTest` 11 例、`AtomicTextFileTest` 6 例、`ExportNamesTest` 7 例（同名会话导出不互相覆盖、
标题里的路径分隔符逃不出导出目录）。`connectedDebugAndroidTest`（61 例）覆盖只有真 Context /
真实 OpenCV / 真实 Activity 才暴露得出来的行为：

- `ScanSessionRestoreTest` 10 例：进程重启后从 `session.json` 恢复、主文件半截时回退 `.bak` 并重新落盘、
  解析中途失败不留半份页面列表、缓存被清时丢页不崩、相对路径在会话目录整体挪动后仍能找到产物。
- `ScanSessionHistoryTest` 18 例：多会话（另起新会话不动上一份、历史按时间倒序、切回去页面不串台）、
  非法/未知会话 id 兜底、删当前会话与删他份会话、复制页（产物一起拷、选区是独立数组、插回原页之后）、
  重拍只作废产物、旧版单会话布局一次性迁移进历史且幂等。
- `PageRendererTest` 4 例：边缘检测能在合成文档上检出四角、三份产物落盘、换滤镜只重写 result 不动 warp、
  回调落在主线程。
- `PdfExportTest` 8 例：A4/Letter 纸张方向跟随图片、图片等比内接并居中、跟随图片比例时不留白，
  以及真用 `PdfRenderer` 打开导出的文件核对页数与每页尺寸。
- `MainActivityHistoryTest` 4 例 / `PageListActivityTest` 2 例 / `PdfPreviewActivityTest` 4 例：
  三个界面在真实 Activity 里 inflate 出来的结果——历史列表按新到旧排列并显示页数、点一行切回那份会话、
  网格页码与「未确认」角标、预览页翻页联动页码指示器、点指示器开合缩略图抽屉、
  删页后 PDF 被重写成少一页且不留临时文件。
- `CropActivityEdgeTest` 5 例：真机拉起裁剪页、等照片解码完成后按固定尺寸量布局，核对满幅选区的
  四个手柄都留在屏幕内、贴着屏幕边缘那一侧也能按住角点、拖回去时角点停在照片边界而不是被拖进黑边、
  角点始终停在指尖上、「全选整页」四个方向都往里收一点。
- `ScanActivityFlowTest` 3 例：镜头不在自动化范围内，就用「相册导入」这条与快门同源的路径喂合成文档，
  核对一页就绪后仍留在取景页（裁剪页一次都没被拉起来）、连导两页按顺序攒下且各只有一页、
  而「重拍」这一条路必须落回裁剪页并且原位替换、不多出一页。
- `CropActivityRetakeTest` 1 例：裁剪页上的「重拍」把这一页交回取景页的替换模式（Intent 带着页 id）、
  裁剪页自己让位，而且这一页连同原图与选区都原样留着——作废产物要等新页真的拍下才算。
- `ScanActivityFramingTest` 1 例：取景页的构图布局。真镜头几帧之内就会自动快门，所以进手动模式后
  自己按屏幕尺寸量一遍整棵树（息屏时真实排版不会跑），核对叠加层与预览逐像素对齐（否则边框画歪）、
  一帧 3:4 画面 fitCenter 之后整块留在顶栏与提示条之间的空档里，而且上下留白相等——也就是
  「绿框偏下」这条抱怨本身。
- `ScanActivityCameraHandoffTest` 1 例：两台取景页的相机交接。「重拍」是在用户原来那台之上再开一台，
  而 `ProcessCameraProvider` 是进程单例，所以这里两头都用真实镜头按快门：重拍那台要拍得出来（它抢得到
  相机），原来那台回到前景后也得拍得出来（它没被抢走）；中间还钉住「让位的那台不再持有绑定」。

四条构建均已在本机跑通（AGP 4.1.3 / Gradle 7.6.4 / JDK 17）：`assembleDebug`、`assembleRelease`、
`testDebugUnitTest`、`assembleAndroidTest`；61 个 instrumentation 用例已在 Honor PTP-AN00
（Android 15 / arm64-v8a）上全绿。上述断言逐条做过变异验证（把实现改坏，确认对应用例必然失败）：
去掉 `.bak` 回退、边解析边提交页面、去掉可用性校验、去掉选区合法性校验、把主线程提交改回后台直调、
复制页不插回原页之后、复制页与原页共用选区数组、迁移不搬产物目录、会话 id 不避开已存在目录、
历史列表不过滤空会话、导出不做重名编号、固定纸张永远是竖版——每一处都被预期用例抓到。
裁剪页的留白另下了八个毒：照片矩形无视 padding、算完内容区忘记偏移回 padding 起点、左右只扣一侧留白、
布局里的左右 padding 被删、触摸容差缩到比手柄半径还小、命中/拖动自己另算一份矩形、
「全选整页」的 0.4% 内收归零、角点不再夹在照片范围内——每个都由对应的用例抓到，其中
「另算一份矩形」和「不再夹在照片范围内」只有「角点停在指尖」与「拖回边缘」这两例能发现，
说明它们不是冗余用例。
「快门只攒页」这条路径又下了四个毒：每就绪一页都弹出裁剪页（留在取景页的两例各抓到一次）、
重拍也不再落回裁剪页（只有重拍那一例抓到）、重拍另起一页而不是原位替换、攒下的页丢掉自动检出的选区。
裁剪页的「重拍」又下了四个毒：改回当场删页、送回取景页但不带页 id、新页还没拍就先把这一页作废、
裁剪页赖在取景页下面不走——四个都由那一例的对应断言抓到。
相机交接下了四个毒：让位时不交还相机（被「让位的那台还占着相机」抓到）、回到前景不重绑
（被「重拍之后回到原来那台取景页的相机接上」抓到）、整体回到出 bug 的写法（只在 onCreate 绑一次 +
绑时 `unbindAll()`，被「重拍之后拍出了一页」抓到——把占用那条断言临时摘掉验证过这一点），
以及解绑改回全局。最后这一个活了下来，而且是有意留下的：既然后台那台已经不持有绑定，
`unbindAll()` 在这台机器上就摘不到别人的东西，它不再是缺陷，只是把
「一台取景页只在前台持有相机」这条不变式当成了前提。
取景页的构图又下了四个毒：预览改回铺满整屏（被「画面下沿压到了提示条」抓到）、
叠加层自己另算一份整屏边界（被「叠加层与预览不对齐」抓到）、只留单边留白
（被「画面没有落在空档正中，偏下了：上留白=12 下留白=36」抓到）、
预览底部改回整屏（同样被「画面下沿压到了提示条」抓到）——四个都被那一例的对应断言抓到。
相机实拍 → 实时边框/自动快门 → 攒页 → 主动进裁剪 → 导出/分享/存相册仍依赖真实镜头，自动化只覆盖了
与快门同源的导入收尾，外加取景页与真实镜头的两次实拍（相机交接那一例）；2026-09-24 已在同一台
PTP-AN00 上手动走完一遍：自动与手动快门都留在取景页、
相册导入同样只攒页、页面菜单与裁剪页两处「重拍」都落回裁剪页且原位替换（页数不变）、
A4 导出经系统选择器真的写进了 Download、相册里多出 DocumentScanner 那张图。
两处「重拍」回到原取景页之后又能继续按快门（页数接着往上涨，logcat 里不再有
`Not bound to a valid Camera`），修复前后各截了一张图：预览区取样亮度从 0.0（灰屏）回到 73~83 的实时画面。
构图布局改完也在同一台上实拍核对过：`uiautomator` 量到预览与叠加层都是 `[0,362][1264,2183]`（逐像素重合），
提示条从 2207 起、控件排从 2335 起，而 fitCenter 之后那帧 3:4 画面落在 430~2115，
上下各留 92px——检出的绿框再也没压到提示条上。

**为什么把 OpenCV 钉在 4.10.0**：两道坎。① 4.11 起 `classes.jar` 是 Java 17 字节码（major 61），
AGP 4.1 自带的 D8 直接报 `Unsupported class file major version 61`；② 4.12 起的 arm64 构建会按 HWCAP 把 `Mat.convertTo` 这类算子
派发到 SVE 内核（在 `libopencv_java4.so` 里能看到 `cnth x10` / `ptrue p0.s`）。Apple 芯片上的
Android 模拟器会在 HWCAP 与 `/proc/cpuinfo` 里上报 sve2/sme2，却实际执行不了，于是 OpenCV 一跑
就 SIGILL（ILL_ILLOPC）。模拟器镜像只有 arm64-v8a，`-qemu -cpu max,sve=off` 被拒
（`Property '.sve' not found`）、`-no-accel` 被忽略，这个假阳性绕不过去。真机不受影响，
要升级只需改回 `app/build.gradle` 里的版本号并在真机上验证。

**关于下载速度**：`gradle/wrapper/gradle-wrapper.properties` 默认把 `distributionUrl`
指向腾讯镜像（`services.gradle.org` 会 307 跳到 GitHub，国内直连常被限速到 20KB/s 以下）。
换回官方源只需替换那一行；依赖仓库同理，根 `build.gradle` 的 `allprojects.repositories` 里把
`mirrors.cloud.tencent.com/nexus/.../maven-public/` 排在 `google()` / `mavenCentral()` 之前
（AGP 4.1 时代仓库声明仍在根工程，没有 `dependencyResolutionManagement`）。

**APK 体积**：`ndk.abiFilters` 目前只留 `arm64-v8a`，debug 包约 28MB（R8 后的 release 约 23MB），
大头是 OpenCV 的 `libopencv_java4.so`（约 21MB）。
需要在 Intel 模拟器上跑时再加一个 ABI：

```groovy
ndk { abiFilters 'arm64-v8a', 'x86_64' }
```

## 2. 目录结构

```
com.documentscanner
├── cv/          算法层（全部阻塞调用，只在后台线程执行）
│   ├── Cv                     native 库加载（幂等）
│   ├── CvImage                Bitmap <-> Mat、YUV 亮度平面提取
│   ├── DocDetector            文档四角检测
│   ├── PerspectiveCorrector   透视变换
│   ├── DocFilter              5 种画面增强
│   ├── QuadGeometry           四边形几何 + 归一化选区
│   ├── DocPipeline            Bitmap 级入口与尺寸常量
│   └── PageRenderer           「确认」后的落盘编排与回调
├── camera/LiveDocAnalyzer     CameraX 逐帧分析：节流、稳定判定
├── model/                     ScanPage / ScanSession（多会话索引 + JSON 落盘 + 历史列表）
├── export/                    PdfExporter / PaperSize / ExportNames / GallerySaver / ShareFiles
├── ui/                        5 个 Activity + 自绘控件 + 适配器
└── util/                      ImageIO（EXIF 烘焙）/ Io（线程调度）/ Ui
```

## 3. 关键设计

**只在亮度平面上做检测。** `ImageAnalysis` 给的是 YUV_420_888，逐帧做色彩转换是帧率杀手。
`CvImage.grayFrom()` 按 `rowStride/pixelStride` 只拷出 Y 平面，再按 `rotationDegrees`
`Core.rotate` 到「显示方向」，于是检测坐标系与预览画面方向一致，后续换算只需一次等比居中映射。

**三条流统一 4:3 + `PreviewView.ScaleType.FIT_CENTER`。** Preview / ImageAnalysis / ImageCapture
共用 `AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY`，检测框从分析帧映到预览视图
才能用同一个 `ImageGeom.fitCenterRect()`，不必处理各流裁剪比例不一致带来的偏移。

**预览只占顶栏与提示条之间的空档。** `FIT_CENTER` 是「在视图里居中」，而取景页的预览以前约束在整屏四条边上，
于是画面按整屏居中——在这台 1264x2800 的窄长屏上，3:4 那一帧要铺到 625~2310，而提示条从 2207 起，
画面下沿整整压过它 100 多像素，检出的文档边框也就贴着快门那一排，看着就是「绿框偏下」。现在预览改量顶栏底到提示条顶（上下各 8dp 留白），
画面落回空档正中、上下各留 92px；叠加层不再自己抄一份约束，而是直接钉在预览的四条边上——
边框必须和预览逐像素对齐，两处各写一份约束就是下一次错位的源头。

**检测链路。** 双边滤波 → 用中位数自适应推导 Canny 阈值 → 形态学闭运算 → `findContours`
→ 多档 `approxPolyDP` 取最优四边形 → 几何合理性过滤（凸性、最小内角 ≥22°、长宽比 ≤4、
面积 ≥12% 画面）；失败时走自适应阈值备选路径。检测前用 `copyMakeBorder` 加边，
让贴边文档也能闭合成环。

**自动快门的判定时机。** 连续 6 帧四角位移小于对角线 1.2% 才算「稳定」，配合 1.8s 冷却，
避免同一份文档被连拍；手动模式随时可点快门。

**取景页只在前台持有相机。** `ProcessCameraProvider` 是进程单例，而「重拍」会在用户原来那台取景页之上
再开一台：新起的那台绑定时走的是全局 `unbindAll()`，于是把原来那台的预览流、分析流、拍照流一并摘走。
当时看不出来——重拍那台自己绑上了，工作得好好的；等这一页拍完回到原来那台，才是灰屏卡住、快门报
`Not bound to a valid Camera`，只能退回首页重开。现在一台取景页只在 `onResume` 绑、`onPause` 解，
解绑又只针对自己绑上的那一组用例（`Preview` / `ImageAnalysis` / `ImageCapture`），
所以两台各自拿一份干净的绑定，回到前景必然重新接上。绑定是异步的，`bindCamera()` 的回调会先看
`cameraWanted` 这个开关：让位之后才赶到的回调直接丢弃，等下一次 `onResume`。

**选区存归一化坐标（`float[8]`，TL/TR/BR/BL，0..1）。** 编辑时位图长边 1500，
导出时 1800/2200，归一化把两者解耦；顺带让「旋转 90°」变成一行 `(x,y) → (1-y, x)`。

**裁剪页用 padding 留出一条拿得住的边。** 照片以前铺满整个 `CropQuadView`，于是文档贴到画面边缘时
（「全选整页」或者整页文档）左右两个手柄正好压在屏幕左右沿上，一半圆被切掉，指尖也挡住边界。
现在 `imageRect()` 先把 padding 扣掉再等比居中，绘制、命中测试与拖动换算都走这一份矩形，
所以留白只是把最外侧的角点推离屏幕边缘，选区本身仍是图像像素，改 padding 不会丢编辑状态。
左右各 20dp、上下各 8dp，够放下 13dp 半径的手柄，也落在 30dp 的触摸容差内。

**每页三份产物：`original`（照片）→ `warp`（矫正后、无滤镜）→ `result`（warp + 滤镜）。**
单独留 `warp` 这一步，换滤镜时只需重跑滤镜，点一下就能看到效果，而不必再解码原始大图。

**不申请任何存储权限。** 运行时权限只有 `CAMERA`：
- 导出走 `MediaStore`（Android 10+ 用 `RELATIVE_PATH` + `IS_PENDING`；8.x 写应用外部目录并触发媒体扫描）；
- 相册导入用 `ACTION_GET_CONTENT`，不引入 photo-picker 依赖；
- 分享经 `FileProvider`（`file_paths.xml` 只暴露 `export/` 与缓存产物目录）。

**EXIF 方向在最上游烘焙一次。** `ImageIO.load()` 解码即转正，下游所有代码都不必再关心方向。

**会话可恢复，而且是多份。** `ScanSession` 给每份会话一个独立目录
`cacheDir/scan_session/<id>/session.json`，当前用哪一份记在同级 `index.json` 里；
页面产物存的是**相对该目录**的路径，所以整个目录挪动、或从旧版单会话布局迁移过来之后仍能找回文件。
「开始新扫描」是另起一份而不是清空上一份，首页的历史列表把有内容的会话按新到旧列出来，
点一行就切回去继续编辑。恢复时丢弃文件已被系统清掉的残缺页面，而不是在后续步骤里崩溃；
未确认裁剪的页面在导出前会按自动识别结果补渲染，所以中途退出不丢页。

**快门只攒页，编辑由用户主动进入。** 拍完一页（手动、自动、相册导入都算）留在取景页，把分析流重新打开，
页面按自动识别的选区先攒着；要微调就点缩略图或「查看」进页面管理再挑一页，不必每拍一张都被弹出取景页。
「重拍」是唯一的例外——它本身就是「回去改这一页」这条路径上的一步，所以拍完直接落回裁剪页。
重拍有两个入口（页面管理的菜单、裁剪页面板），走的都是同一条替换路径：带着页 id 回取景页，
位置与原页都不变，产物要等新那一页真的拍下才作废；在此之前退出取景页，原页仍然完好可用。

**PDF 纸张在导出前问一次。** `PaperSize` 三档：A4 / Letter 会把图片等比内接并居中留出 18pt 页边，
页面方向跟随图片（横图配横向纸张，不挤成竖排）；「跟随图片」则长边统一 842pt、页面即图片本身。
导出文件名来自会话标题，标题可以重复，因此 `ExportNames.unique()` 会往后找 `xxx (2).pdf`，
后一次导出不会盖掉前一次的成品。

## 4. 五种画面效果

| 名称 | 做法 | 适用 |
| --- | --- | --- |
| 原图 | 仅透视矫正 | 保留现场光影 |
| 增强 | 背景估计（缩放→大核模糊→还原）除法归一化 + 非锐化掩模 | **去阴影**、发灰的纸面 |
| 灰度 | 灰度 + CLAHE 局部对比度 | 光线不均的黑白文档 |
| 黑白 | 自适应阈值 + 形态学开运算去椒盐 | 纯文字、签字、传真 |
| 魔法色彩 | Lab 只增强 L 通道 + 提饱和 | 证件、彩色图表 |

## 5. 已知边界

- 页面锁定竖屏；相机预览/取景换算按 portrait 假设写死（`ScanActivity.currentDisplayRotation()`）。
- 单次会话最多 20 页；所有会话的中间产物都在 `cacheDir` 下，系统清理缓存或「清理存储」会把
  未导出的会话（含历史列表里的每一份）一起清掉。
- 检测针对「与背景有反差的平整四边形」调参：深色文档放在深色桌面上、或纸面严重褶皱时，
  请用四角手动调整或「全选整页」。
- 桌面版 OpenCV 全模块 AAR，体积大；若只要检测+矫正，可自行裁剪 native 库或用 OpenCV 精简模块。
- 启动图标是矢量（无位图 mipmap），`mipmap-anydpi-v26` 之外另留了一份矢量兜底给 Android 8 以下。

## 6. 许可与贡献

本项目以 **Apache License 2.0** 开源，全文见 [`LICENSE`](LICENSE)。复用时请保留版权与许可声明；
该版本同时授予专利使用权，适合商业场景。

第三方组件保持各自的许可证，不受本仓库许可影响：OpenCV（Apache 2.0）、AndroidX 与 CameraX、
Material Components（Apache 2.0）、Guava（Apache 2.0）。

欢迎提 Issue 与 Pull Request。改动算法或渲染链路时请补对应用例——本仓库的测试是「变异验证」写出来的：
每条断言都要能因对应的实现被改坏而失败，否则它不算是覆盖。纯逻辑放
`app/src/test`（无需设备），要真实 Context / OpenCV / Activity 的放 `app/src/androidTest`。
