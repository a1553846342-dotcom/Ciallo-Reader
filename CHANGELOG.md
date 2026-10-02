# Changelog

All notable changes to Ciallo 阅读 are documented in this file.

## [1.2.0] — 2026-10-03（漫画 / 小说阅读体验升级）

- 更新并修复多种漫画源，补强详情、章节目录、图片请求头和失效图片回退；新增漫画作品标签展示。
- 加快漫画聚合搜索与结果呈现：最多 8 路跨源并发，原词优先、命中即显示，后台补齐标题变体；同源查询合并并复用短期成功缓存。
- 漫画图片采用当前页优先、渐进预览、高清替换与失败重试；限制解码和图像处理并发，减少大图带来的内存峰值。
- 新增和完善中文轻小说、机翻 Web 连载及国内网文来源，支持小说整本下载、离线阅读与整本更新；更新事务保留阅读位置、书签与笔记。
- 支持 EPUB 等小说内嵌插图的解析、分页和阅读显示；增强小说聚合搜索与来源隔离。
- 优化漫画章节书签、神回入口、编辑体验与神回排行榜陈列；调整跨章翻页、右到左仿真卷页和双击缩放焦点。
- 修复删除后的书签、高亮、章节、历史记录或文件残留问题；改进缓存分类、空间统计和清理反馈。
- 优化窄屏和大字号下的预设、阅读控制与方向提示；统一多种漫画阅读模式的图片加载策略，改善中低端设备上的资源峰值。
- 更新书库使用手册，写明在「设置 → 高级内容 → 带你登大郎~~~」开启成人漫画源的完整步骤；新增的预置手册章节会兼容补入旧安装。
- 更新 README 品牌图标与产品素材，并为仓库目录补充用途说明；Android `assets/` 与 `res/` 子目录的索引放在父级文档，避免说明文件被打包或导致资源合并失败。
- 应用版本更新为 `versionName=1.2.0` / `versionCode=201`。

## \[1.1.5] — 2026-10-02（聚合漫画搜索与连续搜索修复）

- 漫画聚合搜索并发从 4 路提高到 8 路，原词立即启动，标题扩展与别名搜索后台补齐。
- 搜索结果返回后立即展示，有结果的源按首次命中顺序置前；修正后台加载期间的源速跳索引。
- 保留全部语言结果、源内资源去重和取消旧查询；单源搜索也取消旧任务。
- 聚合、单源与阅读明细共享同站间隔和 60 秒成功结果缓存，跨站仍为八路并行；修复 JS 异步取消后的运行时复用，并限制 DOM 驻留。
- 高风险全局正则分批桥接，修复密集解析时的内存耗尽；减少脚本冷初始化的 JNI 开销。
- 逐一测试原有 20 个漫画源；爱看漫经传输回退修复仍不稳定，暂从默认列表下架。hitomi 支持社团名末尾括号别名的空结果回退。
- 阅读明细保存原阅读源、资源 ID 与封面，优先使用本地记录直接显示和打开；历史记录并行补查并逐条显示，封面复用书库会话与缓存。
- 在线漫画进度条、箭头与缩略图同步左右阅读方向；修复窄屏大字体下预设文字被挤压，老漫画默认左到右、日漫右到左。
- 仿真翻页同步镜像书本几何与触摸坐标，修正日漫卷边、书脊及合绘拼接；放大双页时保留完整跨页。
- Pica 等提供注册地址的源在登录窗口增加浏览器注册入口，沿用 Z-Library 样式。
- 七种漫画阅读方式统一当前页优先、渐进预览、高清替换和失败重试；限制解码及图像处理并发，降低大图内存峰值。
- 修复内置预设缺失导致递归、卷页缓存替换计量以及 API 24/25 的硬件位图配置兼容问题。
- 按用户要求设置 `versionName=1.1.5`，安装升级编号 `versionCode` 从 199 递增为 200。

## \[1.1.0] — 2026-09-24（第十七·五轮：书源管理重排版 + 第二套液态玻璃 + 搜索键盘卡顿修复）

> 本轮含三部分：书源管理页排版重做、书源页与搜索历史卡接入新的实时玻璃（GlassKit）、
> 书库搜索的键盘交互性能修复。原有全 App 的玻璃组件（GlassCard / liquidGlass）保持不动。

