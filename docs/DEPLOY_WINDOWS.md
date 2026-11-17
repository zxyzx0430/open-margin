# 清衡 Open Margin｜Windows 部署

Windows 和 VPS 共用 `compose.yaml`、Finance API、Wallos、SQLite 结构及 Android 双向同步协议。区别只是连接入口。**默认 Web 仅可从 Windows 本机访问，远程同步接口需要单独开启。**

> **证据标签（2026-11-17）**：本文的一键安装脚本、Inno Setup 脚本、离线镜像包脚本和 CI 工作流已在本仓库实现，并通过 PowerShell 解析、compose 配置解析与 YAML 语法检查。真实 Windows 干净机、Tailscale Funnel、中国 SIM + 商业 VPN、Android 真机扫码与安装器编译**尚未在本线程实际运行**；发布前必须按第 7 节逐项验收。

## 方案 0：一键安装（v1.3.0，推荐；仅远程 Funnel 同步）

v1 只提供**远程同步（Tailscale Funnel）**这一个网页向导模式，向导里没有“私有 / 远程”选择。需要手机只开商业 VPN 也能同步的用户选这个方案；需要 tailnet 内完整网页的用户继续使用下面的私有 CLI 方案。

### 0.1 下载、校验与 SmartScreen

从 GitHub Releases 下载：

- `OpenMargin-Setup.exe`：安装器（内置部署引擎、网页向导、backend 构建上下文、`compose.yaml` 和 Windows 部署文件）。
- `OpenMargin-Offline-<version>.tar.gz`：**独立的离线 Docker 镜像包**，不在安装器内，可手动转发。

每个资产旁边都有 `.sha256` 文件。下载后先校验：

```powershell
Get-FileHash .\OpenMargin-Setup.exe -Algorithm SHA256
Get-FileHash .\OpenMargin-Offline-1.3.0.tar.gz -Algorithm SHA256
```

把输出与 Release 页面或 `.sha256` 文件逐字符比较；不一致就不要运行。

v1 **没有购买 Windows 代码签名证书**。双击安装器时 SmartScreen 可能显示“Windows 已保护你的电脑”：

1. 点击“更多信息”。
2. 点击“仍要运行”。
3. 不要因为未签名就关闭杀毒软件或系统防护；来源不明时直接删除文件。

### 0.2 安装器做什么

- 安装到 `%ProgramData%\OpenMargin\app`。
- 开始菜单创建：
  - **清衡设置**：启动本机向导（`http://127.0.0.1:18770/` 起，端口被占用时顺延到 18779）并打开浏览器。
  - **清衡账本**：打开 `http://127.0.0.1:18082/`。
- 不要求 Git、Python、Node 或 PowerShell 7；网页向导本身以 Windows PowerShell 5.1 启动（依赖安装步骤目前仍有 PowerShell 7 缺口，见 0.4）。
- **不打包** `secrets/`、`.env`、数据库、Docker 卷或设备令牌。运行期密钥由向导在本机生成。
- 安装器需要管理员权限（一次 UAC）。安装本身只写程序目录和开始菜单，不安装 Docker/Tailscale。
- 支持 Windows 10/11 64 位。Windows 10 22H2 已停止支持：向导会警告并建议升级 Windows 11，但不会直接阻止安装。
- 重复安装会进入升级/修复流程，不覆盖已有 `secrets/` 和 Docker 命名卷。

### 0.3 向导流程

