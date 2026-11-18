# 首次启动引导

引导在现有首页、面板、日历、统计、预算和导入入口上运行；欢迎和权限卡是 Activity 内的遮罩卡片，其余引导内容由独立 Dialog 提供。账本、分类和日历数据不注入演示记录。

## 状态

`OnboardingPreferences` 用 DataStore 保存 `onboarding_completed`、`onboarding_step` 和 `onboarding_locale`。首次初始化时查询现有账单；已有账单的安装直接标记完成。步数从 0 开始，范围为 0–7。步骤改变先持久化，再更新界面状态；中途重启时，第 2–8 屏进入继续确认状态。第一页返回只退出当前引导，跳过或走完最后一步会持久化完成状态。

第一次初始化记录系统语言。没有已有账单且没有已保存的 App 语言选择时，用记录的语言初始化 App 语言；此后沿用 App 设置。设置页可以重看引导。

`OnboardingViewModel` 写入不含账本内容的本地日志：`step_view` 带一步起算的步骤号，`step_skip` 带当前步骤号，`completed` 表示完成或跳过。

顺序为欢迎、新建账单、语音、日历、统计、预算、导入和通知使用权。日历演示向现有网格发送长按拖动事件，由原选区逻辑和真实账本计算汇总。演示输入只存在于真实表单的输入状态；用户操作后停止演示，未操作时恢复空表单。

语音面板只有点「试一下」后才会请求麦克风。通知使用权通过系统设置开启；返回时检查授权，成功后才请求 Android 13+ 的通知运行时权限，拒绝也可结束。

## 文件

- `android/app/src/main/java/com/bookkeeping/app/onboarding/OnboardingPreferences.kt`：持久化。
- `android/app/src/main/java/com/bookkeeping/app/onboarding/OnboardingProgress.kt`：前进、返回和完成边界。
- `android/app/src/main/java/com/bookkeeping/app/onboarding/OnboardingViewModel.kt`：启动判断、进度和本地日志。
- `android/app/src/main/java/com/bookkeeping/app/onboarding/OnboardingWelcome.kt`：覆盖真实首页的欢迎卡。
- `android/app/src/main/java/com/bookkeeping/app/onboarding/OnboardingOverlay.kt`：独立 Dialog 遮罩、坐标和事件转发。
- `android/app/src/main/java/com/bookkeeping/app/onboarding/OnboardingPermission.kt`：权限卡。
- `android/app/src/main/java/com/bookkeeping/app/ui/navigation/RootNavigation.kt`：真实页面和面板的路由、系统权限时机。
- `android/app/src/main/java/com/bookkeeping/app/ui/screen/transactions/`：真实新建、语音面板的演示和坐标上报接入。
- `android/app/src/main/java/com/bookkeeping/app/ui/screen/calendar/CalendarScreen.kt`：真实网格上的演示拖动。
- `android/app/src/main/java/com/bookkeeping/app/ui/screen/billimport/BillImportScreen.kt`：真实导入面板的坐标上报。
- `android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/SettingsScreen.kt`：重看入口。
- `android/app/src/test/java/com/bookkeeping/app/onboarding/OnboardingProgressTest.kt`：进度边界测试。
- `android/app/src/androidTest/java/com/bookkeeping/app/onboarding/OnboardingOverlayTest.kt`：真实新建账单面板上的遮罩输入测试。

现有面板没有增加引导专用控件。面板步骤的提示收在顶部紧凑条，提示背景和文字区域的触摸继续转发给真实面板，按钮单独响应；位置限定在安全显示区域。遮罩点击转发、输入法及无障碍交互需要在设备上检查。
