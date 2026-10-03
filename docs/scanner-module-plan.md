# 文档扫描功能模块化方案（B1-B5 已落地，见 §10）

分支：`spike/scanner-module`。这份稿子原先只做边界与取舍的研究，§7 的五批迁移现已全部执行完；
所有结论都带 `文件:行`，可以直接对着源码核——行号以研究稿阶段（`865b4e9`）为准，
搬迁之后请以 §10 各批条目为准。

## 0. 前提基线

AGP 4.1.3 / Gradle 7.6.4 / JDK 17（语言级别仍是 1.8）/ compileSdk·targetSdk 31 / minSdk 24 /
OpenCV 4.10.0 / CameraX 1.1.0。新模块必须整套沿用：AGP 4.1.3 没有 `namespace` DSL，
库模块只能在 `AndroidManifest.xml` 里写 `package`；`ndk.abiFilters` 只有 app 模块写才真正决定打包 ABI。

## 1. 现状耦合（决定边界的事实）

包间方向（A → B 表示 A 引用 B）：

```
camera → cv → util
cv     → model          ← 与下条成环
model  → cv (FilterType, QuadGeometry)
model  → util (AtomicTextFile)
ui     → cv / camera / model / export / util / ui.adapter / ui.widget
ui.widget → cv (QuadGeometry) 且直接吃 org.opencv.core.Point
export → util           ← 隐藏边：PdfExporter.java:107 用全限定名调 util.ImageIO，import 扫不出来
DocumentScannerApp → cv / model / util
```

三个必须显式处理的结构问题：

1. **`cv ↔ model` 是环**（`cv/PageRenderer.java:6` → `model.ScanPage`；`model/ScanSession.java:7-8` → `cv.FilterType`/`cv.QuadGeometry`）。
   同一个模块内无所谓，一旦想把「算法层」单独发出去就必须拆。
2. **`export → util` 的隐藏边**（全限定调用）。跨模块时它是编译期才炸的坑，先改成正常 import。
3. **进程级全局态**：`ScanSession.instance` 静态单例（`model/ScanSession.java:47,63-69`）、
   根目录钉在 `getCacheDir()/scan_session`（:38）与 `getFilesDir()/export`（:134）、
   全局 2 线程池 `Io`（`util/Io.java:13`）、`DocumentScannerApp.onCreate` 预热 native 与会话。
   「公共组件」带隐式全局态，第二个功能模块接进来就会互相盖会话——这是复用前必须收口的一条。

OpenCV 外泄面（`org.opencv.core.Point` 出现在公开签名里）：

| 位置 | 签名 |
| --- | --- |
| `camera/LiveDocAnalyzer.java:32` | `Callback.onDetected(Point[], int, int, boolean)` |
| `cv/DocPipeline.java:24,42` | `detectQuad(Bitmap, boolean) → Point[]`、`warp(Bitmap, Point[], int)` |
| `cv/QuadGeometry.java:21-206` | 整类以 `Point[]` 为参数（只有 :169/:209/:218 三个方法走 `float[]`） |
| `cv/PageRenderer.java:38-41` | `QuadCallback.onQuad(Point[])` |
| `ui/widget/QuadOverlayView.java:72`、`CropQuadView.java:89,95`、`ImageGeom.java:27,32` | 控件的 set/get 角点 API |

好消息：**持久化边缘已经是 `float[8]` 归一化选区**（`model/ScanPage.java:26`，`QuadGeometry.normalize`），
OpenCV 不在存储格式上，所以将来做「对外只暴露 float[]」的改造有天然落脚点。

类名字符串耦合（跨模块最容易漏的一类）：

- manifest `package="com.documentscanner"` + `.DocumentScannerApp` + 5 个 activity 相对名（`AndroidManifest.xml:3,16,27-55`）。
- layout XML 里写全限定控件名：`res/layout/activity_crop.xml:9`（`CropQuadView`）、`activity_scan.xml:25`（`QuadOverlayView`）。
- proguard keep 按包名：`app/proguard-rules.pro:2`（`org.opencv.**`）、`:6`（`model.**`）、`:7`（`ui.widget.**`）。
- FileProvider：manifest 用 `${applicationId}.fileprovider`（`AndroidManifest.xml:61`），
  运行期拼的是 `getPackageName() + ".fileprovider"`（`export/ShareFiles.java:19`）——模块里这段一旦跟着进库，
  authority 就仍指向宿主包，必须和宿主的 provider 声明成对存在。
- `res/xml/file_paths.xml:4-11` 的目录名（`scan_session/`、`export/`）与代码常量（`ScanSession.java:38,134`）重复，无编译期约束。
- 测试反射私有静态字段：`androidTest/model/SessionFixtures.java:46-49` 打 `ScanSession.instance`。

屏间跳转契约（决定门面长什么样）：全部是**单向 Intent + 单例状态**，没有任何 `setResult`：
`MainActivity→ScanActivity`（`ui/MainActivity.java:118`）、`MainActivity→PageListActivity` 靠
`ScanSession.open()` 切会话而不带 extra（:78-79）、`Scan↔Crop` 用 `"page_id"` / `"replace_page"`
（`CropActivity.java:40,58`、`ScanActivity.java:58,60`）、`PageList→PdfPreview` 带 4 个 extra
（`PdfPreviewActivity.java:56-59,84-91`）。跨模块复用要把这些字符串收成 API，否则宿主得抄字面量。

## 2. 三种切法

| | A. 单模块全收（推荐） | B. 门面 + 实现（`:scanner-api` / `:scanner`） | C. 算法层单独成库（`:scanner-cv`） |
| --- | --- | --- | --- |
| 模块内容 | cv/camera/model/export/util + Scan/Crop/PageList/PdfPreview 四屏 + widget/adapter + 资源 | A 之上再抽纯 DTO/接口层，OpenCV/CameraX 只在实现层 | 只把 cv+camera+model 发出去，UI 留宿主 |
| 对外依赖 | 宿主编译期看不到 OpenCV/CameraX/guava（全 `implementation`） | 同 B，且宿主可只依赖 api 层 | 宿主必须看见 `org.opencv.core.Point`（QuadGeometry 全类外泄） |
| 改动量 | 移文件 + 改 import + 资源归属 | A + 把 `Point[]` 签名换成 `float[]` DTO（约 5 个类 15 个方法） | 要先破 `cv ↔ model` 环 |
| 环 | 不用破（同模块内允许） | 不用破 | 必须破 |
| 适合 | 现在只有一个宿主，但想把「扫描」当组件收口 | 有 ≥2 个功能模块要嵌扫描流程 | 别的模块只想自己拼取景 UI、要逐帧检测回调 |

**推荐 A，并按 B 的包结构预留**（公开类集中在 `com.documentscanner.scanner` 顶层，其余 `internal` 分包）。
理由：A→B 只需要把门面类抽一层，不需要重新分家；反过来先做 B 会在只有一个宿主时白付 DTO 改造的钱。
C 只在「其他功能要复用逐帧检测」出现时再做，破环 + 保 `Point` 面不外泄的代价眼下不划算。

## 3. A 方案的目标形态

```
settings.gradle:  include ':app', ':scanner'

:scanner (com.android.library, package=com.documentscanner.scanner)
├── api/          Scanner（入口/Intent 工厂）、ScannerSession、ScanPage、PdfPreviewRequest、ScannerEngine
├── internal/     cv、camera、model 实现、export 实现、ui.widget、util
├── ui/           ScanActivity、CropActivity、PageListActivity、PdfPreviewActivity + adapter
└── res/          扫描相关 layout/drawable/values（见 §5）

:app
└── MainActivity + SessionAdapter + activity_main/item_session + Theme + FileProvider 声明
```

门面草案（替换掉现在的字符串 extra 与隐式单例）：

```java
Scanner.openScan(Context)                        // 进取景（追加模式）
Scanner.openScanReplacing(Context, String pageId)// 「重拍」：现 ScanActivity.replaceIntent
Scanner.openPages(Context, String sessionId)     // 现 MainActivity.java:78-79 的 open()+裸 Intent
Scanner.openPdfPreview(Context, PdfPreviewRequest)// 4 个 extra 对象化（路径/标题/pageIds/paperSize）
ScannerSession.open(Context, String id) / .list(Context) / .activeId(Context)
ScannerEngine.info()                             // 现 MainActivity.java:43 的 Cv.version()
```

