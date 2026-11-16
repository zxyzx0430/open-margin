<!-- open-margin-components: android,server -->
<!-- Release metadata note: the Android QR scanner is a prerequisite for the
     pairing QR. If v1.3.0 ships without Android changes, change the line above
     to "server" before publishing. -->

# 清衡 v1.3.0｜Windows 一键部署（预发布说明）

> 状态：工程实现与静态检查完成，**尚未完成真实 Windows/Tailscale/Funnel/Android 真机验收**。不要把本文当作“已通过真机测试”的发布声明。

## 主要变化

### Windows 一键安装器

- 新增 `OpenMargin-Setup.exe`（Inno Setup），安装到 `%ProgramData%\OpenMargin\app`。
- 内置部署引擎、网页向导、`backend/` 构建上下文、`compose.yaml`、`deploy/windows/` 和运行所需文档。
- 开始菜单：
  - “清衡设置”启动本机向导并打开浏览器。
  - “清衡账本”打开 `http://127.0.0.1:18082/`。
- 不要求 Git、Python、Node 或 PowerShell 7；向导以 Windows PowerShell 5.1 启动。
- 带一个显式的“安装说明”，包含 SmartScreen“更多信息 → 仍要运行”和 SHA256 校验步骤。
- **不做代码签名**：v1 不购买证书；Release 页面公布 SHA256，不诱导用户关闭杀软。

### 仅远程 Tailscale Funnel 模式

- 网页向导 v1 只提供远程同步，不提供“私有 / 远程”模式选择。
- 手机不需要安装 Tailscale，可继续使用商业 VPN；但商业 VPN 必须能访问 `https://<电脑名>.<tailnet>.ts.net/finance`。
- 私有 Tailscale Serve CLI 保留，不在网页向导中展示；见 `docs/WINDOWS_PRIVATE_FIRST_CONNECTION.md`。
- 公网只放行 `/finance` 同步接口；Wallos、MCP、OAuth、管理 API 不可达。

### 二维码直接带设备密钥

- 完成页二维码内容为 `{"v":1,"kind":"open-margin-pair","url":"...","token":"...","server_id":"..."}`。
- 二维码只在本机显示，10 分钟后自动隐藏；重新显示需要重新打开向导。
- 状态页可“重新生成设备密钥”：旧二维码立即失效，手机需要重新扫码。
- 不新增一次性配对表/后端接口；使用现有 `FINANCE_DEVICE_TOKEN`。

### 隐藏看门狗

- 登录后由隐藏计划任务 `OpenMarginWatchdog` 运行，无窗口、无弹窗。
- 检查 Docker、容器、`/finance/health`、Tailscale 和 Funnel 路由；失败时尝试恢复。
- 不自动登录 Tailscale、不自动重新授权 Funnel、不重启 Windows。
- 状态页提供暂停 1 小时 / 恢复；日志在 `%LOCALAPPDATA%\OpenMargin\logs\watchdog.log`。

### 离线 Docker 镜像包（独立资产）

- `OpenMargin-Offline-<version>.tar.gz` 是**单独的 Release 资产**，不塞进安装器，允许用户手动转发。
- 包含 `finance-api`、`wallos` 构建镜像和 `python:3.11-slim`、`nginx:1.28-alpine`、Wallos 基础镜像；附 `manifest.json`、`RESTORE.md` 和 SHA256。
- 恢复：`tar -xzf ...`，然后 `docker load -i images.tar`。
- **限制**：当前向导以 `docker compose up --build` 启动；完全离线重建仍依赖后续支持预构建镜像 / `--no-build`，端到端离线安装尚未验证。

### CI

- `windows-setup-installer.yml`：windows-latest 安装 Inno Setup、编译安装器、上传 artifact；仅 tag 时附加到 Release（先创建 draft），普通 push 不发布。
- `windows-setup-tests.yml`：windows-latest 运行 `deploy/windows/setup/tests` 下的 Pester 5；目录不存在时明确跳过。
- `windows-script-validation.yml`：递归解析 `deploy/windows/**` 的 `.ps1/.psm1/.psd1`，并扫描合并冲突标记。
- tag 工作流在编译安装器后构建离线镜像包，并作为独立 Release 资产上传；手动触发时也可选择构建。

## 已知限制 / 发布前必须完成

- 真实 Windows 10 Home 22H2 / Windows 11 干净机从零安装未验证。
- Tailscale 注册、MagicDNS/HTTPS、Funnel 授权、公共 DNS 传播未验证。
- 中国 SIM + 商业 VPN 访问 `*.ts.net` 未验证；Funnel 仍是 beta，带宽有限，只适合小数据同步。
- Android 真机扫码、`server_id` 校验、断线补传未验证；Android 二维码扫描代码必须与本版本一起发布，否则二维码不可用。
- 依赖安装的提权 helper 仍调用 `pwsh.exe`（PowerShell 7）；只有 Windows PowerShell 5.1 的机器会在该步骤失败。
- 引擎脚本当前是无 BOM UTF-8，含中文；Windows PowerShell 5.1 可能按 ANSI 误读。需要补 BOM 或改为 ASCII。
- `WAITING_REBOOT` 状态已写入，但没有注册 `RunOnce` 自动续装；重启后需手动重新打开“清衡设置”。
- `compose.yaml` 在当前分支还没有 `windows-public-sync` service；运行时/compose 线程必须补齐，否则远程同步 profile 不完整。
- 安装器未做代码签名；SmartScreen 行为只在文档层面说明，未在真实下载链路验收。
- 完全离线安装未验证（见上）。
- Inno Setup 本地未安装；本线程只做了脚本静态检查，安装器由 CI 编译。

## 升级与兼容

- 重复安装会进入升级/修复，不覆盖 `secrets/`、`.env` 或 Docker 命名卷。
- 不要使用 `docker compose down -v`。
- 已有私有 Serve 路由不会被网页向导覆盖；Funnel 443 被其他路由占用时向导停止。
- 关闭远程模式保留数据；重新启用需要重新走 Funnel 授权与自测。

## 验证状态

已完成（静态）：

- `[System.Management.Automation.Language.Parser]::ParseFile` 解析全部 Windows PowerShell 文件。
- `docker compose --profile windows-private-sync config -q` 与 `--profile windows-public-sync config -q`（临时占位 secrets）。
- 新增 GitHub Actions YAML 语法解析。
- `python scripts/check-public-ready.py`（本机依赖可用时）。

未完成（动态）：安装器编译、干净机安装、Funnel 真机、手机商业 VPN、Android 扫码、看门狗故障注入、离线安装。发布前按 `docs/DEPLOY_WINDOWS.md` 第 0.9 节验收。