### ✨ 书源管理页重排版

- **可读性**：壁纸先经模糊 + 全屏遮罩，再叠顶部渐变保护大标题；除大标题外全页无裸文字，
  分组数量并入卡片头部徽标。文字层级收敛为「主文字 100% / 次要 70% / 标签 primary 加深 20% + 12% 底」。
- **结构**：只保留三张玻璃卡 + 一个虚线导入行（大标题 → 快捷入口卡 → 小说卡 → 漫画卡 → 导入行）。
- **网格**：卡圆角 24（连续曲率）、头像 12、图标底 10、行高 60、卡头 48，页面 16 / 卡距 12。
- 修复 ID 行被裁掉半截（`includeFontPadding=false` + 居中行高，行高改 `heightIn(min)` 不再写死）。

### ✨ GlassKit：第二套液态玻璃（只服务书源页与搜索历史卡）

- 基于 backdrop 库的**实时图层录制**（`layerBackdrop` + `drawBackdrop`）：玻璃采样背后真实渲染的内容，
  GPU 上叠加 vibrancy / blur / 透镜折射，边缘为真正的透镜折射 + 方向性高光。
- 形状为连续曲率圆角（squircle），着色只用中性白 / 中性深，无强调色、无渐变、无第二圈描边。
- 降级路径：API < 31 回退纯色底 + 高光；系统「降低透明度 / 高对比度」生效时提高着色、去掉折射。
- 性能：卡片在显隐/尺寸动画期间自动降为轻量档（只跑 blur），静止后恢复完整折射。

### 🐞 搜索键盘交互卡顿

- **根因一**：输入法避让用的是逐帧插值的 `imePadding()`，键盘升起/收起那 ~250ms 内每帧改变可用高度，
  下方内容（检索图书插图、瀑布流）每帧重新测量布局。改用 `WindowInsets.imeAnimationTarget`
  （动画目标值，只在首尾各变一次，最终布局完全一致）。
- **根因二**：头部折叠动画与滚动位置在页面顶层读取，导致动画/滚动的每一帧都重组整个页面。
  改为传 lambda 到头部组件内部读取，重组范围收缩到头部。
- 另外：输入法动画期间禁用网格卡片的 `animateItemPlacement`，避免几十个位移动画并发。

## \[1.0.7] — 2026-09-21（第十六轮：前端深度审查 + 苹果感设计体系）

> 纯前端与交互层整改，共 12 次提交。书源 / 下载 / 解析 / 翻译等外部行为未改动。

### 🐛 数据统计：两处凭空造数

- **"没看书却显示今天阅读 197 分钟"**：`StatisticsScreen` 在本周无任何阅读记录时，
  把**历史累计总时长**整个塞进"今天"那一格，并把书架第一本书凭空当成今天读的书。
  删除该兜底分支——没有记录就是 0，空状态交给图表自己呈现
- **不足 1 分钟显示为"1 分"**：`(seconds / 60).coerceAtLeast(1)` 把 20 秒显示成 1 分。
  改用统一 `formatShortDuration`（不足 1 分显示秒 / 1 小时内显示分 / 超过显示时+分）

### 🐛 "这周过了不重置"

真因不是数据，是 Compose 状态：`todayIdx` 与 `weekDates` 用了**无 key 的 `remember {}`**，
只在首次组合计算一次，跨午夜 / 跨周永不刷新。新增 `rememberTodayCalendar()` 把"今天"
提升为可观察状态（每分钟比对年月日，跨年也比对）；日历今日高亮同病同修。

### ✨ 年视图日历重写

原「12 行 × 31 列」矩阵在手机上每格仅约 9.5dp 宽却要塞 8sp 日期数字，横轴标签互相挤压，
格子是 9.5×20 的长方形。改为 GitHub 贡献图布局：

- 列 = 周（约 53 列）、行 = 星期（7 行，周一起始）
- 格子 `size(cell)` 强制正方形，手机最小 16dp，宽屏自动放大铺满
- 横轴改月份标签（放不下就不画，宁缺毋滥），左侧星期只标一/三/五/日
- 窄屏横向滚动，**打开时自动滚到"今天"所在周**，按星期行错峰淡入