两条硬约束：
- **`api/` 里的签名不允许出现 `org.opencv.*` 与 `androidx.camera.*`**（否则依赖得开成 `api`，OpenCV 外泄）。
  `MainActivity` 现在直接吃 `Cv.version()`（`ui/MainActivity.java:43`）和 `ScanSession`（:78）就是这两条要收的口子。
- **`ScanSession` 的单例语义要么改成显式句柄，要么在文档里写死「一个进程一份扫描会话」**。
  后者对当前 app 成立，对「公共模块」不成立——这是 A 方案里唯一必须动实现逻辑的点。

## 4. 构建配置清单（AGP 4.1.3 的坑位）

- `scanner/build.gradle`：`apply plugin: 'com.android.library'` + manifest 里的 `package`（AGP 4.1.3 无 `namespace`）；
  `compileSdkVersion 31`、`minSdkVersion 24`、`compileOptions` 1.8、`buildFeatures { viewBinding true }`、`lintOptions { abortOnError false }`。
- viewBinding/databinding 生成类随模块包名走：layout 搬过去的那一批，同批把
  `com.documentscanner.databinding.*` 的 import 改掉（5 个 Activity + 3 个 adapter）。
- **依赖归属**：OpenCV / CameraX 四件套 / guava → `:scanner` 的 `implementation`；
  app 不再声明这些。`app/build.gradle:65-68` 那条 guava 注释（camera-core 1.1.0 只带 `listenablefuture` 空桩）跟着搬。
  `abiFilters 'arm64-v8a'` **留在 app**（库模块写了不决定最终 APK 打包），但要挪一条注释说明「模块带 native，宿主必须筛 ABI」。
- **R8/proguard**：仍由 app 统一 minify（`minifyEnabled true` + `shrinkResources true`）。
  模块用 `consumerProguardFiles 'consumer-rules.pro'` 带出三条 keep：`org.opencv.**`（JNI）、
  `com.documentscanner.scanner.api.**`（若保留反射/JSON）、`...scanner.ui.widget.**`（layout XML 全限定名 inflate）。
  注意：consumer rules 里的包名必须是模块新包名，照抄 `app/proguard-rules.pro:6-7` 会静默失效——
  release 包要在真机跑一遍裁剪页才能确认（XML 找控件类失败是运行时 `InflateException`）。
- `checkDebugAarMetadata` 只管外部 AAR，本地 `project(':scanner')` 不受 minCompileSdk 约束；
  API 31 基线（README §1）对模块依赖版本同样成立，模块里同样不能用 CameraX 1.2+/appcompat 1.5+。
- 多模块别开 configuration cache（AGP 4.1.3 + Gradle 7.6.4 未受支持组合，见 README §1 的说明）。
- 若要发内网 Maven 而不是同仓库 `project` 依赖：`uploadArchives` 在 Gradle 7.6 已移除，只能用 `maven-publish`，
  且 AGP 4.1.3 暴露的 `components.release` 需要实测；发出去的 AAR 会把 OpenCV 4.10.0 作为传递依赖带给宿主，
  所以「OpenCV 版本被钉死」的两条理由（D8 major 61 / 模拟器 SVE SIGILL）要写进模块 README。

## 5. 资源与 Manifest 归属

- **主题**：模块自带 `Theme.Scanner.Camera`（父 `Theme.Material3.Dark.NoActionBar`，现在
  `res/values/themes.xml:3-16` 的两个 style 拆开：`.DocumentScanner` 留宿主，`.Camera` 进模块并被模块 activity 的 manifest 引用）。
- **FileProvider**：两个选择——① 模块自带 provider（authority 固定成 `com.documentscanner.scanner.fileprovider`，
  `ShareFiles.java:19` 不再拼 `getPackageName()`），宿主零配置；② provider 留宿主，则必须把  `file_paths.xml` 的 `scan_session/`、`export/` 目录名写成「接入要求」。倾向 ①，因为现在目录名已经和代码常量重复一份（:38/:134 vs file_paths），是隐性 bug 源。
  （落地时 ① 的「authority 固定」这一条被推翻，改成 `${applicationId}` 占位符，原因见 §9.1。）
- **资源命名**：`ic_back`/`bg_card`/`ic_delete`/`ic_save` 这类通用名进库后会被宿主同名覆盖（资源合并规则）。
  `android.resourcePrefix 'scanner_'` 只对 lint 生效、不会自动改名；所以要么一次性给约 20 个通用 drawable 加前缀，
  要么接受「宿主可覆盖」这个特性（改皮肤时用）。建议加前缀，改名是纯机械操作、放在 B4 一批里做。
- **共享资源**：`R.array.filter_names`（`arrays.xml:4-8`）与 `@string/filter_*` 被 Crop 屏和页面管理屏共用，
  两屏都在模块内 → 一起搬；`ic_launcher_foreground` 被 `activity_main.xml:23` 当 logo 用 → 留宿主。

## 6. 测试搬迁

- 24 例纯 JVM 单测（`QuadGeometryTest`/`AtomicTextFileTest`/`ExportNamesTest`）跟着源码进 `scanner/src/test`；
  模块 `testImplementation` 同一份 junit + OpenCV。
- 61 例 instrumentation：走真实 Activity/真实镜头/真实 OpenCV 的（Scan\*、Crop\*、PageList\*、PdfPreview\*、PageRenderer、
  ScanSession\*、PdfExport）搬到 `scanner/src/androidTest`，`MainActivityHistoryTest` 留宿主。
  库模块的 androidTest 会生成自己的测试 APK（target 是模块 stub app），`InstrumentationRegistry.getTargetContext()`
  拿到的 cache 目录随之变化——`SessionFixtures` 里硬编码的 `scan_session`/`session.json` 字面量（:23-24）要改成引用模块常量。
- `SessionFixtures.resetInstance()` 现在反射私有 `ScanSession.instance`（:46-49）；跨模块后仍跑得动，
  但更稳的是模块提供 `@VisibleForTesting` 的重置入口。这条属于「公共组件的可测性地基」，建议 B3 一起改。
- **`androidx.test` 三件套在模块里同样必须停在 core 1.5.0 / runner 1.5.2 / ext:junit 1.1.5**：
  实测过三件同退到 1.4.0 世代会把 `androidx.test:monitor` 拉到 1.4.0，23 个走 `ActivityScenario` 的用例全卡
  `PRE_ON_CREATE`（Android 15 真机）。多模块时每个模块的 androidTest 都要写 `testInstrumentationRunner`。
- CI（`.github/workflows/ci.yml`）要加 `:scanner:testDebugUnitTest`（app 的 `testDebugUnitTest` 会变空，
  别把 `if-no-files-found: error` 的产物步骤弄断），android-31 平台那步不变。

## 7. 迁移分批（每批都以「四条构建 + 61 例真机全绿」收口）

| 批 | 内容 | 风险 | 收口验证 |
| --- | --- | --- | --- |
| B1 | 建 `:scanner` 空模块 + `settings.gradle` include + 只搬 `util/`（无内部依赖，除 `AtomicTextFileTest`）+ 修 `PdfExporter.java:107` 的全限定调用 | 低 | 四条构建 + 24 单测 + `assembleDebugAndroidTest` |
| B2 | 搬 `cv/`+`camera/`：OpenCV/CameraX/guava 依赖与注释进模块，`consumer-rules.pro` 起草 | 中（native ABI、keep 规则） | 四条 + `PageRendererTest` + release 包真机跑滤镜 |
| B3 | 搬 `model/`+`export/`：会话/产物落盘、FileProvider 归属定稿（§5 选 ①）、`SessionFixtures` 去反射。`cv/PageRenderer` 也在这批走：它包名已是 `scanner.cv`，但类要用的 `model.ScanPage` 还在宿主，库模块不能反向依赖 `:app`，所以 B2 时它暂住 `app/src/main/java/com/documentscanner/scanner/cv/`（B2 已实测：搬进模块即 `找不到符号 ScanPage`） | 高（全局单例 + authority） | 四条 + `ScanSession*`/`PdfExport` 共 36 例 |
| B4 | 搬四屏 UI + 资源 + 资源名加 `scanner_` 前缀 + 主题拆分 + binding import 修正 | 高（XML 全限定类名、R 合并） | 四条 + `Scan*`/`Crop*`/`PageList*`/`PdfPreview*` 共 16 例 + 真机取景实拍核对居中 |
| B5 | 门面收口：`Scanner`/`ScannerSession`/`ScannerEngine` + 字符串 extra 常量化 + 会话单例语义 + README/CI 多模块 | 中 | 四条 + 全量 85 例 + APK 体积核对（debug≈27MB / release≈23MB，不应因分模块变化） |

