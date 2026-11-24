# 清衡 · Open Margin 设计规范（Design System）

> **适用范围**：Android 客户端（Jetpack Compose）、后续 iOS 客户端、官网下载页与桌面安装向导。
> **核心用途**：统一视觉与交互语言，并作为所有开发者与 AI 编程助手（Antigravity / Claude Code / Codex 等）改 UI 时的**强制约束**，避免临时发挥与反复返工。

---

## 1. 设计原则与视觉气质

1. **克制清爽，数据优先**：浅暖灰背景（`#F6F5F4`）承载纯白内容卡片（`#FFFFFF`），用清衡青绿（`#24705C`）表达主操作与品牌感。拒绝花哨渐变、厚重阴影、大面积高饱和警告底色。
2. **零信息丢失，绝不截断金额**：账本里金额是最核心的信息，宁可动态缩字号，也**绝不出现省略号或裁切**。
3. **复用优先，禁止重复造轮子**：账单行、汇总卡、状态标签、设置分组、日期时间选择器均已有标准组件，新页面必须直接复用，不得另起炉灶画一套相似样式。
4. **就地闭环，不绑架导航**：看某日明细、改单、核对截图尽量在当前页弹窗/抽屉或独立子页完成，退出回到原位；不无故跨 Tab 跳转把用户困在筛选态里。

---

## 2. 色彩系统（Design Tokens）

**硬规则**：业务页面**严禁硬编码 `Color(0x...)`**，必须使用 `MaterialTheme.colorScheme`、`Color.kt` 暴露的主题感知属性或 `LocalQingSemanticColors.current`，确保浅色/深色模式同步生效。

### 2.1 品牌与中性色

| 语义角色 | 浅色模式 (Light) | 深色模式 (Dark) | 代码引用 | 用途 |
| --- | --- | --- | --- | --- |
| 主色 Primary | `#24705C` | `#8FD9BC` | `QingPrimary` / `colorScheme.primary` | 主按钮、选中态、品牌强调 |
| 主色容器 | `#E8F5EE` | `#0E5336` | `QingPrimaryContainer` | 汇总卡片底色、正向状态底色 |
| 主色深字 | `#0E5336` | `#E8F0EB` | `QingPrimaryDeep` / `onPrimaryContainer` | 主色容器内标题与高亮文字 |
| 页面底色 | `#F6F5F4` | `#101412` | `QingBackground` / `colorScheme.background` | 全局页面画布 |
| 卡片底色 | `#FFFFFF` | `#18201D` | `QingSurface` / `colorScheme.surface` | 标准内容卡片、列表容器、弹窗 |
| 弱容器底色 | `#F0F2F0` | `#25302B` | `QingSurfaceVariant` | 卡片内嵌套子块、中性徽章底色 |
| 主正文 | `#151918` | `#E8F0EB` | `QingText` / `colorScheme.onSurface` | 商户名、标题、主文案 |
| 次正文 | `#5E605F` | `#A9BDB3` | `QingTextSecondary` / `onSurfaceVariant` | 副标题、时间、说明文字 |
| 卡片描边 / 分割线 | `#E2E5E2` | `#3A4A43` | `QingOutline` / `outlineVariant` | `1.dp` 卡片边框、列表分割线 |
| 输入框描边 | `#7E8C84` | `#71837A` | `colorScheme.outline` | 输入框等交互控件边框（满足 3:1 对比度） |

### 2.2 财务与状态语义色

| 状态 | 浅色前景 / 底色 | 深色前景 / 底色 | 代码引用 | 使用场景 |
| --- | --- | --- | --- | --- |
| **收入 / 正向** | `#1F7A55` / `#E8F5EE` | `#97E0B9` / `#0E5336` | `semantic.income` / `StatusTone.POSITIVE` | 收入金额、已就绪、同步正常 |
| **支出 / 错误** | `#B23A3A` / `#FDECEC` | `#FF8A80` / `#4A1A18` | `semantic.expenseText` / `StatusTone.ERROR` | 支出金额、删除操作、授权阻断 |
| **警告 / 待确认** | `#8A5A12` / `#FFF4DE` | `#F0C36A` / `#4A3510` | `semantic.warningText` / `StatusTone.WARNING` | 待确认重复、同步冲突、时间待确认 |
| **中性 / 未开启** | `#5E605F` / `#F0F2F0` | `#A9BDB3` / `#25302B` | `StatusTone.NEUTRAL` | 来源标签（扫描/导入/通知/手动）、未开启 |

> **禁止项**：严禁把 `tertiaryContainer`（薄荷绿）当警告色使用；严禁用大面积纯黄/纯红铺满整张卡片，警告类卡片统一用 **纯白底（`surface`） + 轻琥珀边框（`warningText.copy(alpha = 0.28f)`） + 顶部 `SoftBadge` 药丸标签**（参考 `DuplicateReviewCard`）。

---

## 3. 字体层级与金额排版

定义位置：`android/app/src/main/java/com/bookkeeping/app/ui/theme/Type.kt`

