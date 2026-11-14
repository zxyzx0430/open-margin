# 清衡 Android UI 重构与微信支付账单/商户图标提取实施报告

- 日期：2026-11-14
- 状态：调研与实施方案，未修改产品代码
- 关联：当前 `main` 的 rc.8 工作树（未提交）；本报告只新增文档
- 目标：把 Android 端视觉/信息架构重构为参考截图的样子，并评估从微信支付页面提取商户名、金额、商户图标的可行性

## 0. 结论先行

1. **UI 重构可行，适合分 5 个实施批次 + 1 个前置调研批次。** 推荐顺序：设计系统与账单首页 → 统计 → 预算 → 设置 → 微信/支付宝账单辅助导入与商户图标。不要一次改完四个页面。
2. **“微信支付账单列表自动抓取”不能承诺 1:1 自动完成。** 公开开源项目里，成熟做法是：
   - 实时识别**支付成功页**（无障碍 + 离线 OCR + 截图裁剪图标），代表项目 `huayoupu-ledger`；
   - 历史账单用**官方导出的 CSV/XLSX 文件**导入，代表项目 `lizhang`、`wechatpay`、`php-wechat-bill-parser`、`china_bean_importers`；
   - 没有找到可靠的开源项目在后台静默抓取“微信支付”账单列表。
3. **商户名和金额**：技术上可以尝试无障碍文本节点或 OCR；但微信部分支付页会给普通无障碍服务返回空节点，必须真机探针，不能直接假设可读。
4. **商户图标**：无障碍节点只给文本/描述/边界，不给图片像素。可行路径是：
   - 首选：本地商户图标库 + 分类图标兜底（最能保证 1:1 视觉）；
   - 增强：`AccessibilityService.takeScreenshot()` 截图后按图标边界裁剪（API 30+，需 `canTakeScreenshot="true"`）；
   - 备选：读取支付通知的 `EXTRA_LARGE_ICON`（需探针确认微信/支付宝是否提供商户 Logo）。
5. **数据正确性优先**：账单扫描只做“当前页可见内容 + 用户确认 + 复用现有去重”，不做后台自动入账；历史账单优先走官方导出文件导入。

## 1. 当前代码基线盘点

### 1.1 技术栈与导航

- Kotlin + Jetpack Compose + Material 3 + Hilt + Room + DataStore。
- 底部四个 tab：账单、统计、预算、设置，见 `android/app/src/main/java/com/bookkeeping/app/ui/navigation/RootNavigation.kt`。
- 当前主题是默认紫色、开启 dynamic color（`ui/theme/Theme.kt`、`Color.kt`），与目标的绿色品牌视觉差距最大。
- 当前导航图标是 Material 图标；目标是自定义线性图标 + 选中胶囊。

### 1.2 四个页面现状

| 页面 | 当前已有 | 与目标差距 |
| --- | --- | --- |
| 账单 | 月度汇总卡、分类筛选 chip、按日分组、待确认重复徽标、删除/编辑/审核 | 缺顶部“清衡”品牌头、采集中/同步状态 chip、搜索框、本月/支出/收入/分类筛选、商户图标、目标卡片样式 |
| 统计 | 支出/收入切换、总额卡、分类饼图、每日柱状图、分类明细（`StatsScreen.kt`、`CategoryPieChart.kt`、`DailyBarChart.kt`） | 缺月份选择、日均支出、与上月对比、环形图中心总额、分类图例、每日趋势 tooltip、目标卡片层级 |
| 预算 | 月总预算 + 分类预算（DataStore 全局）、进度条、超支提示、编辑/添加分类对话框（`BudgetScreen.kt`、`BudgetPreferences.kt`） | 缺月份选择、剩余天数、本月节奏/预测、预算建议、目标卡片与状态徽标；预算目前不是按月隔离 |
| 设置 | 常驻运行状态、成功提醒、语言、关于与更新、权限授权、双向同步、ROM 白名单（`SettingsScreen.kt`、`SyncSettingsCard.kt`） | 需要重排成目标的“采集与运行状态 / 同步 / 权限授权 / 提醒与语言 / 关于与更新”分组，并增加状态行、端点、冲突入口、版本行 |

### 1.3 数据与能力现状