B1-B4 每批都是「移动 + 改 import」的机械操作，唯一涉及行为改变的是 B3 的 provider/authority 与 B5 的会话单例，
这两批要在真机上多走一遍分享/重拍路径（现有自动化只覆盖了导入与相机交接）。

（B5 实测收口口径：全量 102 例 = JVM 27 + instrumentation 75；计划里那句「85 例」是研究稿时按单模块数出来的。
APK 体积核对也没用口头基线，改成从初始提交 `865b4e9` 重建同名包逐项对比，结果见 §10 的 B5 条目。）

**release 包这一档没有自动化可跑**：AGP 4.1.3 下把 `testBuildType` 改成 `release`，
`:app:connectedReleaseAndroidTest` 会装在混淆包上枚举不到任何用例（`No tests found.`，
2.9s 就失败），而 debug 包同一套 61 例全绿——是 AGP 4.1 的坑，不是搬迁引入的。
所以「release 真机跑滤镜」只能手动核：B2 用 `adb` 点完一遍
取景 → 快门（`LiveDocAnalyzer` 逐帧检测）→ 换滤镜 + 使用（`PageRenderer.refilter`）→
导出 PDF（`PdfExporter`）→ 分享（`FileProvider`），全程无崩溃，chooser 正常拉起；
另外从 `mapping/release/seeds.txt` 里查到 `org.opencv.android.OpenCVLoader.initLocal()`
等 8845 条 OpenCV 成员被 keep 住。B3-B5 涉及 provider/keep 规则的批次沿用这套手动核对。

## 8. 风险清单（按会不会真出事排）

1. **XML 里全限定 widget 名 + R8**：consumer keep 写错包名 → release 包一进裁剪页 `InflateException`。debug 全绿不代表过。
   （B4 实测把这条证伪了一半：见 §9.2——widget 的类名其实由 aapt2 自动兜住，写错 keep 不崩。
   真正的风险换成「你没法用不崩来证明 keep 生效」，得去查 `aapt_rules.txt` 与 `mapping.txt`。）
2. **`ScanSession` 进程单例**：作为公共组件被第二个模块使用时互相覆盖会话（现在靠 `MainActivity:78` 显式 `open()` 兜住）。
3. **FileProvider authority 与 `file_paths.xml` 目录名**：两处重复、无编译期约束，改动一次就要真机验分享。
4. **产物在 `getCacheDir()`**：系统可清（现有 `ScanSessionRestoreTest` 覆盖了「缓存被清不崩」），
   公共模块必须在 README 写清「original/warp/result 不是长期存储」。
5. **OpenCV 版本对消费方的约束**：4.10.0 被钉（D8 major 61 / 模拟器 SVE）；宿主若升 AGP/换 OpenCV 需同步评估。
6. **体积**：`libopencv_java4.so` ≈21MB 会跟着任何引用方，只要「检测回调」的场景应走 §2-C。
7. **未受支持的构建组合被放大**：AGP 4.1.3 × Gradle 7.6.4 现在要驱动两个模块，`Execution optimizations disabled`
   之类的告警会变多；不要顺手开 configuration cache 或换 Gradle 大版本。

## 9. 已定结论（2026-09-29）

- **边界**：取 §2-A——`cv/camera/model/export/util` + 取景/裁剪/页面管理/PDF 预览四屏 + 相应资源全进 `:scanner`，
  宿主只留首页历史。包结构按 §2-B 预留（公开类集中在 `com.documentscanner.scanner` 顶层，其余进 `internal` 分包）。
- **分发**：同仓库 `project(':scanner')`，不发 AAR。§4 里关于 `maven-publish` 与「OpenCV 4.10.0 作为传递依赖」的段落
  降级为备忘，等真有第二个仓库再启用。
- **资源前缀**：扫描侧资源加 `scanner_`（B4 执行）。
- **会话单例**：保留进程单例 + 文档化（B3 已把语义写进 `ScanSession` 类注释）。
- **FileProvider**：选 §5 的 ①（模块自带、宿主零配置），但 **authority 不写死**，见下。

### 9.1 B3 落地时对 ① 的一处修正

原方案写的是「authority 固定成 `com.documentscanner.scanner.fileprovider`」。真机上这个做法直接撞车：

```
INSTALL_FAILED_CONFLICTING_PROVIDER: Can't install because provider name
com.documentscanner.scanner.fileprovider (in package com.documentscanner.scanner.test)
is already used by com.documentscanner
```

authority 在整个设备上是唯一的，写死之后：模块自己的 androidTest stub APK 装不上（宿主在场时），
两个接入本组件的 App 也互相装不上——正好毁掉「公共组件」这个前提。
改成 AndroidX 自己的做法：模块 manifest 里写 `${applicationId}.scanner.fileprovider`（合并进宿主时才展开），
运行期由 `ShareFiles.authority(context)` 拼同一个式子。宿主依旧零配置，本宿主机上展开后的值
与写死版一致（`com.documentscanner.scanner.fileprovider`），只是不再独占。

### 9.2 B4 落地的两处修正

**① 四屏的主题改由模块显式声明（原方案没打算动主题归属，只做拆分）。**
`PageListActivity` / `PdfPreviewActivity` 在旧宿主清单里没有 `android:theme`，吃的是
`<application android:theme="Theme.DocumentScanner">`。清单搬进模块之后，模块自己的
androidTest stub APK 里那个 `<application>` 是空的，真机首跑就炸在 MaterialButton：

```
Caused by: java.lang.IllegalArgumentException: The style on this component requires
your app theme to be Theme.MaterialComponents (or a descendant).
    at com.google.android.material.internal.ThemeEnforcement.checkTheme
```

这不是测试脚手架的瑕疵，是组件化的真 bug：任何宿主只要 application theme 不是 Material3 系，
进页面管理/PDF 预览就会崩。所以模块补一份 `Theme.Scanner`（Material3.Dark.NoActionBar + scanner 配色），
`.Camera` 重新挂回它下面（点号隐式继承，回到搬迁前的结构），四条 `<activity>` 全部显式写 theme。
宿主首页仍用自己的 `Theme.DocumentScanner`，两份主题刻意重复 6 行：模块不依赖宿主 theme，
宿主也不必跟着模块的观感走。

**② 「keep 写错包名 → release 必崩」这条推论被证伪。**
把 `consumer-rules.pro` 里的 widget keep 退回老包名（`com.documentscanner.ui.widget.**`，
即匹配不到任何类），重新出 release 包装机进取景页——不崩。查 `mapping.txt` 发现三个 widget 类
恒等映射（`X -> X`），再查 AGP 自己生成的规则：

```
app/build/intermediates/aapt_proguard_file/release/aapt_rules.txt
-keep class com.documentscanner.scanner.ui.widget.CropQuadView { <init>(android.content.Context, android.util.AttributeSet); }
-keep class com.documentscanner.scanner.ui.widget.QuadOverlayView { <init>(android.content.Context, android.util.AttributeSet); }
```

aapt2 会扫合并后的 layout，为 XML 里出现的自定义 View 自动补构造器 keep，连库模块的资源一起扫。
结论有两层：运行期「不崩」证明不了 keep 生效，核对要看 `aapt_rules.txt` + `mapping.txt`；
`-keep class ... .ui.widget.** { *; }` 多出来的价值是成员级（`{ *; }`）保护，
以及覆盖只被反射/动态构造、aapt 扫不到的 View，所以规则留着，注释按实测改写。
补一句边界：widget 类名写错并不会等到运行期才炸——开了 viewBinding 且 XML 节点带 `android:id` 时，
生成的 binding 类直接 `import` 那个名字，debug 编译就失败（实测见 §10 B4）。
所以三层保护各管一段：viewBinding 管带 id 的 XML 名字（编译期），aapt2 管 XML 里的构造器可达性（构建期），
consumer keep 管 aapt 看不见的那些——只被反射或动态构造的 View，以及成员级名字。

