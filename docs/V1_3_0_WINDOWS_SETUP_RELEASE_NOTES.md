<!-- open-margin-components: android,server -->
<!-- Release metadata note: the Android QR scanner is a prerequisite for the
     pairing QR. If v1.3.0 ships without Android changes, change the line above
     to "server" before publishing. -->

# 清衡 v1.3.0｜Windows 一键部署（预发布说明）

> 状态：工程实现与静态检查完成，**尚未完成真实 Windows/Tailscale/Funnel/Android 真机验收**。不要把本文当作“已通过真机测试”的发布声明。

## 主要变化

### Windows 一键安装器

- 新增 `OpenMargin-Setup.exe`（Inno Setup），默认安装到 `D:\OpenMargin\app`；没有 D: 时默认 `E:\OpenMargin\app`，再退回系统盘。安装向导始终显示目录选择页，可安装到任意有空间的磁盘。
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
- **限制**：网页向导默认以 `docker compose up --build` 启动；CLI 已支持 `open-margin-setup.ps1 deploy --no-build` 直接使用已 `docker load` 的镜像。完全离线（预构建镜像 + `--no-build`）的端到端安装尚未在干净机验证；本环境因磁盘空间未实际生成离线包。

### CI

- `windows-setup-installer.yml`：windows-latest 安装 Inno Setup、编译安装器、上传 artifact；仅 tag 时附加到 Release（先创建 draft），普通 push 不发布。
- `windows-setup-tests.yml`：windows-latest 运行 `deploy/windows/setup/tests` 下的 Pester 5；目录不存在时明确跳过。
- `windows-script-validation.yml`：递归解析 `deploy/windows/**` 的 `.ps1/.psm1/.psd1`，并扫描合并冲突标记。
- tag 工作流在编译安装器后构建离线镜像包，并作为独立 Release 资产上传；手动触发时也可选择构建。

## 已知限制 / 发布前必须完成

- 真实 Windows 10 Home 22H2 / Windows 11 干净机从零安装未验证；当前只在开发机跑过环境检查与演练模式。
- Tailscale 注册、MagicDNS/HTTPS、Funnel 授权、公共 DNS 传播未验证；本机未安装 Tailscale。
- 中国 SIM + 商业 VPN 访问 `*.ts.net` 未验证；Funnel 仍是 beta，带宽有限，只适合小数据同步。
- Android 真机扫码与商业 VPN 下的同步未验证；JVM 单测 660 条通过，模拟器相册二维码解码由 Android 分支单独验证。
- 管理员提权安装（WSL2 / Docker Desktop / Tailscale、UAC、URL ACL、重启续装）未在干净机验证；开发机当前不是管理员。
- Wallos 真实容器内的 `/registration.php` 表单未验证（仅 mock）。
- 安装器未做代码签名；本机未安装 Inno Setup，只验证了 `Build-Installer.ps1` 在缺少 ISCC 时以退出码 2 停止且不自动安装。自定义安装路径和安装后自动启动向导已做静态测试，但尚未用新编译的安装器在 D:/E: 实机验收。
- 离线镜像包本环境未实际构建（磁盘空间不足）；`-SkipBuild` 在缺少镜像时安全失败并清理占位 secrets。
- 看门狗用伪 Docker/Tailscale 做了故障注入，真实 Docker Desktop 重启、登录计划任务和“无窗口”行为未在干净机验证。
- 安装完成后自动打开“清衡设置”向导并打开浏览器（正常安装和 `/SILENT` 都生效）；自动化场景可加 `/NOAUTOSTART`。启动失败会显示可见错误对话框。
- 向导服务器复用安装器的一次 UAC 以管理员权限运行，配置 Docker/Tailscale/URL ACL 时不再反复提权；浏览器通过 `explorer.exe` 以普通用户打开。`/NOELEVATE` 可退回普通用户向导。服务器仍只监听 127.0.0.1，使用一次性 token、Host/Origin 校验和固定动作白名单；token URL 只写入受 ACL 限制的临时本机文件并在启动后删除。
- 所有页面顶部都有“返回主界面”按钮；从“查看运行状态”或其他页面都能回到欢迎页，后台部署 Job 继续运行，不会因为返回而中断。
- 环境检查分别测量**实际安装盘**和 **Docker Desktop WSL 数据盘**（从 `HKCU\Software\Microsoft\Windows\CurrentVersion\Lxss` 的 `docker-desktop` BasePath 解析）。安装盘 <2GB 失败、<5GB 警告；Docker 数据盘 <3GB 失败、<5GB 警告；不再固定检查 C: 或要求 10/15GB。

## 升级与兼容

- 重复安装会进入升级/修复，不覆盖 `secrets/`、`.env` 或 Docker 命名卷。
- 不要使用 `docker compose down -v`。
- 已有私有 Serve 路由不会被网页向导覆盖；Funnel 443 被其他路由占用时向导停止。
- 关闭远程模式保留数据；重新启用需要重新走 Funnel 授权与自测。

## 验证状态

已在集成分支实际运行（2026-11-17）：

- Pester 5：68+ 条通过，0 失败（`deploy/windows/setup/tests/Run-Tests.ps1 -CI`）；包含安装器自定义路径/自动启动静态检查、`Get-OmDiskInfo -Path`、Docker 数据盘解析和 D:/E: 磁盘检查。
- 后端 unittest：102 条通过（`FINANCE_PUBLIC_URL=... python -m unittest discover -p "test_*.py"`）。
- Android JVM 单测：660 条，0 失败，1 跳过；`:app:assembleDebug` 成功（versionCode 25 / versionName 1.3.0）。
- 网关：Docker nginx 私有/公网各 5 条允许、15 条拒绝，公网限流生效，访问日志无 token。
- Compose：默认、`windows-public-sync`、`windows-private-sync` 三套配置均 `config -q` 通过。
- 向导 API：未带 cookie 401、错误 Origin 403、坏 JSON 400、未知 action 404、路径穿越 403、错误 Host 403。
- 向导并发：200 次 `/api/health` + 100 次 `/api/status` 全部 200；10 个并发 Job 全部完成；20 个并发状态写入无损坏、无残留临时文件。
- 看门狗 `-Once -DryRun`：Docker 掉线、容器缺失、Tailscale 需登录/掉线、Funnel 未授权/路由缺失、暂停过期、退避耗尽均按设计记录且不弹出窗口。
- 向导演练：欢迎 → 环境检查 → Tailscale → 公网确认 → 管理员校验 → 部署 dry-run → 自测 → 完成 → 状态，无 console error，DOM 无长 token。
- 静态：38 个 PowerShell 文件解析通过；8 个 GitHub Actions YAML 解析通过；`python scripts/check-public-ready.py` 通过。

未完成（动态）：干净机安装、真实 Tailscale/Funnel、管理员提权、真实 Wallos 注册、Android 真机扫码、看门狗真实系统故障、Inno Setup 编译、离线包构建与离线安装。发布前按 `docs/DEPLOY_WINDOWS.md` 第 0.9 节验收。