- 交易实体：`TransactionEntity` 有金额、币种、方向、分类、商户、来源、账户、时间、审核状态、本地 `duplicateOfId`；**没有商户图标字段**。
- 预算：`BudgetPreferences` 用 DataStore 存“月总预算 + 分类预算”，**没有月份维度**。
- 统计：`StatsViewModel` 只算当前月；`TransactionDao.observeBetween` 可复用做月份/上月查询；**没有搜索查询**。
- 同步状态：`BidirectionalSyncClient` / `TransactionSyncWorker` 写入 `last_result`、`last_success_ms`、`pending_sync_conflicts`、`pending_conflict_event_ids`（SharedPreferences），可复用做顶部状态 chip 和设置状态行。
- 采集状态：rc.8 的 `CaptureHealth` / `KeepAliveService` 已能给出连接/断开/最近成功；可复用做“采集中”chip。
- 无障碍：`accessibility_service_config.xml` 开了 `canRetrieveWindowContent`、`flagIncludeNotImportantViews`，**没有 `canTakeScreenshot`**；`ScreenSnapshot` 只保留文本/描述/类名，**没有节点边界**。这决定了图标截取需要扩展。
- 现有无障碍规则：微信转账、微信红包、支付宝转账（`WechatTransferExtractor` 等），尚未覆盖“微信支付账单页”。

## 2. 目标 UI 拆解

### 2.1 设计 token（从截图取色，最终以设计确认为准）

| Token | 参考值 | 用途 |
| --- | --- | --- |
| primary | `#24705C` / `#2A775E` | 选中 chip、按钮、强调文字 |
| primaryDeep | `#0E5336` | 关键数字、深色强调 |
| primaryContainer | `#DCF0E7` / `#E8F5EE` | 汇总卡、浅色底 |
| mint | `#97E0B9` | 图表、进度、装饰 |
| background | `#F6F5F4` | 页面底色 |
| surface | `#FFFFFF` | 卡片 |
| textPrimary | `#151918` | 标题/金额 |
| textSecondary | `#5E605F` | 副标题/说明 |
| outline | `#E5E7E5` | 分割线/描边 |
| expense | 约 `#D64545` | 支出金额/超支 |
| income | 约 `#1F7A55` | 收入金额/正常 |
| warning | 约 `#E8A23A` | 接近超支 |
| info | 约 `#3E7BFA` | 交通/信息类 |

- 版式：页面左右约 16dp；卡片圆角约 16–20dp；卡片内边距 16dp；列表行高约 64–72dp；底部导航选中项用浅绿胶囊。
- 目标截图是浅色主题；深色模式不在截图中。建议**先做浅色 1:1，深色作为后续独立批次**，避免同时做两套视觉。
- 字体：目标标题更粗、数字更大；建议使用系统默认字体 + 明确字重层级（标题 28/24sp，卡片标题 18sp，正文 14–16sp，辅助 12sp），不要引入未授权字体。

### 2.2 账单首页

组件顺序：

1. `HomeHeader`：左侧“清衡”，右侧通知铃铛（带红点）和更多菜单。
2. `StatusChipRow`：`采集中`（CaptureHealth + 服务状态）、`同步正常`（last_result / conflicts）、`最近同步 X 分钟前`（last_success_ms）。
3. `SummaryCard`：本月支出、本月收入、本月收支差三列，浅绿底 + 轻微波浪装饰；数字用语义色。
4. `SearchBar`：按商户、金额、备注搜索；需要新增 DAO 查询或内存过滤。
5. `FilterRow`：全部、本月、支出、收入、分类（下拉）；当前只有分类筛选。
6. `DaySection`：`今天 · 3 笔`，右侧当天支出/收入合计；复用现有 `DayHeader` 逻辑。
7. `TransactionRow`：
   - 左侧 44–48dp 图标：优先商户图标，否则分类图标；
   - 主行商户名，副行“分类 · 来源 · 时间”；
   - 右侧金额（支出红/收入绿）；
   - 待确认重复用橙色小徽标，不打断阅读；
   - 点击进入编辑/审核，长按或滑动删除继续复用现有确认弹窗。
8. 空态、搜索无结果、加载骨架、错误态。
9. 待确认重复卡片：保留现有 `DuplicateReviewCard` 的决策能力，但样式统一到新卡片；不要把它变成每次自动弹窗。

### 2.3 统计页