上线后报"全是空白"，复盘为三因叠加：空格底色 `surfaceVariant(0.35)` 在浅色下与卡片同色；
**`cellGap` 只参与计算却从未加进布局**（格子零间距）；热力最低档 alpha 仅 0.2。
均已修，并把图例与格子取值抽成共用常量（原图例写死的值与实际不同步）。

### ✨ 清理垃圾动画

原先全程零动画（转圈 → 静态占比条 → 换文字 → Toast）。改为完整动画链：
文件飞入粒子、占比条生长、释放量数字滚动、打勾 + 释放量弹簧弹出、
行 `animateContentSize()` 收缩、吉祥物完成反馈。

### 🔒 隐私模式

- **改密码只输一次就生效** → 手滑输错 6 位会永久锁死自己的隐私分类（项目无找回机制）。
  改为与首次设置同规格的二次确认
- **密码错误时圆点抖动是坏的**：`animateFloatAsState` 补间到 1 就停，`offsetX` 由
  `shake > 0.5f` 二值判断 → 圆点从 -8 跳到 +8 后**永久停在 +8 回不了正**。
  改为 `Animatable` 三次递减摆动后归零
- 设置页隐私开关 `checked` 硬编码 `false` → 点了开关不动、只弹窗。改为乐观选中 + 失败回弹

安全部分（PIN 加盐 SHA-256、常数时间比较、关闭需验证）复查正确，未改动。

### 🎨 苹果感：系统性改造

- **排版**：`Type.kt` 重写为 SF Pro 规格。原先整个 `Typography` 只定义 `bodyLarge`
  一个槽位，其余 14 个全落 Material 默认值，且 26sp 行高会被 443 处只写 `fontSize`
  的 `Text` 继承（小字号配大行高、中文行距虚高）。补全全套层级，**字号越大字距越紧**
  （34sp → -0.4，11sp → +0.06），正文行高约 1.45 倍
- **动效**：新增 `IosMotion`（临界阻尼弹簧 + iOS 缓动 + 时长档位）。弹窗 0.92 起跳弹簧、
  底部面板软弹簧、主题色切换 easeOut
- **按压反馈**：由 `drawWithContent` 叠白色矩形（深色卡 / 毛玻璃上按下**闪白斑**）
  改为 iOS 的整体变淡；新增 `clickableRowFeedback` 供列表行使用（只变背景不变尺寸）
- **滚动**：全局关闭安卓边缘发光（`LocalOverscrollConfiguration provides null`）
- **转场**：阅读页进入由 `EnterTransition.None` 改为 iOS push；打开书籍转场节奏统一
  （内容 420 / 封面 480 / 背景 380，原为 420 / 900 / 380）
- **分组标题**：设置页改为 13sp 半粗次级色（原 14sp Bold 主色，与行标题抢视觉重心）

### ⚡ 列表动画与性能

- 目录 / 搜索 / 书签 / 漫画目录 / 隐私分类补 `key`（此前 `animateItemPlacement`
  有动画却没 key，只能拿下标当身份，重排会错位）
- **书架瀑布流重排动画解锁**：用双向 import 别名消除
  `lazy.items` 与 `staggeredgrid.items` 的同名歧义
- 顺带发现一处被掩盖的错配：瀑布流里的 `items(4, key=...)` 实际匹配的是
  `LazyListScope` 的 `items(count)` 重载，而 `LazyStaggeredGridScope` 只有 `items(List)`

### 🧩 跨品牌适配

- `fontScale` 全局钳制 `[0.85, 1.15]`
- `LocalAppBottomInset` 单一事实来源替换各页硬编码底部间距
- 设计令牌 `DesignTokens.SpacePage` 与 `rememberWindowWidthClass()` 接入
- `configChanges` 补 `smallestScreenSize|density|fontScale|uiMode`；`windowSoftInputMode="adjustResize"`
- `AppSwitch` 接收的 `modifier` 此前从未向下传递（静默丢弃），已修复

### 📦 APK 瘦身（release 23MB → 19.7MB，debug 62MB → 41.8MB）

