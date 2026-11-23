# 清衡 Android：扫描原图归档、截图批量导入结果页与「时间待确认」重设计开发文档

- **目标分支**：`feat/scan-evidence-batch-import`（基于 `main` `70b46d3`）
- **适用端**：Android（纯本地凭证归档，不改动服务端 API 与同步协议）

---

## 1. 背景与目标

本次迭代解决截图扫描入账闭环中的三个核心体验断点：

1. **扫描入账后看不到原图**：待确认阶段已有 `CaptureCandidate.screenshotPath` 与 `CaptureImagePreview`，但正式入账后 `TransactionEntity` 未保留图片引用，且 `page_capture/` 仅保留 7 天，导致用户在账单详情页/编辑页无法回看原图核对金额、商户与时间。
2. **截图导入入口过急且结果不可见**：
   - App 内点进「截图记账」会自动拉起系统单选图片器，取消直接退出，无法先看说明页；
   - 仅支持单张选择，批量整理多张支付截图效率低；
   - 自动入账或审阅确认完成后，页面只展示简短文案或单行 `SettledRow` 并在 2.2s–2.6s 后自动关闭，无法像主页一样直接浏览本次导入的账单卡片或点进详情修正。
3. **详情页「时间待确认」视觉割裂且不可操作**：当前在字段卡与备注卡之间悬浮一个 `errorContainer` 粉红小方块，破坏整页通栏卡片对齐，与报错/支出红色冲突，且无法一键确认或跳转修改。

---

## 2. 功能一：扫描原图独立归档与详情/编辑页查看

### 2.1 存储架构与生命周期（与 `page_capture/` 解耦）
- **独立目录**：`filesDir/receipt_evidence/`，文件命名为 `ev_<sha256>.webp`（WebP 85% 质量压缩，常规支付截图约 40–80KB，文字清晰且节省空间）。
- **内容寻址去重**：同一张长截图识别出多笔交易时共用同一 `ev_<sha256>.webp`。
- **引用计数删除**：交易被删除（`TransactionRepository.delete` 写 tombstone `syncDeleted = 1`）时，通过 `TransactionDao.countActiveByEvidenceImageName(name)` 检查是否仍有未删除交易引用该文件；引用归零时删除本地文件。
- **保留策略与配额**：
  - 新增 `ReceiptEvidenceStore`（纯逻辑策略可单测 + Android 文件层）与 `ReceiptEvidenceSettings`：
    - 默认策略：**保留 90 天** + **100MB LRU 总量上限**；
    - 可切换为 **永久保留**（仍保留一键清理入口）；
    - 提供「清理凭证缓存」功能，清理磁盘文件并同步把对应交易的 `evidenceImageName` 置空（或按需优雅降级隐藏）。
  - `ScreenshotEvidenceRetentionWorker` 顺带执行 `ReceiptEvidenceStore.pruneIfNeeded(activeNames)`，不干扰原有的 7 天 `page_capture/` 清理。

### 2.2 数据模型与 Room 迁移（Local-only）
- `TransactionEntity` 新增字段：
  ```kotlin
  // Local-only archived screenshot evidence filename inside filesDir/receipt_evidence/.
  // Never uploaded or overwritten by sync.
  val evidenceImageName: String? = null,
  ```
- `BookkeepingDatabase` 版本由 `12` 升级至 `13`，新增非破坏性迁移 `MIGRATION_12_13`：
  ```sql
  ALTER TABLE transactions ADD COLUMN evidenceImageName TEXT
  ```
- 同步边界：`evidenceImageName` 属于纯本地字段，不写入 `UploadPayload`，远端拉取更新时保留本地已有 `evidenceImageName`。

### 2.3 三条写入与合并路径
1. **`AUTO_BOOK`（`ScreenshotIntakeCoordinator`）**：改用落盘后的 `stored`（包含已验证的 `stored.screenshotPath`），入账前归档为 `evidenceImageName` 并传入 `CaptureReviewMapping.toTransactionEntity`。
2. **审阅确认（`ScreenshotImportActivity` & `CaptureReviewViewModel`）**：确认写入账本前将 `candidate.screenshotPath` 归档为 `evidenceImageName`。
3. **跨通道合并（`CaptureMergePolicy.mergeInto`）**：通知/短信先记账（无图）、扫描截图后合并补全时，执行 `evidenceImageName = keep.evidenceImageName ?: incoming.evidenceImageName`。

### 2.4 UI 展示与全屏预览
- **详情页（`TransactionDetailScreen`）**：
  - 在备注卡下方新增紧凑的「扫描原图」横条卡（约 68dp 高：左侧 48×48 圆角缩略图 +「扫描原图」标题 +「点击查看原图」副文案，右侧箭头）。
  - 仅在 `evidenceImageName != null` 且本地文件可读时渲染；无图或文件已清理时不展示破图。
  - `DetailContent` 增加 `verticalScroll(rememberScrollState())`，普通单屏内容保持紧凑不滚，叠加多张卡片或小屏时可顺畅滚动不截断。
- **编辑页（`TransactionEditScreen`）**：
  - 有可读原图时，在顶部 `TopAppBar` 操作区提供「查看原图」入口，方便边看原图边修改时间、商户或金额。
- **全屏预览**：统一复用现有 `CaptureImagePreview(path, onDismiss)`（1x–6x 缩放、双击 2.5x、平移边界、8MP/8192px 安全降采样）。

---

## 3. 功能二：截图批量导入与结果页重构

