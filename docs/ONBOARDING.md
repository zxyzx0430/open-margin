# 新手必看

新用户首次打开时展示一页原生 Compose 说明，介绍自动记账、手动和语音记账、账单编辑、日历统计与预算，以及导入微信和支付宝订单的入口。点击「开始使用」或「跳过」完成引导并进入首页；设置页可通过「重看引导」重新打开。

## 状态和语言

DataStore 沿用 `onboarding_completed`、`onboarding_step` 和 `onboarding_locale`，以兼容旧版引导数据。单页不再使用步骤：旧版未完成的用户会看到新页面，已完成的用户不重复看到。首次初始化检测已有账单，已有账单的安装直接标记完成。

首次初始化保存系统语言。如果没有已有账单且没有用户保存过的 App 语言选择，就以记录的语言初始化 App 语言；之后跟随 App 内语言设置。

本地日志使用 `step_view`、`step_skip` 和 `completed`，不记录账单内容。

## 权限

页面不会自动请求权限。点击「开启自动记账」后打开系统通知使用权设置；返回时检查状态，开启成功后才在 Android 13 及以上请求通知运行时权限。用户可以随时选择「开始使用」或「跳过」。麦克风仍由现有语音记账流程在使用时申请。

## 主要文件

- `android/app/src/main/java/com/bookkeeping/app/onboarding/OnboardingPreferences.kt`：状态与语言持久化。
- `android/app/src/main/java/com/bookkeeping/app/onboarding/OnboardingViewModel.kt`：首次显示、完成和重看。
- `android/app/src/main/java/com/bookkeeping/app/onboarding/OnboardingWelcome.kt`：单页内容与响应式布局。
- `android/app/src/main/java/com/bookkeeping/app/ui/navigation/RootNavigation.kt`：页面入口与通知使用权处理。
- `android/app/src/main/java/com/bookkeeping/app/ui/screen/settings/SettingsScreen.kt`：重看入口。