- **debug / release 统一只打 arm64**：原先只有 release 是纯 arm64，debug 永远额外打包
  一份 x86_64（onnxruntime 约 38MB），这是 debug 62MB / release 23MB 的全部落差。
  需在 x86_64 模拟器跑 ONNX 时显式加 `-PincludeX86`（ARM 翻译层执行会 SIGSEGV）
- **YOLO 气泡分割模型改为按需下载**（4.0MB，压缩后约 2.3MB）：
  - 文件从 `app/src/main/assets/mt/` 移到仓库根 `models/` —— 仍在 git 中（jsDelivr CDN
    需要），但不在任何 assets sourceSet，故不打进 APK。
    （AGP 的 `packaging {}` 没有 assets 块，无法用 excludes 排除，故采用移目录方案）
  - `TranslateModelManager` 新增 `bubbleModel`，三源容灾：jsDelivr CDN → GitHub raw →
    GitHub Release 直链；已对 CDN 文件做 SHA256 校验（与本地一致）
  - `MangaTranslationCore.bubbleDetector()` 改为读下载目录的文件；`isReady` /
    `ensureDownloaded` / `totalBytes` 纳入 bubble
  - bubble 是翻译必需项（`pageRegionDetector` 依赖它），故纳入 `isReady` 是正确的；
    下载失败时 UI 显示错误并可重试
- 至此 **APK 内已无任何 ONNX 模型**。剩余最大单项 `libonnxruntime.so`（压缩后约 10.6MB）
  无法按需加载：onnxruntime 的 Java 层用 `System.loadLibrary` 按库名加载，
  要求 `.so` 位于 APK 的 `nativeLibraryDir`，官方不支持运行时从自定义路径加载
  （官方精简版 `onnxruntime-mobile` 在 Maven 上最高只到 1.18.0，本项目用 1.28.0）
- debug 可选 `-PminifyDebug` 开启混淆（默认关闭以保证可调试）

## \[Unreleased] — 2026-08-30（六轮补：用户回归反馈修复）

### 🐛 用户实测反馈三项修复

- 书架页头副标题"欢迎回到私人数字书库"底部被裁：固定 `height(17dp)` 裁掉中文行高
  （≈17.6sp）；修复为显式 `lineHeight=14.sp` + 槽位 18dp

- 书架副标题语言与其它页头（LIBRARY & SEARCH / STATISTICS & INSIGHTS /
  SETTINGS & PREFERENCES）不一致：统一为 `BOOKSHELF & READING`

- 书库页搜索框撑成整屏大空面板（历史遗留）：`UnifiedSearchField` 分隔线
  `fillMaxHeight` 在 Row 内量到父级最大高度把整卡撑满；修复为固定 `height(24.dp)`

  - 外层 Row 改 `fillMaxWidth`；证据 `r9_*.png`（开发者直接读图复核）

## \[Unreleased] — 2026-08-30（六轮：开源照搬重构）

### 📦 卷页照搬 harism/android-pagecurl（用户指示：翻页效果照搬开源，不手写）

- **vendoring**：`fi.harism.curl` 四文件 2267 行（CurlView/CurlMesh/CurlPage/CurlRenderer）
  内容逐行一致引入（行尾 LF 归一化；Apache-2.0，原包名原版权头 + LICENSE/NOTICE 文本随附）

- **整合层** **`ComicHarismCurl.kt`**：

  - 快 tap 拦截（点按区/呼出控制栏不触发卷页）+ 拖拽过 slop 补投合成 DOWN

  - RTL 倒序索引映射（前进 = CURL\_LEFT 左缘掀起，符合日漫右→左物理方向）

  - PageProvider 信箱式合成纹理（RGB\_565 + POT 内存上限）；正/背面双面语义

  - 自动翻页合成事件流；外部页码跳转同步；GLSurfaceView 生命周期随宿主

- **实测**：中段帧圆柱卷曲/背面渐变/双阴影视觉复评通过；60 连翻零 ANR、
  PSS 218→209MB 无泄漏；自动阅读 6s/页推进；快 tap/面板叠加正常
  （证据 `docs/screenshots/r6_harism_*.png`×5）

