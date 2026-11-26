# 清衡 · Open Margin 设计规范（Design System v3.0）

> **适用范围**：Android 客户端（Jetpack Compose）、后续 iOS 客户端（SwiftUI）、官网下载页（`zxyzx.me`）、Windows 安装向导与桌面 Web 端。
> **核心用途**：作为全项目唯一视觉与交互标准（Single Source of Truth），供开发者与所有 AI 编程助手（Antigravity / Claude Code / Codex 等）在设计和改版 UI 时强制遵循，彻底消除「想到哪写到哪」导致的反复返工。


## V3 normative contract (P0 infrastructure)

Source: [P0-P4 construction document](https://docs.google.com/document/d/1CSNzBqGUkAUkWruDeP9xDDN7-KZBGY5vA4aIan24lKU/edit), sections 1-8.
The values in this section and the construction document override all V2 values below.
Existing V2 consumers remain unchanged in P0; page migrations happen only in their assigned phases.

| Android role | Web variable | Light | Dark |
| --- | --- | --- | --- |
| surfaceCanvas | --om-surface-canvas | #F6F5F4 | #0B0D0C |
| surfaceCard | --om-surface-card | #FFFFFF | #141716 |
| surfaceElevated | --om-surface-elevated | #F0F2F0 | #1C211F |
| surfaceSheet | --om-surface-sheet | #FFFFFF | #222825 |
| textPrimary | --om-text-primary | #151918 | #ECF2EE |
| textSecondary | --om-text-secondary | #5E605F | #A3B3AB |
| accentPrimary | --om-primary | #24705C | #8FD9BC |
| accentContainer | --om-primary-container | #E8F5EE | #0E5336 |
| semanticIncome | --om-income | #1F7A55 | #6ED0A5 |
| semanticExpense | --om-expense | #B23A3A | #FF8A80 |
| semanticWarning | --om-warning | #8A5A12 | #FBBF24 |
| strokeHairline | --om-stroke-hairline | rgba(21,25,24,0.07) | rgba(255,255,255,0.08) |

Use qingColors, QingRadiusTokens, QingTypeTokens and QingMotionTokens.
Radius: badge 8dp, control 14dp, card 22dp, hero 28dp, pill 999dp (same values in px on Web).
Nested radius = max(8dp, outer radius - padding). Page-specific exceptions only as explicitly defined in section 8.2 of the construction document.
Hero 34sp bold tnum (-0.025em); title 18-22sp semibold (-0.015em); body 15-16sp; secondary 12-13sp medium (+0.015em).
Receipt amount 36sp; all new amounts/percentages use tnum or tabular-nums; new amount floor 14sp, single line. Existing 11sp AutoSizeAmountText floor is a legacy behavior, to be migrated and tested in P1.

Static groups: opaque, zero shadow, 0.5dp hairline. Interactive objects: light 1-2dp shadow with alpha <= 0.06, dark no black shadow and 0.5dp top highlight at 8% white. WalletCardSurface encapsulates these roles.
QingChromeSurface reserves tiers SOLID/TINTED/BLUR/ENHANCED, always solid before P4. No blur, dependencies or animation installed in P0.

| Motion | dampingRatio | stiffness | Web |
| --- | --- | --- | --- |
| press | 1.0 | 900 | 100ms cubic-bezier(0.22,1,0.36,1) |
| standard | 1.0 | 400 | 220ms cubic-bezier(0.22,1,0.36,1) |
| gesture | 0.82 | 360 | 500ms cubic-bezier(0.32,0.72,0,1) |
| snappy | 1.0 | 600 | 160ms cubic-bezier(0.22,1,0.36,1) |

Press scales 0.97 cards / 0.985 rows. P2 wires reduced motion, haptics and interruptibility. No decorative animation on high-frequency actions. P3 must retain explicit route transition protections until locally replaced and verified.
Main card padding >=18dp, hero 20dp, row vertical 12-14dp, card gap 14-16dp; Web max width 1120px.
Only defined hero gradients are allowed (#FFFFFF to #F4FAF7, #17211E to #121716); do not add them until P1.
P3 subscription palette: #1B4D3E, #1E2226, #23303D, #332D29, #263830; white/light text >=7:1, 0.5dp 10% white highlight.
Never use individual transaction frames, random badge colors, thick shadows, unnecessary centering or glass content.
Preserve five tabs, VoiceFab state machine, UI test tags, translations, business data flow and existing high-frequency action steps. No new runtime dependencies.
For exact per-page pixel values, all seven redlines and device acceptance gates, the linked construction document is mandatory.
P0 cannot be accepted without baseline screenshots, two-device performance and all regressions passing.

## V2 baseline reference (not normative for migrated pages)

The existing implementation notes below document the pre-P1 behavior. When a value conflicts with the V3 contract or construction document, V3 takes precedence. Preserve nonvisual behavior, category palette and safety/accessibility contracts.

---

## 1. 品牌气质与设计原则

1. **克制清爽，数据优先**：浅暖灰画布（`#F6F5F4`）承载纯白卡片（`#FFFFFF`），以清衡青绿（`#24705C`）作为全端唯一品牌主色。**关闭 Material You 动态取色**（`dynamicColor = false`），拒绝蓝紫 SaaS 渐变、大面积荧光底色和厚重阴影。
2. **金额永不裁切**：账本中金额是第一优先级信息，宁可动态缩小字号（最低 `11sp`），**绝不允许出现省略号 `...` 或右侧截断**。
3. **单源真相，复用优先**：色彩、分类配色、账单行、汇总卡、状态标签、日期时间选择器、空态卡片均已有标准组件，新功能必须复用，禁止另画一套相似样式。
4. **就地闭环，不绑架导航**：局部明细（如某日账单、原图核对、待确认截图）优先当页弹窗/抽屉完成，退出回到原位；不无故跨 Tab 跳转把用户困在筛选态里。
5. **浅深双色与中英双语一等公民**：任何页面不得硬编码浅色常量或裸中文，必须在 `360dp` 窄屏、系统深色模式和英文界面下均无遮挡、无断字、无溢出。

---

## 2. 色彩系统（Design Tokens）

**硬规则**：业务代码**严禁硬编码 `Color(0x...)`**，必须使用 `MaterialTheme.colorScheme`、`Color.kt` 暴露的主题感知属性或 `LocalQingSemanticColors.current`。

### 2.1 品牌与中性色

| 语义角色 | 浅色模式 (Light) | 深色模式 (Dark) | 代码引用 | 用途 |
| --- | --- | --- | --- | --- |
| 主色 Primary | `#24705C` | `#8FD9BC` | `QingPrimary` / `colorScheme.primary` | 主按钮、选中态、品牌强调 |
| 主色容器 | `#E8F5EE` | `#0E5336` | `QingPrimaryContainer` | 汇总卡片底色、正向状态底色 |
| 主色深字 | `#0E5336` | `#E8F0EB` | `QingPrimaryDeep` / `onPrimaryContainer` | 主色容器内标题、图表选中气泡底 |
| 薄荷辅色 | `#97E0B9` / `#DCF0E7` | `#97E0B9` / `#25302B` | `QingMint` / `QingMintSoft` | 柱状图默认柱色、次级浅绿容器 |
| 页面画布 | `#F6F5F4` | `#101412` | `QingBackground` / `colorScheme.background` | 全局页面背景 |
| 卡片表面 | `#FFFFFF` | `#18201D` | `QingSurface` / `colorScheme.surface` | 标准卡片、列表容器、弹窗 |
| 弱容器底色 | `#F0F2F0` | `#25302B` | `QingSurfaceVariant` | 卡片内嵌套子块、中性徽章底色 |
| 主正文 | `#151918` | `#E8F0EB` | `QingText` / `colorScheme.onSurface` | 商户名、标题、正文 |
| 次正文 | `#5E605F` | `#A9BDB3` | `QingTextSecondary` / `onSurfaceVariant` | 副标题、时间、说明文字 |
| 描边 / 分割线 | `#E2E5E2` | `#3A4A43` | `QingOutline` / `outlineVariant` | `1.dp` 卡片边框、列表分割线 |
| 输入框描边 | `#7E8C84` | `#71837A` | `colorScheme.outline` | 输入框等交互控件边框（满足 3:1 对比度） |

### 2.2 财务与状态语义色（唯一映射：`StatusTone`）

| 状态 | 浅色前景 / 底色 | 深色前景 / 底色 | 代码引用 | 使用场景 |
| --- | --- | --- | --- | --- |
| **收入 / 正向** | `#1F7A55` / `#E8F5EE` | `#97E0B9` / `#0E5336` | `semantic.income` / `StatusTone.POSITIVE` | 收入金额、已就绪、同步正常 |
| **支出 / 错误** | `#B23A3A` / `#FDECEC` | `#FF8A80` / `#4A1A18` | `semantic.expenseText` / `StatusTone.ERROR` | 支出金额、删除操作、授权阻断 |
| **警告 / 待确认** | `#8A5A12` / `#FFF4DE` | `#F0C36A` / `#4A3510` | `semantic.warningText` / `StatusTone.WARNING` | 待确认重复、同步冲突、时间待确认 |
| **中性 / 未开启** | `#5E605F` / `#F0F2F0` | `#A9BDB3` / `#25302B` | `StatusTone.NEUTRAL` | 来源标签（扫描/导入/通知/手动）、未开启 |

> **红线**：
> - 严禁把 `tertiaryContainer`（薄荷绿）当警告色使用。
> - 严禁用大面积纯黄/纯红铺满整张卡片；警告类卡片统一用 **纯白底（`surface`） + 轻琥珀边框（`warningText.copy(alpha = 0.28f)`） + 顶部 `SoftBadge` 药丸标签**（参考 `DuplicateReviewCard`）。
> - 严禁在界面中用 `🟢 / 🔴 / ⚪` emoji 充当状态指示点，必须使用 `StatusDot(tone)`。

### 2.3 15 分类单源色盘与图标体系（`CategoryColors.kt` + `CategoryVisuals.kt`）

饼图扇区、图例色块、账单列表图标、日历抽屉图标、预算页图标**必须共用同一份 `CATEGORY_PALETTE` 和 `categoryVisual(category)`**，不得各页单独配色（受 `CategoryColorPaletteTest` 最小两两色差门禁保护）。

- **底色生成公式**：浅色模式用 `accent.copy(alpha = 0.14f)` 做底色、`accent` 做图标色；深色模式先 `lerp(accent, Color.White, 0.30f)` 提亮，再用 `0.18f` 做底色。
- **固定 15 色映射**：
  - `FOOD 餐饮` `#E8590C` (`Restaurant`) · `EDUCATION 教育` `#74B816` (`School`) · `BONUS 奖金` `#FAB005` (`Payments`)
  - `SALARY 工资` `#2F9E44` (`Payments`) · `UTILITIES 水电` `#0CA678` (`Bolt`) · `INVESTMENT 理财` `#1098AD` (`Payments`)
  - `TRANSPORT 交通` `#1C7ED6` (`DirectionsSubway`) · `INSURANCE 保险` `#4263EB` (`MedicalServices`) · `HOUSING 住房` `#7048E8` (`Home`)
  - `SHOPPING 购物` `#AE3EC9` (`ShoppingBag`) · `ENTERTAINMENT 娱乐` `#E64980` (`SportsEsports`) · `GIFT 红包` `#F03E3E` (`Payments`)
  - `HEALTH 医疗` `#8C6D3F` (`MedicalServices`) · `TRANSFER 转账` `#343A40` (`SwapHoriz`) · `OTHER 其他` `#868E96` (`MoreHoriz`)

---

## 3. 字体层级、图标风格与金额排版

### 3.1 字体角色（`Type.kt` / `QingType`）

| 角色 | 样式 | 字号 / 行高 / 字重 | 使用场景 |
| --- | --- | --- | --- |
| 页面大标题 | `QingType.pageTitle` (`headlineSmall`) | `24sp / 30sp / Bold` | 所有主 Tab 与子页顶部标题（**跨页必须统一为 24sp Bold**） |
| 核心主 KPI | `QingType.primaryKpi` (`headlineMedium`) | `28sp / 34sp / Bold` | 详情页主金额、统计页本月总支出 |
| 次级 KPI / 列表主文 | `QingType.secondaryKpi` (`titleMedium`) | `16sp / 22sp / SemiBold` | 商户名、列表金额、汇总卡金额 |
| 分组 / 行标题 | `titleSmall` | `14sp / 20sp / SemiBold` | 设置项标题、卡片小标题 |
| 标准正文 | `bodyMedium` | `14sp / 20sp / Normal` | 弹窗说明、表单内容 |
| 辅助副文 | `bodySmall` | `12sp / 17sp / Normal` | 账单副行、设置副标题、提示语 |
| 标签 / 指标名 | `labelMedium` / `QingType.metricLabel` (`labelSmall`) | `12sp / 11sp / Medium` | `StatusChip`、`SoftBadge`、汇总卡指标名 |

### 3.2 金额排版铁律
1. **统一使用 `AutoSizeAmountText`**：按字符数初判 + `onTextLayout` 动态检测宽度溢出逐级缩字号（保底 `11sp`），**禁止使用 `TextOverflow.Ellipsis`**。
2. **多列汇总防溢出**：三列汇总卡各自 `Modifier.weight(1f)`，内部 `AutoSizeAmountText` 必须带 `Modifier.fillMaxWidth()`。
3. **符号与外币**：收入用 `+`，支出与负结余用标准减号 `−`（U+2212，不用半角 `-`）；外币保留原币种前缀（如 `USD 12.00`），不混入 CNY 汇总。

### 3.3 图标风格统一
- 业务内容区（分类、列表、空态、操作行）统一优先使用 **`Icons.Outlined.*`**；
- 底部导航栏选中态、警示实心标识可使用 `Icons.Filled.*`；
- 禁止在同一卡片里混用多种描边粗细的图标或用彩色 Emoji 替代矢量图标。

---

## 4. 圆角、间距、安全区与响应式布局

### 4.1 圆角体系（`QingShapes`）
- `6.dp`（`extraSmall`）：`SoftBadge` 微标签（`扫描 / 导入 / 通知`）
- `10.dp`（`small`）：轻量行项、日历单元格、预览条
- `12–14.dp`：按钮（`Button` / `OutlinedButton`）、嵌套子卡片、横幅
- `16–20.dp`（`medium` / `large`）：页面主卡片、汇总卡片、`SettingsGroupCard`、`DuplicateReviewCard`、`LedgerEmptyState`
- `28.dp`（`extraLarge`）：底部弹窗（`ModalBottomSheet`）顶角
- `50%`（`RoundedCornerShape(50)`）：`StatusChip` 药丸标签、筛选 Chip

### 4.2 间距与边框
- **页面左右安全边距**：统一 `16.dp`。
- **卡片间距**：纵向列表卡片间隔 `10–12.dp`。
- **卡片内边距**：标准 `14–16.dp`；设置分组卡片横向 `16.dp`、纵向 `4.dp`（由行内 `vertical = 12.dp` 撑开）。
- **边框与阴影**：默认 `1.dp` 描边（`outlineVariant`）+ `0.dp` 阴影，靠明度与细描边建立层次。

### 4.3 安全区、挖孔屏与触控目标（高频踩坑红线）
- **独立全屏子页**（账单详情、编辑页、导入页、诊断页、向导页等）：
  - 若由 `RootNavigation` 的外层 `Scaffold(innerPadding)` 托管（已消费状态栏安全区），子页自身 `Scaffold` / `TopAppBar` 应传 `WindowInsets(0, 0, 0, 0)` 以防出现双重状态栏留白；
  - 若脱离外层 `Scaffold` 独立展示（如向导全屏页），自定义顶栏**必须加 `.statusBarsPadding()`**，或保留 `TopAppBar` 默认 `windowInsets`，防止被状态栏和前置摄像头打孔遮挡。
- **底部弹窗（`ModalBottomSheet`）与键盘避让**：
  - 凡含文本输入框或表单的 `ModalBottomSheet`，**必须**配置 `rememberModalBottomSheetState(skipPartiallyExpanded = true)`，防止弹出时半展开截断内容；
  - 弹窗内部主 `Column` **必须**同时加 `.verticalScroll(rememberScrollState())` 与 `.imePadding()`（以及 `.navigationBarsPadding()`），确保软键盘弹出时输入框自动顶起、不被遮挡。
- **底部安全区**：底部操作栏必须处理 `.navigationBarsPadding()`，含输入框的页面必须处理 `.imePadding()`。
- **最小触控面积与无障碍**：可点击图标按钮触控区域不小于 `48.dp`；自绘图表（饼图、柱状图）必须补充 `Modifier.semantics { contentDescription = ... }`。
- **360dp 窄屏与大字号适配**：避免一行硬塞 4 个以上等宽数值列；筛选 Chip 组与汇总标签优先用 `FlowRow` 或横向滚动。

---

## 5. 五大主界面与底部导航契约

- **底部导航栏（`RootNavigation.kt`）**：固定 5 个 Tab——`账单 / 日历 / 统计 / 预算 / 设置`，标签固定 `maxLines = 1` 不折行。
- **账单页（Ledger）**：顶部品牌/操作栏 → 状态胶囊 → 更新横幅（如有）→ 权限/待确认/待确认重复卡片 → `MonthlySummaryCard` → 搜索框与筛选 Chip → 按日分组的 `DayCard`（内部每行统一用 `LedgerTransactionRow`）→ 右下角三手势 `VoiceFab`。
- **日历页（Calendar）**：月份切换头 → 月度三列汇总 → 7 列热力日历格（绿/红分位底色，选中深青绿实心胶囊）→ 底部可展开区间抽屉（明细行**强制复用 `LedgerTransactionRow`**，禁止裸露机器备注）。
- **统计页（Stats）**：月份选择器 → 月度概览卡片（本月总支出 / 日均 / 结余 / 较上月）→ 分类占比环形图 + 可点击图例列表 → 每日趋势柱状图（点击「查看某日账单」**当页弹出 `DayDetailDialog`**，不跳账单 Tab）。
- **预算页（Budget）**：月度总预算进度卡（金额走 `AutoSizeAmountText`）→ 预算指标采用 2×2 网格而非 4 列挤压 → 删除预算必须二次确认。
- **设置页（Settings）**：顶部可折叠「采集与同步状态」总览卡 → 4 组 `SettingsGroupCard`（自动记账与权限 / 账本与导入导出 / 同步与备份 / 关于与反馈），每行标题和副标题均单行省略。

---

## 6. 数据可视化与图表规范

### 6.1 分类占比环形图（`CategoryPieChart.kt`）
- **形态**：纯环形（无环外引线、无环外文字），环宽 `16.dp`，相邻扇区留 `2°` 细缝（极小扇区保留 `0.6°` 最小可见弧度），画布留 `4.dp` 外边距防裁边。
- **选中交互**：点击扇区（容差 `10.dp`）或点击下方图例行联动选中；选中扇区**内半径不动、外半径平滑外扩 `7.dp`**（`200ms` 动画）。
- **环心文案**：占内圈直径 `84%`，显示选中分类的 `AutoSizeAmountText` 金额 + `分类名 · 占比%`。

### 6.2 每日趋势柱状图（`DailyBarChart.kt`）
- **形态**：高度 `190.dp`，圆角柱体；默认柱色 `QingMint`，选中柱色 `QingPrimary`。
- **默认态与气泡**：默认自动选中当月支出最高日；顶部悬浮深绿气泡（`QingPrimaryDeep` 底 + `QingSurface` 白字）展示 `X月X日 ¥金额`。

---

## 7. 标准复用组件清单（写 UI 前必查）

| 组件 | 路径 | 核心约束 |
| --- | --- | --- |
| `LedgerTransactionRow` | `ui/screen/transactions/LedgerTransactionRow.kt` | 主页、日历抽屉、截图导入统一复用。左侧 `42.dp` `MerchantAvatar` + 商户名（主行）+ 来源 `SoftBadge` 与 `分类 · 账户/来源 · 时间`（副行）+ 右侧 `AutoSizeAmountText`。**严禁在商户名旁拼接 `· 转账 / · 退款 / · 已确认` 等冗余后缀**。 |
| `MonthlySummaryCard` | `ui/components/SummaryCard.kt` | 青绿浅底 `QingPrimaryContainer` + `1.dp` 边框，三列等宽 `AutoSizeAmountText`。 |
| `AutoSizeAmountText` | `ui/components/AutoSizeAmountText.kt` | 所有金额唯一入口，单行自适应缩放，永不用省略号。 |
| `StatusChip` / `SoftBadge` / `StatusDot` | `ui/components/StatusChip.kt` | 统一走 `StatusTone`，禁止页面内手写状态色块或 Emoji。 |
| `LedgerEmptyState` | `ui/components/EmptyState.kt` | **左对齐紧凑卡片**（非居中大空白插画），让上方筛选栏保持视觉锚点，必带恢复操作按钮。 |
| `SettingsGroupCard` & `Settings*Row` | `ui/screen/settings/SettingsComponents.kt` | 标题/副标题单行省略，右侧徽章与箭头右缘对齐。 |
| `LedgerDateTimePickerDialog` | `ui/components/LedgerDateTimePickerDialog.kt` | 全局统一自研滚轮日期时间选择器，禁止退回系统原生弹窗。 |
| `SiteUpdateBanner` | `ui/components/SiteUpdateBanner.kt` | 首页新版本提示，提供明确三动作（忽略此版本 / 下次再提醒 / 立即更新）。 |
| `VoiceFab` | `ui/components/VoiceFab.kt` | 单击手记、双击批量导截图、长按离线语音。 |

---

## 8. 交互、空态/加载/错误态与文案规范

### 8.1 按钮层级与弹窗规则
- **主按钮**：青绿实心 `Button`（保存、确认导入、合并为一笔、立即更新），高 `40–48.dp`，圆角 `12–14.dp`。
- **次按钮**：`OutlinedButton`（`1.dp` `outlineVariant` 边框），用于保留两笔、取消、导出。
- **危险按钮**：`errorContainer` 浅红底 + 红字，或红字描边（删除、清空、关闭远程同步）；清空/批量删除必须二次确认。
- **并排对称**：同行多按钮等宽（`Modifier.weight(1f)`）、等高、等圆角，文字 `maxLines = 1`。
- **弹窗切 Tab 清状态**：切换子标签（如待确认 ↔ 已忽略）必须清空残留的瞬态提示 `message`，并同步刷新标题。

### 8.2 三态规范（空态 / 加载态 / 错误态）
- **空态**：使用 `LedgerEmptyState` 左对齐紧凑卡片，明确区分「无筛选结果 / 本月无账单 / 账本为空」，给出一步动作。
- **加载态**：列表/汇总使用占位骨架卡（如 `LoadingMonthlySummaryCard`），避免整页白屏大转圈。
- **错误态**：必须用人话说明原因并附可执行动作（重试 / 去设置 / 导出排障日志），禁止静默吞错或只弹裸异常栈。

### 8.3 机器备注与权限文案
- 面向用户的账单行（首页、日历抽屉、诊断页）**禁止裸露 `[导入] 微信账单 第X行 · 单号...` 机器前缀**；通过 `CalendarNote.kt` / `ledgerTransactionSourceTag` 提炼为来源徽章与干净的手工备注。
- 系统权限向导与设置项文案**必须与 `strings.xml` 中的系统服务全称 100% 一致**，并写清系统菜单路径与开关名称。

---

## 9. 国际化与防溢出规范

1. **全量 `tr(...)` 包裹**：所有可见中文字符串必须包 `tr("...")`，并同步在 `EnglishUiText.kt` 补齐完全一致的 Key（禁止重复 Key）。
2. **单行防断字**：行标题、Tab、Chip、按钮统一 `maxLines = 1` + `TextOverflow.Ellipsis`，禁止中文断成单字折行（如「双向同」+「步」）或英文撑爆布局。

---

## 10. 跨端视觉统一规范（Web / Windows / 未来 iOS）

- **全端统一清衡青绿体系**：
  - Android 客户端（`#24705C` / `#F6F5F4`）与官网下载页（`zxyzx.me`，`#1B5E43` / `#F6F7F4`）已对齐。
  - Wallos desktop extension (ui-unified.css) already uses #24705C / #F6F5F4. Windows setup remains outside P0; do not introduce new blue/purple brand surfaces.
- **Web 金额排版**：所有 KPI 与账单金额样式必须开启 `font-variant-numeric: tabular-nums` 保证数字纵向对齐。

---

## 11. 自动化门禁测试与 AI 交付自检清单

### 11.1 仓库内置 UI 门禁测试（改动相关区域必过）
- `CategoryColorPaletteTest`：锁定 15 分类颜色完整性与最小两两色差。
- `AmountTextSizingTest`：锁定金额自适应缩字号分档与 `11sp` 下限。
- `TranslationCoverageTest` & `EnglishUiTextTest`：锁定 `tr(...)` 与 `EnglishUiText.kt` 全覆盖无重复键。
- `CalendarAdaptiveLayoutTest` & `CalendarNumberDisplayTest`：锁定日历分屏高度与数字显示。
- `StatsPieChartTest` & `StatsAggregationTest`：锁定环形图几何与统计口径。

### 11.2 AI 协作交付前自检清单
每次让 AI 助手（Antigravity / Claude Code / Codex 等）修改界面时，请声明「**严格遵循 `docs/DESIGN_SYSTEM.md`**」，提交前逐项核对：

- [ ] **色彩与深色模式**：无硬编码 `Color(0x...)`，深色模式可读，无大面积刺眼黄/红底卡片，无 Emoji 状态点。
- [ ] **分类单源**：分类色与图标均来自 `categoryColor` / `categoryVisual`，未另写配色表。
- [ ] **金额安全**：金额统一走 `AutoSizeAmountText`，大额（如 `-¥999,999.99`）无截断无省略号。
- [ ] **挖孔屏与安全区**：独立子页顶栏有 `.statusBarsPadding()` 或默认 `TopAppBar` insets，底栏有 `.navigationBarsPadding()`。
- [ ] **组件复用**：账单行复用 `LedgerTransactionRow`（无 `· 转账` 等冗余后缀），时间选择复用 `LedgerDateTimePickerDialog`，空态复用 `LedgerEmptyState`。
- [ ] **图表与交互**：环形图无环外引线、局部明细不跨 Tab 跳转、按钮等宽对称、切 Tab 清空残留提示、机器备注不裸露。
- [ ] **双语与窄屏**：新文案均包 `tr(...)` 并补 `EnglishUiText.kt`，`360dp` 窄屏与英文模式下无断字溢出。
