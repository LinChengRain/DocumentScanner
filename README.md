# DocumentScanner · 类 iOS「扫描文稿」的开源安卓文档扫描 App

纯 Java 实现，OpenCV 做文档边缘检测与透视矫正，CameraX 负责取景与拍照，
端到端覆盖「实时识别 → 自动/手动快门 → 四角手动调整 → 滤镜增强 → 多页管理 → PDF/相册导出」。
以 Apache License 2.0 开源，可自由用于学习与商业项目。

```
首页（历史会话）→ 扫描取景（实时绿框 · 快门攒页）→ 裁剪编辑（四角 + 滤镜）→ 页面管理（排序/删除/复制/换滤镜/重拍）→ PDF 预览 · 分享
          ↘ 相册导入        ↘「查看」直接导出 A4 并进 PDF 预览（竖向连排）                        ↘ 存到相册 / 分享（宿主可关）
```

## 1. 环境与构建

| 项 | 版本 |
| --- | --- |
| JDK | 17+（实测 Microsoft OpenJDK 17.0.19） |
| Gradle | 7.6.4（wrapper 已生成，走腾讯镜像） |
| AGP | 4.1.3 |
| compileSdk / targetSdk | 31 |
| minSdk | 24（Android 7.0） |
| OpenCV | 4.10.0（Maven Central 官方 AAR，无需手动 ndk-build；版本被钉住，原因见下） |
| CameraX | 1.1.0（API 31 基线下的最后一档，见下） |

首次使用请确认 `local.properties` 里的 `sdk.dir` 指向本机 Android SDK。命令行构建必须把
`JAVA_HOME` 指到 JDK 17（例：`JAVA_HOME=<你的 jdk-17 路径> ./gradlew :app:assembleDebug`）；
用 JDK 20 跑 wrapper 会在解析 `settings.gradle` 时就炸成 `Unsupported class file major version 64`。

> **AGP 4.1.3 为什么要配 Gradle 7.6.4**：AGP 4.1.x 官方只测到 Gradle 6.x，而 Gradle 6.x 内置的
> Groovy 读不了 JDK 17 的 class（`Unsupported class file major version 61`），本机又没有 JDK ≤ 14。
> 7.6.4 是「JDK 17 能跑 + AGP 4.1.3 能配起来」的最小跨度过渡，属于未受支持组合；
> 若日后补上 JDK 11，把 `distributionUrl` 换回 `gradle-6.5-bin.zip` 即可回到官方支持区间。

> **compileSdk / targetSdk 为什么停在 31**：这是 AGP 4.1.3 能对齐的最高一档——AGP 4.1.x 是
> Android 12（API 31）同期发布的工具链，再往上（33/34）就纯粹是靠 AGP 不校验新平台属性的空档。
> 降下来是有代价的，`checkDebugAarMetadata`（AGP 4.1.3 也会跑这一步）会把越界的 AndroidX 挡在构建期：
> appcompat 1.5 起要 32、camera 1.2 起要 33、recyclerview 1.3 起要 33。所以依赖整体退回 SDK 31 同期
> 的那一档（appcompat 1.4.2 / activity 1.4.0 / core 1.7.0 / recyclerview 1.2.1 / exifinterface 1.3.3 /
> material 1.5.0 / CameraX 1.1.0），只有一处例外：instrumentation 三件套（androidx.test:core 1.5.0、
> runner 1.5.2、ext:junit 1.1.5）不跟着降——它们的 AAR 压根不声明 minCompileSdk，降不降都不影响构建。
> 实测过：三件一起退回 1.4.0 世代，23 个走 `ActivityScenario` 的用例全卡在 `PRE_ON_CREATE`（Android 15
> 真机，crash 缓冲里干干净净，Activity 根本没起来）；而单独退回其中任何一件都复现不了，差别在
> `androidx.test:monitor`——单退一件时它仍被同伴拉到 1.6.1，三件同退才落到 1.4.0。
>
> CameraX 1.1.0 少了 1.3 的 `ResolutionSelector`，取景页改回 `setTargetAspectRatio(RATIO_4_3)`
> （预览、拍照流）与 `setTargetResolution(640x480)`（分析流），三条流仍统一 4:3，
> `ScanActivityFramingTest` 量到的画面居中结果与降级前一致。
>
> 语言级别保持 1.8：构建跑在 JDK 17 上就够了，把 `sourceCompatibility` 提到 17 会让 AGP 4.1.3 的
> D8 在 dex 阶段撞上 major 61，和 OpenCV 4.11 那个坑一模一样。

```bash
./gradlew :app:assembleDebug      # 产物 app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug       # 连接手机后直接安装
./gradlew :app:assembleRelease    # R8 压缩版，用 debug 签名，仅用于本地验证
./gradlew :scanner:assembleRelease # 只出库模块的 AAR（组件能不能脱离 demo 首页构建）
./gradlew :scanner:testDebugUnitTest       # 纯逻辑测试（选区几何 + 原子落盘 + 导出命名 + 请求对象 + 显隐与默认滤镜 + 体积档 + 缩边算术）
./gradlew :scanner:connectedDebugAndroidTest # 真机/模拟器测试（模块侧 101 例）
./gradlew :app:connectedDebugAndroidTest     # 真机/模拟器测试（宿主侧 4 例）
```

`:app:testDebugUnitTest` 从 B3 起就一条用例都不剩（纯逻辑测试跟着源码进了 `:scanner`），命令留着只是为了
别把 CI 的产物步骤弄断——所以单测必须显式点 `:scanner:testDebugUnitTest`，只跑 `:app` 那一份会报绿但零执行。

测试分两层，共 176 例。JVM 单测 71 例全在 `:scanner/src/test`，覆盖不依赖 Android 运行时的几何与文件语义：
`QuadGeometryTest` 11 例、`ExportNamesTest` 7 例（同名会话导出不互相覆盖、
标题里的路径分隔符逃不出导出目录）、`AtomicTextFileTest` 6 例、`ScannerConfigTest` 12 例
（两个显隐开关默认显示、关掉一个不动另一个、关回去能恢复；体积档一次都没调时是出厂那一档、
传 null 退回它、`resetForTesting` 把它一起带回、它不牵动那两个开关；成品上限默认不设、
按 1 KB=1024 B 折算、0 与负数都等同于不设、它不动其余三个旋钮、`resetForTesting` 把它也清零）、
`FilterTypeTest` 3 例
（默认那一档是不改像素的原图、新页面带着它上场、越界序号退回到它）、`ImageBudgetTest` 5 例
（出厂档的四个数就是本次改动前散在各处的常量、任何一档都不许解码得比它自己输出的长边还小、
四档按体积自洽排序、单页峰值内存是长边的平方乘 4、名字读不出退回出厂档）、`PdfPreviewRequestTest` 3 例
（空页列表归一成不可变空表、构造时做防御性拷贝、拒绝 null PDF）、`PdfSizeFitTest` 24 例
（「按体积目标决定下一枪用多长解码边」这套算术，见 §4 的成品上限那一段）。`connectedDebugAndroidTest`
（105 例，模块 101 + 宿主 4）覆盖只有真 Context /
真实 OpenCV / 真实 Activity 才暴露得出来的行为：