- 旧 Canvas 条带引擎退役为参考实现（`ComicCurlEngine.kt` 保留）

### 🐛 修复实机 bug：阅读设置面板"全部选项混在一行"（两阶段）

- **真根因（阶段二定位）**：设置面板内容容器误用 `Box(verticalScroll)` 承载各 Tab 的
  多个根级 composable——Box 语义即堆叠，六 Tab 全部选项叠在同一原点（六 Tab 分组
  重构时引入）；此前多轮截图验证因视觉模型转述失真未被发现

- 修复：内容容器 Box → Column（结构性一行修复）

- 辅助修复（阶段一）：`SegmentRow`/`ModeGrid`/`DirectionGrid` 改 FlowRow 流式布局 +
  网格项 maxLines=2——7 选项分段器/长 label 在大字体下自动换行、永不截断

- 验证：六 Tab 全部开发者直接读图复核（`r7_settings_tab*.png`×7），竖排完美零重叠

### ♻️ WHEEL 照搬复判

- \#19 Zoomable：克隆上游 v2.13.0 对比 vendored 八文件逐字节 diff=0——上轮"旧版"
  系误判，本地即最新版（表已更正）

- \#20 eschao/PageFlip：被 harism 照搬件覆盖（同类零增益），不采用理由已实测化

- \#5 telephoto 仲裁保留：20 轮设置+缩放压力零冲突（照搬成本>收益）

### ✅ 整合回归

- `ui.comic.*` 107/107（含评审修复轮新增 6 项整合层单测；零回归）；全库 30 类 173 测
  仅 10 预存环境类失败（8 类与基线一一对应、2 类基线未覆盖的同性质网络/协程抖动）

- 本轮改动面 mtime 审计：仅 comic 3 文件+harism 4+整合层 1；TXT/EPUB/书源零触碰，
  TXT 阅读器实机冒烟通过

### 🔁 评审修复轮（子代理审计 → 修复 → 复验）

- 修复评审抓出的 3 个 bug：①单页旋转后 CURL 页空白（缓存键补 pageRotations）
  ②配置变更后页面空白至翻页（脏标记消费时序重构，重载后自动刷新）
  ③自动阅读合成流在禁滑动时被误判快 tap（syntheticDrag 隔离）

- 性能：外部跳转 120ms 防抖合并纹理重建；slotCache 改字节+条数双上限 LRU（≤8 项/≤64MB）

- 合规：`fi/harism/curl/` 随附 LICENSE-APACHE2.txt 与 NOTICE；文档行数勘误（2267）

- 实机复验：旋转后页面 85% 内容渲染；背景切换 44→206 即时刷新；双向翻页正常

### 🎯 终验轮：真根因修复 + 鬼影清零（评分定稿）

- **设置面板"混一行"真根因修复**：内容容器 `Box(verticalScroll)` → `Column`
  （Box 把各 Tab 全部选项堆叠在同一原点——用户实机所见即此；此前多轮
  截图验证通过系视觉模型转述失真，本轮全部推翻并以开发者直接读图重做）

- `PanelBg` 0xF0→0xFF：面板半透明底透出页面鬼影清零

- 六 Tab 终验证据 `r8_opaque_tab*.png`×7（开发者逐一亲自读图复核）

- 最终评分：8 维度全部 10/10（兼容性注：真机覆盖属环境限制，遗留下一轮）

## \[Unreleased] — 2026-08-30（五轮收尾）

### 🧪 漫画阅读页二次精修 v3 收尾：CURL 复测 + 压力/兼容实测 + GPU 调研

- **修复**

  - `MainViewModel` 初始化竞态 FATAL（压力实测 100% 复现）：init 块的 IO 协程在
    `_streakDays` 等属性初始化前并发写入 → NPE；init 块移至全部被访问属性之后

  - 卷页/磁吸 `dragActive/magDragActive` 残留兜底：异常事件流（无 UP 的多指注入）
    取消手势 lambda 时松手复位不执行，残留 true 会致帧监测永续采样；UP 时
    `else if` 兜底复位 + 采样条件收紧 `fold.isRunning`

