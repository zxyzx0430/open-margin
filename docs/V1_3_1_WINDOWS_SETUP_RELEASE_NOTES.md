# 清衡 v1.3.1 Windows 一键部署：验证状态

## 本次修复

- 使用 SID 写入 secrets ACL，并通过 `icacls /save` 检查 DACL 非空及安装用户读取权限；验证不依赖 PowerShell Security 模块自动加载。
- 在线部署前实际拉取基础镜像；镜像拉取失败时明确提示离线包，Docker daemon 暂不可用时等待后只重试一次。
- Funnel 延迟授权后写入同步地址、等待 DNS、自测，并执行看门狗、手机标记和 COMPLETE 收尾。
- 配对优先读取本机同步接口的账本身份；覆盖容器重建后服务器进程无法读取客户端本地账本的回归场景。
- 升级时把旧部署状态中的引擎版本迁移到当前版本，同时保留 COMPLETE、公网地址等部署信息；二维码取不到身份时可在当前页面一键修复服务并重试。
- 将安装器、向导和离线镜像包信息统一到 v1.3.1，登记已发布离线包的 SHA256 和文件大小。

## 验证状态（2026-11-18）

- Windows PowerShell 5.1：`deploy/windows/setup/tests/Run-Tests.ps1 -CI`，213 项中 211 通过、0 失败、2 跳过。跳过项是当前 .NET 环境缺少 `System.Formats.Tar` 写入器时的路径穿越和符号链接压缩包构造测试；相关安全校验仍由其余离线包用例覆盖。
- PowerShell 7：同一全量套件 213 通过、0 失败、0 跳过。
- 后端：`FINANCE_PUBLIC_URL=https://local.openmargin.invalid/finance python -m unittest discover -p "test_*.py"`，114 项通过；`test_web_login` 在当前全量运行中没有复现 401/403 顺序失败。
- Inno Setup 6.3.3：`Build-Installer.ps1 -Version 1.3.1` 编译成功。
- 本机原位升级后发现旧状态保留 COMPLETE，但 Finance API 容器未运行；通过向导一键修复后，API `/health` 返回 200，二维码接口返回成功且页面二维码可见。
- 在线镜像拉取、首次 Funnel 授权与 DNS、手机扫码后的连接测试仍待真实新部署/手机流程验证；本次二维码生成不代表这些步骤已通过。

安装器升级会先停止旧看门狗和 Compose；Docker 命名卷及 secrets 保留。旧 COMPLETE 状态会保留，并升级版本号；服务异常时完成页二维码区提供一键修复并重试。