### 9.3 B5 对 §3 目标形态的两处偏离

**① 门面按草案落地，但三个签名按实测改过。**

| §3 草案 | 实际 | 原因 |
| --- | --- | --- |
| `Scanner.openScan(Context)` | 拆成 `startNewScan` + `continueScan` | 「首页开始扫描」要另起一份会话，页面管理的「添加页面」必须沿用当前会话，两者共用一个名字就等于把「要不要先 `startNew()`」重新变成口头约定 |
| `ScannerEngine.info()` | `version()`，另加 `warmUp()` | 宿主只需要一个字符串；`warmUp()` 把 `Cv.ensure()` 从首页 `onCreate` 挪进 `Application`，第一屏不再等 native 加载 |
| `ScannerSession.open/list/activeId` | 再加 `startNew` / `delete` / `prepare` | 宿主首页本来就在做这三件事（切会话、删会话、启动预热），不露出去就得让宿主继续 `import` model |

**② `internal/` 分包不做。** 这条现在只以 `api/` 约定存在：AGP 4.1.3 下没有 `module-info`、也没有
比 `public` 更硬的可见性，把 34 个类挪进 `internal/` 换来的只是包名里多一段，既挡不住 `import`
也不会有编译器告警。更关键的是 §3 设想的「公开类集中在顶层」本身就漏了一处：宿主必须拿到
`ScanSession.Info`（历史列表的行模型），而 `ScanSession` 不能进 `api/`——它是单例本体，
把它当门面暴露出去等于把 `save()`/`createPage()` 这一整套写盘 API 交给宿主。
所以真实的边界是「`api/` 四个类 + `api/` 之外的两个 import」（F3 之后 `api/` 是五个类，多出来的
`ScannerConfig` 是纯配置，不参与起屏契约，见 §10 F1–F3）：`model.ScanSession.Info`（历史列表的
行模型，`MainActivity` 与 `SessionAdapter` 都要用）和 `ui.PageImageLoader`（首页封面复用模块那套
带缓存与 tag 校验的解码器，宿主只用它的 `File` 重载）。收口靠两条约束而不是包名：门面签名里不许出现
`org.opencv.*` / `androidx.camera.*`（§3 的硬约束，实测见 §10 B5），以及宿主 `src/main` 除 `api`
与这两个类之外不 `import` 模块内部类。
将来真要把面收硬，做法是 `api/` 自己声明一份 `SessionInfo` DTO，并把封面解码包成一个只收
`ImageView` + `File` 的入口，而不是给 `ScanSession` 开 `internal`。

### 9.4 F5 新增的第三处越界 import

体积档的参数类型 `cv.ImageBudget` 也被宿主看到了（`ScannerConfig.setImageBudget(ImageBudget.TINY)`），
所以 §9.3 那句「两个 import」现在是三个。没有把它再包一层 `api/` 的同名枚举：`ImageBudget` 的四个数
就是 `cv`/`export` 两边要用的那四个数，包一层要么把值复制两份（改档时必然漂移），要么在门面里写
`switch` 转发——两种都比多一个 import 更贵。真要把面收硬的做法是把档位数值也搬进 `api/`，
让 `cv` 反过来依赖门面，那是另一次边界改动，不在这一批。

G1 复核补一句，免得后来人按 grep 下结论：演示宿主现在**没有**调用 `setImageBudget`
（`grep 'ImageBudget\|ScannerConfig' app/src` 零命中，`DocumentScannerApp` 只有 warmUp 与 prepare），
所以照着 app 侧 import 数只剩两处。但这条越界是**签名层面**的：`ScannerConfig.setImageBudget` 的
入参类型就在 `cv` 包里，任何真去用体积档的宿主都必然 `import com.documentscanner.scanner.cv.ImageBudget`。
「三处」按公开面算，不按 demo 用没用算。

## 10. 进度（截至 G2）

- B1 `14c5a3f`：`:scanner` 骨架 + `util/`。
- B2 `d414c58`：`cv/` + `camera/`；`PageRenderer` 因依赖 `model.ScanPage` 暂留宿主。
- B3：`model/` + `export/` + `PageRenderer` + provider 归属；测试侧 `SessionFixtures` 挪进
  `scanner/src/androidTestShared/java`，两个模块的 androidTest 各引同一份源码
  （AGP 4.1 没有 Android 版 testFixtures，抄两份必然漂移）。`ExportNamesTest` 跟着 `ExportNames` 进模块，
  于是 `:app:testDebugUnitTest` 变成 0 例——CI 的 Unit tests 步骤当场补上了 `:scanner:testDebugUnitTest`，
  否则 CI 会一条单测都不跑还报绿（原计划这条留到 B5，实际破坏发生在 B3）。
- 用例数（B3 收口时）：JVM 单测 24（app 7 + scanner 17）；instrumentation 由 61 涨到 64（新增 `ShareFilesTest` 3 例），
  分布 app 21 + scanner 43。三条下毒已验证：改坏 `AUTHORITY_SUFFIX` → 两条正向用例失败；
  把 `cache-path` 放宽到 `/` → 越界那条失败；`dropInstanceForTesting()` 改成空实现 → 会话用例成片失败。
- 体积（B3 收口时）：debug 29,337,835 B / release 25,384,345 B。B5 收口时用初始提交重建一次同名 APK 做逐项对比，
  避免拿口头记录的「27MB / 23MB」当基线。
- B4：四屏 UI 入住。
  - 代码：`ScanActivity`/`CropActivity`/`PageListActivity`/`PdfPreviewActivity` + `PageImageLoader`/`ThumbCache`
    + `ui/adapter/{FilterAdapter,PageAdapter}` + `ui/widget/{CropQuadView,ImageGeom,QuadOverlayView}`
    → `com.documentscanner.scanner.ui.*`；`R` 与 databinding 换成 `com.documentscanner.scanner.{R,databinding}`。
    宿主只留 `MainActivity` + `SessionAdapter`（`SessionAdapter` 是首页历史行的适配器，属宿主 UI，不跟屏走），
    它靠 `import com.documentscanner.scanner.ui.{ScanActivity,PageListActivity}` 起屏。
  - 资源：8 layout + 27 drawable + 101 string + 12 color + `scanner_arrays.xml` 全部加 `scanner_` 前缀进模块；
    主题拆成模块侧 `Theme.Scanner` / `Theme.Scanner.Camera`（见 §9.2），宿主留 `Theme.DocumentScanner`。
    view id 名不改：`R.id.*` 在合并后仍是同一批符号，改前缀只会让 8 个用例一起跟着重写而没有收益。
  - 清单：四条 `<activity>` + `CAMERA` 权限 + 两条 `uses-feature` 移进模块 manifest
    （`merged_manifests/debug` 核对：类名已是 `com.documentscanner.scanner.ui.*`，
    authority 展开仍是 `com.documentscanner.scanner.fileprovider`）。
  - proguard：`ui.widget` 的 keep 从宿主搬进 `consumer-rules.pro` 并改成模块包名；宿主的
    `org.opencv.**` keep 一并交给模块（宿主 main 里已 `grep` 不到任何 `org.opencv` / `androidx.camera` 引用）。
  - 用例分布：JVM 单测 24 全在 `:scanner`（11 + 7 + 6）；instrumentation 64 例重新切成
    app 4（只剩 `MainActivityHistoryTest`）+ scanner 60。四条构建 + 两个 `connectedDebugAndroidTest` 全绿。
  - 下毒两条：`PageAdapter` 的未确认角标改成恒 `GONE` → `anUnconfirmedPageCarriesThePendingTag`
    报 `expected:<0> but was:<8>`（证明搬家后的用例仍在真断言）；把 crop 布局里的 widget 全限定名
    退回老包名 → 预想是「release 才炸」，实测 `:scanner:compileDebugJavaWithJavac` 当场失败：
    viewBinding 为带 id 的 `<com.documentscanner.ui.widget.CropQuadView>` 生成了
    `import com.documentscanner.ui.widget.CropQuadView;` + `public final CropQuadView cropView;`，
    包不存在直接编译不过。`quad_overlay` 同样带 id，所以 B4 这处最危险的改动其实是编译期就锁住的，
    只有「XML 名字不带 id」的写法才会漏到运行期——风险等级比 §8-1 原先记的低。
    第三条本想验 keep 规则，结果是空转的，见 §9.2 ②。
  - release 手动走查（装机包 24,305,537 B）：首页历史渲染 → 取景（自动检测到边框并自动快门 1 页）→
    页面管理（MaterialButton 正常）→ 裁剪页（`CropQuadView` + 五档滤镜）→ 魔法色彩 + 使用 →
    导出 PDF（A4，`共 1 页 · 1.9 MB`，OpenCV 在 R8 下工作正常）→ 分享（荣耀 chooser 拉起，
    `logcat` 无 `FileProvider`/`SecurityException`）。全程无崩溃。
  - debug 28,476,812 B / release 24,305,537 B，比 B3 各降 ~860 KB / ~1,079 KB（模块边界改变后
    R8 的裁剪面变大，不是回归）。（B5 复核：这条「下降」站不住——见下面 B5 条目的体积核对，
    B3 那两份数字极可能取自构建缓存恢复出来的包。）

