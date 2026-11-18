# 清衡 v1.3.1 Windows 一键部署：验证状态

## 本次修复

- 使用 SID 写入 secrets ACL，并通过 `icacls /save` 检查 DACL 非空及安装用户读取权限；验证不依赖 PowerShell Security 模块自动加载。
- 在线部署前实际拉取基础镜像；镜像拉取失败时明确提示离线包，Docker daemon 暂不可用时等待后只重试一次。
- Funnel 延迟授权后写入同步地址、等待 DNS、自测，并执行看门狗、手机标记和 COMPLETE 收尾。
- 配对优先读取本机同步接口的账本身份；覆盖容器重建后服务器进程无法读取客户端本地账本的回归场景。
- 将安装器、向导和离线镜像包信息统一到 v1.3.1，登记已发布离线包的 SHA256 和文件大小。

## 验证状态（2026-11-18）

- Windows PowerShell 5.1：`deploy/windows/setup/tests/Run-Tests.ps1 -CI`，209 通过、0 失败、2 跳过。跳过项是当前 .NET 环境缺少 `System.Formats.Tar` 写入器时的路径穿越和符号链接压缩包构造测试；相关安全校验仍由其余离线包用例覆盖。
- PowerShell 7：Compose 定向测试 8 项通过，包含中文及空格路径、Security 模块不可自动加载时的 ACL 验证。
- 后端：`FINANCE_PUBLIC_URL=https://local.openmargin.invalid/finance python -m unittest discover -p "test_*.py"`，114 项通过；`test_web_login` 在当前全量运行中没有复现 401/403 顺序失败。
- Inno Setup 6.3.3：`Build-Installer.ps1 -Version 1.3.1 -NoAutoInstall` 编译成功。
- 原位安装包已在本机启动并获得管理员权限；安装器窗口需要在桌面完成 Docker 说明确认后继续。端到端安装、在线镜像拉取、真实 Funnel 授权与 DNS、手机扫码配对及最终 COMPLETE 尚未完成验证。

本机 Docker 与 Tailscale 当前有运行中的 Open Margin 服务。安装器升级会先停止旧看门狗和 Compose；Docker 命名卷及 secrets 保留。完整升级结果以本机向导最终状态为准。