- `ScanSessionRestoreTest` 10 例：进程重启后从 `session.json` 恢复、主文件半截时回退 `.bak` 并重新落盘、
  解析中途失败不留半份页面列表、缓存被清时丢页不崩、相对路径在会话目录整体挪动后仍能找到产物。
- `ScanSessionHistoryTest` 18 例：多会话（另起新会话不动上一份、历史按时间倒序、切回去页面不串台）、
  非法/未知会话 id 兜底、删当前会话与删他份会话、复制页（产物一起拷、选区是独立数组、插回原页之后）、
  重拍只作废产物、旧版单会话布局一次性迁移进历史且幂等。
- `PageRendererTest` 4 例：边缘检测能在合成文档上检出四角、三份产物落盘、换滤镜只重写 result 不动 warp、
  回调落在主线程。
- `PageRendererBudgetTest` 3 例：体积档真的落在像素上。四档各渲染一次，3000×2400 的源图必须按
  各档自己的长边出图（源图刻意比最高档大——矫正只缩不放，源图不够大时四档会写出同一个尺寸，断言就成了空话）；
  换档之后的重跑滤镜必须按新档重出 result，但 warp 连字节都不许动（它是当初那次渲染的底片）；
  最低档也不许回头改原始照片——那是唯一的一份源图，一旦按 78 的质量重编码，换回高档也找不回细节。
- `PdfExportTest` 8 例：A4/Letter 纸张方向跟随图片、图片等比内接并居中、跟随图片比例时不留白，
  以及真用 `PdfRenderer` 打开导出的文件核对页数与每页尺寸。
- `PdfExportBudgetTest` 5 例：体积档对合成 PDF 的实际作用，全在一台真机上量出来的字节。
  先钉住图样本身（1700×2400 的确定性噪声图，逐字节重现同一份页面 JPEG），再钉住出厂档写出的 PDF
  仍是本次改动前那个字节数（`2,333,347`）——默认档不许顺手改了所有人已导出的成品；
  然后是四档的单调性（`537,865 < 1,010,445 < 2,333,347 < 4,422,958`，只承诺顺序不承诺倍数，
  因为膨胀多少由框架决定）、每一档都还得是一份 `PdfRenderer` 打得开的 PDF，
  以及档位是起导出时取好随参数传进去的：进度回调里把配置改成最低档，这一份 PDF 的字节一个都不该变。
- `PdfExportTargetTest` 8 例：成品上限（体积目标）对合成 PDF 的实际作用，要证的不是「变小了」，而是三件
  各自会单独坏掉的事——上限够用时一次都不多写（不设上限仍是 2,333,347 B，第一份就装得进去时
  `writes==1`）；上限卡在两档之间时落点是**连续**边长而不是查表（断言它不等于任何一档的解码边，
  字节还落在上限的 0.8~0.97 之间——余量既得管用，也不能一路砍到底白丢清晰度）；上限根本够不着时
  交的是最小那份而不是一个异常（10 KB 的目标一步踩到 600 的下限、两轮写完、`PdfRenderer` 照样打得开）。
  外加「同一上限下三页的边长必须比一页更低」（证明它量的是真实字节，不是按档猜的数）、
  「每轮覆写同一个目标名，目录里不留中间产物」、「上限是起导出时取好传进来的」（与体积档那条同构），
  以及一条专门复现走查翻车的真文档图样：随机粗细「字」构成的两页 A4 设 250,000 B 上限，
  必须三轮内收敛、且边长离下限还远着——先验指数对这类内容大约乐观两倍，四轮打完还在目标外面，
  这一条正是当时没被任何用例抓住的那个洞。
- `ShareFilesTest` 3 例：分享出口。provider 声明过的目录里（导出成品、会话产物）真的能
  `getUriForFile` 换出 URI 并读回内容，声明之外的文件必须拒绝——authority 由模块 manifest 的
  `${applicationId}` 占位符与 `ShareFiles.authority()` 两边各算一份，对不上只有运行期才抓得到。
- `ScannerIntentTest` 6 例（`api/`）：门面产出的 Intent 契约——跳到哪一屏、extra 用什么 key、值对不对。
  这里把 key 写成字面量（`"page_id"` 那批字符串在门面之外没有第二份定义），它们是跨屏 wire format 的锁：
  模块内部改常量名或某处漏传一个 key，编译期都不报错，只有这类断言会当场变红。
- `ScannerSessionTest` 5 例（`api/`）：历史列表按新到旧返回、`open` 移动活动指针而 `startNew` 不碰它、
  未知会话 id 兜底回有效的那一份、`prepare` 在磁盘上留下一份可用会话、删会话只少自己那一行。
- `MainActivityHistoryTest` 4 例（宿主）/ `PageListActivityTest` 5 例 / `PdfPreviewActivityTest` 7 例（模块）：
  三个界面在真实 Activity 里 inflate 出来的结果——历史列表按新到旧排列并显示页数、点一行切回那份会话、
  网格页码与「未确认」角标、预览页两页竖着排成上下两条且指示器跟着滚动、点指示器开合缩略图抽屉、
  删页后 PDF 被重写成少一页且不留临时文件。这两屏另各带显隐用例：默认四个按钮都在，
  宿主只关分享时保存照旧、只关保存时分享照旧，两个都关时页面管理那一行整条收起。
- `PdfPreviewActivityTargetTest` 3 例：上限得真的穿过 PDF 预览页这一屏。删掉一页会重新合成一份 PDF，
  宿主设了 800 KB 时那份重出来的成品必须在 800 KB 之内（缺这一条，起导出那三处里任何一处把上限丢掉
  都能全绿）；同一份改动不设上限时它必须明显更大，否则前一条只是「恰好都小」；
  第三条钉「上限是起屏时取的那一份」——起屏之后把配置翻掉，这一屏的重合成仍按老上限走。
- `CropActivityEdgeTest` 5 例：真机拉起裁剪页、等照片解码完成后按固定尺寸量布局，核对满幅选区的
  四个手柄都留在屏幕内、贴着屏幕边缘那一侧也能按住角点、拖回去时角点停在照片边界而不是被拖进黑边、
  角点始终停在指尖上、「全选整页」四个方向都往里收一点。
- `ScanActivityFlowTest` 4 例：镜头不在自动化范围内，就用「相册导入」这条与快门同源的路径喂合成文档，
  核对一页就绪后仍留在取景页（裁剪页一次都没被拉起来）、连导两页按顺序攒下且各只有一页、
  而「重拍」这一条路必须落回裁剪页并且原位替换、不多出一页，
  「查看」则先补齐再按 A4 导出、带着两页的 id 落到 PDF 预览页（且不顺手弹裁剪页）。
- `CropActivityFilterTest` 2 例：裁剪页那条滤镜条。一页没被挑过滤镜的页面进来，选中的必须是
  「原图」那一格（而且标签真的写着「原图」）；另一例反过来钉——页面自己带着「魔法色彩」上场时
  必须落在那一格，否则「永远选中原图」这种写法也能蒙过第一例。
- `CropActivityBudgetTest` 2 例：宿主给的档位要能真的穿过这一屏。临时把 `ScannerConfig` 调到最低档，
  走真实裁剪页点「使用」，产出的 result 长边必须是那一档的 900（缺了这一条，四屏各自硬编码出厂档
  也能全绿）；另一次一个配置都不改，1600 的照片在 1800 的出厂档上必须仍是 1600——矫正不放大。