- B5：门面收口 + 文档/CI 改到多模块口径。
  - 门面四个类进 `scanner/.../api/`：`Scanner`（五组 `xxxIntent` + 七个 `openXxx`，`openPages` 带/不带
    会话 id 两个重载，见 §9.3 的偏离表）、
    `ScannerSession`（`list`/`open`/`startNew`/`delete`/`activeId`/`prepare`）、
    `ScannerEngine`（`version`/`warmUp`）、`PdfPreviewRequest`（把 PDF 预览的四个 extra 收成不可变入参）。
    extra 常量按 §3 的设想留在各自 Activity 里（私有），由 Activity 的 `intentFor` 持有，
    门面只做转发——这样 key 的定义点只有一处，而跨屏契约由 `ScannerIntentTest` 用字面量钉住。
    `PdfPreviewActivity` 读回 Intent 时按外部输入处理：路径缺失→沿用原来的 `exists()` 提示后 `finish()`，
    纸张名不认识→退到 `FOLLOW_IMAGE`，不新增异常分支。
  - 宿主侧只剩 `MainActivity` + `SessionAdapter` + `DocumentScannerApp` 三个文件引用模块，
    import 面收成 `api.{Scanner,ScannerEngine,ScannerSession}` + `model.ScanSession`（为了那个 `Info` 行模型）
    + `ui.PageImageLoader`（首页封面缩略图复用模块的解码器）；最后这条是 §9.3 里「内部类不外泄」的一个已知破口。
    `DocumentScannerApp` 现在只有两行：`ScannerEngine.warmUp()` + `ScannerSession.prepare(this)`。
  - 用例：JVM 27（新增 `PdfPreviewRequestTest` 3 例）+ instrumentation 75（新增 `ScannerIntentTest` 6 例、
    `ScannerSessionTest` 5 例），真机 PTP-AN00 全绿（app 4 + scanner 71，2026-09-30）。
  - 门面下毒两条：`ScannerSession.delete` 改成删「当前那一份」而不是传入 id →
    `deleteRemovesOnlyThatSessionFromHistory` 报 `expected:<s-1790728351623> but was:<s-1790728351598>`；
    `CropActivity.EXTRA_PAGE_ID` 由 `"page_id"` 改名 `"pageId"` → 只有
    `ScannerIntentTest.cropIntentCarriesThePageToEdit` 红（`expected:<p7> but was:<null>`），
    同一份改动下 `CropActivityEdgeTest` 5 例照旧绿。后者就是 §1 里那句「字面量是 wire format 的锁」的实证：
    模块自读自写，编译器和屏内用例都看不见这一类改动。
  - 依赖隐藏实测：在 `app/src/main/java` 临时放一个引用 `androidx.camera.core.*` /
    `com.google.common.util.concurrent.*` / `org.opencv.android.*` 的探针类，
    `:app:compileDebugJavaWithJavac` 报 6 处「程序包不存在 / 找不到符号」，删掉探针即恢复绿——
    §3 那条「宿主编译期看不到 OpenCV/CameraX」由 `implementation` 作用域真的兜住了。
  - 体积核对改用真基线：`git worktree add .baseline 865b4e9` + 同一台机器同一套工具链，
    两边都 `rm -rf app/build` 再 `--no-build-cache` 出 clean 包。

    | | debug | release |
    | --- | --- | --- |
    | `865b4e9` 单模块 | 28,453,619 B | 24,303,277 B |
    | B5 两模块 | 28,476,129 B | 24,305,537 B |
    | 差 | +22,510（+0.08%） | +2,260（+0.01%） |

    §7 表里那句「debug≈27MB / release≈23MB」的口头基线就此作废。
    顺带挖出一个坑：不加 `--no-build-cache` 时 B5 的 release 会量到 25,384,951 B，
    多出来的 1,079,414 B 全在 zip 首个条目之前（一段 1,079,290 B 的对齐占位），
    条目本身的压缩后总量两边只差 1,896 B——也就是说 §10 B3 记的 25,384,345 B 与今天这份缓存产物
    只差 606 B，B3/B4 那对「降了 1.08MB」的数字极可能是同一现象，而不是 R8 裁剪面的变化。
    结论：分模块既没让包涨过 1MB，也没降过 1MB。
  - release 真机走查（clean 包 24,305,537 B，专门覆盖 B5 改过的起屏路径）：
    `startNewScan` 开新会话进取景 → 自动快门 + 手动快门各攒一页（留在取景页）→ `openPages` 进页面管理
    → `openCrop` 进裁剪、换魔法色彩并使用（该页「未确认」角标消失）→ 导出 A4 → `openPdfPreview`
    翻页与缩略图抽屉 → 分享（chooser 里 `扫描件 2026-09-30 08.45.pdf · 6.75 MB`，`logcat -b crash` 空）
    → 「另存为…」真的写进 Download（6,745,767 B）。会话相关两条也走通：`continueScan`（添加页面）
    中途退出后当前会话仍是 2 页，没被误清空；`openScanReplacing`（重拍本页）拍完落回裁剪页且原位替换
    （「第 2 / 2 页」）；首页历史行 `openPages(ctx, id)` 切回那份会话。
    首页「识别引擎 OpenCV 4.10.0」由 `ScannerEngine.version()` 供数，R8 下门面→`Cv` 这条链是通的。
  - 文档与 CI：README 从单模块口径整体改写（§1 构建/用例数、§2 两模块目录树、新增 §3「作为组件接入」、
    §6 加两条组件化边界、§7 贡献指引指到正确的 `src/test`），PR 模板的验证清单同步；
    CI 补 `:scanner:assembleRelease` 与该 AAR 的产物上传（`scanner/build/outputs/aar/scanner-release.aar`，
    210,766 B——模块产物只有 206 KB，21MB 的 native 库由宿主的传递依赖带进去）。
    B5 之后 `:app:testDebugUnitTest` 是 `NO-SOURCE`（宿主没有 JVM 单测了），
    这条正是 §6 里「别把产物步骤弄断」那句预警的落点。