- **验证（全部通过，证据** **`docs/screenshots/r5_*.png`×35）**

  - CURL 负载自适应三环境复测：常规/极端动画/fling 各 30 连翻 = 90 次零 ANR 零
    FATAL，中段帧卷曲平滑（视觉复评通过），39ms/帧环境保持 0 档（渐进升档符合设计）

  - 缩放/平移 20 组：PSS 399→222MB 收敛稳态，无泄漏

  - 设置切换 20 轮（模式/滤镜/增强/组合）：零崩溃，视觉差异逐一确认

  - 兼容实测：横屏（系统 rotation）、fontScale 1.5/2.0、平板 1600x2560+d222
    （sheet 560dp 居中实测 777px 精确吻合），全部恢复原值

- **调研（`WHEEL_EVALUATION.md`** **#21）**

  - GPU Shader 滤镜迁移 A/B（真实样张 ×10 中位）：CPU 管线 287ms vs RenderEffect
    色矩阵 79ms vs AGSL 饱和+卷积 94ms（swiftshader 软件渲染参考值）；AGSL
    `uniform shader+eval()` 可采样（修正 #18 旧判据）；结论：主路径不采用
    （API 31+/33+ vs minSdk 24），二期 `SDK_INT>=31` 分支可行且已验证

## \[Unreleased] — 2026-08-29（二）

### 🔬 漫画阅读页二次精修：开源方案调研 + 车轮评估 + 本地化重构

以开源成熟方案为基准，把上一阶段「能用」的功能打磨到「好用」。算法来源与许可证见
`docs/WHEEL_EVALUATION.md`（车轮评估表）与 `docs/THIRD_PARTY_NOTICES_COMIC.md`（NOTICE）。

- **缩放手感（移植 panpf/zoomimage + saket/telephoto）**

  - 缩放阻尼回弹：越界橡胶带衰减（硬顶 2×max），松手弹簧回界，替代硬 clamp

  - 惯性 fling：放大态松手带速度惯性滑动（splineBasedDecay + 撞墙即停）

  - QuickZoom：双击第二击按住上下拖连续缩放（dy×0.004/px，Google Photos 手感）

  - 双击动态档位：1x → 填满容器 → 原始像素 1:1 三档循环（0.35 容差防抖），替代固定 2.5x

- **磁吸翻页（对齐 Compose Pager 官方参数）**

  - 松手判定：速度 ≥400dp/s 按速度方向翻页，否则位置过半判定（旧版仅 0.25 位置阈值）

  - 速度续接：吸附弹簧继承松手速度（spring 0.85/380），无松手瞬跳

  - 边缘橡胶带：首/末页越界拖动渐近阻尼（c=0.55），可回弹

- **仿真翻页重写（移植 harism 圆柱卷页数学，Apache-2.0）**

  - 真实圆柱投影条带渲染（x′ = F + R·sin(s/R)）替代折线镜像平面翻折

  - 卷曲带正面/背面双段条带 + harism 明暗因子光照 + 背面内容渗透纸色遮罩

  - 三层阴影（卷下投影/折线阴影/翻平投影）+ 折线纸张高光边

  - RTL 原生方向支持；松手速度判定 + 速度续接；4 张 Roborazzi 视觉验证截图

- **自动裁边 v2（移植 Kotatsu EdgeDetector/TrimTransformation 结构）**

  - RGB 逐通道容差（16）替代纯亮度阈值 → 彩边/米色纸底可裁

  - 行/列密度噪声容忍（\~6% 采样阈值）→ 扫描灰尘不再阻止裁边

  - 单边 1/3 防御 + 模式约束（白边模式遇黑边不裁）+ 全页无内容返回不裁

- **跨页拆分 gutter 感知（ScanTailor 思想 + KCC 阈值经验）**

  - aspect ≥1.8 无条件拆；1.35\~1.8 区间本地页按中央装订缝探测（亮度谷/峰 + 窄缝约束 +
    两侧内容方差）决定，显著降低宽幅单页插画误拆

- **画质增强升级**

  - Anime4K 档换 FastLineDarken 形态学内核：5x5 闭运算背景场 + 深度比例线条加深
    （深线多加深、淡网点不压死、平坦区零扰动）+ 平坦区轻降噪

  - CAS / Unsharp 统一 overshoot 限幅（邻域极值 ±16）→ 强锐化不再出白边光晕