| 角色 | 样式 | 字号 / 行高 / 字重 | 使用场景 |
| --- | --- | --- | --- |
| 页面大标题 | `QingType.pageTitle` (`headlineSmall`) | `24sp / 30sp / Bold` | 各主 Tab 与子页顶部标题 |
| 核心大数字 | `QingType.primaryKpi` (`headlineMedium`) | `28sp / 34sp / Bold` | 详情页主金额、统计页本月总支出 |
| 卡片标题 / 列表主文 | `QingType.secondaryKpi` (`titleMedium`) | `16sp / 22sp / SemiBold` | 商户名、列表金额、汇总卡金额 |
| 分组 / 行标题 | `titleSmall` | `14sp / 20sp / SemiBold` | 设置项标题、卡片小标题 |
| 标准正文 | `bodyMedium` | `14sp / 20sp / Normal` | 弹窗说明、表单内容 |
| 辅助副文 | `bodySmall` | `12sp / 17sp / Normal` | 账单副行、设置副标题、提示语 |
| 标签 / 徽章 | `labelMedium` / `labelSmall` | `12sp / 11sp / Medium` | `StatusChip`、`SoftBadge`、指标小标签 |

### 金额排版铁律
1. **所有可能超过 6 位数的金额展示位**（汇总卡、列表右侧、统计卡、预算卡）**必须使用 `AutoSizeAmountText`**（支持 `onTextLayout` 动态检测宽度溢出并逐级缩字号，保底 `11sp`），**禁止使用 `TextOverflow.Ellipsis`**。
2. 汇总卡三列（支出 / 收入 / 结余）各自使用 `Modifier.weight(1f)`，内部 `AutoSizeAmountText` 必须带 `Modifier.fillMaxWidth()`，避免大额数字向右溢出容器。
3. 符号统一：收入前缀用 `+`，支出与负结余前缀用标准减号 `−`（U+2212），不使用半角连字符 `-`。

---

## 4. 圆角、间距与安全区规范

### 4.1 圆角体系（`QingShapes`）
- `6.dp`（`extraSmall`）：小徽章 `SoftBadge`（`扫描 / 导入 / 通知`）
- `10.dp`（`small`）：轻量行项、预览条
- `12–14.dp`：按钮（`Button` / `OutlinedButton`）、卡片内部嵌套子卡片、横幅
- `16–20.dp`（`medium` / `large`）：标准页面卡片、汇总卡片、`SettingsGroupCard`、`DuplicateReviewCard`
- `28.dp`（`extraLarge`）：底部弹窗（ModalBottomSheet）顶角、大弹窗
- `50%`（`RoundedCornerShape(50)`）：`StatusChip` 药丸标签、筛选 Chip

### 4.2 间距与边框
- **页面左右安全边距**：统一 `16.dp`。
- **卡片间距**：纵向列表卡片间隔 `10–12.dp`。
- **卡片内边距**：标准 `14–16.dp`；设置分组卡片横向 `16.dp`、纵向 `4.dp`（由行内 `vertical = 12.dp` 撑开）。
- **边框与阴影**：默认用 `1.dp` 描边（`outlineVariant`）区分层次，**不滥用大阴影（elevation）**。

### 4.3 安全区与挖孔屏铁律（高频踩坑项）
- **独立全屏子页**（账单详情页、编辑页、导入页、诊断页等）：
  - 自定义顶部栏必须添加 `.statusBarsPadding()`。
  - 使用 `TopAppBar` 时**保留默认 `windowInsets`**，**严禁写 `windowInsets = WindowInsets(0, 0, 0, 0)`**（仅在外部 `Scaffold` 已经处理过状态栏内边距的 Tab 页才允许清零），否则会被状态栏时间和前置摄像头打孔遮挡。
- **底部操作栏**：需适配导航栏手势条（`.navigationBarsPadding()`）与输入法弹起（`.imePadding()`）。

---

## 5. 标准复用组件清单

开发或修改界面前，优先查下表复用已有组件：