- F1–F3（分支 `feat/scanner-preview-actions`，B5 之后宿主提的三条功能）：预览页竖向连排、「查看」直连预览、
  保存/分享显隐可配。
  - F1 `PdfPreviewActivity` 的 `pager_pdf`（ViewPager2 一页一屏）换成竖向 `RecyclerView`：一条 item 一页，
    从上往下连排。`viewpager2` 依赖随之从 `scanner/build.gradle` 删掉（模块内再无引用）。
    两条判据各管一段：「当前页」取跨过视口中线的那一页（不取第一个可见项——一页往往比屏幕还高，
    按第一个可见项会在半页处抖），item 高度在绑定时按 `PdfRenderer.Page` 的宽高比预占
    （`列表宽 ÷ 宽高比`）。预占这条不是优化而是修 bug：位图后台逐页出，没占位时一条只有 160dp 兜底高，
    两页一起塞进视口，真机第一轮就跑出 `expected:<[1] / 2> but was:<[2] / 2>`，而且用户不动它就一直错。
    宽高比在装载那一趟后台里顺手读（开每页只为拿字典里的宽高，不解码像素），主线程一次性接上。
  - F2 取景页「查看」改道：`btn_finish` → 补齐未确认页 → 按 A4 导出 → `Scanner.openPdfPreview`；
    `btn_thumbnail` 仍然进页面管理，管理入口不丢。补齐那段逻辑从 `PageListActivity` 抽成
    `ui/PendingRenderer`（两屏共用一份，「补齐」才只有一种含义），导出走 `PREVIEW_PAPER = PaperSize.A4`
    常量——纸张在这里不问，是刻意让「查看」这一击直达成品，要挑纸张走页面管理那条路。
    成功路径收尾用 `endWork(false)`：分析流保持暂停到预览页起来，否则回到前台前会被自动快门塞进一页幽灵页。
  - F3 新增 `api/ScannerConfig`：两个 `volatile` 布尔，进程级，宿主在 `Application` 调一次。
    没选 Intent extra（每个起屏点都要带一份，漏一个就退回默认，正是 §9.3 要避免的那种口头约定），
    也没选 `R.bool`（要宿主改资源、模块内也覆盖不掉）。四处生效：页面管理 `btn_save_gallery` /
    `btn_share_images`，预览页 `btn_share` / `btn_save_device`；两个都关掉时页面管理那一行整条收起。
    这个类不碰 Android 类型，所以默认值/互不干扰/可恢复交给 JVM 单测（`ScannerConfigTest` 4 例），
    屏上显隐交给 instrumentation（各屏 3 例），`resetForTesting()` 供用例收尾。
  - 用例：JVM 27 → 31；instrumentation 75 → 82（模块 71 → 78）。新增 7 例真机用例 + 4 例单测。
    五处下毒全部被抓（红字见 README §1）：布局改回横向、`btn_finish` 改回页面管理、
    `PREVIEW_PAPER` 改成跟随图片、两处开关串线（页面管理让保存跟分享走、预览页让分享跟保存走）。
  - 门禁：四条构建全绿 + 真机整轮 82 例全绿（PTP-AN00 / Android 15，2026-09-30）；
    clean release 包 24,301,525 B（比 B5 那份少 4 KB，去掉 `viewpager2` 换来的），
    真机走查覆盖三屏改动，其中显隐那一条走的是宿主真实调用点——临时在 `DocumentScannerApp.onCreate`
    加 `ScannerConfig.setSaveActionVisible(false)`（走查完删掉），装机上页面管理只剩「分享」并撑满整行、
    预览页顶栏只剩「删除 + 分享」，分享 chooser 照常。细节记在 README §1。
  - 已知边界两条：`ScannerConfig` 读的是「起那一屏时」的值，起屏后再改不动当前屏（README §3 第五条约束）；
    短文档在高屏上一屏装得下时滚不动，指示器就停在第 1 页（README §6）。

- F4（同分支）：画面效果默认选「原图」。
  - 做法：默认值收成一处 `FilterType.DEFAULT`，四个引用点全指过去——`ScanPage.filter` 的字段初值、
    `FilterAdapter` 收到 null 时的兜底、`ScanSession` 恢复会话时 JSON 缺 `filter` 键的兜底、
    `FilterType.byOrdinal` 序号越界的兜底。收成一处的理由是坏味道很具体：常量散在四处时改默认值必然漏点，
    留下「新页面是原图、恢复回来的旧页面是增强」这种同一页面两个身份的分裂。
    为什么是原图：五档里只有它一个像素不改（其余四档都把纸面重画一遍），挑哪一档该由用户点；
    读不出来的滤镜也退到这里——宁可少处理一层，不做没被要求的处理。
    副作用记在 README §5：自动快门攒下的页面其 `result` 与 `warp` 同像素，换滤镜那一步才第一次真跑算法。
  - 用例：JVM 31 → 34（新增 `FilterTypeTest` 3 例：默认档不改像素、新页面带着它上场、越界序号退回到它）；
    instrumentation 82 → 84（模块 78 → 80，新增 `CropActivityFilterTest` 2 例——裁剪页真的选中「原图」那一格
    且标签写着「原图」；另一例反向钉：页面自带魔法色彩上场必须落在第 4 格，专门用来堵住「恒选中原图」
    也能蒙过前一种写法）。真机那两例要建一页真的带 JPEG 原图的页面，没复用 `SessionFixtures.addPage`——
    那个 fixture 硬编码 `FilterType.BINARY`，用它造的前提就是坏的。
  - 三处下毒全部被抓（红字见 README §1）：新页面默认档改回增强、越界兜底改回增强、滤镜条无视页面自带档位恒选第一格。
  - 门禁：四条构建全绿 + 真机整轮 84 例全绿（PTP-AN00 / Android 15，2026-09-30）；release 装机走查
    自动快门 → 页面管理 → 裁剪，`uiautomator` 量到五格 `selected` 为 原图=true、其余 false，
    点「增强」能翻过去——默认值没有把选择条钉死。

- F5（新分支 `feat/scanner-image-budget`，基线 `3750314`）：图片体积档做成宿主可配的配置项。
  - 需求是「支持设置图片最大内存大小，用于控制合成的 PDF 大小」。落点选了 B 方案（宿主集成方能改，
    与 F3 的 `ScannerConfig` 同一形态），所以 App 内没有设置界面、不落 `SharedPreferences`；
    两个语义此后共存于同一个类：布尔那两个是**能力开关**，这一个是**产品档**。
  - 编码前先 auditing 了自己的方案，查出三处错，纠正之后才动手（不纠正的话这功能会是个假开关）：
    ① 「导出上限 1600 会把 1800 的页面悄悄降采样」是错的——`BitmapFactory` 的 `inSampleSize` 是向下
    取整的 2 的幂，1800 配 1600 原样通过；② 由此「导出侧只给一个自由长边」也是假杠杆：真正的膨胀源是
    `android.graphics.pdf.PdfDocument` 把画上去的位图按接近无损重编码（本机实测 JPEG→PDF 膨胀
    2.25~7.5 倍，真实文档 5.6~7.5 倍、确定性噪声图样 2.25 倍），能动的只有像素数，所以导出解码补了
    一次精确 `scaleMaxEdge`，且出厂档的导出解码边取 **1800**（等于改动前的行为）而不是顺手写的 1600；
    ③ 「档位统一管住 `page.original`」是错的——那是唯一的一份源图，最低档按 78 的质量重编码一次就永久糊了，
    于是 `DocPipeline.JPEG_QUALITY_ORIGINAL` 从档位里独立出来。另外补了 `sourceDecodeEdge`：
    原先渲染硬编码 `SOURCE_EDGE=2200`，若让 `FINE` 的 2400 输出吃 2200 的解码就是在放大。
  - 做法：`cv/ImageBudget` 四档枚举（`TINY(900) / DRAFT(1200) / STANDARD(1800) / FINE(2400)`，
    一档同时定产物长边、渲染解码长边、JPEG 质量、导出解码长边——这四个数互相牵制，
    拆成两个自由数字就配得出「又大又糊」）。`DocPipeline.EDGE_OUTPUT`/`JPEG_QUALITY`、
    `PageRenderer.SOURCE_EDGE`、`PdfExporter.DECODE_EDGE` 四处常量删掉，单一来源变成档位；
    档位以**显式参数**穿过 `PageRenderer.render/refilter/refilterAll`、`PendingRenderer.prepare`、
    `PdfExporter.export`，四个屏在「起屏 / 起导出」各读一次 `ScannerConfig.imageBudget()`。
    选显式传参而不是让 cv/export 自己 ambient 读配置，是为了把「一次动作一个档」写成可断言的事。
    `EDGE_DETECT_SOURCE`(1500) 与缩略图常量刻意留在档位外（一个管检出率，一个 <2% 字节）。
  - 基线（PTP-AN00，1700×2400 确定性噪声图样 → A4 单页 PDF）：页面 JPEG `1,970,078 B`；
    PDF `TINY 537,865 / DRAFT 1,010,445 / STANDARD 2,333,347 / FINE 4,422,958 B`——大致随长边平方走。
    出厂档那份 2,333,347 B 被 `theStandardTierStillWritesThePdfItUsedTo` 钉成断言：改默认值会当场红。
  - 用例：JVM 34 → 42（新增 `ImageBudgetTest` 5 例；`ScannerConfigTest` 4 → 7 例）；
    instrumentation 84 → 94（模块 80 → 90：新增 `PageRendererBudgetTest` 3、`PdfExportBudgetTest` 5、
    `CropActivityBudgetTest` 2）。三处坑在写用例时踩到并记下：① 图样长边必须 ≥ 最高档的导出解码边，
    否则 `FINE` 与 `STANDARD` 写出同一份字节、单调性断言空转（第一版用 1349×1800 就是这么红的）；
    ② `PerspectiveCorrector.warp` 只缩不放，渲染用例的源图因此得比档位长边大（用了 3000×2400）；
    ③ 「中途改配置不影响这一份」借的是 `PdfExporter.Progress` 这个已有接缝，没为测试新开口子。
  - 七处下毒全部被抓（红字见 README §1）：改出厂档四个数（JVM 3 例 + 真机 3 例同时红）、导出去掉精确
    `scaleMaxEdge`、重跑滤镜恒用出厂档、导出逐页现读配置、渲染时按档位重编码原始照片、
    `setImageBudget(null)` 不回落、裁剪页硬编码出厂档。最后那条与「一次都不调」那例互为补位——
    硬编码出厂档时只有前一例红。
  - 门禁：四条构建全绿 + 真机整轮 94 例全绿（PTP-AN00 / Android 15，2026-09-30）；clean release
    `24,301,525 B`，与 F4 那份逐字节同大小（一度量到 `25,375,669 B`，是带缓存增量产物的 zip 对齐占位，
    即 §1 B5 那条老坑，重出 clean 包即归位）；`:scanner` release AAR `218,671 B`。
  - release 走查做了两次对照，路径同一条（取景自动攒 1 页 + 相册导入同一张照片 = 2 页 →「查看（2）」→
    预览副标题 →「另存为…」落 Download）：临时在 `DocumentScannerApp.onCreate` 注入
    `ScannerConfig.setImageBudget(ImageBudget.TINY)` 时屏显 `共 2 页 · 582.7 KB`、文件 `596,702 B`
    （582.7 KB 就是 596,702/1024，界面体积与真实字节对得上）；删掉注入重装后屏显 `共 2 页 · 1.5 MB`、
    文件 `1,544,779 B`。`logcat -b crash` 两轮全程为空，注入那一句测完即删，提交里 `app/` 无变化。
  - 已知边界三条（写进 README §3/§6）：档位只管此后新渲染的页面与此后合成的每一份 PDF，
    已渲染的页面不重写、已导出的 PDF 不重生成；宿主想让用户自己选档得自建设置项与持久化；
    混档是可能的（先默认渲染两页、改档后再拍一页），模块不做整会话重渲染。

