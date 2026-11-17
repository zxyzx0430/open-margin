# English UI residual sweep

## Changed copy

| 中文 | English |
| --- | --- |
| 版本: | Version: |
| 机型: | Device: |
| Android: | Android: |
| 语言: | Language: |
| 说明: 该报告只含版本与设备信息，不含账单金额、商户、账户或日志内容。 | Note: This report includes only the app version and device details; it contains no bill amounts, merchants, accounts, or log contents. |
| 导入 / 导出账单 | Import / Export bills |
| 导入 CSV、导出全部账单 | Import CSV, export all bills |
| 清衡反馈 | Qingheng feedback |
| 清衡诊断信息 | Qingheng diagnostics |
| 清衡崩溃了，日志在这里 | Open Margin crashed; the log is below. |
| 复制日志 | Copy log |
| 已复制，粘贴发给 Hermes | Copied. Paste it into your message to Hermes. |
| 分享日志 | Share log |
| 分享崩溃日志 | Share crash log |
| 将电脑上的二维码放入框内 | Place the QR code shown on your computer inside the frame. |

Feedback diagnostics now translate every generated label and the privacy note through `tr()`. The Settings action still opens the same import/export bottom sheet; only its title and subtitle changed. The About dialog, import/export sheet copy, and settings section headings were also checked and already had English map entries.

## Audit boundaries / remaining questions

- The crash report body produced by `CrashCatcher` still contains Chinese diagnostic fields. It is deliberately left unchanged as diagnostic payload, not app UI copy; the crash screen's own title, buttons, toast, and share chooser are translated.
- CSV column headings, parser/domain terms, logs/comments, and user-entered or source-provided transaction text are not UI copy and remain unchanged.