| 组件 | 路径 | 规范要点 |
| --- | --- | --- |
| `LedgerTransactionRow` | `ui/screen/transactions/LedgerTransactionRow.kt` | 首页账单、日历抽屉、截图导入结果统一复用。**结构固定为**：左侧 `42.dp` `MerchantAvatar` + 中间商户名（主行）与「来源 `SoftBadge` + 分类 · 账户/来源 · 时间」（副行）+ 右侧 `AutoSizeAmountText`。**严禁在商户名旁拼接 `· 转账 / · 退款 / · 已确认` 等冗余状态后缀**。 |
| `MonthlySummaryCard` | `ui/components/SummaryCard.kt` | 顶部三列汇总卡（支出 / 收入 / 结余），青绿浅底 `QingPrimaryContainer` + `1.dp` 边框，三列等宽且金额自适应缩字号。 |
| `AutoSizeAmountText` | `ui/components/AutoSizeAmountText.kt` | 所有金额展示统一入口，单行不换行、不省略、自动缩字号。 |
| `StatusChip` / `SoftBadge` / `StatusDot` | `ui/components/StatusChip.kt` | 所有状态指示统一传入 `StatusTone`，禁止在页面里手写彩色小框。 |
| `SettingsGroupCard` & `Settings*Row` | `ui/screen/settings/SettingsComponents.kt` | 设置页统一卡片与行样式：行标题与副标题固定 `maxLines = 1` + `Ellipsis`，右侧徽章与箭头右边缘严格对齐。 |
| `LedgerDateTimePickerDialog` | `ui/components/LedgerDateTimePickerDialog.kt` | 所有日期时间选择统一使用自研滚轮选择器，禁止调用系统原生 `DatePickerDialog` / `TimePickerDialog`。 |
| `SiteUpdateBanner` | `ui/components/SiteUpdateBanner.kt` | 首页新版本提示，提供明确三动作（忽略此版本 / 下次再提醒 / 立即更新）。 |
| `VoiceFab` | `ui/components/VoiceFab.kt` | 首页右下角三手势主入口：单击手记、双击批量导截图、长按离线语音。 |

---

## 6. 交互、按钮与弹窗规范

### 6.1 按钮层级与排版
- **主按钮（Primary）**：青绿实心 `Button`，用于保存、确认导入、合并为一笔、立即更新。高度 `40–48.dp`，圆角 `12–14.dp`。
- **次按钮（Secondary）**：`OutlinedButton`（`1.dp` `outlineVariant` 边框），用于保留两笔、取消、导出。
- **危险按钮（Destructive）**：浅红底 `errorContainer` + 红字，或红字描边按钮，用于删除、关闭远程同步、清空。
- **双按钮 / 多按钮并排**：同一行操作按钮尽量等宽（`Modifier.weight(1f)`）、统一高度与圆角，视觉对称；文字强制 `maxLines = 1`。

### 6.2 弹窗与抽屉规范
- **局部明细不跳 Tab**：例如统计页点击某日柱状图，当页弹出 `DayDetailDialog` 查看明细，关闭后留在统计页原位置。
- **筛选态必须有一键退出**：凡进入按日/按分类筛选的列表视图，顶部必须有显眼的一键「全部账单」或「清空筛选」按钮。
- **Tab 切换清状态**：弹窗内部切换子标签（如待确认 ↔ 已忽略）时，必须同步清空上一页残留的瞬态提示 `message`，并同步更新弹窗标题。
- **列表项可预览可清理**：截图审核与已忽略列表的缩略图均需支持点击放大预览原图，并提供单条删除与一键清空（高风险清空需二次确认）。

### 6.3 机器备注与可读性
- 面向用户的列表（日历抽屉、账单列表、诊断页）**禁止直接裸露原始机器前缀**（如 `[导入] 微信账单 第245行 · 单号...`）；统一提炼为来源徽章 + 结构化字段，仅在存在用户手工备注时单独展示。
- 系统权限向导与设置项文案**必须与 `strings.xml` 中的系统服务名称 100% 一致**，并写明「跳转后在哪个菜单找哪个名字、点哪个开关」。

---

## 7. 国际化与防溢出规范

1. **所有界面中文文案必须用 `tr("...")` 包裹**，并在 `EnglishUiText.kt` 补齐完全一致的 Key（不允许重复 Key），确保 `TranslationCoverageTest` 单测通过。
2. **窄屏（360dp）+ 英文模式兼容**：
   - 标题、Tab、Chip、按钮、设置行均需设 `maxLines = 1` 与 `overflow = TextOverflow.Ellipsis`（或使用 `FlowRow` 自动换行），禁止出现中文单字折行（如把「双向同步」折成「双向同」+「步」）或英文撑爆布局。

---

## 8. 交付前自检清单（AI 协作专用）

每次修改或新增界面前，请直接向 AI 助手声明「**严格遵循 `docs/DESIGN_SYSTEM.md`**」，并在提交前逐项核对：

- [ ] **色彩合规**：没有硬编码 `Color(0x...)`，浅色/深色模式与 `StatusTone` 语义正确，无大面积刺眼黄/红底卡片。
- [ ] **金额安全**：所有金额均使用 `AutoSizeAmountText`，测试 `-¥999,999.99` 级别大额无截断、无省略号。
- [ ] **挖孔屏与安全区**：独立子页顶部已处理 `statusBarsPadding()` / 默认 `TopAppBar` insets，不被状态栏或摄像头遮挡。
- [ ] **组件复用**：账单行复用 `LedgerTransactionRow`（无 `· 转账` 等冗余后缀），时间选择复用 `LedgerDateTimePickerDialog`。
- [ ] **交互闭环**：按钮对称等宽、无死胡同跳转、弹窗切 Tab 会清理旧提示语、机器备注不裸露。
- [ ] **文案与翻译**：所有新文案均包裹 `tr(...)` 并已在 `EnglishUiText.kt` 补齐，英文与窄屏下无断字溢出。
