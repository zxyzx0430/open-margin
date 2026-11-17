# Windows 一键部署引擎与安装器 / Setup engine and installer

本目录是清衡（Open Margin）Windows 一键部署向导的全部实现：本机网页 UI、确定性 PowerShell 部署引擎、CLI、Tailscale Funnel 公网同步网关、Wallos 管理员创建、配对二维码、隐藏看门狗，以及 Inno Setup 安装器和离线镜像包构建脚本。

This directory contains the local web wizard, deterministic setup engine, CLI, Tailscale Funnel ingress, Wallos owner creation, pairing QR, hidden watchdog, plus the Inno Setup installer and offline image bundle scripts for the Windows one-click deployment. It targets **Windows PowerShell 5.1** and does not require Git, Python, Node or PowerShell 7 to run the web wizard.

## 入口 / Entry points

| 文件 / File | 用途 / Purpose |
|---|---|
| `Start-OpenMarginSetupWeb.ps1` | 启动本机网页向导（仅监听 `127.0.0.1`） / local wizard server on `127.0.0.1:18770-18779` |
| `open-margin-setup.ps1` | 机器可读 CLI（`status`, `check`, `deploy`, `verify`, `disable`, `repair`, ...） / CLI for advanced users and agents |
| `OpenMargin.Setup.psd1` / `.psm1` | 部署引擎模块（Windows PowerShell 5.1 与 PowerShell 7） / engine module |
| `Watchdog.ps1` | 登录后隐藏运行的看门狗（计划任务 + VBS 拉起） / hidden per-user watchdog |
| `web/` | 静态网页资源（无外部 CDN，`qrcode.min.js` 本地） / static wizard assets |
| `lib/` | 状态机、日志脱敏、端口、探测、Compose、Tailscale、Wallos、配对、自测、依赖安装、看门狗、Web 动作 / engine libraries |
| `tests/` | Pester 5 测试与 fixtures / Pester 5 suite and fixtures |
| `installer/Launch-Setup.ps1` | 开始菜单启动器；选择第一个空闲向导端口 / Start Menu launcher; picks the first free wizard port |
| `installer/Install-UrlAcl.ps1` | 一次性提权 `netsh http add urlacl`（仅回环向导端口） / one-time elevated loopback URL ACL |
| `installer/Build-Installer.ps1` | 构建 `OpenMargin-Setup.exe` / build the installer |
| `Build-OfflineBundle.ps1` | 构建离线 Docker 镜像包 / build the offline Docker image bundle |

For a repo checkout (not the installed copy), run `installer/Install-UrlAcl.ps1` once from an elevated PowerShell before starting the wizard; `HttpListener` needs the loopback reservation. The installer does this automatically.

## 快速开始 / Quick start

生产环境由安装器调用；开发时可直接启动：

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File deploy/windows/setup/Start-OpenMarginSetupWeb.ps1 `
  -Port 18770 -StateRoot E:\DevCaches\OpenMarginTest -Dev -NoBrowser
```

- `-Dev` 跳过本机 setup token，仅用于开发。生产不要使用。
- `-DryRun` 让所有 Web 动作走演练：不安装依赖、不启动容器、不开启 Funnel。浏览器测试必须使用它。
- `-Resume` 由重启后的 `RunOnce` 使用；启动后自动删除 `RunOnce` 项。
- 端口被占用时会在 `Port..Port+10` 内自动顺延；向导自己的端口不会在环境检查里被误报为冲突。
- 状态文件：`<StateRoot>/setup-state.json`；日志：`<StateRoot>/logs/setup-YYYYMMDD.log`；Job：`<StateRoot>/jobs/`。

CLI 示例：

```powershell
# 状态（同时兼容 --json 与 -Json）
.\open-margin-setup.ps1 status --json
.\open-margin-setup.ps1 check --json --state-root E:\DevCaches\OpenMarginTest
.\open-margin-setup.ps1 install-deps --dry-run --json
.\open-margin-setup.ps1 deploy --admin-user alex --admin-password-file C:\path\pw.txt --ack-public --json
.\open-margin-setup.ps1 deploy --admin-user alex --admin-password-file - --ack-public --json   # 从 stdin
.\open-margin-setup.ps1 verify --json
.\open-margin-setup.ps1 disable --json
.\open-margin-setup.ps1 enable --json
.\open-margin-setup.ps1 pair-qr --json
.\open-margin-setup.ps1 rotate-token --json
.\open-margin-setup.ps1 install-watchdog --dry-run --json
```