1. 顶部月份选择器（`2026年11月`）+ 更多菜单。
2. 支出/收入分段切换。
3. 汇总卡：本月总支出、日均支出、较上月（百分比和上月值）。
4. 说明行：“不包含转账和退款，统计仅计入实际消费支出。”
5. 分类占比：环形图（中心总额）+ 右侧/下方分类图例（图标、百分比、金额）。
6. 每日趋势：柱状图 + 选中日 tooltip；横轴日期，纵轴金额。
7. 分类明细：分类图标、名称、百分比、笔数、金额；按金额降序。
8. 月份切换后所有数字联动；空月份显示空态。

现有 `CategoryPieChart`/`DailyBarChart` 是自绘 Canvas，可复用并重绘成环形/圆角柱，不建议第一版引入新的图表依赖。

### 2.4 预算页

1. 月份选择器 + “调整预算”按钮。
2. 汇总卡四列：月预算、已使用、预算剩余、剩余天数；进度条 + 已使用百分比。
3. “本月节奏”卡：按当前日均预测月末结余/超支，点击查看说明。
4. 分类预算列表：分类图标、预算/已用、进度条、状态徽标（正常/接近超支/已超预算）、百分比。
5. “预算建议”卡：基于当前使用率给出文案，不做未经解释的财务结论。
6. “添加分类”入口、编辑/删除分类预算。
7. 月份维度：当前 `BudgetPreferences` 是全局预算，需要改成按月存储；迁移时把现有全局预算落到当前月，避免用户感觉预算消失。

### 2.5 设置页

分组：

- 采集与运行状态：自动记账（已开启/未开启）、无障碍兜底（正常/未开启）、短信权限（可选）、最近同步。
- 同步：Windows/VPS 双向同步、当前端点、立即同步、冲突处理、首次连接向导。
- 权限授权：通知使用权、短信权限、无障碍权限；每行显示真实状态和跳转。
- 提醒与语言：成功提醒开关、常驻状态开关、语言分段控件。
- 关于与更新：应用版本、检查更新、关于清衡、开源信息。

复用现有 `KeepAliveCard`、`SuccessNotificationCard`、`PermissionCard`、`SyncSettingsCard`、`RomGuideCard` 的逻辑，只重排视觉和状态文案；不要改同步/权限实现。

## 3. 数据与架构改造

### 3.1 主题与组件

- 新增绿色 Material 3 color scheme，**关闭 dynamic color**（目标品牌色固定，动态取色会破坏 1:1）。
- 新增 `ui/components/`：`StatusChip`、`SummaryCard`、`SearchBar`、`FilterChipRow`、`TransactionRow`、`CategoryIcon`、`DonutChart`、`TrendChart`、`BudgetProgress`、`SettingsSection`、`SettingsRow`。
- 现有页面逐步替换组件，保留 ViewModel/DAO/Repository 行为；先做 UI 层，不改同步/去重/通知逻辑。

### 3.2 商户图标

- `TransactionEntity` 增加本地字段（建议 `merchantIconKey: String?` 或 `merchantIconPath: String?`），只存本地资源键/文件路径，不上传、不参与同步；类似现有 `duplicateOfId` 的本地字段策略。
- 新增 `MerchantIconCatalog`：常见商户名/别名 → 本地图标；未知商户 → 分类图标；允许用户手动指定。
- 动态截取的图标存到应用私有目录（PNG/WebP，128×128），数据库只存路径；清理策略与交易删除联动。
- 品牌 Logo 涉及商标/版权：只用于用户本机展示，不随开源仓库分发未授权品牌资源；仓库内只放通用分类图标和可替换的本地映射示例。

### 3.3 预算按月

- `BudgetPreferences` 改成按 `YYYY-MM` 的 key：`budget_total_YYYY-MM`、`budget_cat_YYYY-MM_CATEGORY`。
- 迁移：首次读取时把旧的全局 `monthly_total`/`cat_*` 复制到当前月，再保留旧 key 只读一版；不删除用户数据。
- 若未来要跨设备同步预算，再接后端 `expense_budgets`（MCP 已有月度预算接口）；第一版建议保持本地预算，避免把同步范围扩大。

### 3.4 统计与搜索

- `StatsViewModel` 增加 `selectedMonth`、上一月汇总、日均；查询复用 `observeBetween`。
- `TransactionDao` 增加搜索查询（merchant/note/category LIKE）和类型/时间过滤；大数据量用 SQL，不在 UI 全量过滤。
- 顶部状态 chip 需要 `SyncStatusRepository`/`SettingsPreferences` 暴露 Flow；采集状态复用 `CaptureHealth`。

### 3.5 测试与迁移