1. 打开“清衡设置”，浏览器进入本机向导。
2. 环境检查：Windows 版本/64 位、管理员、磁盘、WSL2、Docker Desktop、Tailscale、端口、已有安装。
3. 一键安装依赖：WSL2、Docker Desktop、Tailscale。会再请求一次 UAC；取消 UAC 表示没有安装任何东西，可以重试。
4. 注册/登录 Tailscale；在 Tailscale 管理台开启 **MagicDNS** 和 **HTTPS**。
5. 安全确认：向导只开放同步接口，需要设备密钥。
6. 设置 Wallos 管理员用户名/密码（可选邮箱）。
7. 一键部署：启动 `windows-public-sync` compose profile 和本机 API-only 网关。
8. 浏览器弹出 Tailscale Funnel 授权页，点“允许”。
9. 自测：`/finance/health` 200；无令牌 401；有令牌 200；Wallos/MCP/OAuth 等路径 404。
10. 完成页给出手机 App 下载二维码和配对二维码。二维码内容为 `{"v":1,"kind":"open-margin-pair","url":"...","token":"...","server_id":"..."}`，**直接包含设备密钥**，只在本机显示，10 分钟后自动隐藏；重新显示需要重新打开向导。截图/转发等于把账本交给别人。

状态页提供“重新生成设备密钥”：旧二维码立即失效，手机需要重新扫码。状态与日志不写入设备令牌或管理员密码。

### 0.4 重启与续装

- WSL2 / Docker Desktop 首次安装后可能需要重启。
- 重启后从开始菜单重新打开“清衡设置”；状态文件 `%LOCALAPPDATA%\OpenMargin\setup-state.json` 会保留，向导继续检查。
- **v1 尚未实现 `RunOnce` 自动弹出向导**；不要假设重启后会自动打开。
- 当前脚手架还有一个已知问题：依赖安装的提权 helper 调用 `pwsh.exe`（PowerShell 7）。只有 Windows PowerShell 5.1 的机器会在该步骤失败；在修复前，这类机器需先安装 PowerShell 7，或等待后续版本。安装器本身和向导服务器不依赖 PowerShell 7。

### 0.5 手机、商业 VPN 与 Tailscale 冲突

- **远程 Funnel 模式下手机不需要安装 Tailscale**：手机只开自己的商业 VPN 即可，但该 VPN 必须能访问 `https://<电脑名>.<tailnet>.ts.net/finance`。如果商业 VPN 把 `*.ts.net` 也阻断，远程同步不可用。
- **私有同步模式需要手机安装 Tailscale**。Android 通常只有一个 VPN 槽，Tailscale 与商业 VPN 不能同时启用。v1 网页向导不提供私有模式；私有 CLI 见 [Windows 私有同步与首次连接](WINDOWS_PRIVATE_FIRST_CONNECTION.md)。
- Windows 端 Tailscale 与商业 VPN 通常可以共存；强制全局接管所有流量的 VPN 仍可能阻断 Funnel 控制面或 DERP。
- 电脑关机、休眠、Docker 停止或 Tailscale 掉线时同步暂停；手机继续本地记账，恢复后补传。Docker Desktop 是桌面应用，Windows 未登录用户时不会运行。

### 0.6 Funnel 限制

- Funnel 仍是 Tailscale 的 **beta** 功能；可用性和配额以 Tailscale 当前计划/文档为准。
- 只使用 **443** 端口；443 被其他 Serve/Funnel 路由占用时向导停止并显示占用，不会覆盖别人的路由。
- 带宽有限，只适合小数据同步，不适合传大文件或长期高吞吐。
- 公共 DNS 传播最长可能需要约 10 分钟；部署后立即用手机测试可能暂时失败。
- 电脑必须保持开机、联网、Docker 运行；不提供“关机也同步”。
- 公网只放行 `/finance` 的同步/健康接口；Wallos、MCP、OAuth、管理 API 不可达。Tailscale 不能替代后端设备密钥认证。

### 0.7 离线镜像包（独立 Release 资产）

国内网络拉取 Docker Hub 失败时使用。离线包由 `deploy/windows/setup/Build-OfflineBundle.ps1` 生成，包含：

- `images.tar`：`finance-api`、`wallos` 构建镜像 + `python:3.11-slim`、`nginx:1.28-alpine`、Wallos 基础镜像。
- `manifest.json`：版本、镜像列表、Docker 版本。
- `RESTORE.md`：恢复说明。
- 外层 `OpenMargin-Offline-<version>.tar.gz` 与 `.sha256`。

