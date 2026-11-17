# 列表账户提示与保存反馈

## 副标题规则

来源徽标（例如 `[扫描]`）仍独立显示；副标题只在来源和账户之间选择一个，不改详情页。

| 账户 | 来源标签 | 副标题示例 |
| --- | --- | --- |
| `微信` | `手动` | `餐饮 · 微信 · 12:00` |
| 空值或空白 | `手动` | `餐饮 · 手动 · 12:00` |
| `微信` | `微信` | `餐饮 · 微信 · 12:00`（只出现一次） |

## 保存反馈时序

1. 手填、一句话预填、语音预填继续共用 `AddTransactionDialog.onConfirm`。
2. `TransactionViewModel.addTransaction` 等待 Room 插入，返回插入的 `Long` id；账户字段仍按原规则将空白转换为 `null`。
3. 插入成功后显示底部 Snackbar「已记录」。快速连续保存时，仅最新成功请求决定滚动目标和高亮目标。
4. 只有新记录符合当前类型、分类、期间、搜索和精确日期筛选时，列表才滚动到对应日期组，并通过 `BringIntoViewRequester` 将目标行带入视口；不符合筛选时只提示，不改筛选、不滚动。
5. 目标行进入视口后背景高亮约 1.1 秒，再用 0.5 秒渐隐。完成后清除高亮状态。

本次不改语音面板、日历、同步协议或数据库结构；详情页账户行保持原状。

## 自动测试

- `TransactionListPresentationTest.accountReplacesTheSourceLabel`
- `TransactionListPresentationTest.emptyAccountFallsBackToTheSourceLabel`
- `TransactionListPresentationTest.sameSourceAndAccountAppearOnlyOnce`
- `EnglishUiTextTest.transactionAndNavigationTranslations`
- CI 的 `Build Android APK` 执行 `testDebugUnitTest`、Debug/Release APK 构建；`Open Source Readiness` 验证公开文件与后端测试。

## 真机待测

- 手填含账户和不填账户两种保存：副标题分别显示账户/来源，扫描徽标仍保留。
- 一句话和语音预填保存：均显示「已记录」、滚动至新行并完成高亮渐隐。
- 快速连续保存：最终滚动及高亮落在最新成功记录。
- 当前筛选不包含新记录：只显示 Snackbar，不改变筛选或滚动位置。
- 打开详情页确认既有账户行未变。