- `CropActivityRetakeTest` 1 例：裁剪页上的「重拍」把这一页交回取景页的替换模式（Intent 带着页 id）、
  裁剪页自己让位，而且这一页连同原图与选区都原样留着——作废产物要等新页真的拍下才算。
- `ScanActivityFramingTest` 1 例：取景页的构图布局。真镜头几帧之内就会自动快门，所以进手动模式后
  自己按屏幕尺寸量一遍整棵树（息屏时真实排版不会跑），核对叠加层与预览逐像素对齐（否则边框画歪）、
  一帧 3:4 画面 fitCenter 之后整块留在顶栏与提示条之间的空档里，而且上下留白相等——也就是
  「绿框偏下」这条抱怨本身。
- `ScanActivityCameraHandoffTest` 1 例：两台取景页的相机交接。「重拍」是在用户原来那台之上再开一台，
  而 `ProcessCameraProvider` 是进程单例，所以这里两头都用真实镜头按快门：重拍那台要拍得出来（它抢得到
  相机），原来那台回到前景后也得拍得出来（它没被抢走）；中间还钉住「让位的那台不再持有绑定」。

六个构建目标均已在本机跑通（AGP 4.1.3 / Gradle 7.6.4 / JDK 17 / compileSdk·targetSdk 31）：
`:app:assembleDebug`、`:app:assembleRelease`、`:scanner:assembleRelease`、两个模块的 `testDebugUnitTest`、
两个模块的 `assembleDebugAndroidTest`；105 个 instrumentation
用例已在 Honor PTP-AN00（Android 15 / arm64-v8a）上全绿（2026-09-30 整轮复跑：宿主 4 + 模块 101，
含走真实镜头的三处取景屏用例）。上述断言逐条做过变异验证（把实现改坏，确认对应用例必然失败）：
去掉 `.bak` 回退、边解析边提交页面、去掉可用性校验、去掉选区合法性校验、把主线程提交改回后台直调、
复制页不插回原页之后、复制页与原页共用选区数组、迁移不搬产物目录、会话 id 不避开已存在目录、
历史列表不过滤空会话、导出不做重名编号、固定纸张永远是竖版——每一处都被预期用例抓到。
裁剪页的留白另下了八个毒：照片矩形无视 padding、算完内容区忘记偏移回 padding 起点、左右只扣一侧留白、
布局里的左右 padding 被删、触摸容差缩到比手柄半径还小、命中/拖动自己另算一份矩形、
「全选整页」的 0.4% 内收归零、角点不再夹在照片范围内——每个都由对应的用例抓到，其中
「另算一份矩形」和「不再夹在照片范围内」只有「角点停在指尖」与「拖回边缘」这两例能发现，
说明它们不是冗余用例。
「画面效果默认原图」这一条下三个毒：把新页面的默认档改回增强 → 单测 `aFreshPageStartsOnTheDefaultEffect`
与真机 `aFreshPageOpensOnTheOriginalEffect` 各红一次（后者报 `expected:<ORIGINAL> but was:<ENHANCED>`）、
把越界序号的兜底改回增强 → `anUnreadableOrdinalFallsBackToTheDefault` 红、
滤镜条改成无视页面自带的那一档恒选第一格 → `aPageThatAlreadyPickedAFilterOpensOnIt`
红（`expected:<4> but was:<0>`），这一例正是为了不让前一例被「恒选中原图」蒙过去而存在。
「图片体积档」（F5）下了七个毒，每个都由对应用例当场抓到：把出厂档的四个数改成 `1700/2100/88/1600` →
`ImageBudgetTest` 三例红（出厂值、解码边不许小于输出边、单页内存）＋真机 `PdfExportBudgetTest` 三例红
（页面 JPEG 基线、PDF 字节基线、中途改配置那一例）；导出侧去掉精确缩放、只留 2 的幂次降采样 →
`aBiggerTierWritesABiggerPdf` 红（`TINY 应当比 DRAFT 小`——两档被同一个 2 的幂压到了同一个尺寸，
这正是「把长边做成自由数字就是个假杠杆」的现场证据）；重跑滤镜无视传入的档位恒用出厂档 →
`refilterRewritesTheResultButLeavesTheWarpAlone` 红；导出改成逐页现读配置 →
`theExportUsesTheBudgetItWasHandedNotWhateverIsSetMidway` 红（同时把四档读成一档，单调性那例也跟着红）；
渲染时顺手把原始照片也按档位重编码 → `thePhotoKeepsItsOwnResolutionRegardlessOfTheTier` 红；
`setImageBudget(null)` 不再退回出厂档 → `anUnsetBudgetDoesNotLeakThePreviousOne` 红；
裁剪页硬编码出厂档 → `theScreenRendersAtTheTierTheHostSet` 红而「一次都不调」那一例仍绿，
说明这两例钉的是两个方向，互为补位而不是重复。
「按体积目标缩边」（F6）在 `PdfSizeFit` 上下了十三个毒（M1 有两种下法：永远用先验、永远钳到最浅斜率），
十二个都有当场变红的用例：标定整个失效（永远用先验）→ JVM 8 例红，
真机 `PdfExportTargetTest` 同时红一条——`该三轮内收敛：4`，
正是 release 走查那次翻车的复现；标定永远钳到最浅斜率 → 5 例红；斜率下限不钳 → 1 例红
（`aFlatOrRisingMeasurementIsClampedToTheShallowestSlope`）；上限不钳 → 1 例红；
「没有可用对也硬算」→ 3 例红（第一枪本该走先验）；`Search` 只记第一枪 → 1 例红
（`theSearchCarriesForwardTheMostRecentPoint`——第三枪若沿用第一、二枪的斜率，用的是过期段落）；
下限停止条件去掉 → 4 例红；余量从 0.9 改成 1.0 → 7 例红；斜率分子写反 → 6 例红；
先验从 2.15 改成 1.0 → 7 例红；「等号不算装得进去」→ 2 例红；算出的边由 floor 改成 ceil → 1 例红。
只有 `MIN_DECODE_EDGE` 600→300 活了下来，而且它是**等价变种**而不是漏网：所有断言都拿这个常量自己比，
所以没有任何一条能钉住「600 以下就不是扫描件」——那一句是产品判断，不是一段能验证的算术，
记在这里是为了别让下一个人把它当成一条被漏掉的用例。
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
门面这一层另下两个毒：`ScannerSession.delete` 改成删「当前那一份」而不是传入的 id，
被 `deleteRemovesOnlyThatSessionFromHistory` 抓到（`expected:<s-1790728351623> but was:<s-1790728351598>`）；
把 `CropActivity.EXTRA_PAGE_ID` 从 `"page_id"` 改名成 `"pageId"`，只有
`ScannerIntentTest.cropIntentCarriesThePageToEdit` 变红（`expected:<p7> but was:<null>`），
同一份改动下 `CropActivityEdgeTest` 5 例全绿——裁剪页自己读写的是同一个常量，模块内一切照常，
这正是要在门面用例里把 key 写成字面量的理由：它锁的是跨屏 wire format，不是模块内部。
反向也记一条边界：门面 `openXxx` 里 `startActivity` 那一半没有自动化覆盖
（从 instrumentation 的 target context 起屏会把真相机拉起来污染后续用例），
被覆盖的是它两端的零件——`xxxIntent` 的契约由 `ScannerIntentTest` 锁，
「起屏之前先动会话状态」由 `ScannerSessionTest` 锁。
这一批（F1–F3）另下五个毒，每个都由对应用例当场抓到：预览列表的布局管理器改回横向 →
`等待超时：两页一起排在列表里`；`btn_finish` 的落点改回页面管理 → `「查看」必须把 PDF 预览页拉起来`；
`PREVIEW_PAPER` 从 A4 改成跟随图片 → `查看必须走 A4`（`expected:<[A4]> but was:<[FOLLOW_IMAGE]>`）；
页面管理让「存到相册」跟着分享开关走 → 关掉保存那例报 `expected:<8> but was:<0>`；
预览页让「分享」跟着保存开关走 → 关掉分享那例报 `expected:<8> but was:<0>`、
关掉保存那例报 `expected:<0> but was:<8>`（`View.VISIBLE` 是 0、`GONE` 是 8）。
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
B5 把起屏路径换成门面之后，又在同一台上装了一次 clean 出的 release 包（24,305,537 B）专门走这些改动过的路口：
首页「开始新扫描」（`startNewScan`）→ 取景自动检出边框并攒页、手动快门再加一页 →「查看（2）」
（`openPages`；这一条记录在 F1/F2 之后有两处变了：「查看」不再进页面管理，
预览页也不是横向翻页，见 §4）→ 点第一页进裁剪（`openCrop`）→ 换「魔法色彩」并使用后
该页的「未确认」角标消失 →
导出 A4 → PDF 预览翻页与缩略图抽屉（`openPdfPreview` + `PdfPreviewRequest`）→ 分享（荣耀 chooser 里
是 `扫描件 2026-09-30 08.45.pdf · 6.75 MB`，`logcat -b crash` 干净）→「另存为…」真的写进
Download（6,745,767 B）。会话相关的两条也各走一次：「添加页面」（`continueScan`）回取景页且不动当前会话，
中途退出之后页数仍是 2；页面菜单的「重拍本页」（`openScanReplacing`）拍完落回裁剪页且原位替换，
标题仍是「第 2 / 2 页」。回首页点历史行是 `openPages(ctx, sessionId)`，切回那份会话列出 2 页；
首页那行「识别引擎 OpenCV 4.10.0」由 `ScannerEngine.version()` 供数，
说明 R8 之后「门面 → `Cv`」这条不暴露 `org.opencv.*` 的路是通的。
F1–F3 之后又在同一台装了一次 clean 的 release 包（24,301,525 B，比 B5 那份小 4 KB——去掉的
`viewpager2` 比新增的代码多）：取景攒到 3 页 →「查看（3）」直接落到 PDF 预览（`共 3 页 · 6.8 MB`、
指示器 `1 / 3`）→ 一次上滑到底，指示器跟着到 `3 / 3`，回落时又回到 `1 / 3` →
点指示器开缩略图抽屉、点第 3 张跳到底、点第 1 张跳回顶（`uiautomator` 量到两条 item 分别在
`[48,338][1216,1793]` 与 `[48,1817][1216,2642]`，第二条是横向 A4，所以两条高度不同）。
显隐那一条走的是宿主真实调用点：临时在 `DocumentScannerApp.onCreate` 里加一句
`ScannerConfig.setSaveActionVisible(false)`（走查完即删），release 装机上页面管理那一行只剩「分享」
并且它自己撑满整行（`btn_save_gallery` 从 `uiautomator` 树里整个消失），
PDF 预览顶栏只剩「删除当前页 + 分享」，分享照旧拉起 chooser（`扫描件 2026-09-30 09.46 (3).pdf · 7.16 MB`），
`logcat -b crash` 全程为空。
「画面效果默认原图」（F4）也在这台上装了 release 包核对：自动快门攒下一页 → 缩略图进页面管理 →
点这页进裁剪，`uiautomator` 量到五格的 `selected` 依次是 原图=true、增强/灰度/黑白/魔法色彩=false；
点「增强」之后翻成 增强=true——默认值没有把选择条钉死。
「图片体积档」（F5）在这台上装了两次 clean 的 release 包做对照，走的是同一条路径：取景自动攒一页 +
相册导入同一张照片凑成 2 页 →「查看（2）」→ 预览页副标题 →「另存为…」真的写进 Download。
第一次临时在 `DocumentScannerApp.onCreate` 注入一句 `ScannerConfig.setImageBudget(ImageBudget.TINY)`
（走查完即删，提交里 `app/` 一个字节都没动），屏幕写着 `共 2 页 · 582.7 KB`，Download 里落的是
`扫描件 2026-09-30 11.51.pdf · 596,702 B`——582.7 KB 就是 596,702/1024，界面上那句体积与文件真实字节对得上；
删掉那一句重装、同样两页再走一遍，副标题 `共 2 页 · 1.5 MB`、文件 `1,544,779 B`。
宿主只改一行，同一份扫描件按 38.6% 落地；`logcat -b crash` 两轮全程为空。
「成品上限」（F6）在这台上装了 clean 的 release 包走查，路径与 F5 那条一样（取景自动攒 1 页 +
相册导入同一张照片凑成 2 页 →「查看（2）」→ 预览副标题 →「另存为…」落 Download），
只是把注入的那一句换成 `ScannerConfig.setMaxPdfSizeKb(500)`（500 KB = 512,000 B，走查完即删，
提交里 `app/` 一个字节都没动）。这一条走查先失败过一次，而失败正是它最值钱的部分：`logcat` 记的是
`909,516 → 降到 1311 → 668,330 → 降到 1102 → 576,395 → 降到 993 → 521,829 B`——四轮（上限）打完
仍在目标外面，屏幕上写着 `共 2 页 · 509.6 KB`，看着像成功，其实超标 9,829 B，而当时
`PdfExportTargetTest` 那七条全绿。根因是先验指数对**真实扫描件**乐观了约两倍：同一条边→字节曲线，
F5 那条噪声图样的斜率是 2.06~2.22，这份真会话量出来只有 0.85、0.96、0.97。改成「第一枪用先验、
之后每一枪用相邻两枪实测出的斜率」再走一次同样两页：`1,094,589 → 降到 1203 → 788,931 → 降到 620 →
312,852 B，共写 3 轮`，副标题 `共 2 页 · 305.5 KB`（312,852/1024），Download 里那个文件真是
312,852 B，`logcat -b crash` 全程为空。成品比上限小四成不是 bug：上限是上限，不是精确值，
而斜率只在实测点附近局部成立，第一枪只能保守。