恢复：

```powershell
tar -xzf .\OpenMargin-Offline-1.3.0.tar.gz
docker load -i .\images.tar
```

**限制（必须如实告知用户）**：当前向导以 `docker compose up --build` 启动；加载离线包可以避免重复下载镜像，但完全离线重建仍依赖后续引擎支持预构建镜像 / `--no-build`。因此 v1 的端到端离线安装**尚未验证**，不要把“有离线包”宣传成“保证离线可装”。

### 0.8 看门狗、备份与关闭

- 部署成功后安装隐藏的登录计划任务 `OpenMarginWatchdog`：每 60 秒检查 Docker、容器、`/finance/health`、Tailscale 和 Funnel 路由；失败时尝试拉起，不弹窗、不自动登录 Tailscale、不自动重启 Windows。
- 状态页可“暂停 1 小时 / 恢复”。看门狗日志：`%LOCALAPPDATA%\OpenMargin\logs\watchdog.log`。
- 一键备份调用现有 `maintenance` profile；默认备份到 `D:\OpenMarginBackups`（不存在时让用户选）。本机备份不等于异地备份。
- 关闭远程模式：移除本项目 Funnel 路由，保留 `secrets/` 和数据卷；重新启用时重新走 Funnel 授权和自测。**不要使用 `docker compose down -v`。**
- 日志位置：
  - 向导/引擎：`%LOCALAPPDATA%\OpenMargin\logs`
  - 看门狗：`%LOCALAPPDATA%\OpenMargin\logs\watchdog.log`
  - Inno Setup 安装日志：`%TEMP%\Setup Log*.txt`（临时目录，不长期保留）

### 0.9 卸载、升级与本地权限（lifecycle / ACL）

**卸载做什么（`installer/Uninstall-OpenMargin.ps1`，由 Inno `[UninstallRun]` 在删除文件前调用）：**

1. 停止属于本安装目录的隐藏向导进程（只按命令行中的 `Start-OpenMarginSetupWeb.ps1` 绝对路径匹配，不杀无关 PowerShell）。
2. 停止看门狗进程，并执行 `open-margin-setup.ps1 uninstall-watchdog` 注销 `OpenMarginWatchdog` 计划任务。
3. 用部署引擎移除本项目的 Tailscale Funnel / Serve 路由；检测到别人的路由时返回 `FOREIGN_*` 并跳过，不会误删。
4. 执行 `docker compose --project-name personal-finance-system ... down`，**不带 `-v`**，Docker 命名卷保留。
5. 删除 18770–18779 的 loopback URL ACL 预留。
6. Docker 或 Tailscale 缺失时继续卸载并在摘要中标记 `skipped`。

**卸载保留什么：** `secrets/`、`.env`、Docker 命名卷（账本数据）、`%LOCALAPPDATA%\OpenMargin`（状态、日志、备份、看门狗状态）。Inno 只删除安装日志里记录的安装文件；运行期创建的这些路径不会被删。卸载脚本会把动作、保留项和手动清理命令写入：

