# UI 视觉精修清单（P4）

> 来源：UI 前端方案 P4 线。每项做完跑 `powershell -File tools\ui-gate.ps1`，
> token 替换后用 `-UpdateBaseline` 收紧基线。约束：不降低任何现有视觉效果。

## 1. 玻璃栈收敛（4 → 2）
- [ ] 全站统计玻璃材质来源：`haze`（1.1.1）、`liquidglass-compose`、`backdrop`、自绘 blur 各用在哪屏
- [ ] 首页底栏 + 书库搜索栏保留主玻璃栈；次级弹层/卡片降级为半透明色 + elevation
- [ ] 性能档（低端机 / 省电模式）确认玻璃自动降级路径仍然生效

## 2. 阴影与描边规则
- [ ] 卡片统一：暗色主题用 `1dp` 描边 + 无阴影，亮色用 `DesignTokens.CardElevation`(2dp)
- [ ] 浮层统一 `DesignTokens.FloatingElevation`(8dp)，禁止再出现随手 4/6/12dp 阴影
- [ ] 检查深色模式下黑底黑阴影不可见的卡片（重点：书架长按浮层、下载管理卡）

## 3. 图标一致性
- [ ] 同语义同形状：关闭=Close、返回=AutoMirrored ArrowBack、拖动柄=DragHandle 全站对齐
- [ ] 图标粗细一致（Filled 单独出现的页面核对是否应为 Outlined）
- [ ] 装饰图标保持 `contentDescription = null`（当前 113 处均为正确用法，勿误改）

## 4. 色彩与对比
- [ ] `MintPrimary` 在暗色背景上的次级文本可读性（4.5:1）抽查
- [ ] `TextSecondary` 类 alpha 文本在玻璃叠底上的对比度抽查
- [ ] 纯色 `Color(0x…)` 259 处按语义归入主题色（token 化时逐批 -UpdateBaseline）

## 5. 排版收敛（当前字面量 513 处）
- [ ] 8 批次替换 fontSize 字面量 → `MaterialTheme.typography`（每批一个功能域）
- [ ] 行高/字距由 style 统一携带，替换时禁止顺手改字号数值

## 6. 形状与间距（radiusLiteral 294 / dpLiteral 2261）
- [ ] `RoundedCornerShape(8/12/16/20/24.dp)` → `DesignTokens.Radius*`
- [ ] 间距字面量 → `DesignTokens.Space*`（先列表容器，后散点）
- [ ] 特殊值（如 14.dp 圆角）要么归入最近档位（视觉 QA），要么留注释说明原因

## 7. 暗色 / 折叠屏边界
- [ ] 状态栏图标对比：透明状态栏 + 各主题下系统栏图标颜色
- [ ] 折叠屏开合（smallestScreenSize 已在 manifest configChanges）后再抽查书架拖拽几何
- [ ] fontScale 1.3x 下底栏 FluidSlider 与标签行不截断
