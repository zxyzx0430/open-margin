# Android 版本号与 GitHub 更新检查（v1.2.0 RC6）

## 用户入口

在 **设置 → 关于与更新（About & updates）**：

- 显示当前安装的 `versionName`、整数 `versionCode`（构建号）和发布 tag。
- 点击 **检查更新**，从官方 GitHub Releases 查询最新已发布的**已签名 APK**。
- 可选择是否包含 RC 测试版；测试版本默认开启，正式版本默认关闭。
- 有更新时显示版本 tag 与“查看 GitHub 发布页”；也可以直接浏览全部发布版本。
- 不会自动下载安装，不修改应用权限、Room 数据、服务器身份或同步设置。

## 版本规则

`v1.1.3` ＜ `v1.2.0-rc.4` ＜ `v1.2.0-rc.5` ＜ `v1.2.0-rc.6` ＜ `v1.2.0`。只接受严格匹配的 `vN.N.N` 和 `vN.N.N-rc.N` tag，并过滤草稿版本。Android 和双端更新必须有非空签名 APK；单独服务端发布无需 APK。

当前候选版 `v1.2.0-rc.6`、`versionCode=11`。上一版 RC5 的整数构建号为 10，不会被误判为新更新。下一次正式出包必须同步提升 `versionCode` 和 `UpdateVersionPolicy.CURRENT_TAG`。GitHub Releases 路径和仓库所有者固定在源码中。

## 验证

- JVM `ReleaseVersionTest` 检查正式版与 RC 排序、降级不提示、筛选测试版、非签名文件不提示。
- JVM `EnglishUiTextTest` 检查紧凑底部导航英文标签 `Records` 以及更新页面英文翻译。
- UI 底部 NavigationBarItem 的 icon 明确为 `24dp`，label 限制 `maxLines=1` 且 `softWrap=false`，避免长英文在中文四栏布局中折为两行。
- CI 构建与 Android 35 模拟器验收最终以此变更分支和合并提交的最新 SHA 为准。

## 合并到 main 的额外发布门槛

PR #3 已将 v1.2.0 代码合并至 main，VPS 也已部署合并提交；此 UI 改动的通过 **不等于** 全部真机验收已完成。正式发布仍需：

1. 最新集成 SHA 的 Android 单测、APK 构建、Android 模拟器启动、后端测试、Windows PowerShell、API-only 路由与 Open Source Readiness 均通过。
2. Windows Docker Desktop 干净安装及首次 Wallos 管理员注册、Tailscale Serve URL / 模式检测、Android 真机首次连线、断线重连的实际端到端验收。
3. Android **同签名 v1.1.3/RC4 → 新版** 真机覆盖升级验证：Room 数据、未同步交易、通知权限及同步账本游标均保留；升级前后数量/金额对账。
4. 使用隔离生产数据副本检查 VPS/Windows 旧数据库升级，Finance/Wallos/OAuth 三库备份与恢复，账本身份/游标、墓碑、退款关联、断线恢复及回滚。
5. 独立代码审查、生产变更审批及签名验收记录；完成验收并取得正式发布审批后才创建稳定的 v1.2.0 tag 和带原永久私钥签名的 Release。不得擅自重置生产数据库卷。

测试包可保持 prerelease；正式版的发布、上生产、数据库迁移属于独立审批阶段。


## 每次 Release 必填：更新范围元数据

在每一条 GitHub Release 的正文中单独放置以下**一行**，三种选项只能选一种：

```text
<!-- open-margin-components: android -->
<!-- open-margin-components: server -->
<!-- open-margin-components: android,server -->
```

上方为三种可选写法的展示，**实际 Release 正文中只能包含其中一行**：

- `android`：仅 Android APK 修改，电脑/VPS **不需因此次发布而重部署**。
- `server`：仅 Windows/VPS 服务端修改；可没有新 APK，APP 仍必须收到提醒。
- `android,server`：两端都包含更新，先看版本说明确定更新顺序。
- 历史版或遗漏元数据：显示「更新范围未注明」，明确要求先阅读发布说明，**不通过 APK 存在与否推断服务端是否已升级**。

Android 或双端发布必须有非空的、以 `-signed.apk` 或 `-original-signature.apk` 结尾的 APK 才提示该版本；server-only 发布无需 APK。仍不检查当前 Windows/VPS 真实部署 SHA、账本身份、版本，因此**更新范围不等于已安装服务端状态**，页面会明确提示这一限制。

RC6 本身是 **android-only**：只增加显示更新类型的提示，不修改同步 API/数据库/Windows 部署内容，已经部署 v1.2.0 的 VPS 无须因 RC6 再部署。