- Room 新增字段/预算迁移必须有 JVM 测试和真机升级测试；不能只在全新安装验证。
- 统计/预算/搜索的纯计算逻辑抽成纯函数，补 JVM 单测。
- UI 使用 Compose screenshot/预览或真机截图对比；当前项目没有 Compose UI 测试基础设施，可在 Batch 1 引入最小 screenshot 测试或人工截图验收清单。

## 4. 微信支付账单/商户图标提取调研

### 4.1 截图里的对象是什么

用户提供的微信截图是“微信支付”公众号/服务号的**账单卡片列表**：每条含商户名、金额、付款方式（如银行卡）、详情入口和商户头像。它不是“支付成功页”，也不是官方账单文件。

这两类页面要分开看：

| 页面 | 能拿到什么 | 可靠性 |
| --- | --- | --- |
| 支付成功页 | 金额、商户、时间、订单号、商户 Logo/头像 | 有成熟开源实现，但依赖微信版本和无障碍兼容 |
| 微信支付账单列表 | 可见卡片的商户/金额/时间/图标 | 未找到可靠的自动抓取先例；需要逐页读取 + 用户确认 |
| 官方导出账单 | 完整历史、金额、商户、时间、订单号、收支类型 | 最可靠；CSV/XLSX 解析成熟 |

### 4.2 Android 能力矩阵

| 方法 | 商户名/金额 | 商户图标 | 成本/风险 |
| --- | --- | --- | --- |
| Accessibility 文本节点 | 可能可读；部分微信支付页返回空节点 | 不能直接取像素 | 需真机探针；微信改版会失效 |
| Accessibility 节点边界 | 可得位置 | 只能定位区域 | 需扩展 `ScreenSnapshot` 保存 bounds |
| `AccessibilityService.takeScreenshot` | 可配合 OCR | 可按区域裁剪图标 | API 30+，需 `canTakeScreenshot="true"`；隐私与安全窗口限制 |
| 通知 `EXTRA_LARGE_ICON` | 已有标题/正文 | 可能有商户图 | 需探针确认微信/支付宝是否带商户 Logo |
| 本地 OCR（PP-OCR/ML Kit） | 可补金额/商户/时间 | 可配合截图裁剪 | 包体/内存/延迟增加；需本地运行 |
| 官方 CSV/XLSX 导入 | 完整、准确 | 通常无图标或只有商户名 | 用户需导出文件；最可靠 |

### 4.3 GitHub 先例