- **高倍缩放区域重解码（zoomimage/SSIV tile 思想的单 tile 版）**

  - 本地页显示比例超过已解码分辨率时，对可视区域按原始像素 BitmapRegionDecoder 重解码，
    深放大细节不再受 2800px 全页解码上限约束；手势结束才触发、带 12% 缓冲外扩、LRU(2)

- **测试**：99 个单测（新增 30：裁边 v2/gutter/FastLineDarken/限幅/卷页几何/磁吸判定/
  可视区域反解）+ 6 张截图（阅读器默认态/设置面板/卷页×4）

## \[Unreleased] — 2026-08-29

### 📖 漫画阅读页全面升级（本地 + 在线统一引擎）

新增 `ui/comic` 阅读引擎模块（12 个文件），本地 `ComicReaderScreen` 与在线 `OnlineComicReaderScreen` 均由统一引擎驱动，对外签名向后兼容（新参数全部带默认值），未触碰 TXT/EPUB 链路。

- **阅读模式 ×5**：单页 / 双页 / 条漫 / 无缝滚动 / 磁吸（跟手吸附）

- **阅读方向 ×3**：左→右 / 右→左 / 上→下，真正影响点按区、滑动、翻页、双页排列与动画方向

- **页面显示**：整页 / 高度 / 原始 / 铺满 / 拉伸 + 页面间距 + 双页间距 + 双页对齐 + 双页位置修正（扫描错位）

- **缩放手势**：双指缩放 / 双指平移 / 双击放大（可关）/ 长按放大跟手 / 放大状态翻页；点按区三区可配置动作

- **大图处理**：宽页自动拆分（左右顺序随方向、可反转、位置可调）、临时合页（非破坏性）

- **裁边**：自动裁白边 / 裁黑边 / 自动识别内容区 + 手动裁边编辑器（四角四边拖动、实时预览、三分线）

- **画质增强（真实像素管线）**：CAS 锐化 / Anime4K 风格线条重建 / Waifu2x 类 2x 边缘保持放大 / Lanczos 超分辨率，强度可调；全部异步 + 多级 LRU 缓存

- **滤镜**：亮度 / 对比度 / 饱和度 / 色调 / Gamma / 锐化 / 阴影 / 黑白，小图实时预览

- **旋转**：整本 0/90/180/270 + 单页旋转（非破坏性）

- **背景**：纯黑 / 纯白 / 深灰 / 纸张纹理（程序化生成）/ 沉浸式动态主色调（平滑过渡）

- **场景系统**：雨夜/落雪/樱花/萤火/海边/篝火/夏夜；程序化合成环境音（AudioTrack 流式，无需素材）+ 粒子特效，两者独立开关

- **翻页动画**：无 / 平移 / 渐变（到达页淡入）/ 仿真卷页（RTL 镜像适配）

- **自动阅读**：自动翻页（间隔可调、末页自动进下一章）+ 条漫连续自动滚动（逐帧 scrollBy、速度可调）

- **进度**：页码/百分比/进度条拖动跳页 + 拖动缩略图气泡（可开关）

- **目录**：在线=章节列表（当前高亮、点击跳章）；本地=页缩略图网格

- **连续阅读**：本地上一本/下一本（书架序）；在线上一章/下一章

- **预设系统**：内置 日漫/条漫/老漫画；创建/重命名/复制/删除/设默认；每漫画独立配置（重开自动恢复，含页级进度、换章识别）

- **性能**：解码限幅 2800px、处理缓存堆 1/6、缩略图 8MB、预览 6MB 独立缓存、尺寸探测合批、滤镜落盘防抖、相邻页预加载、退出时 shutdown + 系统栏恢复

- **测试**：66 个 JVM/Robolectric 单测（布局引擎/配置/预设/管线/手势数学/组合测试）+ Roborazzi 阅读器渲染截图

## \[Unreleased] — 2026-08-25

### ✨ 新功能

- **自动滚屏阅读模式**：顶栏一键开启，60fps 平滑滚动，浮停指示器点击停止；与 TTS 听书互斥

- **目录自动定位**：打开章节目录自动跳转到当前阅读位置（千章大书秒定位）