- `%ProgramData%\OpenMargin\logs\uninstall-summary-<yyyyMMdd-HHmmss>.txt`
- 写入失败时回退到 `%LOCALAPPDATA%\OpenMargin\logs\`。

手动彻底清理（**删除 volume 会永久丢失账本数据**，只在确认不再需要时执行）：

```powershell
docker compose --project-name personal-finance-system --project-directory "%ProgramData%\OpenMargin\app" -f "%ProgramData%\OpenMargin\app\compose.yaml" down
docker volume ls --filter label=com.docker.compose.project=personal-finance-system
docker volume rm <上一步列出的卷名>
Remove-Item -Recurse -Force "$env:ProgramData\OpenMargin\app\secrets"
Remove-Item -Force "$env:ProgramData\OpenMargin\app\.env"
Remove-Item -Recurse -Force "$env:LOCALAPPDATA\OpenMargin"
```

**升级 / 覆盖安装：** Inno 对相同 `AppId` 的新版本采用覆盖式升级（追加卸载日志，不会自动运行旧版卸载程序）。因此 `OpenMargin.iss` 的 `PrepareToInstall` 会把随安装包携带的 `Stop-OpenMargin.ps1` / `Lifecycle.Common.ps1` 提取到 `{tmp}` 后运行，先停掉旧的隐藏向导、看门狗和 compose 项目，再替换文件，避免锁文件或继续跑旧代码。`[UninstallRun]` 只用于真正的卸载，并在 `waituntilterminated` 下等待清理完成；`RunOnceId` 防止多次升级后重复执行。从更早版本（没有 lifecycle 脚本）升级时，Pascal 代码回退为 `uninstall-watchdog` + `schtasks /End` + `docker compose ... down`。升级后旧容器已停止，重新打开“清衡设置”并执行一次部署即可；数据卷和 `secrets/` 不受影响。

**单实例：** 隐藏向导把 PID/端口写入 `%LOCALAPPDATA%\OpenMargin\setup-web.json`。`Stop-OpenMargin.ps1 -Status -Json` 返回：

```json
{"wizard":{"running":true,"pids":[1234],"statePid":1234,"stateMatches":true,"url":"http://127.0.0.1:18770/"}}
```

`Launch-Setup.ps1`（由启动器线程维护）应在选择新端口前先调用它：`running=true` 时直接打开已有 URL；`statePid` 存活但 `stateMatches=false` 时先删除过期 `setup-web.json`，不要静默在 18771 再起一个向导。`Stop-OpenMargin.ps1 -WizardOnly` 用于明确停止旧向导。

**本地权限（`Set-AppAcl.ps1` / `Install-UrlAcl.ps1`）：**

- URL ACL 不再授权 `BUILTIN\Users`（`S-1-5-32-545`）或 `Everyone`；改为 `sddl=D:(A;;GX;;;<安装用户SID>)(A;;GX;;;S-1-5-32-544)`，只给安装用户和 Administrators。SDDL 被系统拒绝时回退为仅安装用户的 `user=*<SID>` 预留，并在输出中警告。向导如果以管理员运行，URL ACL 本身不是必需；这条窄预留只服务 `-NoElevate` 路径。
- 应用根目录只有安装/原始用户可写（Modify），Administrators / SYSTEM 完全控制；`backend`、`deploy`、`docs` 给 Users 只读+执行（RX）；`secrets/` 仅安装用户 / Administrators / SYSTEM。升级时会显式删除旧安装留下的 Users/Everyone 显式 ACE（`/remove:g`），不只依赖 `/inheritance:r`。
- 脚本接受 `-OriginalUserSid` 和 `-OriginalUser`；正常 UAC 提权时当前进程身份仍是同一用户 SID，可作为兜底。若安装器由另一个管理员账户“以其他用户身份”提权，`{username}` 是管理员账户而不是原用户，此时应由集成方传入 `-OriginalUserSid <原用户SID>`；否则原用户可能无法写入 `.env` 和 `secrets/`。这是已知限制。

### 0.10 本线程实际验证了什么 / 没验证什么

已验证（静态）：

- 全部 `deploy/windows/**` PowerShell 文件通过 `[System.Management.Automation.Language.Parser]::ParseFile`。
- `docker compose --profile windows-private-sync config -q` 与 `--profile windows-public-sync config -q`（使用临时占位 secrets）。
- 新增 GitHub Actions YAML 通过 Python YAML 解析。
- `python scripts/check-public-ready.py` 通过（若本机依赖可用）。
- Pester 5.7.1：`deploy/windows/setup/tests` 共 83 项通过（本机 Windows 10 19045，含 `Lifecycle.Tests.ps1` 22 项）。Lifecycle 测试使用临时目录 + 假 `docker.cmd` / `tailscale.cmd`，覆盖：dry-run 不产生外部调用、真实路径 `compose down` 无 `-v`、只杀命令行匹配本安装目录的向导进程、`setup-web.json` 单实例状态、保留 `secrets/`、Funnel/Serve 只删自己的路由、Docker/Tailscale 缺失时继续、ACL 的 `icacls`/`netsh` 参数计划、`OpenMargin.iss` 的 `[UninstallRun]`/`PrepareToInstall` 静态接线。
- `OpenMargin.iss` 未在本机编译（本机无 Inno Setup）；静态检查确认 `[UninstallRun]` 在 `[Code]` 之前配置了 `Uninstall-OpenMargin.ps1`、`waituntilterminated`、`RunOnceId`、`skipifdoesntexist`，`PrepareToInstall` 提取 `dontcopy` 的 `Stop-OpenMargin.ps1`，`CloseApplications=yes`，且没有 `[UninstallDelete]` 指向 `secrets/` 或 `.env`。

未验证（发布前必须做）：

- Inno Setup 实际编译 `OpenMargin-Setup.exe`（本机未安装 Inno；CI 会安装并编译）。
- 在本机真实安装上执行一次卸载与一次覆盖升级（本轮只在隔离临时目录和假 CLI 上验证，未触碰 `D:\OpenMargin\app`）。
- 以管理员实际执行 `netsh http add urlacl ... sddl=...` 并确认 HttpListener 非提权绑定成功（本轮只验证参数计划；SDDL 被拒时的 `user=` 回退分支也未实机触发）。
- 真实 Tailscale 账号下卸载时移除 Funnel/Serve（本轮用 fixture 假 `tailscale`）。
- 当前分支的 `compose.yaml` 还没有 `windows-public-sync` service；运行时/compose 线程补齐前，远程同步 profile 不完整。
- 干净 Windows 10 Home 22H2 / Windows 11 虚拟机从零安装。
- 真实 Tailscale 账号、MagicDNS/HTTPS、Funnel 授权与公共 DNS 传播。
- 中国 SIM + 商业 VPN 手机访问 `*.ts.net`。
- Android 真机扫码、`server_id` 校验、断线补传。
- 完全离线安装（见 0.7）。
- 安装器无签名时的 SmartScreen 文案只在文档层面提供，未在真实下载链路验收。

## 方案 A：只在 Windows 本机使用（手动，不需要 Tailscale）

准备 Windows 10/11、Git、Docker Desktop（Linux containers / WSL2）、PowerShell 5.1+。Docker Desktop 启动后：

~~~powershell
git clone https://github.com/zxyzx0430/open-margin.git
cd open-margin
powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy\windows\Install-OpenMargin.ps1
~~~

打开 `http://127.0.0.1:18082/` 注册首位 Wallos 用户，并使用该账号登录。默认 Finance API 端口 `127.0.0.1:8767`、Wallos `127.0.0.1:18082`；API-only 网关 `127.0.0.1:18768` 使用同一 Compose 可选 profile，但**未启用 Tailscale Serve/Funnel 时不对远端开放**。

## 方案 B：Windows + Android 私有双向同步（手动 CLI，保留）

Windows 和 Android 均安装并登录 Tailscale，加入同一 tailnet；Tailscale 管理台开启 MagicDNS 和 HTTPS。首位 Wallos 管理员必须先通过本机 `127.0.0.1:18082` 注册。

在项目根目录执行首次安装向导：

~~~powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy\windows\Start-OpenMarginSetup.ps1
~~~

向导检查 Docker、Tailscale，识别 `*.ts.net` HTTPS 主机名，启动同一套 Docker Compose，并在注册成功且用户输入 `READY` 后才开启私有 Tailscale Serve。默认 `ApiOnly` 通过独立 Nginx 网关转发同步相关路径；**不会对手机开放 Wallos、OAuth、MCP 或财务管理 API**。

已有本机安装也可以明确执行：

~~~powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy\windows\Install-OpenMargin.ps1 -PublicBase "https://your-pc.your-tailnet.ts.net/finance" -ExposureMode ApiOnly -ConfigureTailscale
~~~

如果需要在 tailnet 内访问完整网站，只能在首次安装时明确选用 `-ExposureMode PrivateFull`。已有模式不允许无审计切换，安装器不会自动清空或覆盖其他应用的 Serve 路由；不要使用公开的 `tailscale funnel` 开启私有模式。

Android 进入 **设置 → Windows 首次连接向导**，输入 `https://your-pc.your-tailnet.ts.net/finance` 和本机 `secrets/backend.env` 中 `FINANCE_DEVICE_TOKEN`。先做只读连接测试，核对服务器账本身份和同步游标，再确认启用。也可继续使用原 VPS 同步状态卡。

**API-only 正常响应：** `/finance/health` → 200；`/finance/api/v2/sync/changes` 无设备密钥 → 401；`/finance/wallos/` → 404。不要因 404 而关闭 Android HTTPS 校验；这是隔离策略。

## 可选：本机 AI 客户端使用只读 MCP（实验功能）

对于支持 MCP stdio 的桌面 AI 客户端，可将程序配置为调用 Windows PowerShell 脚本 `deploy/windows/Start-OpenMarginLocalMcp.ps1`（以 PowerShell -NoProfile -ExecutionPolicy Bypass -File 启动）。它复用现有 Finance API Docker 容器和同一份账本，只暴露 8 个只读工具。不需要公网地址、OAuth、管理密钥或 Android 设备密钥；只有具有 Docker 容器运行权限的本机用户可以使用。需保持 Docker Desktop 运行。这里不支持云端 ChatGPT 网页版直连，未来需使用独立的安全隧道。Windows + Docker Desktop 实机端到端验收尚未完成；详细架构见 `WINDOWS_MCP_ARCHITECTURE.md`。

## 备份、恢复与排障

- Windows 关闭、Docker 停止或 Tailscale 离线时，Android Room 仍可继续记账，恢复网络后由 WorkManager 补传（后台约 15 分钟起，实际受系统限制）。
- SQLite 使用 Docker 命名卷，源码更新不会主动清空它。**不要使用 `docker compose down -v`，不要删除旧 APK 以尝试升级。**
- 运行 `docker compose --profile maintenance run --rm backup` 备份已初始化的 Finance、Wallos 与 OAuth SQLite。备份在宿主机外的独立介质保留更稳妥。
- `docker compose ps` 检查容器；`docker compose logs --tail=100 finance-api` 排查后端；`tailscale funnel status --json` / `tailscale serve status --json` 查看路由；同时检查 Docker Desktop、手机网络/VPN 和证书。
- 手机不能直接切换到另一份未经迁移的服务器账本；服务器版本、身份或游标不一致时停止并人工核对。
- 手机与网站目前双向复制交易记录与排除状态，服务器管理退款关系和订阅预算。原 v1.1.3 已删除且未留下删除标记的记录无法从历史中自动重建。

更完整的使用状态见 [Windows 首次连接详细文档](WINDOWS_PRIVATE_FIRST_CONNECTION.md)。Tailscale CLI 规则以 [官方 Serve 文档](https://tailscale.com/docs/reference/tailscale-cli/serve) 和 [Funnel 文档](https://tailscale.com/kb/1223/funnel) 为准。

**验收约束：** 只有完成 Windows 实机从零安装、Android 首次连接和 v1.1.3 → v1.2.0 同签名无损升级后，才可将 v1.2.0 宣称为正式发布可用。v1.3.0 一键安装同样需要完成第 0.9 节的未验证项，才能宣称为正式可用。