**为什么把 OpenCV 钉在 4.10.0**：两道坎。① 4.11 起 `classes.jar` 是 Java 17 字节码（major 61），
AGP 4.1 自带的 D8 直接报 `Unsupported class file major version 61`；② 4.12 起的 arm64 构建会按 HWCAP 把 `Mat.convertTo` 这类算子
派发到 SVE 内核（在 `libopencv_java4.so` 里能看到 `cnth x10` / `ptrue p0.s`）。Apple 芯片上的
Android 模拟器会在 HWCAP 与 `/proc/cpuinfo` 里上报 sve2/sme2，却实际执行不了，于是 OpenCV 一跑
就 SIGILL（ILL_ILLOPC）。模拟器镜像只有 arm64-v8a，`-qemu -cpu max,sve=off` 被拒
（`Property '.sve' not found`）、`-no-accel` 被忽略，这个假阳性绕不过去。真机不受影响，
要升级只需改回 `app/build.gradle` 里的版本号并在真机上验证。

**关于下载速度**：依赖仓库的腾讯镜像由 `gradle.properties` 里的 `useChineseMirror` 控制（默认开），
海外环境设为 `false` 或构建时加 `-PuseChineseMirror=false` 即回退 `google()` / `mavenCentral()` 官方源。
`gradle/wrapper/gradle-wrapper.properties` 的 `distributionUrl` 仍默认指向腾讯镜像
（`services.gradle.org` 会 307 跳到 GitHub，国内直连常被限速到 20KB/s 以下），换回官方源只需替换那一行。
CI（`.github/workflows/ci.yml`）不走 wrapper，用 `setup-gradle` 直接装官方源的 7.6.4，并关闭依赖镜像；
compileSdk 降到 31 之后，CI 多了一步「runner 镜像里没有 `platforms/android-31` 就用自带的 sdkmanager 现装」。