### 3.1 入口与批量选图（上限 10 张）
- **App 内进入（非分享）**：`ScreenshotImportActivity` 不再在 `onCreate` 自动弹出系统选图器，直接展示 `PickImageCard` 落地页（明确说明支持一次最多选 10 张）；用户点击「选择图片」才拉起 `ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)`。
- **取消选图**：留在当前页不自动 `finish()`。
- **上限兜底**：对返回的 URI 列表执行 `ScreenshotBatchPolicy.capSelection(uris, maxCount = 10)`，超出时截断前 10 张并提示用户。
- **外部系统分享**：
  - 保持 `ACTION_SEND` 进来直接识别；
  - `AndroidManifest.xml` 新增 `ACTION_SEND_MULTIPLE`（`image/*`），同样受最多 10 张限制并直接开始识别。

### 3.2 批量识别进度与结果页排版（先清待办）
- 按顺序逐张调用 `ScreenshotIntakeCoordinator.ingest(uri, source)`，顶部进度卡实时展示「正在识别第 X / N 张…」，每张识别完成立即把结果追加到页面。
- `ScreenshotIntakeCoordinator.Outcome` 新增 `bookedTransactionIds: List<Long> = emptyList()`；审阅确认路径同样回传 `transactionId`。
- 页面自上而下顺序：
  1. **顶部进度 / 本次导入汇总条**（已记入 X 笔 · 待确认 Y 笔 · 跳过/未识别 Z 项）；
  2. **「待你确认」区**（先清待办；每张卡片确认「记入账本」后，其 `transactionId` 立即加入已入账集合并从待办区移出）；
  3. **「本次已记入账本」区**（从 Room 实时订阅本次入账的所有 `TransactionEntity`，复用与主页完全一致的 `LedgerTransactionRow` 卡片）；
  4. **可折叠「跳过 / 重复 / 未识别」说明区**（默认折叠，有失败或重复项时可展开查看原因）。

### 3.3 点击卡片进详情与主动退出
- **复用主页账单卡片**：将 `TransactionListScreen.kt` 中的 `LedgerTransactionRow` 及配套辅助函数抽离为公共组件 `LedgerTransactionRow.kt`，主页与截图导入页共用同一套视觉与信息密度。
- **点击进入账单详情**：在 `ScreenshotImportActivity` 内支持点击任一已记入账单卡片，打开 `TransactionDetailScreen`（可查看扫描原图、确认/修改时间、编辑、添加分类规则或删除），返回后留在导入结果页且卡片实时刷新。
- **取消自动关闭**：移除 `LaunchedEffect` 的 2.2s / 2.6s 自动 `finish()`，底部固定提供「继续导入（最多 10 张）」与「完成」按钮，由用户主动退出。

---

## 4. 功能三：详情页「时间待确认」方案 A 重设计

- **移除悬浮粉红块**：删除 `DetailContent` 中位于字段卡与备注卡之间的独立 `errorContainer` 文本块。
- **并入字段卡「发生时间」行**：
  - `FieldCard` 支持在「发生时间」行下方（同一张 16dp 圆角白卡内部）渲染一条通栏 `10.dp` 圆角**淡琥珀色提示条**（使用暖琥珀 Warning 语义色，与支出/删除红明确区分）。
  - 提示条布局：左侧 `Icons.Outlined.Schedule` 图标 +「时间为截图时间，建议核对」，右侧提供两个紧凑文字操作：
    - **「确认」**：一键将 `eventTimeNeedsConfirmation` 置为 `false`（经 `TransactionRepository.update` 落库并标记 `syncDirty`，无需进入编辑页空保存）；
    - **「修改」**：直接进入 `TransactionEditScreen` 修改时间。

---

## 5. 影响文件与测试清单

### 主要新增 / 修改文件
- **数据与存储**：`TransactionEntity.kt`、`BookkeepingDatabase.kt`（v12→v13）、`TransactionDao.kt`、`TransactionRepository.kt`、`CaptureMergePolicy.kt`、`ReceiptEvidenceStore.kt`（新增）、`ReceiptEvidenceSettings.kt`（新增）、`ScreenshotEvidenceRetentionWorker.kt`
- **截图识别与导入**：`ScreenshotIntakeCoordinator.kt`、`ScreenshotImportReviewState.kt`、`ScreenshotBatchPolicy.kt`（新增）、`ScreenshotImportActivity.kt`、`CaptureReviewMapping.kt`、`CaptureReviewViewModel.kt`、`AndroidManifest.xml`
- **UI 组件与页面**：`LedgerTransactionRow.kt`（抽离公共组件）、`TransactionListScreen.kt`、`TransactionDetailScreen.kt`、`TransactionEditScreen.kt`、`TransactionViewModel.kt`、`ScreenshotCaptureCard.kt`、`EnglishUiText.kt`

### 单元测试覆盖
1. `ReceiptEvidenceStoreTest`：WebP/文件归档命名去重、90 天 TTL 清理、LRU 容量清理、活跃引用保护。
2. `ScreenshotBatchPolicyTest`：最多 10 张截断与提示判断、空选择与超限选择边界。
3. `ScreenshotImportReviewStateTest`：批量追加、已入账 `bookedTransactionIds` 记录、确认后转入已入账且不重复计数。
4. `CaptureMergePolicyTest`：跨通道合并补挂 `evidenceImageName`（已有不覆盖、缺失时补全）。
5. `TranslationCoverageTest`：确认所有新增 `tr("...")` 文案在 `EnglishUiText.kt` 均有对应英文翻译。