- F6（新分支 `feat/scanner-pdf-budget`，基线 `a958937`）：给合成 PDF 加一个字节上限，超出就缩边重导。
  - 需求是用户的原话「多图合成 pdf 后，pdf 文档还是很大，是否可以通过先压缩图片到不大于某个尺寸
    （如不大于 500kb），然后再合成 pdf」。先否掉了这个提法本身：F5 已经量过 `PdfDocument` 会把画上去的
    位图按接近无损重编码（膨胀 2.25~7.5 倍），页面 JPEG 存得多小都不影响成品字节，所以「先压图片」
    在现有框架下是空的。给出两条真路并让用户挑：A 写完量真实字节、按目标缩像素再导（复用现有接缝，
    不动产物格式，成本是多写几轮）；B 自己写最小 PDF 生成器、用 `DCTDecode` 把 JPEG 原样塞进去
    （能把 JPEG 字节真变成杠杆，但要自己承担 PDF 书写的正确性）。用户选 **A**，并定目标口径为
    **整份 PDF**（不是每页）。B 没做，是下一步的候选而不是这批的欠账。
  - 宿主面只多一个旋钮：`ScannerConfig.setMaxPdfSizeKb(int)`（KB=1024 B，0 与负数都算不设），
    它是这个类上的第三种语义——布尔那两个是**能力开关**，`setImageBudget` 是**产品档**，
    这一个是**成品上限**。三个屏（取景「查看」、页面管理导出、PDF 预览删页后重合成）与体积档同一处
    「起屏 / 起导出」读一次，传进 `PdfExporter.exportFitting(...)`。
    **没有新增越界 import**：参数是 `int`，不像 `ImageBudget` 那样把 `cv/` 的类型递到宿主手上，
    所以 §9.4 那处偏离仍然算三处。
  - `exportFitting` 返回 `Outcome{pdf, decodeEdge, bytes, writes}`。后三个不是给界面用的：光看文件字节
    没法区分「按目标缩过」和「碰巧第一枪就够小」，而这两件事正是这个类全部的难度。
    三处调用点现在取 `.pdf`，用了哪条边、写了几轮都只进日志——这是用户在方案阶段就接受的默认口径
    （界面一个新控件都不加）。
  - 算术全住在包内的 `export/PdfSizeFit`（不进任何公开面），连同逐轮拟合的状态 `Search`：
    `PdfExporter` 那一边满手 `android.graphics.*`，纯函数住在里面就只能上真机验证，
    而最容易写错的恰恰是这几个数——指数取多少、下限卡在哪、什么时候该停。
  - 实现中途被 release 走查推翻过一次，这是这批最值钱的一段。第一版按 F5 的四档基线拟合出
    先验指数 2.15 就直接用，走查那两页真扫描件打出的是：`909,516 → 降到 1311 → 668,330 → 降到 1102 →
    576,395 → 降到 993 → 521,829 B`，四轮（`MAX_ATTEMPTS`）打完仍在 512,000 B 目标之外，
    屏幕上还写着 `共 2 页 · 509.6 KB`——看着像成功，其实超标，而当时 `PdfExportTargetTest` 的七条全绿
    （它们只证「变小了」，没证「变到位」）。根因是 `p` 随内容摆得极远，同一台机上的实测段：

    | 图样 | 实测段（长边→字节） | 斜率 |
    | --- | --- | --- |
    | 确定性噪声图样（F5 四档基线） | 900→1200 / 1200→1800 / 1800→2400 | 2.19 / 2.06 / 2.22 |
    | 多尺度「字」的两页 A4 | 1800→1278 / 1278→697 | 0.73 / 1.45 |
    | 真机走查那次真会话 | 1800→1311 / 1311→1102 / 1102→993 | 0.97 / 0.85 / 0.96 |
    | 单一粗细线条的探针图样（12 档 600~2400） | 600→1800 整段 / 1500→2400 | 0.25 / 负数 |

    四行都是相邻两枪的实测字节算出来的（`p = ln(字节比) / ln(边比)`），不是估的。

    于是改成「先验只当第一枪的起点，从第二枪起用相邻两枪实测出的斜率 `p = ln(字节比)/ln(边比)`，
    钳进 0.8~3.0」。先验必须偏大是硬道理：边只降不升，砍过头没法回头，指数取大一点最多是多写一轮。
    修好之后同一台同一路径同样两页：`1,094,589 → 1203 → 788,931 → 620 → 312,852 B，共写 3 轮`。
  - 那个探针图样是临时用例 `ZzzMeasureFlatFixtureTest`（用完即删，不进提交）量出来的，
    它顺手暴露了一个坑：**单一粗细的线条会和重采样比例共振**，字节随边长非单调（1500→2400 甚至更贵），
    所以永久留存的文档图样必须用随机粗细的多尺度笔画，否则用例会被共振骗到、断言写得像空转。
  - 用例：JVM 42 → 71（新增 `PdfSizeFitTest` 24 例；`ScannerConfigTest` 7 → 12 例）；
    instrumentation 94 → 105（模块 90 → 101：新增 `PdfExportTargetTest` 8 例、
    `PdfPreviewActivityTargetTest` 3 例）。写用例时踩到并记下的三条：
    ① 空转防护得双向——「图样本身要够大否则这条用例就是空转」那条断言把自己红了一次
    （合成文档图样在 1800 只出 469,664 B，不是预估的 739,168 B，因为源 JPEG 走的路径不同），
    阈值改成 1.5 倍并按 logcat 里的真实轨迹重推了三枪；
    ② `Search` 就算这一枪返回 0 也要把实测点记下来，两条「返回 0」的用例起初用「已经装得进去」的
    数字写，于是斜率那条断言恒真——换成确实超标的数字才有意义；
    ③ 设备侧看不到 `Log.i`（测试进程被吞、`run-as` 被拒），所有数字得靠 `Assert.fail` 从
    `TEST-*.xml` 里读出来；App 进程的 logcat 则照常能读，所以走查那两次是 `adb logcat -d -s PdfExporter`。
  - 下毒：`PdfSizeFit` 上 13 个（M1 两种下法），12 个被抓（红字见 README §1），其中 M1（标定整个失效）
    在 JVM 红 8 例、
    真机红 1 例——红的正是 `该三轮内收敛：4`，也就是把走查那次失败钉成了一条会咬人的断言。
    唯一活下来的是 `MIN_DECODE_EDGE` 600→300，而且它是**等价变种**：所有断言都拿这个常量自己比，
    所以没有任何一条能钉住「600 以下就不是扫描件」这一句产品判断。
    工具坑一条必须记：第一版变异脚本把 M1 的锚写成单行，替换后留下半截表达式 → 编译失败 →
    `scanner/build/test-results` 里剩的还是上一轮的 XML → 脚本照旧报「0 红（等价变种）」，
    **它会替你说谎**。现在每条变种前先 `rm -rf test-results`，并且「没有 XML」当错误处理而不是绿。
  - 门禁：四条构建全绿 + JVM 71 全绿 + 真机整轮 105 全绿（PTP-AN00 / Android 15，2026-09-30 复跑）；
    clean release `24,301,525 B`（与 F4/F5 那份逐字节同大小，缩边算术被 R8 压进了既有对齐缝隙）、
    debug `28,485,004 B`、`:scanner` release AAR `218,671 → 221,935 B`（+3,264 B 是这批唯一看得见的成本）。
    debug 比 F5 大 1,469 B 而 release 纹丝不动，是同一份代码的两种命运：debug 留着 javac 的行号表与
    局部变量表，注释多一行它就漂一点，R8 把这些剥干净了——比体积只能比 release。
    设备状态记一条：整轮 `connectedDebugAndroidTest` 跑完之后 `com.documentscanner` 从设备上消失了
    （`pm list packages -u` 查无此包），App 私有目录连同上一手走查残留的那份两会话一起没了——
    省掉了「删走查会话」那一步，但**别把这条当成可依赖的清理手段**，要再走查得重装（本批已重装）。
    走查留下的文件已清：Download 里那份 PDF、`uiautomator` 的 xml、以及上一批遗留的 5 张 png，
    `/sdcard/` 里 `walk*` 计数为 0。
  - 已知边界（写进 README §3/§6）：上限是**上限**不是精确值，成品通常比它小一成左右；
    它是整份 PDF 的，「每页 500KB」得宿主自己乘页数；只管此后合成的每一份，已导出的不重生成；
    够不着时交最小那份而不是异常；「最多四轮」与「600 以下不再缩」是产品判断，没有断言守着。
  - 覆盖缺口诚实记一条：`exportFitting` 里 `write >= MAX_ATTEMPTS` 那半边守卫没有真机用例钉——
    标定之后的循环总是先撞上「装得进去」或「踩到下限」，四轮的天花板只在 JVM 侧
    `theDescentTerminatesWithinTheAttemptCap` 里被模拟。