退出码：

| 码 | 含义 |
|---:|---|
| 0 | 成功 |
| 10 | 需要用户操作（UAC、管理员密码、公网确认、Funnel 授权） |
| 20 | 可重试失败（网络、Docker 未就绪、自测失败） |
| 30 | 不可恢复失败或参数错误 |

密码只通过 `--admin-password-file`、stdin 或交互式安全输入；不接受命令行明文密码。`--json` 输出不包含设备密钥、管理员密码或配对令牌（`pair-qr` / `rotate-token` 这类本地显式命令除外，且只输出给本机调用者）。

## 状态机 / State machine

```text
UNINITIALIZED -> CHECKING -> NEEDS_USER -> INSTALLING_DEPS -> WAITING_REBOOT -> READY
READY -> DEPLOYING -> VERIFYING -> COMPLETE
COMPLETE -> DISABLING -> DISABLED -> SWITCHING -> READY/COMPLETE
任意步骤 -> FAILED（可回到检查/安装/就绪）
```

- 状态文件原子写入，命名 Mutex 防并发；损坏时备份为 `.corrupt-<时间>` 并重建。
- 状态文件不存 device token、管理员密码、admin token 或配对 payload。
- `WAITING_REBOOT` 会写 `HKCU\...\RunOnce`，重启登录后自动重新打开向导（`-Resume`）。
- 端口：`FINANCE_API_PORT`（默认 8767）、`WALLOS_PORT`（18082）、`GATEWAY_PORT`（18768）、`SETUP_PORT`（18770）。Compose、网关、Funnel 目标都使用同一组状态端口。

## 公网网关 / Public gateway

`compose.yaml` 的 `windows-public-sync` profile 启动 `public-sync-gateway`（nginx），宿主机端口 `127.0.0.1:${GATEWAY_PORT:-18768}`，配置为 `deploy/windows/public-sync-gateway.conf`。

只允许：

- `GET /finance/health`
- `GET /finance/api/v2/sync/changes`
- `POST /finance/api/v2/sync/push`
- `POST /finance/api/v1/transactions/batch`
- `POST /finance/api/v1/transactions/correct-rounding`

其他路径 404；错误方法 405；包含 `%` 的 URI 404；`limit_req` / `limit_conn`、`client_max_body_size 4m`；访问日志只记录方法/规范化路径/状态/字节数，不含请求头或查询串，因此设备 token 不会落盘。

## 看门狗 / Watchdog

`Install-OmWatchdog` 把 `Watchdog.ps1`、`watchdog-config.json`、`Watchdog.vbs` 写入 `<StateRoot>/watchdog/`，并注册登录触发的隐藏计划任务：

- VBS 以 `wscript.exe` 隐藏启动 `powershell.exe`，无窗口。
- 工作目录设为仓库/app 根，保证 `docker compose` 找到 `compose.yaml`。
- 每 60 秒检查 Docker、容器、`/health`、Tailscale、Funnel；只修复允许的项。
- `NeedsLogin` 只记录“需要登录”，不自动登录；Funnel 未授权只记录，不自动授权。
- 两次修复失败后指数退避（10 分钟内最多 3 次）；暂停状态写入状态文件。
- 永不执行 `docker compose down -v`。

状态页可暂停/恢复看门狗，关闭远程同步时自动暂停，重新开启时恢复。

## 安装器 / Installer

```powershell
# Double-click fallback, or:
powershell -NoProfile -ExecutionPolicy Bypass -File .\installer\Build-Installer.ps1

# Install Inno Setup 6.3+ first, or let the script offer winget:
winget install --id JRSoftware.InnoSetup --exact
```