**APK 体积**：`ndk.abiFilters` 目前只留 `arm64-v8a`，大头是 OpenCV 的 `libopencv_java4.so`（约 21MB）。
分模块不收口体积，B5 用 `git worktree` 把初始提交 `865b4e9` 重建了一份同名 APK 做逐项对比
（同一台机器、同一套 JDK/Gradle/AGP、`rm -rf app/build` + `--no-build-cache` 各出一次 clean 包）：

| | debug | release（R8 + `shrinkResources`） |
| --- | --- | --- |
| `865b4e9` 单模块 | 28,453,619 B | 24,303,277 B |
| B5 两模块 | 28,476,129 B | 24,305,537 B |
| 差 | +22,510 B（+0.08%） | +2,260 B（+0.01%） |

对比时别拿构建缓存里的产物：加了缓存的 `:app:assembleRelease` 会多出 1,079,414 B 的「非条目字节」
（zip 首个条目之前一段 1,079,290 B 的对齐占位；debug 那侧同理多出 167,965 B 的条目间空隙），
而两边条目本身的压缩后总量只差 1,896 B——一眼看去像分模块涨了 1.08MB，其实是 AGP 两版 zip 写入器的差异，
与模块边界无关。
F5 又踩进这条坑一次：那次量到 release `25,375,669 B`，比 clean 的 F4 那份大 `1,074,144 B`，看着像
「加了个枚举涨了一 MB」；`rm -rf app/build scanner/build` + `--no-build-cache` + 新 daemon 重出之后是
**`24,301,525 B`**，与 F4 那份（也就是 F1–F3 记下的数）逐字节同大小——体积档这一批对成品 APK 零成本，
多出来的一段仍然只是 zip 写入器的对齐占位。同一次 clean 里 debug 是 `28,483,535 B`，
`:scanner` 的 release AAR 是 `218,671 B`。
F6（成品上限）按同一条 clean 路子重出：release 仍是 **`24,301,525 B`**（与 F4/F5 那份逐字节同大小——
新增的缩边算术被 R8 压进了既有的对齐缝隙），debug `28,485,004 B`，
`:scanner` 的 release AAR 从 `218,671 B` 涨到 **`221,935 B`**（+3,264 B：`PdfSizeFit` 全套算术
加 `PdfExporter.Outcome`，这是这批唯一看得见的成本）。debug 那份比 F5 大 1,469 B 而 release 一个字节没动，
是同一份代码的两种命运：debug 留着 javac 的行号表与局部变量表，注释多一行它就漂一点，
R8 把这些剥干净了——**要比体积只能比 release**。
B5 那份 `:scanner` AAR 只有 210,766 B（release）——它不带 OpenCV，`implementation` 依赖是传递坐标，
21MB 那份 native 库由宿主解析后才进 APK。
需要在 Intel 模拟器上跑时再加一个 ABI：

```groovy
ndk { abiFilters 'arm64-v8a', 'x86_64' }
```

## 2. 目录结构

```
:scanner  （com.documentscanner.scanner，com.android.library）—— 扫描功能本体
├── api/                        门面：宿主该碰的那一层（三处例外见 §3）
│   ├── Scanner                 起屏入口（startNewScan / continueScan / openPages / openCrop / …）
│   │                           + 同名 Intent 工厂，便于不启动 Activity 就断言跳转契约
│   ├── ScannerSession          会话：list / open / startNew / delete / activeId / prepare
│   ├── ScannerEngine           识别引擎：version() / warmUp()
│   ├── ScannerConfig           进程级配置：显隐开关（保存 / 分享）+ 图片体积档 + 成品上限（见 §3）
│   └── PdfPreviewRequest       PDF 预览入参（不可变，构造期就把 null PDF 挡掉）
├── cv/                         算法层（全部阻塞调用，只在后台线程执行）
│   ├── Cv                      native 库加载（幂等）
│   ├── CvImage                 Bitmap <-> Mat、YUV 亮度平面提取
│   ├── DocDetector             文档四角检测
│   ├── PerspectiveCorrector    透视变换
│   ├── DocFilter               5 种画面增强
│   ├── QuadGeometry            四边形几何 + 归一化选区
│   ├── ImageBudget             4 档体积：产物长边 / 解码长边 / JPEG 质量 / 导出解码长边
│   ├── DocPipeline             Bitmap 级入口与检测、缩略图常量
│   └── PageRenderer            「确认」后的落盘编排与回调
├── camera/LiveDocAnalyzer      CameraX 逐帧分析：节流、稳定判定
├── model/                      ScanPage / ScanSession（多会话索引 + JSON 落盘 + 历史列表）
├── export/                     PdfExporter / PdfSizeFit（包内，缩边算术）/ PaperSize /
│                               ExportNames / GallerySaver / ShareFiles
├── ui/                         4 个 Activity（取景 / 裁剪 / 页面管理 / PDF 预览）+ widget + adapter
├── util/                       ImageIO（EXIF 烘焙）/ Io（线程调度）/ Ui / AtomicTextFile
└── res/                        scanner_ 前缀的 8 layout + 27 drawable + 101 string
                              + Theme.Scanner(.Camera) + scanner_file_paths.xml

:app  （com.documentscanner）—— 演示宿主，只有首页
├── DocumentScannerApp          启动只做两件事：ScannerEngine.warmUp() + ScannerSession.prepare()
├── ui/MainActivity             历史会话列表 + 「开始新扫描」/「相册导入」入口
├── ui/adapter/SessionAdapter   历史行的适配器（属宿主 UI，不跟扫描屏走）
└── res/                        activity_main / item_session / 启动图标 / Theme.DocumentScanner
```

`cv ↔ model` 之间本来就有环（`PageRenderer` 要写 `ScanPage`，`ScanPage` 的产物路径由 `DocPipeline` 定），
A 方案把两侧收进同一个模块才不用破环；这也是 `PageRenderer` 在 B2 只能暂留宿主、B3 才跟着 `model/`
一起入住的原因。分家的过程记录在 [`docs/scanner-module-plan.md`](docs/scanner-module-plan.md)。

## 3. 作为组件接入

```groovy
// app/build.gradle
android.defaultConfig.ndk { abiFilters 'arm64-v8a' }   // 见下，唯一必须自己做的事
dependencies { implementation project(':scanner') }
```

