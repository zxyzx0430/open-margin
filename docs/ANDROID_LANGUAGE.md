# 清衡 Android：中文 / English 切换

## 使用

进入 **设置 → 语言 / Language**，点击 **简体中文** 或 **English**。选择会立即保存并重新创建当前 Activity；关闭、重新打开 App 后继续使用上次的语言。原有用户默认简体中文，不需要迁移账本。

English 模式覆盖主导航、交易列表与编辑、分类显示、预算、统计、通知/SMS/无障碍权限说明、Windows 首次连接向导、双向同步设置及常驻通知的文字。交易金额与币种保持原值，不执行汇率转换。银行短信和微信/支付宝通知中的中文原文、商户名、备注等用户数据保持原样。

## 实现与兼容性

- 独立 `open_margin_ui_language` SharedPreferences 保存 `zh` / `en`，与 Finance/Room SQLite、同步密钥及服务器身份分离。
- `AppLanguage.wrap()` 在 MainActivity 创建前为 Android resources 提供指定 Locale；UI 的硬编码中文逐处接入只读翻译表 `EnglishUiText`。
- `TransactionCategory.name` / 收支枚举、数据库记录、网关、SMS/通知匹配规则**不作翻译或迁移**，只翻译显示名称。已保存交易与离线未同步交易不变。
- Android 设备的系统应用标签和已建立的通知渠道可能按系统语言显示，或在下次系统/服务刷新后更新；这与 App 内界面语言不同。
- 目前只切换 **Android App UI**；自托管 Wallos 网页有自己的语言能力，当前功能不修改 Windows 安装脚本或服务器网页。

## 验收

1. 现有账单与同步设置非空时，先切换 English：底部标签、账单/预算/统计/设置和分类应为英文，历史原始商户名和金额不变。
2. 打开编辑交易和 Windows 向导，核对按钮、提示、错误文字；不运行真实上传即可检查界面。
3. 彻底关闭并重新打开：仍保持 English。
4. 切回简体中文并重新启动：界面恢复中文，账单数量、Room 数据、同步游标和权限不应被重置。
5. Android JVM `EnglishUiTextTest` 和 API 35 模拟器 `LanguageSwitchInstrumentationTest` 通过后，使用原 v1.1.3 永久签名密钥签署候选 APK；真机无损覆盖升级仍单独验收。

新增 UI 文案须提供对应中文展示键及英文翻译，不能将翻译后的字符串写入交易/同步数据库。