| 项目 | 做法 | 对本项目的意义 |
| --- | --- | --- |
| [AldenHe/huayoupu-ledger](https://github.com/AldenHe/huayoupu-ledger)（约 277★，GPL-3.0） | 识别微信/支付宝**支付成功页**；无障碍 + 离线 PP-OCRv6 Tiny；提取金额/商户/时间/订单号；按文字与几何位置裁剪 128×128 商户 Logo/头像；摇一摇/截图补记；历史账单不保证后台自动采集 | 最接近“商户名+金额+图标”的先例；但复杂、依赖 OCR、需兼容入口，且是成功页不是账单列表 |
| [amb-er/auto-ledger](https://github.com/amb-er/auto-ledger) | Android 自动记账，支付宝/微信 + 无障碍金额回填 + CSV 导入 | 证明无障碍补录可行，成熟度较低 |
| [MageGojo/lizhang](https://github.com/MageGojo/lizhang) | Flutter 本地记账，微信/支付宝 CSV/XLSX/文本导入，预览/重复/错误行 | 历史账单首选：文件导入 + 预览去重 |
| [run-liyi/wechatpay](https://github.com/run-liyi/wechatpay)（约 1326★） | 微信账单分析，Electron，导入账单文件 | 官方账单文件解析成熟 |
| [zxc7563598/php-wechat-bill-parser](https://github.com/zxc7563598/php-wechat-bill-parser) | 微信账单压缩包/密码/数据提取 | 说明官方账单是主要数据源 |
| [MickLife/KeepAccounts_v2.0](https://github.com/MickLife/KeepAccounts_v2.0)（约 311★） | 微信/支付宝账单合并到 Excel | 文件导入路线 |
| [jiegec/china_bean_importers](https://github.com/jiegec/china_bean_importers)（约 158★） | Beancount 中国账单导入器 | 文本/CSV 解析参考 |
| [ctf7879/alipayAndWechatpayBillsImport](https://github.com/ctf7879/alipayAndWechatpayBillsImport) | 支付宝/微信账单导入 | 同类文件导入 |

> 对标项目的代码级拆解、可照搬/不可照搬边界和 Phase 0A/0B/0C 探针，见 [huayoupu-ledger 对标拆解与清衡移植规格](HUAYOUPU_LEDGER_BENCHMARK_AND_PORTING_SPEC.md)。

`huayoupu-ledger` 的关键技术细节（README/代码可查）：

- 部分微信支付成功页对普通无障碍服务只返回空根节点；它用 `SelectToSpeakService` 兼容入口类名 + `isAccessibilityTool=false` 读到 32 个节点。这是**未公开的兼容技巧**，可能随微信/系统/商店政策变化，不能当作稳定 API。
- `accessibility_service.xml` 开启 `flagRetrieveInteractiveWindows`、`canTakeScreenshot="true"`；先确认成功页身份，再截图，避免误截其他页面。
- 离线 OCR 选型 PP-OCRv6 Tiny（约 5.95 MiB 权重 + 运行时），比 ML Kit 包体大但字段结果/速度更适合账单；这会给 APK 增加约 20 MiB 级体积。
- 图标裁剪不是“从节点取图”，而是从截图里按 OCR/几何 hint 找连通区域、裁剪、居中到 128×128；无法可靠定位时用默认图标。

### 4.4 可行性判断

- **商户名/金额**：值得做真机探针。若账单页节点可读，可直接用 `ScreenSnapshot` 解析；若为空，再考虑截图 + OCR。不要先写死“一定能读”。
- **商户图标**：不要承诺从无障碍节点直接取图。可行顺序：
  1. 本地商户图标库 + 分类图标（第一版 1:1 视觉）；
  2. 支付通知 `EXTRA_LARGE_ICON` 探针（成本最低）；
  3. 成功页/账单页截图裁剪（实验特性，需 `canTakeScreenshot`）。
- **历史账单自动抓取**：不建议。用官方导出文件导入 + 预览去重；或用户打开账单页后点“扫描当前页”，只读取可见卡片并确认。这样不会后台翻页、不会误记、不会与现有通知采集重复。
- **1:1 视觉**：本地图标库可以立刻做到；动态截取图标只能覆盖“抓到的支付”，不能保证每个历史商户都有图标。

### 4.5 建议的探针（Phase 0，必须做）

在真机（当前 Xiaomi HyperOS Android 16）和一台接近原生 Android 的模拟器上：

1. 打开微信支付账单列表，导出无障碍节点树：`text`、`contentDesc`、`className`、`boundsInScreen`、是否可点击；记录 20 条账单。
2. 打开单条账单详情，重复上述记录；检查“支付成功/交易详情/商户/金额”是否可读。
3. 给 debug 版临时加 `canTakeScreenshot="true"`，测试 `takeScreenshot` 是否能拿到账单页；记录安全窗口/黑屏限制。
4. 在 `PaymentNotificationListenerService` 里打印/落盘 `Notification.EXTRA_LARGE_ICON`/`getLargeIcon()` 是否存在（只记录是否存在与尺寸，不记录图片内容），覆盖微信/支付宝 20 条通知。
5. 用 20 条真实账单评估：商户名/金额准确率、图标裁剪成功率、页面版本差异、是否需要滚动、是否误读退款/失败/待支付。

**Go/No-Go 标准**：商户名+金额 ≥95% 正确、图标裁剪 ≥80% 成功（失败可回退本地图标）、0 条未确认自动入账、无崩溃/隐私越界。达不到就只做“官方文件导入 + 本地图标”，不做账单页自动抓取。

## 5. 分批实施计划

> 原则：先设计系统和账单首页，后统计/预算/设置；微信提取单独实验，不阻塞 UI。每批都要保留现有同步/去重/通知/权限行为。

### Phase 0：调研与设计冻结（2–3 个工作日）

- 目标：设计 token、组件清单、微信/通知/图标探针、预算和图标数据方案定稿。
- 交付：本报告的深化版、截图取色表、组件树、探针报告、数据迁移草案。
- 验收：设计 token 与参考截图逐项对应；探针有原始节点/截图证据；明确“账单页自动抓取”的 go/no-go。

### Batch 1：设计系统 + App Shell + 账单首页（5–7 个工作日）

- 目标：绿色主题、底部导航、顶部品牌头、状态 chip、汇总卡、搜索、筛选、交易行图标、日分组、重复徽标。
- 交付：`ui/theme` 绿色 scheme（关闭 dynamic color）；`ui/components` 基础组件；`TransactionListScreen` 重构；搜索/筛选 DAO；商户/分类图标目录。
- 验收：
  - 941×1672 参考图逐屏对比，布局/颜色/字号/间距在约定容差内；
  - 现有新增/编辑/删除/审核/重复确认/同步/通知不回归；
  - 搜索、筛选、空态、错误态、英文/中文、字体放大 1.3× 可用；
  - 无数据丢失；无主线程 DB 查询；列表滚动流畅。

### Batch 2：统计页（4–5 个工作日）

- 目标：月份选择、支出/收入、总额/日均/上月对比、环形图、每日趋势、分类图例/明细。
- 交付：`StatsViewModel` 月份/上月/日均；环形图/趋势图重绘；分类图例组件。
- 验收：
  - 所有数字与 DAO/现有统计口径一致；转账/退款/排除规则不变；
  - 月份切换、空月份、收入/支出切换正确；
  - 图表在 941×1672 和 1080×2400 下不裁切；性能可接受；
  - 现有统计单测/新增计算单测通过。

### Batch 3：预算页（4–5 个工作日）

- 目标：月份选择、月总预算、剩余天数、使用进度、本月节奏、分类预算、状态徽标、建议、添加/调整。
- 交付：`BudgetPreferences` 月维度迁移；预算计算纯函数；预算 UI 重构。
- 验收：
  - 旧全局预算迁移到当前月且不丢失；跨月独立；
  - 0/负数/超 100%/无预算/删除分类等边界正确；
  - 预算建议只基于可解释的使用率，不做无依据财务结论；
  - 现有预算功能不回归。

### Batch 4：设置页（3–4 个工作日）

- 目标：分组卡片、状态行、同步端点/冲突入口、权限状态、提醒/语言、版本/更新/关于。
- 交付：`SettingsSection`/`SettingsRow`；同步/采集/权限状态 Flow；现有卡片重排。
- 验收：
  - 所有开关/跳转/同步/语言/更新行为保持；
  - 状态与实际一致（通知使用权、无障碍、短信、最近同步、冲突数）；
  - 小米/Redmi/POCO 常驻通知/自启动/电池提示仍可见；
  - 中英文、字体放大、TalkBack 可用。

### Batch 5：微信/支付宝账单辅助导入与商户图标（5–8 个工作日，实验特性）

- 目标：官方 CSV/XLSX/文本导入优先；可选的“扫描当前账单页”只读可见卡片，预览、去重、用户确认；商户图标本地匹配/裁剪。
- 交付：文件导入解析器 + 预览；`BillScan` 实验入口（feature flag）；图标本地存储；探针报告。
- 验收：
  - 20 条真实账单：商户/金额 ≥95% 正确；图标 ≥80% 成功或回退；0 条未确认自动入账；
  - 与现有通知/手动账目去重，重复率 0；退款/失败/待支付不误记；
  - 所有数据本地处理；不后台翻页、不自动操作支付；
  - 功能开关默认关闭，debug/内测可开；不通过验收就不进正式版。

### Batch 6：硬化、真机与发布（3–4 个工作日）

- 目标：回归、性能、无障碍、真机矩阵、迁移、发布。
- 验收：
  - Android JVM 单测 + 新增 UI/计算测试；lint/CI 全绿；
  - Xiaomi HyperOS Android 16、原生 Android 13/14/15 模拟器、至少一台其他 ROM 真机；
  - 冷启动、列表滚动、图表渲染、内存可接受；
  - 升级安装保留数据；回滚路径明确；
  - 发布说明与截图/验收记录归档。

### 并行建议

- Phase 0 与 Batch 5 的探针可以并行；UI 的 Batch 1→4 共享组件，建议顺序做。
- 设计系统冻结后，统计/预算/设置可以拆成 2–3 个子线程并行，但必须先合并 `ui/components` 和主题，避免同时改同一批文件。
- 账单扫描/图标实验放独立 worktree/branch；不要和 UI 批次混在一个 PR。

## 6. 验收矩阵

| 维度 | 验收内容 |
| --- | --- |
| 视觉 | 941×1672 参考图逐屏对比；颜色 token、圆角、间距、字号；允许的差异清单 |
| 功能 | 新增/编辑/删除/审核/待确认重复/同步/通知/权限/更新/语言/预算全部不回归 |
| 数据 | 统计总额、分类、日均、上月对比与 DAO 一致；预算迁移不丢；搜索/筛选正确 |
| 性能 | 冷启动、列表滚动、图表渲染、搜索响应；无主线程 DB；无不必要重组 |
| 无障碍 | contentDescription、48dp 触控目标、TalkBack、字体放大 1.3×/2.0× |
| 本地化 | 中文/英文；长英文不折行/不裁切；数字/金额格式一致 |
| 设备 | Xiaomi HyperOS Android 16（当前）、原生 Android 13/14/15 模拟器、其他 ROM 至少一台 |
| 账单提取 | 20 条真实账单；商户/金额准确率、图标成功率、重复率、退款/失败/待支付、隐私、功能开关 |
| 发布 | CI 全绿、签名、升级安装、回滚、发布说明 |

## 7. 风险与待确认

1. **1:1 需要设计资产**：截图只能还原布局/色彩；真实品牌图标、字体、阴影/波浪装饰需要设计源文件或本地资产；否则只能“高度接近”，不能严格逐像素。
2. **微信兼容性**：`huayoupu-ledger` 的 `SelectToSpeakService` 类名兼容技巧未公开、可能随版本失效，也可能触及微信/应用商店政策；建议只作为实验，不作为唯一数据源。
3. **OCR 成本**：PP-OCRv6 Tiny + ONNX/OpenCV 会增加包体和内存；是否值得取决于 Phase 0 探针结果。先用通知/本地图标，不急着上 OCR。
4. **图标版权**：品牌 Logo 只能本地展示，仓库不要分发未授权素材；未知商户用分类图标/首字母兜底。
5. **预算同步**：当前本地 DataStore 预算与后端 `expense_budgets` 是两套模型；第一版建议本地月预算，跨端同步单独评估。
6. **深色模式**：目标截图只有浅色；先做浅色，深色独立批次，避免一次改两套。
7. **当前 rc.8 工作树**：UI 重构应在 rc.8 合并/签名后开新分支，避免与未提交的采集状态改动混在一起。
8. **后台抓取边界**：不做后台自动翻微信账单、不自动点击支付；只做用户触发的当前页扫描 + 确认。

## 8. 推荐下一步

1. 先把 rc.8 收尾（CI unsigned → VPS 签名 → 真机验证），保持单一基线。
2. 做 Phase 0：设计 token + 组件清单 + 微信账单/通知大图标探针；产出 go/no-go。
3. 如果探针通过，按 Batch 1→4 做 UI；Batch 5 作为独立实验分支，feature flag 默认关闭。
4. 如果探针不通过，UI 仍可 1:1（本地图标库），账单历史走官方文件导入，不阻塞视觉重构。

## 9. 参考来源

- [AldenHe/huayoupu-ledger](https://github.com/AldenHe/huayoupu-ledger)：支付成功页无障碍 + 离线 OCR + 商户图标裁剪 + 摇一摇/截图补记
- [amb-er/auto-ledger](https://github.com/amb-er/auto-ledger)：Android 自动记账 + 无障碍金额回填
- [MageGojo/lizhang](https://github.com/MageGojo/lizhang)：微信/支付宝 CSV/XLSX/文本导入 + 预览去重
- [run-liyi/wechatpay](https://github.com/run-liyi/wechatpay)：微信账单分析
- [zxc7563598/php-wechat-bill-parser](https://github.com/zxc7563598/php-wechat-bill-parser)：微信账单解析
- [MickLife/KeepAccounts_v2.0](https://github.com/MickLife/KeepAccounts_v2.0)：微信/支付宝账单合并到 Excel
- [jiegec/china_bean_importers](https://github.com/jiegec/china_bean_importers)：Beancount 中国账单导入器
- [ctf7879/alipayAndWechatpayBillsImport](https://github.com/ctf7879/alipayAndWechatpayBillsImport)：微信/支付宝账单导入
- [Android AccessibilityService.takeScreenshot](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshot(int,%20java.util.concurrent.Executor,%20android.accessibilityservice.AccessibilityService.TakeScreenshotCallback))
- [Android Notification.EXTRA_LARGE_ICON](https://developer.android.com/reference/android/app/Notification#EXTRA_LARGE_ICON)
- [Android 无障碍服务配置](https://developer.android.com/guide/topics/ui/accessibility/service#configuration-files)
