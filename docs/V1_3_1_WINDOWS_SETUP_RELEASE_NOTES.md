# 清衡 v1.3.1 Windows 一键部署：验证状态

## 本次修复

- 限制并检查 secrets ACL，保存安装用户 SID，供提权后的向导和看门狗使用。
- 在线构建前实际拉取所需基础镜像；拉取失败时提示离线包，Docker daemon 暂不可用时等待并重试一次。
- Funnel 延迟授权后写入同步地址、等待 DNS、自测并完成看门狗和手机状态收尾。
- 配对优先从本机同步接口读取账本身份；服务端二维码使用同步公网地址，并在地址变化后重建 API 容器加载配置。修复过期向导状态误复用其他进程的情况。

## 验证状态（2026-11-18）

- Windows PowerShell 5.1：`deploy/windows/setup/tests/Run-Tests.ps1 -CI`，203 通过、0 失败、2 跳过。跳过的是 .NET tar 写入器不可用时的路径穿越和符号链接压缩包用例；这两项在 PowerShell 7 的定向测试中通过。
- PowerShell 7：Compose、离线包、Tailscale、看门狗定向测试共 38 项通过。
- 后端：设置 `FINANCE_PUBLIC_URL=https://local.openmargin.invalid/finance` 后执行 unittest discover，114 项通过。
- Docker Desktop 引擎可用；实际拉取 `python:3.11-slim` 时镜像层大小校验失败，未验证完整在线部署。失败原因尚未确定。
- 使用本机 Inno Setup 6.3.3 执行 `Build-Installer.ps1 -Version 1.3.1 -NoAutoInstall`：安装包编译成功；尚未进行干净机安装验证。

两个独立服务进程读取同一临时账本的回归通过，身份一致。仍需在可交互的干净 Windows 环境验证跨账号 UAC、原安装用户实际访问、真实 Tailscale Funnel 授权与 DNS、容器重建后的扫码配对，以及安装包安装和离线包导入。以上测试结果不代表这些端到端场景已验证。