`Build-Installer.ps1` locates `ISCC.exe` in Program Files, the registry, PATH or the winget package cache. It writes `OpenMargin-Setup.exe` and `OpenMargin-Setup.exe.sha256` under `installer/Output` by default. The installer is unsigned by design for v1; publish the SHA256 and SmartScreen guidance with every Release.

The Inno Setup script shows the directory page and defaults to `D:\OpenMargin\app` (then `E:\OpenMargin\app`, then `<system drive>\OpenMargin\app`); users can install on any drive. It creates the start-menu shortcuts “清衡设置” and “清衡账本”, reserves the loopback wizard ports, and never ships `secrets/` or `.env`. After a normal or silent install it starts the wizard elevated (reusing the installer's one UAC) and opens the browser through `explorer.exe` at the original user's integrity level; `/NOELEVATE` runs the wizard user-level and `/NOAUTOSTART` skips it. The wizard remains loopback-only and token-authenticated; the token URL file is ACL-restricted and deleted after the handoff. The environment check measures the actual install drive and the Docker Desktop WSL data drive separately (install: fail <2GB / warn <5GB; Docker data: fail <3GB / warn <5GB).

## 离线镜像包 / Offline Docker bundle

`Build-OfflineBundle.ps1` builds the compose services, saves built images plus compose-declared base images to `images.tar`, compresses to `OpenMargin-Offline-<version>.tar.gz`, and writes `.sha256` and `.manifest.json`. It is a separate Release asset, never part of the installer.

```powershell
# Validate image classification without building, pulling or writing output:
powershell -NoProfile -ExecutionPolicy Bypass -File .\Build-OfflineBundle.ps1 -DryRun

# Exercise the portable YAML fallback used when --format json is unavailable:
powershell -NoProfile -ExecutionPolicy Bypass -File .\Build-OfflineBundle.ps1 -DryRun -ForceLegacyComposeConfig

powershell -NoProfile -ExecutionPolicy Bypass -File .\Build-OfflineBundle.ps1 -Version 1.3.0
```

Safety: it never runs `docker compose down`, never deletes volumes, and only
removes placeholder files it created itself. It creates temporary `.env`,
`secrets/backend.env` and `secrets/wallos-ledger.env` placeholders before any
compose command (needed on a clean checkout) and removes only those it created.
Base images declared by Dockerfiles, `compose.yaml` image-only services and the
explicit nginx/python/Wallos defaults are pulled if missing; a failed pull stops
with a clear error before `docker save`.

## 测试 / Tests

```powershell
# Pester 5（首次运行会把 Pester 5.7.1 装到 E:\DevCaches\PowerShellModules）
pwsh -NoProfile -ExecutionPolicy Bypass -File deploy/windows/setup/tests/Run-Tests.ps1 -CI

# Compose 配置
docker compose config -q
docker compose --profile windows-public-sync config -q

# 公网/私有网关负向测试（优先 Docker nginx，宿主机 nginx 为兜底）
python scripts/test_private_sync_gateway.py --backend docker
```

Pester 覆盖状态转换、状态文件原子写/损坏恢复、日志脱敏、端口冲突与自端口排除、Serve/Funnel 路由所有权、配对 payload、Verify 的 mocked HTTP、Web 动作契约、Compose/网关 allowlist、看门狗暂停/隐藏启动工件。

CI 由 `windows-setup-tests.yml` 运行 Pester，`windows-setup-installer.yml` 构建安装器与离线包。

## 未在此环境验证 / Not verified here

- 真实 Tailscale 登录、MagicDNS/HTTPS、Funnel 浏览器授权、公网 DNS 传播。
- 真实 Docker Desktop / WSL2 / Tailscale 安装、UAC 提权、URL ACL、重启续装。
- Wallos 真实容器内的 `/registration.php` 表单（已用本地 mock 验证 HttpClient 表单字段、Cookie、302 跳转；真实 Wallos 版本变化需实机确认）。
- 安装器签名、SmartScreen 与真实干净虚拟机安装（本环境未安装 Inno Setup 时只做脚本/配置校验）。
- Android 真机扫码与商业 VPN 下的 Funnel 可达性。