除此之外宿主什么都不用写。清单合并已经把该带的都带过来了（`merged_manifests/debug/AndroidManifest.xml`
核对过）：`CAMERA` 权限与两条 `uses-feature`、四屏的 `<activity>`（类名已是
`com.documentscanner.scanner.ui.*`）、以及模块自带的 `FileProvider`——它的 authority 写成
`${applicationId}.scanner.fileprovider`，合并进宿主时才展开，所以两个接入本组件的 App 不会互相装不上
（authority 在设备上是全局唯一的，写死必撞车）。四屏的 `android:theme` 也是显式的，宿主的
application theme 不是 Material3 也不会把页面管理和 PDF 预览炸掉。

宿主侧的调用面基本收在 `api/` 四个类上：

```java
ScannerEngine.warmUp();                       // App 启动时把 OpenCV 的 native 库丢到后台加载
ScannerSession.prepare(context);              // 保证磁盘上有一份可用的当前会话

List<ScanSession.Info> history = ScannerSession.list(context);  // 历史会话，新到旧
Scanner.openPages(context, info.sessionId);   // 点一行：切会话再进页面管理
Scanner.startNewScan(context);                // 开一份新会话进取景页（旧会话不动）
Scanner.openPdfPreview(context, new PdfPreviewRequest(pdf, title, pageIds, paper));
```

`openXxx` 与 `xxxIntent` 成对出现：后者只造 Intent，前者是「造好再 start」。
配对的意义在于把「起屏」和「起屏之前该改的状态」绑在一处——
`Scanner.openPages(ctx, sessionId)` 内部是 `ScanSession.open()` + 裸 Intent，
原先这两句散在宿主里，漏掉前半句屏幕就会接着上一份会话写，编译期查不出来。
另有两处 import 落在 `api/` 之外（第三处是下面的 `ImageBudget`），是 `docs/scanner-module-plan.md` §9.3 记下的偏离而不是笔误：
历史列表要引用 `scanner.model.ScanSession` 拿 `Info`（sessionId / title / createdAt / pages / cover，
封面是 `File`），首页适配器用 `scanner.ui.PageImageLoader` 把封面上屏；
页面级的 `ScanPage` 则只在模块内部流转。

不想在自己的 App 里露出「保存」和「分享」的话，还有第五个类管显隐，同一个类上还管体积档与成品上限（都在 `Application` 里调一次就够）：

```java
ScannerConfig.setSaveActionVisible(false);   // 收起页面管理的「存到相册」+ 预览页的「另存为」
ScannerConfig.setShareActionVisible(false);  // 收起页面管理的「分享」+ 预览页的「分享」

ScannerConfig.setImageBudget(ImageBudget.TINY);  // 成品 PDF 的体积档，默认 STANDARD

ScannerConfig.setMaxPdfSizeKb(500);              // 整份 PDF 的体积上限，默认不设
```

四个档各是一次「清晰度换体积」的产品决定，收成一个枚举而不是两个自由数字，理由见 §4 的体积档那一段：

| 档 | 页面产物长边 | 单页峰值内存 | 适用 | 本机实测（同一份 1700×2400 图样 → A4 单页 PDF） |
| --- | --- | --- | --- | --- |
| `TINY` | 900 | 3.2 MB | 邮件正文、微信里发出去能看 | 537,865 B |
| `DRAFT` | 1200 | 5.8 MB | 只在屏幕上读 | 1,010,445 B |
| `STANDARD` | 1800 | 13.0 MB | **默认**，等于本批改动前的行为 | 2,333,347 B |
| `FINE` | 2400 | 23.0 MB | 要打回纸面、要留档 | 4,422,958 B |

默认就是 `STANDARD`，一次都不调的话页面产物与已导出的 PDF 字节数都与本批之前一致
（`PdfExportBudgetTest` 把那份 2,333,347 B 钉成了断言，改默认档会当场红）。
生效范围只到「此后」：此后新渲染的页面、此后合成的每一份 PDF；已经渲染好的页面不会被重写，
已经导出的 PDF 也不会自动重生成——按新档出图就得重新导出一次。
`ImageBudget` 落在 `cv/` 而不是 `api/`，是宿主侧第三处越出 `api/` 的 import（前两处见上），
它只是一个四个数的枚举，门面没有再包一层。

上限（`setMaxPdfSizeKb`）与档位不是一回事，两者是叠着用的：档位选「默认出多大」，上限管「最多多大」——
先按档写一份，超出上限才把像素继续往下缩，缩到装进去为止，所以再宽松的上限也不会出比这一档更大的成品。
它是**整份 PDF** 的上限，不是每页的：模块不知道也不该猜这次导出几页，宿主想要「每页 500KB」
就把上限写成 `500 × 页数`。单位是 KB（1 KB = 1024 B，与界面上那句「共 N 页 · X KB」同一口径），
0 或负数就是不设，不设时导出产物与本功能之前逐字节相同。两条得先知道：上限是**上限**不是精确值，
成品通常比它小一成左右（斜率只在实测点附近局部成立，第一枪只能保守，见 §4）；
目标够不着时（页多、目标小）交的是写得出来的最小那份**而不是报错**——一份超标的扫描件只是大了点，
一份「因为装不进 500KB 所以没生成」的扫描件是丢了。缩到哪一档、写了几轮都不进界面，只在
`PdfExporter` 的日志里。

两个开关各管两个入口，关掉哪一个都只收起哪一个；两个都关掉时页面管理那一行跟着收起，不留空带。
默认全是显示，一次都不调就跟分模块之前的行为一致。关掉只是不给按钮，导出本身照常——
PDF 仍然落在 `getFilesDir()/export/`，宿主想自己接保存也拿得到。

五条接入约束：

- **一个进程一份「当前会话」**。底层 `ScanSession` 是进程单例，`open()`/`startNew()` 会替换它，
  模块内四屏共享同一份。要在同一进程里并行跑两份扫描，得先把单例改成显式句柄——目前没有这个需求。
- **中间产物不是长期存储**。`original/warp/result` 全在 `getCacheDir()/scan_session/` 下，
  系统清理缓存会把历史列表里的每一份一起清掉（清理后不崩、丢页可恢复，有 `ScanSessionRestoreTest` 兜着）；
  真正落得久的是 `getFilesDir()/export/` 里的 PDF 成品。
- **ABI 要宿主自己筛**。`libopencv_java4.so` ≈21MB 跟着模块走，但库模块的 `ndk.abiFilters`
  决定不了最终 APK，所以这一条必须写在宿主里；只要「逐帧检测回调」而不想要 21MB 的场景，
  见 `docs/scanner-module-plan.md` §2 的 C 方案。
- **依赖是被隐藏的**。`:scanner` 对 OpenCV / CameraX / guava 用的是 `implementation`，
  宿主编译期拿不到 `org.opencv.*`、`androidx.camera.*`、`com.google.common.util.concurrent.ListenableFuture`
  （实测过：在宿主里临时写一个引用这三者的类，`compileDebugJavaWithJavac` 报 6 处「程序包不存在/找不到符号」）。
  相应地，`api/` 的签名里也不许出现这些类型——一旦泄漏就得把依赖开成 `api`，OpenCV 4.10.0 的版本约束
  （§1 里那两道坎）会跟着外泄给所有接入方。