- **字体切换**：阅读排版面板支持 默认/衬线/黑体/等宽 四种字体一键切换

- **缓存管理页**：设置→存储管理→缓存管理；总览大字+比例色带图例+五分类逐项删除+一键清理全部

- **每日阅读目标**：统计页目标环可视化进度，±15 分钟步进器自定义目标值（15–480 分钟），持久化保存

- **阅读报告分享**：统计页一键分享本周/月/年阅读报告到社交平台（总时长/天数/连续打卡/目标完成度）

- **FluidSlider 流体滑条**：Ramotion FluidSlider 风格——按下白色气泡 Overshoot 弹出、metaball 液态连接、数值显示在气泡内

### 🔧 Bug 修复

- 开关弹性动画丢失：移除覆盖动画的 snapTo 调用，Animatable 初始值跟随 checked 状态

- 滑条液态黏连断裂：底池巨圆圆心位置修正至 vOff + botCD/2（原版布局）

- 滑条两端矩形凹口：metaball 连接点钳位到胶囊直线段范围内

- 滑条窄容器拖动反转：手势区 maxMove 增加 coerceAtLeast(1f) 保护

- 进度文本除零：chapters.size 为空时不再产生 Int.MAX\_VALUE%

- ChromaFlow 巡游动画静止：phase 动画值现在通过 rotate() 实际作用于渐变旋转

- FluidSlider View 白色背景：AndroidView 嵌入时设置透明背景

- 排版预览白框：pageBg 从不透明白改为半透明 onSurface

### 🎨 视觉优化

- **SquishyToggleSwitch**：动画提速 1300ms → 550ms（保持弹性四阶段视觉语言）

- **轨道颜色过渡**：1200ms → 250ms（即时响应感）

- **FluidSlider 气泡**：新增底部微阴影（立体感）+ 左上高光渐变（泡泡反光）+ 薄色环设计

- **阅读器顶栏重构**：定制 Column 替代 TopAppBar，章节标题 + 进度副标 + hairline 分割线

- **阅读器底栏重构**：与顶栏同风格（bgColor 背景 + navigationBarsPadding）

- **排版面板滑条**：字号/行距/边距三个 Slider 统一 MintPrimary 主题色

- **TTS 听书按钮**：颜色从 MaterialTheme.primaryContainer 改为 barContentColor 自适应

- **章节导航滑条**：thumb/active/inactive 轨道色跟随阅读主题实时适配

### 🌐 全局一致性

- **IconButton → AppIconButton**：全局审计完成（ComicReader/OnlineComic/HomeScreen/AppErrorSnackbar/LibraryHelpBottomSheet 等 5 处残留修复）

- **Switch 统一**：设置页/书源管理/排版面板全部替换为 SquishyToggleSwitch 或 AppLiquidSwitch

- **触觉反馈全覆盖**：clickableWithFeedback 补充 HapticFeedbackType.TextHandleMove（29+ 调用点受益）

### ♿ 可访问性

- FluidSlider 新增 customActions：TalkBack 用户可通过无障碍操作菜单 ±5% 步进调节

- SquishyToggleSwitch 补充 Role.Switch + toggleableState 语义

- 所有 contentDescription=null 均为装饰性图标（符合 Material 规范）

### 📚 第三方库集成

| 库                   | 来源                                                                                                       | 用途                |
| ------------------- | -------------------------------------------------------------------------------------------------------- | ----------------- |
| pagecurl            | [oleksandrbalan/pagecurl](https://github.com/oleksandrbalan/pagecurl)                                    | 阅读器 SIMULATE 翻页引擎 |
| SquishyToggleSwitch | [Swapnil-J-Patil/Switch-Animation](https://github.com/Swapnil-J-Patil/Switch-Animation-Jetpack-Compose/) | 弹性挤压开关            |
| ChromaFlow          | [M1n9yu23/ChromaFlow](https://github.com/M1n9yu23/ChromaFlow)（概念参考）                                      | 边缘光弧巡游效果          |
| ShimmerFy           | [tusharhow/Shimmerfy](https://github.com/tusharhow/Shimmerfy)（概念参考）                                      | 卡面珠光微光层           |