- G1（门禁补全，基线 `2daf255`）：CI 从「只跑宿主」补成真正把组件关住。
  - 现状与问题：模块化整批落地时没动 `.github/workflows/ci.yml`，它还停在单模块口径——只跑
    `:app:testDebugUnitTest`（本树已 `NO-SOURCE`，等于一条都不跑）+ `:app:assembleDebug`。
    于是 71 例 JVM 单测、R8、`shrinkResources`、`consumerProguardFiles` 的传播，PR 上一律无人验证；
    而 README §7 当时已经写着 CI 会跑 `:scanner` 那两条，是文档在前、门禁在后的一次「写了但没做」。
  - 补的四步：`:scanner:testDebugUnitTest` + `:app:testDebugUnitTest`（宿主那条留着，理由见 §10 B3
    那条——只跑一侧会报绿但零执行）；`:scanner:assembleRelease`（AAR 脱离 demo 宿主也得能出）；
    `:app:assembleRelease`（keep 规则、资源收缩只在 release 这条路生效）。三个产物都上传。
  - 顺带修一条会被踩的坑：CI 里补了「runner 没有 `platforms/android-31` 就用自带 sdkmanager 现装」。
    README 早就写着有这一步，实际在「构建脚本清理」那次被删了——当时 compileSdk 还是 34，用不上；
    降到 31 之后它变成必需，而 AGP 4.1.3 不会自己把缺的平台拉下来。**这条是本次唯一在本地无法证伪的改动**，
    它唯一的验证是第一次 Actions 运行。
  - 门禁自己的变异验证（改坏实现，看新步骤是否变红）：
    M2 把 `QuadGeometry.rotateNormalized90Clockwise` 的方向写反 → `:scanner:testDebugUnitTest` 红
    （`rotatingNormalizedQuad_matchesRotatingPixelQuad`），旧 CI 全绿；
    M3 在 `scanner/consumer-rules.pro` 里写一条 `-keepa`（R8 才解析它）→ `:app:assembleDebug` 绿、
    `:app:assembleRelease` 红（`proguard.txt:25: R8: Unknown option "-keepa"`），这就是旧 CI 看不见的那一类。
  - 一条等价变种如实记下来：M1 把 `QuadGeometry.MIN_USABLE_AREA_RATIO` 从 0.01 降到 0.001，
    全绿抓不住。根因是 `isUsableQuad_rejectsTinySelection` 用的是 12×12（占 1000×1000 的 0.014%），
    任何高于 0.000144 的门限都能让它通过，所以这条断言钉的是「极小要拒」而不是「门限在哪」。
    与 `MIN_DECODE_EDGE`、`MAX_ATTEMPTS` 同一类：数值是产品判断，没有边界成对的用例守着。

- G2（用例补强，基线 `e8145a3`）：收掉 G1 那条等价变种，并把同一类「断言只跟常量自己比」的自指用例一次清完。
  - 三条新 JVM 用例、钉四个数：`isUsableQuad_gatesTheAreaAtOnePercentOfTheFrame`（成对给占画面
    1.21% 与 0.9025% 的四边形，凸度内角都一样，只差面积）、
    `isUsableQuad_gatesTheSharpestCornerAtEightDegrees`（9.48° 放行 / 6.50° 拒，都凸、面积都够）、
    `theFloorAndTheCapAreTheShippedNumbers`（字面量钉 `MIN_DECODE_EDGE=600`、`MAX_ATTEMPTS=4`）。
    边界那两个四边形不是猜的：按 `QuadGeometry` 同一套鞋带面积/最小内角/凸性公式先在脚本里算出来再写进用例。
  - 逐个下毒，每次只红对应那一条：面积门限 0.01→0.001（M1，G1 记下的那条存活变种）与 →0.02（M2，往严也抓）、
    内角 8→5（M3）、600→300（M4，就是 F6 认下来的那条）、4→5（M5）。
    仍然存活的只剩 M6：`PdfExporter` 里 `write >= PdfSizeFit.MAX_ATTEMPTS` 改成 `>` 全绿——
    缩边循环住在 `PdfExporter`、`PdfSizeFit` 只暴露单轮算术，要钉它得把整轮循环搬成可注入的纯函数，
    那是另一次边界改动，不在这一批。
  - 字面量那条要讲清它买到的是什么：拦得住「顺手改数字忘了改文档」的漂移，拦不住「这个值本身选错」，
    所以 README「已知边界」里原来那句「600 以下就不是扫描件没有断言守着」已改写成这个口径。
  - 一次差点误判成回归：补完用例后整轮门禁 5 例 instrumentation 失败（`ScanActivityFlowTest` 四例
    各卡 31s 超时、`ScanActivityCameraHandoffTest` 一例页数 `expected:<2> but was:<3>`）。根因不在代码——
    是前面几轮**单跑变异**留下的设备侧脏会话：`ScanActivity` 起来先恢复上次会话，于是页面落进了恢复出来的
    那份 `ScanSession`，测试自己那份永远等不到页。清空应用数据后 101 例全绿。**教训：变异脚本单跑之后
    必须卸载或 `pm clear` 再跑整轮**，否则下一轮红的是环境。
  - 用例数同步：JVM 71→74（`QuadGeometryTest` 11→13、`PdfSizeFitTest` 24→25），总数 176→179；
    README §1 的分文件计数与 §4「拆开之后 25 条」都跟着改。整轮复跑（同一台 emulator-5554）：
    四项构建 + JVM 74 + instrumentation 105 全绿，release APK 24,301,525 B、AAR 221,963 B 与既有基线一致。