- **`ScannerConfig` 是进程级的静态配置（两个显隐开关 + 一个体积档 + 一个成品上限），读的是「起那一屏 / 起那一次导出」时的值**。
  它不进 Intent、不落盘，所以起屏之后再改，已经在那一屏上的按钮与正在渲染的页面不会跟着变；
  同一进程里两处同时起屏也必然读到同一份配置。
  要按入口给不同显隐，目前得自己保证起屏顺序，或者把它改成显式句柄（同上面「当前会话」那条单例的处境）。

## 4. 关键设计

**只在亮度平面上做检测。** `ImageAnalysis` 给的是 YUV_420_888，逐帧做色彩转换是帧率杀手。
`CvImage.grayFrom()` 按 `rowStride/pixelStride` 只拷出 Y 平面，再按 `rotationDegrees`
`Core.rotate` 到「显示方向」，于是检测坐标系与预览画面方向一致，后续换算只需一次等比居中映射。

**三条流统一 4:3 + `PreviewView.ScaleType.FIT_CENTER`。** Preview / ImageCapture 用
`setTargetAspectRatio(AspectRatio.RATIO_4_3)`，分析流用 `setTargetResolution(new Size(640, 480))`
（API 31 基线下 CameraX 停在 1.1.0，1.3 的 `ResolutionSelector` 那套不可用），检测框从分析帧映到预览视图
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

**成品 PDF 的体积由像素数决定，不由页面 JPEG 的字节决定。** `android.graphics.pdf.PdfDocument`
只看到我们画上去的那张位图，它会按接近无损的质量把位图重编码一遍——本机实测同一页从 JPEG 到 PDF 膨胀
2.25~7.5 倍（真实文档 5.6~7.5 倍，确定性噪声图样 2.25 倍），所以「限制图片大小来控住 PDF」这句话里
能动的只有像素数，而字节大致随长边的平方走。`ImageBudget` 因此做成四档（数值与实测见 §3），
一档同时定四个互相牵制的数：产物长边、渲染时解码原图的长边（必须不低于产物长边，否则矫正是在放大）、
页面 JPEG 质量、合成 PDF 时解码页面产物的长边——拆开暴露出去，宿主就配得出「又大又糊」这种谁都没想要的组合。
导出侧那个解码长边必须**精确**缩放，不能只按 2 的幂向下取整：`inSampleSize` 是 floor，
1800 的页面配任何大于 900 的上限都原样通过，「调小」这一步在多数档位上根本不产生作用
（这一条不是推测，`aBiggerTierWritesABiggerPdf` 就是这么被抓红的）。
两个例外是刻意留在档位之外的：`original` 永远按 `DocPipeline.JPEG_QUALITY_ORIGINAL` 存，最低档也不许回头
重编码它——那是唯一的一份源图，糊一次就再也换不回细节；`EDGE_DETECT_SOURCE`(1500) 与缩略图常量归检测精度
和列表流畅管，跟成品体积无关。档位随参数一路传进 `PageRenderer` / `PdfExporter` / `PendingRenderer`，
读数只发生在「起那一屏」与「起那一次导出」，不在页循环里现读——一次动作一个档，
中途有人改配置也不会让同一份 PDF 前半 1800、后半 900。

**成品上限靠「写一份、量一次、按实测斜率缩边再写」，不是一次算到位。** 宿主给的是字节，
而模块唯一动得了的旋钮是像素（上一条），中间的桥是 `bytes ≈ 长边^p`。麻烦在于 `p` 不是常数：
同一台机器上量过的几条曲线，每条都是相邻两枪实测出来的段——F5 那条确定性噪声图样 2.06~2.22，
多尺度「字」的合成文档图样（两页 A4，1800→1278→697）只有 0.73 与 1.45，真机走查那份真会话
（1800→1311→1102→993）是 0.97、0.85、0.96，而单一粗细线条的探针图样 600→1800 整段只算出 0.25、
1500→2400 那一段甚至是负的。固定先验 2.15 对**真实扫描件**乐观约两倍，按它算出的下一枪只缩到该缩的一半，
于是四轮打完还在目标外面——走查那次要 512,000 B，
交出 521,829 B，而当时真机用例全绿（旧的七条只证「变小了」，没证「变到位」）。所以先验只当**第一枪的
起点**（它必须偏大：边只降不升，砍过头没法回头，指数取大一点最多是多写一轮），从第二枪起改用相邻两枪
实测出的斜率 `p = ln(字节比) / ln(边比)`，钳在 0.8~3.0——下限防的就是上面那种「字节几乎不跟着边动」的
共振（单一粗细的线条碰上整数缩放比），上限防的是一次量出个假陡、
结果几乎不缩。三个守卫各由一条断言钉着：目标乘 0.9 留余量（贴线来回磨的话四轮也收敛不完）、
解码边到 600 就停（再小不是扫描件，是带几个灰点的白纸）、最多四轮。只降不升是硬约束，
也是这里唯一不需要额外钳位的原因：页面产物只有渲染档那 `outputEdge` 个像素，把解码边抬到它之上是空操作
（`scaleMaxEdge` 原样返回），多写一轮只会把同一份 PDF 再来一遍。每一轮覆写的是同一个目标名
（调用方给的要么按 `ExportNames.unique` 挑过，要么是 PDF 预览页自己的暂存名），所以目录里不会多出中间产物。
这套算术整个住在包内的 `PdfSizeFit`（连同逐轮拟合的 `Search`）而不是 `PdfExporter`：那一边满手
`android.graphics.*`，纯函数住在里面就只能上真机才能验证，而最容易写错的恰恰就是这几个数——指数取多少、
下限卡在哪、什么时候该停。拆开之后 24 条 JVM 用例能在真机之外复现「四枪怎么收敛」：把走查那次量到的四条
字节喂进去，先验走四轮仍超标、标定后三轮装得进去，两种写法都在 `src/test` 里钉着。

**不申请任何存储权限。** 运行时权限只有 `CAMERA`：
- 导出走 `MediaStore`（Android 10+ 用 `RELATIVE_PATH` + `IS_PENDING`；8.x 写应用外部目录并触发媒体扫描）；
- 相册导入用 `ACTION_GET_CONTENT`，不引入 photo-picker 依赖；
- 分享经 `FileProvider`（模块自带的 `scanner_file_paths.xml` 只暴露 `export/` 与缓存产物目录）。

**EXIF 方向在最上游烘焙一次。** `ImageIO.load()` 解码即转正，下游所有代码都不必再关心方向。

**会话可恢复，而且是多份。** `ScanSession` 给每份会话一个独立目录
`cacheDir/scan_session/<id>/session.json`，当前用哪一份记在同级 `index.json` 里；
页面产物存的是**相对该目录**的路径，所以整个目录挪动、或从旧版单会话布局迁移过来之后仍能找回文件。
「开始新扫描」是另起一份而不是清空上一份，首页的历史列表把有内容的会话按新到旧列出来，
点一行就切回去继续编辑。恢复时丢弃文件已被系统清掉的残缺页面，而不是在后续步骤里崩溃；
未确认裁剪的页面在导出前会按自动识别结果补渲染，所以中途退出不丢页。

