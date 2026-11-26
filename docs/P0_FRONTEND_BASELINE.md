# P0 baseline audit

Baseline: 125fc8fefaa1a14800155e18b92ba6bc71c78997

Non-theme source matches for literal Color or RoundedCornerShape (review candidates, not automatically violations; approved category palettes and explicit pixel exceptions must be distinguished): 281

No business call sites changed. ADB device list empty at baseline; real-device screenshots and performance are BLOCKED.

## Source inventory

~~~text
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/AutomationSetupPrompt.kt:57:                    .clip(RoundedCornerShape(12.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/CategoryIcon.kt:42:            .clip(RoundedCornerShape(size * 0.34f))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/CategoryIcon.kt:80:                .clip(RoundedCornerShape(size * 0.34f)),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/CategoryIcon.kt:98:            .clip(RoundedCornerShape(size * 0.34f))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/EmptyState.kt:62:        shape = RoundedCornerShape(18.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/FilterChipRow.kt:33:        shape = RoundedCornerShape(50),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/LedgerCalendarDialog.kt:377:            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/LedgerDateTimePickerDialog.kt:117:            shape = RoundedCornerShape(20.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/LedgerDateTimePickerDialog.kt:194:                            .clip(RoundedCornerShape(12.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/LedgerDateTimePickerDialog.kt:269:                        shape = RoundedCornerShape(10.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/LedgerDateTimePickerDialog.kt:283:            .clip(RoundedCornerShape(8.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/LedgerDateTimePickerDialog.kt:307:            .clip(RoundedCornerShape(9.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/LedgerDateTimePickerDialog.kt:499:                        .clip(RoundedCornerShape(14.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/LedgerDateTimePickerDialog.kt:535:                        .clip(RoundedCornerShape(14.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/LedgerDateTimePickerDialog.kt:577:            .clip(RoundedCornerShape(10.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/MerchantRuleDialog.kt:107:                shape = RoundedCornerShape(14.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/MerchantRuleDialog.kt:147:                        .clip(RoundedCornerShape(14.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/MerchantRuleDialog.kt:178:                    shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/MerchantRuleDialog.kt:196:                    shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/MonthPickerDialog.kt:102:                                        .clip(RoundedCornerShape(10.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/QuickEntryBar.kt:66:            shape = RoundedCornerShape(14.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/SearchBar.kt:34:        shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/SiteUpdateBanner.kt:46:        shape = RoundedCornerShape(18.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/SiteUpdateBanner.kt:107:                    shape = RoundedCornerShape(999.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/StatusChip.kt:79:            .clip(RoundedCornerShape(50))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/StatusChip.kt:116:            .clip(RoundedCornerShape(6.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/SummaryCard.kt:37:            .clip(RoundedCornerShape(20.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/SummaryCard.kt:39:            .border(1.dp, QingOutline, RoundedCornerShape(20.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/VoiceHintBubble.kt:46:                .shadow(8.dp, RoundedCornerShape(12.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/VoiceHintBubble.kt:47:                .background(bubbleColor, RoundedCornerShape(12.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/billimport/BillImportScreen.kt:421:        shape = RoundedCornerShape(18.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/billimport/BillImportScreen.kt:437:                shape = RoundedCornerShape(10.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/billimport/BillImportScreen.kt:468:                                shape = RoundedCornerShape(8.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/billimport/BillImportScreen.kt:546:        shape = RoundedCornerShape(18.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/billimport/BillImportScreen.kt:715:        shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/billimport/BillImportScreen.kt:825:                    shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/billimport/BillImportScreen.kt:863:                    shape = RoundedCornerShape(10.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/billimport/BillImportScreen.kt:1107:                RoundedCornerShape(10.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/budget/BudgetScreen.kt:190:                            .clip(RoundedCornerShape(14.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/budget/BudgetScreen.kt:192:                        shape = RoundedCornerShape(14.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/budget/BudgetScreen.kt:230:                        shape = RoundedCornerShape(50),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/budget/BudgetScreen.kt:408:                .clip(RoundedCornerShape(50))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/budget/BudgetScreen.kt:410:                .border(1.dp, QingOutline, RoundedCornerShape(50))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/budget/BudgetScreen.kt:420:                    .clip(RoundedCornerShape(50))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/budget/BudgetScreen.kt:479:        shape = RoundedCornerShape(20.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/budget/BudgetScreen.kt:541:                    .clip(RoundedCornerShape(50)),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/budget/BudgetScreen.kt:647:                    .clip(RoundedCornerShape(12.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/budget/BudgetScreen.kt:759:                .clip(RoundedCornerShape(50)),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/budget/EditBudgetDialog.kt:91:            shape = RoundedCornerShape(24.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/budget/EditBudgetDialog.kt:118:                                shape = RoundedCornerShape(14.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/budget/EditBudgetDialog.kt:182:                    shape = RoundedCornerShape(14.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/budget/EditBudgetDialog.kt:200:                        shape = RoundedCornerShape(14.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/budget/EditBudgetDialog.kt:215:                        shape = RoundedCornerShape(14.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/calendar/CalendarScreen.kt:1005:            .clip(RoundedCornerShape(50))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/calendar/CalendarScreen.kt:1007:        shape = RoundedCornerShape(50),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/calendar/CalendarScreen.kt:1429:                                    .clip(RoundedCornerShape(8.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/calendar/CalendarScreen.kt:1447:                                            Color(0xFFF59E0B)
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/calendar/CalendarScreen.kt:1449:                                            Color(0xFF3B82F6)
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/calendar/CalendarScreen.kt:1565:    val drawerShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/calendar/CalendarScreen.kt:1761:        shape = RoundedCornerShape(14.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/calendar/CalendarScreen.kt:1786:                        .clip(RoundedCornerShape(10.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/calendar/CalendarScreen.kt:1900:        shape = RoundedCornerShape(18.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/capture/CaptureImagePreview.kt:57:private val PreviewScrim = Color(0xF0000000)
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/capture/CaptureImagePreview.kt:252:                    .clip(RoundedCornerShape(20.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/capture/CaptureReviewCard.kt:80:        shape = RoundedCornerShape(18.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/capture/CaptureReviewCard.kt:158:                        shape = RoundedCornerShape(999.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/capture/CaptureReviewDialog.kt:137:            shape = RoundedCornerShape(24.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/capture/CaptureReviewDialog.kt:161:                            shape = RoundedCornerShape(999.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/capture/CaptureReviewDialog.kt:244:                        shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/capture/CaptureReviewDialog.kt:423:        shape = RoundedCornerShape(18.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/capture/CaptureReviewDialog.kt:449:                            shape = RoundedCornerShape(999.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/capture/CaptureReviewDialog.kt:468:                                shape = RoundedCornerShape(999.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/capture/CaptureReviewDialog.kt:524:                                .clip(RoundedCornerShape(14.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/capture/CaptureReviewDialog.kt:553:                    shape = RoundedCornerShape(10.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/capture/CaptureReviewDialog.kt:568:                    shape = RoundedCornerShape(10.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/capture/CaptureReviewDialog.kt:582:                    shape = RoundedCornerShape(10.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/capture/CaptureReviewDialog.kt:630:                shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/capture/CaptureReviewDialog.kt:647:                        shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/capture/CaptureReviewDialog.kt:697:                    shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/capture/CaptureReviewDialog.kt:715:        shape = RoundedCornerShape(999.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/capture/CaptureReviewDialog.kt:808:        shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/capture/CaptureReviewDialog.kt:845:        shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/capture/CaptureReviewDialog.kt:866:                            .clip(RoundedCornerShape(12.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/diagnostics/TransactionDiagnosticsScreen.kt:424:            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/diagnostics/TransactionDiagnosticsScreen.kt:463:                    shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/diagnostics/TransactionDiagnosticsScreen.kt:506:                    shape = RoundedCornerShape(14.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/diagnostics/TransactionDiagnosticsScreen.kt:515:                    shape = RoundedCornerShape(14.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/diagnostics/TransactionDiagnosticsScreen.kt:519:                    shape = RoundedCornerShape(14.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/diagnostics/TransactionDiagnosticsScreen.kt:590:                        shape = RoundedCornerShape(14.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/diagnostics/TransactionDiagnosticsScreen.kt:631:                        shape = RoundedCornerShape(14.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/diagnostics/TransactionDiagnosticsScreen.kt:873:                shape = RoundedCornerShape(6.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/diagnostics/TransactionDiagnosticsScreen.kt:948:                    shape = RoundedCornerShape(8.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/diagnostics/TransactionDiagnosticsScreen.kt:1062:        shape = RoundedCornerShape(999.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/diagnostics/TransactionDiagnosticsScreen.kt:1238:                        shape = RoundedCornerShape(8.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/AboutDialog.kt:45:            shape = RoundedCornerShape(50),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/AboutDialog.kt:59:            shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/AboutDialog.kt:166:            shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/FeedbackDialog.kt:95:            shape = RoundedCornerShape(14.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/FeedbackDialog.kt:107:            shape = RoundedCornerShape(14.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/FeedbackDialog.kt:146:            shape = RoundedCornerShape(14.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/FeedbackDialog.kt:164:            shape = RoundedCornerShape(14.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/KeepAlivePanel.kt:179:        shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/MerchantRulesScreen.kt:201:                shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/MerchantRulesScreen.kt:339:    val shape = RoundedCornerShape(16.dp)
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/MerchantRulesScreen.kt:455:                    shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/MerchantRulesScreen.kt:485:                        val shape = RoundedCornerShape(16.dp)
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/MerchantRulesScreen.kt:659:                        .clip(RoundedCornerShape(14.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/MerchantRulesScreen.kt:694:                    shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/MerchantRulesScreen.kt:708:                    shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/MerchantRulesScreen.kt:784:        shape = RoundedCornerShape(14.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/MerchantRulesScreen.kt:825:    val shape = RoundedCornerShape(16.dp)
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/MerchantRulesScreen.kt:870:            .clip(RoundedCornerShape(50))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/MerchantRulesScreen.kt:880:        shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/MerchantRulesScreen.kt:1017:                    .clip(RoundedCornerShape(14.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/MerchantRulesScreen.kt:1048:                    .clip(RoundedCornerShape(14.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/MerchantRulesScreen.kt:1086:                        shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/MerchantRulesScreen.kt:1104:                    shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/SettingsScreen.kt:596:                shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/SettingsScreen.kt:618:                shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/SettingsScreen.kt:645:                shape = RoundedCornerShape(14.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/SettingsScreen.kt:1049:        shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/SettingsScreen.kt:1091:                shape = RoundedCornerShape(50),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/SettingsScreen.kt:1114:            shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/SettingsScreen.kt:1143:            shape = RoundedCornerShape(14.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/SettingsScreen.kt:1177:            shape = RoundedCornerShape(24.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/SettingsScreen.kt:1475:            shape = RoundedCornerShape(14.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/SettingsScreen.kt:1529:        shape = RoundedCornerShape(14.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/CategoryColors.kt:15:    TransactionCategory.FOOD          to Color(0xFFE8590C), // 橙 - 餐饮
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/CategoryColors.kt:16:    TransactionCategory.EDUCATION     to Color(0xFF74B816), // 黄绿 - 教育
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/CategoryColors.kt:17:    TransactionCategory.BONUS         to Color(0xFFFAB005), // 琥珀 - 奖金
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/CategoryColors.kt:18:    TransactionCategory.SALARY        to Color(0xFF2F9E44), // 绿 - 工资
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/CategoryColors.kt:19:    TransactionCategory.UTILITIES     to Color(0xFF0CA678), // 青绿 - 水电
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/CategoryColors.kt:20:    TransactionCategory.INVESTMENT    to Color(0xFF1098AD), // 蓝绿 - 理财
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/CategoryColors.kt:21:    TransactionCategory.TRANSPORT     to Color(0xFF1C7ED6), // 蓝 - 交通
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/CategoryColors.kt:22:    TransactionCategory.INSURANCE     to Color(0xFF4263EB), // 靛蓝 - 保险
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/CategoryColors.kt:23:    TransactionCategory.HOUSING       to Color(0xFF7048E8), // 紫罗兰 - 住房
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/CategoryColors.kt:24:    TransactionCategory.SHOPPING      to Color(0xFFAE3EC9), // 品红紫 - 购物
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/CategoryColors.kt:25:    TransactionCategory.ENTERTAINMENT to Color(0xFFE64980), // 粉 - 娱乐
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/CategoryColors.kt:26:    TransactionCategory.GIFT          to Color(0xFFF03E3E), // 红 - 红包
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/CategoryColors.kt:27:    TransactionCategory.HEALTH        to Color(0xFF8C6D3F), // 棕 - 医疗
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/CategoryColors.kt:28:    TransactionCategory.TRANSFER      to Color(0xFF343A40), // 深灰 - 转账
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/CategoryColors.kt:29:    TransactionCategory.OTHER         to Color(0xFF868E96), // 中灰 - 其他
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/CategoryColors.kt:33:private val CATEGORY_FALLBACK_COLOR = Color(0xFF868E96)
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:451:    val shape = RoundedCornerShape(14.dp)
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:495:    val pillShape = RoundedCornerShape(10.dp)
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:571:    val containerShape = RoundedCornerShape(50)
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:609:    val shape = RoundedCornerShape(50)
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:639:        shape = RoundedCornerShape(14.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:678:    val shape = RoundedCornerShape(20.dp)
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:715:                            .clip(RoundedCornerShape(10.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:759:                                .clip(RoundedCornerShape(50))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:768:                                        .clip(RoundedCornerShape(50))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:781:                        .clip(RoundedCornerShape(10.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:822:        shape = RoundedCornerShape(20.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:836:                    .clip(RoundedCornerShape(50))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:881:                    shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:894:                    shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:925:            .clip(RoundedCornerShape(20.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:942:                    .clip(RoundedCornerShape(50))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:1008:        shape = RoundedCornerShape(20.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:1104:            .clip(RoundedCornerShape(16.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:1106:        shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:1150:        shape = RoundedCornerShape(20.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:1175:                        .clip(RoundedCornerShape(8.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:1222:                            .clip(RoundedCornerShape(50))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:1229:                                .clip(RoundedCornerShape(50))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:1246:        shape = RoundedCornerShape(20.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:1284:                            .clip(RoundedCornerShape(50))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:1291:                                .clip(RoundedCornerShape(50))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:1320:        shape = RoundedCornerShape(20.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:1350:                            .clip(RoundedCornerShape(50))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:1387:                        .clip(RoundedCornerShape(10.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:1548:            .clip(RoundedCornerShape(20.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:1668:        shape = RoundedCornerShape(20.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:1773:    val shape = RoundedCornerShape(20.dp)
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:1823:            .clip(RoundedCornerShape(8.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:1833:                .clip(RoundedCornerShape(12.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:1872:    val shape = RoundedCornerShape(20.dp)
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:1898:                        .clip(RoundedCornerShape(50))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:1975:    val shape = RoundedCornerShape(20.dp)
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:2003:                        .clip(RoundedCornerShape(50))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:2025:                            .clip(RoundedCornerShape(50))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:2036:                                    .clip(RoundedCornerShape(50))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:2065:    val shape = RoundedCornerShape(20.dp)
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:2082:                    .clip(RoundedCornerShape(50))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:2210:                    shape = RoundedCornerShape(20.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:2244:                            shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:2258:                            shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:2274:                        shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:2372:                    shape = RoundedCornerShape(20.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:2519:    val shape = RoundedCornerShape(50)
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:2562:    val shape = RoundedCornerShape(50)
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:2618:    val shape = RoundedCornerShape(50)
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/EditSubscriptionSheet.kt:210:        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/EditSubscriptionSheet.kt:246:                    shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/EditSubscriptionSheet.kt:259:                                    .clip(RoundedCornerShape(10.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/EditSubscriptionSheet.kt:261:                                shape = RoundedCornerShape(10.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/EditSubscriptionSheet.kt:322:                                .clip(RoundedCornerShape(10.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/EditSubscriptionSheet.kt:324:                            shape = RoundedCornerShape(10.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/EditSubscriptionSheet.kt:369:                    shape = RoundedCornerShape(14.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/EditSubscriptionSheet.kt:466:                shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/EditSubscriptionSheet.kt:553:                shape = RoundedCornerShape(14.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/EditSubscriptionSheet.kt:626:                shape = RoundedCornerShape(14.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/EditSubscriptionSheet.kt:710:        shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/EditSubscriptionSheet.kt:774:            shape = RoundedCornerShape(20.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/EditSubscriptionSheet.kt:970:                        shape = RoundedCornerShape(10.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/EditSubscriptionSheet.kt:984:            .clip(RoundedCornerShape(8.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/SubscriptionDetailSheet.kt:114:        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/SubscriptionDetailSheet.kt:186:                shape = RoundedCornerShape(18.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/SubscriptionDetailSheet.kt:265:                shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/SubscriptionDetailSheet.kt:340:                    shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/SubscriptionDetailSheet.kt:362:                    shape = RoundedCornerShape(14.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/SubscriptionDetailSheet.kt:391:                    shape = RoundedCornerShape(14.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/SubscriptionDetailSheet.kt:412:                        shape = RoundedCornerShape(14.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/SubscriptionDetailSheet.kt:432:                        shape = RoundedCornerShape(14.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/SubscriptionDetailSheet.kt:534:                .clip(RoundedCornerShape(8.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/SubscriptionDetailSheet.kt:536:                .border(1.dp, QingOutline, RoundedCornerShape(8.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/SubscriptionDetailSheet.kt:556:                .clip(RoundedCornerShape(8.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/SubscriptionDetailSheet.kt:558:                .border(1.dp, QingOutline, RoundedCornerShape(8.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/SubscriptionDetailSheet.kt:582:            .clip(RoundedCornerShape(size * 0.30f))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/SubscriptionsManageScreen.kt:216:                    shape = RoundedCornerShape(14.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/SubscriptionsManageScreen.kt:393:        shape = RoundedCornerShape(18.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/SubscriptionsManageScreen.kt:456:            .clip(RoundedCornerShape(14.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/SubscriptionsManageScreen.kt:458:        shape = RoundedCornerShape(14.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/SubscriptionsManageScreen.kt:513:        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/SubscriptionsManageScreen.kt:571:                            shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/SubscriptionsManageScreen.kt:626:                                        shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/SubscriptionsManageScreen.kt:641:                                        shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/SubscriptionsManageScreen.kt:668:        shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/SubscriptionsManageScreen.kt:722:            .clip(RoundedCornerShape(16.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/SubscriptionsManageScreen.kt:724:        shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/SubscriptionsManageScreen.kt:812:        shape = RoundedCornerShape(18.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/AddTransactionDialog.kt:499:                    shape = RoundedCornerShape(18.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/AddTransactionDialog.kt:569:                            shape = RoundedCornerShape(50),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/AddTransactionDialog.kt:735:                shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/AddTransactionDialog.kt:782:            shape = RoundedCornerShape(14.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/AddTransactionDialog.kt:850:        shape = RoundedCornerShape(50),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/AddTransactionDialog.kt:866:                        .clip(RoundedCornerShape(50))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/AddTransactionDialog.kt:868:                    shape = RoundedCornerShape(50),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/AddTransactionDialog.kt:897:        shape = RoundedCornerShape(14.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/AddTransactionDialog.kt:929:        shape = RoundedCornerShape(14.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/AddTransactionDialog.kt:970:        shape = RoundedCornerShape(50),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/AddTransactionDialog.kt:1062:        shape = RoundedCornerShape(50),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/LedgerTransactionRow.kt:97:                shape = RoundedCornerShape(14.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionDetailScreen.kt:317:                        .clip(RoundedCornerShape(6.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionDetailScreen.kt:388:            .clip(RoundedCornerShape(16.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionDetailScreen.kt:453:        shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionDetailScreen.kt:482:                                    .clip(RoundedCornerShape(4.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionDetailScreen.kt:505:                        .clip(RoundedCornerShape(10.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionDetailScreen.kt:532:                            .clip(RoundedCornerShape(6.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionDetailScreen.kt:542:                            .clip(RoundedCornerShape(6.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionDetailScreen.kt:569:            .clip(RoundedCornerShape(16.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionDetailScreen.kt:571:        shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionDetailScreen.kt:589:                        .clip(RoundedCornerShape(10.dp)),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionDetailScreen.kt:595:                        .clip(RoundedCornerShape(10.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionDetailScreen.kt:630:        shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionDetailScreen.kt:657:        shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionDetailScreen.kt:742:private val ACTION_BUTTON_SHAPE = RoundedCornerShape(12.dp)
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionEditScreen.kt:209:                            shape = RoundedCornerShape(14.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionEditScreen.kt:221:                            shape = RoundedCornerShape(14.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionEditScreen.kt:230:                            shape = RoundedCornerShape(14.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionEditScreen.kt:240:        val fieldShape = RoundedCornerShape(14.dp)
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionEditScreen.kt:259:                shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionEditScreen.kt:436:                shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionEditScreen.kt:530:        shape = RoundedCornerShape(50),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionEditScreen.kt:578:                shape = RoundedCornerShape(14.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionListScreen.kt:961:        shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionListScreen.kt:1114:        shape = RoundedCornerShape(18.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionListScreen.kt:1160:        shape = RoundedCornerShape(18.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionListScreen.kt:1174:                    shape = RoundedCornerShape(50),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionListScreen.kt:1195:                    shape = RoundedCornerShape(14.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionListScreen.kt:1222:                                shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionListScreen.kt:1245:                                shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionListScreen.kt:1260:                                shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionListScreen.kt:1306:        shape = RoundedCornerShape(20.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionListScreen.kt:1394:        shape = RoundedCornerShape(14.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/VoiceCapturePanel.kt:507:        shape = RoundedCornerShape(50),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/VoiceCapturePanel.kt:518:                    .background(dotColor, RoundedCornerShape(50)),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/VoiceCapturePanel.kt:572:        shape = RoundedCornerShape(18.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/VoiceCapturePanel.kt:696:                shape = RoundedCornerShape(14.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/VoiceCapturePanel.kt:705:            shape = RoundedCornerShape(14.dp),
~~~

## Candidate counts

- Android non-theme color-literal lines: 19
- Android non-theme RoundedCornerShape lines: 262
- Android noncanonical literal dp radius lines: 137 (including documented page-specific exceptions; review before migrating)
- Web CSS color/radius candidate lines: 140

## Noncanonical literal radius candidates

~~~text
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/AutomationSetupPrompt.kt:57:                    .clip(RoundedCornerShape(12.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/EmptyState.kt:62:        shape = RoundedCornerShape(18.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/LedgerCalendarDialog.kt:377:            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/LedgerDateTimePickerDialog.kt:117:            shape = RoundedCornerShape(20.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/LedgerDateTimePickerDialog.kt:194:                            .clip(RoundedCornerShape(12.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/LedgerDateTimePickerDialog.kt:269:                        shape = RoundedCornerShape(10.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/LedgerDateTimePickerDialog.kt:307:            .clip(RoundedCornerShape(9.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/LedgerDateTimePickerDialog.kt:577:            .clip(RoundedCornerShape(10.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/MerchantRuleDialog.kt:178:                    shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/MerchantRuleDialog.kt:196:                    shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/MonthPickerDialog.kt:102:                                        .clip(RoundedCornerShape(10.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/SearchBar.kt:34:        shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/SiteUpdateBanner.kt:46:        shape = RoundedCornerShape(18.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/StatusChip.kt:116:            .clip(RoundedCornerShape(6.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/SummaryCard.kt:37:            .clip(RoundedCornerShape(20.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/SummaryCard.kt:39:            .border(1.dp, QingOutline, RoundedCornerShape(20.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/VoiceHintBubble.kt:46:                .shadow(8.dp, RoundedCornerShape(12.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/components/VoiceHintBubble.kt:47:                .background(bubbleColor, RoundedCornerShape(12.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/billimport/BillImportScreen.kt:421:        shape = RoundedCornerShape(18.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/billimport/BillImportScreen.kt:437:                shape = RoundedCornerShape(10.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/billimport/BillImportScreen.kt:546:        shape = RoundedCornerShape(18.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/billimport/BillImportScreen.kt:715:        shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/billimport/BillImportScreen.kt:825:                    shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/billimport/BillImportScreen.kt:863:                    shape = RoundedCornerShape(10.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/billimport/BillImportScreen.kt:1107:                RoundedCornerShape(10.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/budget/BudgetScreen.kt:479:        shape = RoundedCornerShape(20.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/budget/BudgetScreen.kt:647:                    .clip(RoundedCornerShape(12.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/budget/EditBudgetDialog.kt:91:            shape = RoundedCornerShape(24.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/calendar/CalendarScreen.kt:1786:                        .clip(RoundedCornerShape(10.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/calendar/CalendarScreen.kt:1900:        shape = RoundedCornerShape(18.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/capture/CaptureImagePreview.kt:252:                    .clip(RoundedCornerShape(20.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/capture/CaptureReviewCard.kt:80:        shape = RoundedCornerShape(18.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/capture/CaptureReviewDialog.kt:137:            shape = RoundedCornerShape(24.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/capture/CaptureReviewDialog.kt:244:                        shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/capture/CaptureReviewDialog.kt:423:        shape = RoundedCornerShape(18.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/capture/CaptureReviewDialog.kt:553:                    shape = RoundedCornerShape(10.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/capture/CaptureReviewDialog.kt:568:                    shape = RoundedCornerShape(10.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/capture/CaptureReviewDialog.kt:582:                    shape = RoundedCornerShape(10.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/capture/CaptureReviewDialog.kt:630:                shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/capture/CaptureReviewDialog.kt:647:                        shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/capture/CaptureReviewDialog.kt:697:                    shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/capture/CaptureReviewDialog.kt:808:        shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/capture/CaptureReviewDialog.kt:845:        shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/capture/CaptureReviewDialog.kt:866:                            .clip(RoundedCornerShape(12.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/diagnostics/TransactionDiagnosticsScreen.kt:463:                    shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/diagnostics/TransactionDiagnosticsScreen.kt:873:                shape = RoundedCornerShape(6.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/AboutDialog.kt:59:            shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/AboutDialog.kt:166:            shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/KeepAlivePanel.kt:179:        shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/MerchantRulesScreen.kt:201:                shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/MerchantRulesScreen.kt:339:    val shape = RoundedCornerShape(16.dp)
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/MerchantRulesScreen.kt:455:                    shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/MerchantRulesScreen.kt:485:                        val shape = RoundedCornerShape(16.dp)
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/MerchantRulesScreen.kt:694:                    shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/MerchantRulesScreen.kt:708:                    shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/MerchantRulesScreen.kt:825:    val shape = RoundedCornerShape(16.dp)
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/MerchantRulesScreen.kt:880:        shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/MerchantRulesScreen.kt:1086:                        shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/MerchantRulesScreen.kt:1104:                    shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/SettingsScreen.kt:596:                shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/SettingsScreen.kt:618:                shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/SettingsScreen.kt:1049:        shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/SettingsScreen.kt:1114:            shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/SettingsScreen.kt:1177:            shape = RoundedCornerShape(24.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:495:    val pillShape = RoundedCornerShape(10.dp)
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:678:    val shape = RoundedCornerShape(20.dp)
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:715:                            .clip(RoundedCornerShape(10.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:781:                        .clip(RoundedCornerShape(10.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:822:        shape = RoundedCornerShape(20.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:881:                    shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:894:                    shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:925:            .clip(RoundedCornerShape(20.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:1008:        shape = RoundedCornerShape(20.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:1104:            .clip(RoundedCornerShape(16.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:1106:        shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:1150:        shape = RoundedCornerShape(20.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:1246:        shape = RoundedCornerShape(20.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:1320:        shape = RoundedCornerShape(20.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:1387:                        .clip(RoundedCornerShape(10.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:1548:            .clip(RoundedCornerShape(20.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:1668:        shape = RoundedCornerShape(20.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:1773:    val shape = RoundedCornerShape(20.dp)
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:1833:                .clip(RoundedCornerShape(12.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:1872:    val shape = RoundedCornerShape(20.dp)
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:1975:    val shape = RoundedCornerShape(20.dp)
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:2065:    val shape = RoundedCornerShape(20.dp)
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:2210:                    shape = RoundedCornerShape(20.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:2244:                            shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:2258:                            shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:2274:                        shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/stats/StatsScreen.kt:2372:                    shape = RoundedCornerShape(20.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/EditSubscriptionSheet.kt:246:                    shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/EditSubscriptionSheet.kt:259:                                    .clip(RoundedCornerShape(10.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/EditSubscriptionSheet.kt:261:                                shape = RoundedCornerShape(10.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/EditSubscriptionSheet.kt:322:                                .clip(RoundedCornerShape(10.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/EditSubscriptionSheet.kt:324:                            shape = RoundedCornerShape(10.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/EditSubscriptionSheet.kt:466:                shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/EditSubscriptionSheet.kt:710:        shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/EditSubscriptionSheet.kt:774:            shape = RoundedCornerShape(20.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/EditSubscriptionSheet.kt:970:                        shape = RoundedCornerShape(10.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/SubscriptionDetailSheet.kt:186:                shape = RoundedCornerShape(18.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/SubscriptionDetailSheet.kt:265:                shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/SubscriptionDetailSheet.kt:340:                    shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/SubscriptionsManageScreen.kt:393:        shape = RoundedCornerShape(18.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/SubscriptionsManageScreen.kt:571:                            shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/SubscriptionsManageScreen.kt:626:                                        shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/SubscriptionsManageScreen.kt:641:                                        shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/SubscriptionsManageScreen.kt:668:        shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/SubscriptionsManageScreen.kt:722:            .clip(RoundedCornerShape(16.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/SubscriptionsManageScreen.kt:724:        shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/subscriptions/SubscriptionsManageScreen.kt:812:        shape = RoundedCornerShape(18.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/AddTransactionDialog.kt:499:                    shape = RoundedCornerShape(18.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/AddTransactionDialog.kt:735:                shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionDetailScreen.kt:317:                        .clip(RoundedCornerShape(6.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionDetailScreen.kt:388:            .clip(RoundedCornerShape(16.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionDetailScreen.kt:453:        shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionDetailScreen.kt:482:                                    .clip(RoundedCornerShape(4.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionDetailScreen.kt:505:                        .clip(RoundedCornerShape(10.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionDetailScreen.kt:532:                            .clip(RoundedCornerShape(6.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionDetailScreen.kt:542:                            .clip(RoundedCornerShape(6.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionDetailScreen.kt:569:            .clip(RoundedCornerShape(16.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionDetailScreen.kt:571:        shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionDetailScreen.kt:589:                        .clip(RoundedCornerShape(10.dp)),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionDetailScreen.kt:595:                        .clip(RoundedCornerShape(10.dp))
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionDetailScreen.kt:630:        shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionDetailScreen.kt:657:        shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionDetailScreen.kt:742:private val ACTION_BUTTON_SHAPE = RoundedCornerShape(12.dp)
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionEditScreen.kt:259:                shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionEditScreen.kt:436:                shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionListScreen.kt:961:        shape = RoundedCornerShape(16.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionListScreen.kt:1114:        shape = RoundedCornerShape(18.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionListScreen.kt:1160:        shape = RoundedCornerShape(18.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionListScreen.kt:1222:                                shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionListScreen.kt:1245:                                shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionListScreen.kt:1260:                                shape = RoundedCornerShape(12.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/TransactionListScreen.kt:1306:        shape = RoundedCornerShape(20.dp),
HEAD:android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/VoiceCapturePanel.kt:572:        shape = RoundedCornerShape(18.dp),
~~~

## Web CSS inventory

~~~text
HEAD:deploy/wallos-extension/dashboard-refresh.css:3:  --om-line: var(--om-border, #E2E5E2);
HEAD:deploy/wallos-extension/dashboard-refresh.css:4:  --om-bg: var(--om-surface, #FFFFFF);
HEAD:deploy/wallos-extension/dashboard-refresh.css:5:  --om-text: var(--om-ink, #151918);
HEAD:deploy/wallos-extension/dashboard-refresh.css:6:  --om-muted: var(--om-secondary, #5E605F);
HEAD:deploy/wallos-extension/dashboard-refresh.css:7:  --om-accent: var(--om-primary, #24705C);
HEAD:deploy/wallos-extension/dashboard-refresh.css:16:.om-home .finance-metric[data-finance-detail="expense"] strong { color: var(--om-expense, #B23A3A); }
HEAD:deploy/wallos-extension/dashboard-refresh.css:17:.om-home .finance-metric[data-finance-detail="income"] strong { color: var(--om-income, #1F7A55); }
HEAD:deploy/wallos-extension/dashboard-refresh.css:38:  border-radius: var(--om-radius, 16px);
HEAD:deploy/wallos-extension/dashboard-refresh.css:67:  border-radius: var(--om-radius, 16px);
HEAD:deploy/wallos-extension/dashboard-refresh.css:93:  border-radius: var(--om-radius, 16px);
HEAD:deploy/wallos-extension/dashboard-refresh.css:103:  border-radius: 999px;
HEAD:deploy/wallos-extension/dashboard-refresh.css:108:.om-budget-track > span { display: block; height: 100%; background: var(--om-accent); border-radius: 999px; }
HEAD:deploy/wallos-extension/dashboard-refresh.css:119:.om-budget-track.is-over-budget > span { background: var(--om-expense, #B23A3A); }
HEAD:deploy/wallos-extension/dashboard-refresh.css:120:.om-budget-foot .om-budget-over { color: var(--om-expense, #B23A3A); font-weight: 650; }
HEAD:deploy/wallos-extension/dashboard-refresh.css:125:  border-radius: var(--om-radius, 16px);
HEAD:deploy/wallos-extension/dashboard-refresh.css:162:  border-radius: var(--om-radius-sm, 10px);
HEAD:deploy/wallos-extension/dashboard-refresh.css:163:  background: var(--om-surface-low, #F7F8F7);
HEAD:deploy/wallos-extension/dashboard-refresh.css:188:  border-radius: 999px;
HEAD:deploy/wallos-extension/dashboard-refresh.css:189:  background: var(--om-expense-bg, #FDECEC);
HEAD:deploy/wallos-extension/dashboard-refresh.css:190:  color: var(--om-expense, #B23A3A);
HEAD:deploy/wallos-extension/dashboard-refresh.css:206:  border-radius: var(--om-radius-sm, 10px);
HEAD:deploy/wallos-extension/finance-unified.css:2:.finance-native{--fin-accent:var(--om-primary,var(--main-color,#24705C));--fin-fg:var(--om-ink,var(--text-color,#151918));--fin-muted:var(--om-secondary,var(--text-muted,#5E605F));--fin-panel:var(--om-surface,var(--box-background-color,#fff));--fin-border:var(--om-border,var(--box-border-color,#E2E5E2));color:var(--fin-fg);padding:14px 0 24px;}
HEAD:deploy/wallos-extension/finance-unified.css:7:.finance-native-heading input{min-height:37px;padding:6px 10px;border:1px solid var(--om-field-border,var(--fin-border));background:var(--fin-panel);border-radius:var(--om-radius-sm,10px);color:var(--fin-fg);max-width:165px;}
HEAD:deploy/wallos-extension/finance-unified.css:10:.finance-metric{display:flex;flex-direction:column;align-items:flex-start;gap:8px;min-width:0;padding:18px;border:1px solid var(--fin-border);border-radius:var(--om-radius,16px);color:var(--fin-fg);background:var(--fin-panel);box-shadow:var(--om-shadow,none);font:inherit;text-align:left;transition:border-color .15s ease,box-shadow .15s ease;}
HEAD:deploy/wallos-extension/finance-unified.css:15:.finance-budget,.finance-chart-section,.finance-comparison{background:var(--fin-panel);border:1px solid var(--fin-border);border-radius:var(--om-radius,16px);box-shadow:var(--om-shadow,none);padding:18px;margin:12px 0;}
HEAD:deploy/wallos-extension/finance-unified.css:16:.finance-progress{height:9px;border-radius:999px;background:color-mix(in srgb,var(--fin-accent) 15%,transparent);overflow:hidden;margin:12px 0;}
HEAD:deploy/wallos-extension/finance-unified.css:17:.finance-progress>div{height:100%;background:var(--fin-accent);border-radius:999px;max-width:100%;transition:width .2s ease;}
HEAD:deploy/wallos-extension/finance-unified.css:20:.finance-budget-form input{width:150px;min-height:36px;border:1px solid var(--om-field-border,var(--fin-border));background:var(--fin-panel);color:var(--fin-fg);border-radius:var(--om-radius-sm,10px);padding:6px 10px;}
HEAD:deploy/wallos-extension/finance-unified.css:21:.finance-budget-form button,.finance-tab,.finance-switch button,.finance-category-row,.finance-day-chip{background:var(--fin-panel);border:1px solid var(--fin-border);border-radius:var(--om-radius-sm,10px);color:var(--fin-fg);padding:8px 14px;cursor:pointer;font:inherit;}
HEAD:deploy/wallos-extension/finance-unified.css:23:.finance-switch{display:inline-flex;gap:4px;background:var(--om-surface-variant,#F0F2F0);padding:3px;border-radius:var(--om-radius-pill,999px);border:1px solid var(--fin-border);}
HEAD:deploy/wallos-extension/finance-unified.css:24:.finance-switch button{font-size:12px;min-width:54px;border:0;background:transparent;border-radius:var(--om-radius-pill,999px);padding:6px 12px;}
HEAD:deploy/wallos-extension/finance-unified.css:25:.finance-switch .is-current{background:var(--fin-accent);color:#fff;font-weight:600;}
HEAD:deploy/wallos-extension/finance-unified.css:31:.finance-category-row:hover,.finance-category-row:focus-visible{border-color:var(--fin-accent);background:var(--om-surface-low,#F7F8F7);}
HEAD:deploy/wallos-extension/finance-unified.css:32:.finance-category-track{height:9px;border-radius:999px;background:color-mix(in srgb,var(--fin-accent) 12%,transparent);overflow:hidden;}
HEAD:deploy/wallos-extension/finance-unified.css:33:.finance-category-fill{display:block;height:100%;background:var(--fin-accent);border-radius:999px;}
HEAD:deploy/wallos-extension/finance-unified.css:35:.finance-subscription{padding:12px 14px;border:1px solid var(--fin-border);border-radius:var(--om-radius-sm,10px);display:flex;justify-content:space-between;gap:10px;align-items:center;background:var(--fin-panel);}
HEAD:deploy/wallos-extension/finance-unified.css:43:.finance-day-dialog{min-width:0;width:min(590px,calc(100vw - 24px));max-width:calc(100vw - 24px);max-height:min(86vh,750px);overflow-y:auto;overscroll-behavior:contain;border:1px solid var(--fin-border,#dde3eb);border-radius:17px;background:var(--box-background-color,#fff);color:var(--text-color,#111);padding:17px;box-shadow:0 12px 44px #0003;}
HEAD:deploy/wallos-extension/finance-unified.css:44:.finance-day-dialog::backdrop{background:#0007;}
HEAD:deploy/wallos-extension/finance-unified.css:45:.finance-day-dialog header{position:sticky;top:-17px;z-index:2;display:flex;align-items:center;justify-content:space-between;gap:8px;margin:0 0 12px;padding:12px 0;background:var(--box-background-color,#fff);border-bottom:1px solid var(--box-border-color,#ddd);}
HEAD:deploy/wallos-extension/finance-unified.css:47:.finance-day-dialog header button{border:1px solid var(--box-border-color,#ddd);border-radius:8px;background:transparent;color:inherit;padding:6px 11px;cursor:pointer;}
HEAD:deploy/wallos-extension/finance-unified.css:50:.finance-day-amounts>div{padding:12px 7px;background:color-mix(in srgb,var(--main-color) 7%,var(--box-background-color));border-radius:9px;}
HEAD:deploy/wallos-extension/finance-unified.css:53:.finance-day-entry{padding:13px 0;border-bottom:1px solid var(--box-border-color,#ddd);}
HEAD:deploy/wallos-extension/finance-unified.css:59:.finance-time-pending{display:inline-block;flex:0 0 auto;padding:2px 6px;border-radius:6px;background:color-mix(in srgb,#d99b00 15%,var(--box-background-color));color:#765400;font-size:11px;white-space:nowrap;}
HEAD:deploy/wallos-extension/finance-unified.css:60:.finance-entry-badge.is-excluded{background:color-mix(in srgb,var(--main-color) 8%,transparent);padding:2px 6px;border-radius:6px;}
HEAD:deploy/wallos-extension/finance-unified.css:70:.finance-day-entry input,.finance-day-entry select{min-width:0;max-width:100%;width:100%;background:var(--input-background-color,var(--box-background-color));border:1px solid var(--input-border-color,var(--box-border-color));color:inherit;border-radius:7px;min-height:35px;padding:7px;}
HEAD:deploy/wallos-extension/finance-unified.css:71:.finance-day-entry button{background:var(--main-color);border:0;color:white;border-radius:7px;padding:8px 13px;cursor:pointer;}
HEAD:deploy/wallos-extension/finance-unified.css:75:.finance-day-plan{padding:10px;background:color-mix(in srgb,var(--main-color) 6%,transparent);border-radius:9px;margin-top:12px;}
HEAD:deploy/wallos-extension/finance-unified.css:77:.finance-unavailable{border:1px dashed var(--box-border-color);padding:15px;border-radius:10px;color:var(--text-muted);}
HEAD:deploy/wallos-extension/finance-unified.css:78:@media(max-width:850px){.finance-metric-grid{grid-template-columns:repeat(2,minmax(0,1fr));}.finance-metric{padding:12px 10px;}.finance-day-dialog{position:fixed;left:0;right:0;top:auto;bottom:0;margin:0 auto;border-radius:17px 17px 0 0;width:100%;max-width:100vw;max-height:86dvh;padding:14px 14px max(16px,env(safe-area-inset-bottom));}}
HEAD:deploy/wallos-extension/finance-unified.css:89:  .calendar .finance-calendar-day .calendar-event{position:relative;width:min(30px,100%);min-height:24px;line-height:24px;padding:0;margin:0;overflow:hidden;font-size:0;text-align:center;border-radius:7px}
HEAD:deploy/wallos-extension/finance-unified.css:96:.finance-subscription-monthly-row{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:11px 12px;border:1px solid var(--fin-border);border-radius:9px;min-width:0;}
HEAD:deploy/wallos-extension/finance-unified.css:114:  border:2px solid var(--fin-accent);border-radius:5px;
HEAD:deploy/wallos-extension/finance-unified.css:120:.finance-native .finance-trend-focus-zero{border-radius:50%;background:var(--fin-accent)}
HEAD:deploy/wallos-extension/finance-unified.css:135:  border-radius:8px;background:var(--fin-panel);color:var(--fin-fg);font:inherit;
HEAD:deploy/wallos-extension/finance-unified.css:139:  border:2px solid var(--fin-accent);border-radius:11px;
HEAD:deploy/wallos-extension/ui-unified.css:2: * Aligns 1:1 with Android Material 3 Forest Green (#24705C) & Warm Alabaster (#F6F5F4).
HEAD:deploy/wallos-extension/ui-unified.css:9:  --om-primary: #24705C;
HEAD:deploy/wallos-extension/ui-unified.css:10:  --om-primary-deep: #0E5336;
HEAD:deploy/wallos-extension/ui-unified.css:11:  --om-primary-container: #E8F5EE;
HEAD:deploy/wallos-extension/ui-unified.css:12:  --om-mint: #97E0B9;
HEAD:deploy/wallos-extension/ui-unified.css:13:  --om-mint-soft: #DCF0E7;
HEAD:deploy/wallos-extension/ui-unified.css:15:  --om-bg: #F6F5F4;
HEAD:deploy/wallos-extension/ui-unified.css:16:  --om-surface: #FFFFFF;
HEAD:deploy/wallos-extension/ui-unified.css:17:  --om-surface-low: #F7F8F7;
HEAD:deploy/wallos-extension/ui-unified.css:18:  --om-surface-variant: #F0F2F0;
HEAD:deploy/wallos-extension/ui-unified.css:19:  --om-ink: #151918;
HEAD:deploy/wallos-extension/ui-unified.css:20:  --om-secondary: #5E605F;
HEAD:deploy/wallos-extension/ui-unified.css:21:  --om-border: #E2E5E2;
HEAD:deploy/wallos-extension/ui-unified.css:22:  --om-field-border: #C4CCC7;
HEAD:deploy/wallos-extension/ui-unified.css:24:  --om-expense: #B23A3A;
HEAD:deploy/wallos-extension/ui-unified.css:25:  --om-expense-bg: #FDECEC;
HEAD:deploy/wallos-extension/ui-unified.css:26:  --om-income: #1F7A55;
HEAD:deploy/wallos-extension/ui-unified.css:27:  --om-income-bg: #E8F5EE;
HEAD:deploy/wallos-extension/ui-unified.css:28:  --om-warning: #8A5A12;
HEAD:deploy/wallos-extension/ui-unified.css:29:  --om-warning-bg: #FFF4DE;
HEAD:deploy/wallos-extension/ui-unified.css:56:  --om-primary: #8FD9BC;
HEAD:deploy/wallos-extension/ui-unified.css:57:  --om-primary-deep: #5CB896;
HEAD:deploy/wallos-extension/ui-unified.css:58:  --om-primary-container: #1B332A;
HEAD:deploy/wallos-extension/ui-unified.css:59:  --om-mint: #5CB896;
HEAD:deploy/wallos-extension/ui-unified.css:60:  --om-mint-soft: #1F3B30;
HEAD:deploy/wallos-extension/ui-unified.css:62:  --om-bg: #101412;
HEAD:deploy/wallos-extension/ui-unified.css:63:  --om-surface: #18201D;
HEAD:deploy/wallos-extension/ui-unified.css:64:  --om-surface-low: #141A17;
HEAD:deploy/wallos-extension/ui-unified.css:65:  --om-surface-variant: #25302B;
HEAD:deploy/wallos-extension/ui-unified.css:66:  --om-ink: #E8F0EB;
HEAD:deploy/wallos-extension/ui-unified.css:67:  --om-secondary: #A9BDB3;
HEAD:deploy/wallos-extension/ui-unified.css:68:  --om-border: #2C3A34;
HEAD:deploy/wallos-extension/ui-unified.css:69:  --om-field-border: #4A5C54;
HEAD:deploy/wallos-extension/ui-unified.css:71:  --om-expense: #FF8A80;
HEAD:deploy/wallos-extension/ui-unified.css:72:  --om-expense-bg: #4A1A18;
HEAD:deploy/wallos-extension/ui-unified.css:73:  --om-income: #8FD9BC;
HEAD:deploy/wallos-extension/ui-unified.css:74:  --om-income-bg: #1B332A;
HEAD:deploy/wallos-extension/ui-unified.css:75:  --om-warning: #F0C36A;
HEAD:deploy/wallos-extension/ui-unified.css:76:  --om-warning-bg: #4A3510;
HEAD:deploy/wallos-extension/ui-unified.css:85:    --om-primary: #8FD9BC;
HEAD:deploy/wallos-extension/ui-unified.css:86:    --om-primary-deep: #5CB896;
HEAD:deploy/wallos-extension/ui-unified.css:87:    --om-primary-container: #1B332A;
HEAD:deploy/wallos-extension/ui-unified.css:88:    --om-mint: #5CB896;
HEAD:deploy/wallos-extension/ui-unified.css:89:    --om-mint-soft: #1F3B30;
HEAD:deploy/wallos-extension/ui-unified.css:91:    --om-bg: #101412;
HEAD:deploy/wallos-extension/ui-unified.css:92:    --om-surface: #18201D;
HEAD:deploy/wallos-extension/ui-unified.css:93:    --om-surface-low: #141A17;
HEAD:deploy/wallos-extension/ui-unified.css:94:    --om-surface-variant: #25302B;
HEAD:deploy/wallos-extension/ui-unified.css:95:    --om-ink: #E8F0EB;
HEAD:deploy/wallos-extension/ui-unified.css:96:    --om-secondary: #A9BDB3;
HEAD:deploy/wallos-extension/ui-unified.css:97:    --om-border: #2C3A34;
HEAD:deploy/wallos-extension/ui-unified.css:98:    --om-field-border: #4A5C54;
HEAD:deploy/wallos-extension/ui-unified.css:100:    --om-expense: #FF8A80;
HEAD:deploy/wallos-extension/ui-unified.css:101:    --om-expense-bg: #4A1A18;
HEAD:deploy/wallos-extension/ui-unified.css:102:    --om-income: #8FD9BC;
HEAD:deploy/wallos-extension/ui-unified.css:103:    --om-income-bg: #1B332A;
HEAD:deploy/wallos-extension/ui-unified.css:104:    --om-warning: #F0C36A;
HEAD:deploy/wallos-extension/ui-unified.css:105:    --om-warning-bg: #4A3510;
HEAD:deploy/wallos-extension/ui-unified.css:162:  border-radius: 50%;
HEAD:deploy/wallos-extension/ui-unified.css:191:  border-radius: var(--om-radius-pill);
HEAD:deploy/wallos-extension/ui-unified.css:220:  border-radius: var(--om-radius-pill);
HEAD:deploy/wallos-extension/ui-unified.css:231:  border-radius: var(--om-radius-sm);
HEAD:deploy/wallos-extension/ui-unified.css:239:  border-radius: 7px;
HEAD:deploy/wallos-extension/ui-unified.css:287:  border-radius: var(--om-radius-sm);
HEAD:deploy/wallos-extension/ui-unified.css:289:  color: #FFFFFF;
HEAD:deploy/wallos-extension/ui-unified.css:300:  border-radius: var(--om-radius-sm);
HEAD:deploy/wallos-extension/ui-unified.css:312:  border-radius: var(--om-radius-sm);
HEAD:deploy/wallos-extension/ui-unified.css:332:  border-radius: var(--om-radius);
HEAD:deploy/wallos-extension/ui-unified.css:364:  border-radius: var(--om-radius);
HEAD:deploy/wallos-extension/ui-unified.css:388:  border-radius: var(--om-radius);
HEAD:deploy/wallos-extension/ui-unified.css:393:  border-radius: var(--om-radius);
HEAD:deploy/wallos-extension/ui-unified.css:409:  border-radius: var(--om-radius-pill);
HEAD:deploy/wallos-extension/ui-unified.css:419:  border-radius: var(--om-radius-pill);
HEAD:deploy/wallos-extension/ui-unified.css:429:  color: #FFFFFF;
HEAD:deploy/wallos-extension/ui-unified.css:434:  color: #0E231B;
HEAD:deploy/wallos-extension/ui-unified.css:471:  border-radius: var(--om-radius-sm);
HEAD:deploy/wallos-extension/ui-unified.css:477:  border-radius: var(--om-radius);
HEAD:deploy/wallos-extension/ui-unified.css:513:  border-radius: var(--om-radius-pill);
HEAD:deploy/wallos-extension/ui-unified.css:542:  border-radius: var(--om-radius);
HEAD:deploy/wallos-extension/ui-unified.css:557:  border-radius: var(--om-radius);
HEAD:deploy/wallos-extension/ui-unified.css:570:  border-radius: var(--om-radius);
HEAD:deploy/wallos-extension/ui-unified.css:574:.om-ui .om-subscriptions-page :where(.button, .view-toggle-button) { border-radius: var(--om-radius-sm); }
HEAD:deploy/wallos-extension/ui-unified.css:578:.om-ui .om-about-page .credits-list { border-radius: var(--om-radius); }
HEAD:deploy/wallos-extension/ui-unified.css:579:.om-ui .om-about-page .om-integration-intro { padding: 22px; border: 1px solid var(--om-border); border-radius: var(--om-radius); background: var(--om-surface); box-shadow: var(--om-shadow); }
HEAD:deploy/wallos-extension/ui-unified.css:585:  border: 1px solid var(--om-border); border-radius: 12px; display: flex; flex-direction: column; gap: 10px;
~~~

## Static checks

Existing CSS is text-identical after reversing the four legacy variable aliases and removing the appended V3 declarations. All business Kotlin sources, navigation, VoiceFab, localization, and runtime dependencies are unchanged. This does not replace device visual comparison.