**快门只攒页，编辑由用户主动进入。** 拍完一页（手动、自动、相册导入都算）留在取景页，把分析流重新打开，
页面按自动识别的选区先攒着；要微调就点缩略图进页面管理再挑一页，不必每拍一张都被弹出取景页。
「查看」是收尾那一下：先把没确认的页面按自动选区补齐，再按 A4 导出，直接落到 PDF 预览——
纸张在那里不问，是因为那一下要的是立刻看见成品，要挑纸张随时可以走缩略图那条路。
「重拍」是唯一的例外——它本身就是「回去改这一页」这条路径上的一步，所以拍完直接落回裁剪页。
重拍有两个入口（页面管理的菜单、裁剪页面板），走的都是同一条替换路径：带着页 id 回取景页，
位置与原页都不变，产物要等新那一页真的拍下才作废；在此之前退出取景页，原页仍然完好可用。

**PDF 预览竖向连排，页高在绑定时就占好。** 预览页原来是 ViewPager2 一页一屏，现在是一条竖向
RecyclerView：一页接一页从上往下排，长文档滚动而不是逐页翻。两处是踩过才知道要这么写的：
① 每条 item 的高度在绑定时就按 `PdfRenderer.Page` 的宽高比算出来（列表宽 ÷ 宽高比），不等位图解码回来撑——
位图是后台逐页出的，没占位时一条只有 160dp 的兜底高，两页能一起塞进视口，
于是「当前页」在第一帧就报错，用户不动它就一直错下去（真机实测到的第一条红）；
② 页码指示器取「跨过视口中线的那一页」，不取「第一个可见项」——连排时一页可能比屏幕还高，
按第一个可见项算会在半页处来回抖。缩略图抽屉走 `smoothScrollToPosition`，与手滚共用同一条判据。

**PDF 纸张在导出前问一次。** `PaperSize` 三档：A4 / Letter 会把图片等比内接并居中留出 18pt 页边，
页面方向跟随图片（横图配横向纸张，不挤成竖排）；「跟随图片」则长边统一 842pt、页面即图片本身。
问一次的入口是页面管理的「导出 PDF」，取景页的「查看」则固定按 A4（见上一条）。
导出文件名来自会话标题，标题可以重复，因此 `ExportNames.unique()` 会往后找 `xxx (2).pdf`，
后一次导出不会盖掉前一次的成品。

## 5. 五种画面效果

| 名称 | 做法 | 适用 |
| --- | --- | --- |
| 原图 | 仅透视矫正 | 保留现场光影 |
| 增强 | 背景估计（缩放→大核模糊→还原）除法归一化 + 非锐化掩模 | **去阴影**、发灰的纸面 |
| 灰度 | 灰度 + CLAHE 局部对比度 | 光线不均的黑白文档 |
| 黑白 | 自适应阈值 + 形态学开运算去椒盐 | 纯文字、签字、传真 |
| 魔法色彩 | Lab 只增强 L 通道 + 提饱和 | 证件、彩色图表 |

默认落在「原图」：五档里只有它是真的一个像素都不改，其余四档都会把纸面重画一遍，
挑哪一档该由用户点，而不是替他决定。这一档写在 `FilterType.DEFAULT` 一处，
新页面的字段初值、滤镜条的 null 兜底、会话恢复的缺键兜底、序号越界兜底四路都指到它——
所以改默认值会四处一起改，不会留下「新页面是原图、恢复回来的旧页面是增强」这种分裂。
副作用是自动快门攒下的页面结果就等于矫正图（`result` 与 `warp` 同像素），
换滤镜时那一步才第一次真正跑算法。

## 6. 已知边界

- 页面锁定竖屏；相机预览/取景换算按 portrait 假设写死（`ScanActivity.currentDisplayRotation()`）。
- 单次会话最多 20 页；所有会话的中间产物都在 `cacheDir` 下，系统清理缓存或「清理存储」会把
  未导出的会话（含历史列表里的每一份）一起清掉。
- 检测针对「与背景有反差的平整四边形」调参：深色文档放在深色桌面上、或纸面严重褶皱时，
  请用四角手动调整或「全选整页」。
- 桌面版 OpenCV 全模块 AAR，体积大；若只要检测+矫正，可自行裁剪 native 库或用 OpenCV 精简模块。
- 启动图标是矢量（无位图 mipmap），`mipmap-anydpi-v26` 之外另留了一份矢量兜底给 Android 8 以下。
- 体积档是**宿主侧**的，App 自己没有设置界面也不落盘：`ScannerConfig` 是进程级静态，进程一重启就回到
  `STANDARD`。要给用户提供「清晰度/体积」滑块，得宿主自己做设置项、自己存，再在启动时调 `setImageBudget`。
- 体积档只管**此后**的产物：已渲染好的页面不会被重写，已导出的 PDF 也不会自动重生成，换档要重新导出一次。
  同一份会话里混过两档是可能的（先按默认渲染两页、宿主改档后再拍一页），模块不做「整会话重渲染」。
- 成品上限同样只管**此后合成的每一份 PDF**：已导出的那份不会自动重缩，改上限要重新导出一次。它是**上限**
  不是精确值，成品通常比它小一成左右（第一枪只能按先验保守缩）；目标够不着时（页多、目标小）
  交的是最小那份而不是异常，超出多少只写在日志里，界面不会告诉用户「这份其实超标了」。
- 上限是**整份** PDF 的：模块不知道也不该猜这次导出几页，「每页 500KB」得宿主自己乘页数传进来。
- 「最多四轮」和「解码边 600 以下就不再缩」是产品判断，不是能验证的算术：把 `MIN_DECODE_EDGE` 改成 300
  时 24 条 JVM 用例全绿（每条断言都拿这个常量自己比，所以它是**等价变种**），意思是「600 以下就不是
  扫描件」这一句没有断言守着，动它得靠人眼核对清晰度。
- `FINE` 一页的位图约 23 MB（ARGB_8888，`outputEdge² × 4`），低内存机器上多页连拍要留意；
  导出仍是逐页解码即回收，峰值只与单页有关。
- PDF 预览的「当前页」取视口中线那一页，所以整份文档刚好一屏装得下时（短文档 + 高屏），
  列表滚不动，指示器也就一直停在第 1 页——这不是卡住，是确实没有第二页跨过中线。

## 7. 许可与贡献

本项目以 **Apache License 2.0** 开源，全文见 [`LICENSE`](LICENSE)。复用时请保留版权与许可声明；
该版本同时授予专利使用权，适合商业场景。

第三方组件保持各自的许可证，不受本仓库许可影响：OpenCV（Apache 2.0）、AndroidX 与 CameraX、
Material Components（Apache 2.0）、Guava（Apache 2.0）。

欢迎提 Issue 与 Pull Request（仓库带 Issue / PR 模板）。每个 PR 会由 GitHub Actions 跑
`:app:testDebugUnitTest` + `:scanner:testDebugUnitTest`，再出 `:app:assembleDebug` 与
`:scanner:assembleRelease`（后者是组件本体的 AAR，脱离 demo 首页也得能构建）（官方源、JDK 17）。
改动算法或渲染链路时请补对应用例——本仓库的测试是「变异验证」写出来的：
每条断言都要能因对应的实现被改坏而失败，否则它不算是覆盖。纯逻辑放被改代码所在模块的
`src/test`（无需设备），要真实 Context / OpenCV / Activity 的放同侧 `src/androidTest`；
跨模块共用的会话脚手架在 `scanner/src/androidTestShared/java`，两个模块各引同一份源码
（AGP 4.1.3 没有 Android 版 testFixtures，抄两份必然漂移）。
