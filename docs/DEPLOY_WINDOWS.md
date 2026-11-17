# 清衡 Open Margin｜Windows 部署

Windows 和 VPS 共用 `compose.yaml`、Finance API、Wallos、SQLite 结构及 Android 双向同步协议。区别只是连接入口。**默认 Web 仅可从 Windows 本机访问，远程同步接口需要单独开启。**

> **证据标签（2026-11-17）**：本文的一键安装脚本、Inno Setup 脚本、离线镜像包脚本和 CI 工作流已在本仓库实现，并通过 PowerShell 解析、Pester、compose 配置解析与 YAML 语法检查；安装器已在本机用 Inno Setup 6.7.4/6.3.3 实际编译。真实 Windows 干净机、Tailscale Funnel、中国 SIM + 商业 VPN、Android 真机扫码与真实安装/卸载**尚未在本线程实际运行**；发布前必须按第 7 节逐项验收。

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

- 安装到用户选择的位置；默认 `D:\OpenMargin\app`，没有 D: 时默认 `E:\OpenMargin\app`，再退回系统盘。安装向导始终显示“选择安装位置”页面，可改到任意有空间的磁盘。
- 安装向导分别检查**安装盘**和 **Docker 数据盘**：安装盘低于 2GB 阻止、低于 5GB 警告；Docker 数据盘低于 3GB 阻止、低于 5GB 警告。检查的是实际盘符，不再固定检查 C:。
- 开始菜单创建：
  - **清衡设置**：启动本机向导（`http://127.0.0.1:18770/` 起，端口被占用时顺延到 18779）并打开浏览器。
  - **清衡账本**：打开 `http://127.0.0.1:18082/`。
- 不要求 Git、Python、Node 或 PowerShell 7；网页向导本身以 Windows PowerShell 5.1 启动。
- **不打包** `secrets/`、`.env`、数据库、Docker 卷或设备令牌。运行期密钥由向导在本机生成。
- 安装器需要管理员权限（一次 UAC）。安装本身只写程序目录和开始菜单，不安装 Docker/Tailscale。
- 支持 64 位 Windows 10 21H2（内部版本 19044）及以上，推荐 Windows 10 22H2（19045）或 Windows 11；低于 19044 会被安装器直接阻止。Windows 10 22H2 已停止支持：向导会警告并建议升级 Windows 11，但不会因为 21H2/22H2 而阻止安装。
- 安装完成后自动启动“清衡设置”向导并打开浏览器；自动化安装可加 `/NOAUTOSTART` 跳过自动启动。`Launch-Setup.ps1` 启动失败会弹出可见的错误对话框，并在浏览器无法打开时显示完整 URL。
- **一次 UAC 的向导**：安装器已经提权，向导服务器复用该管理员权限运行，配置 Docker/Tailscale/URL ACL 时不再反复弹 UAC；浏览器通过 `explorer.exe` 以当前普通用户打开，不是管理员浏览器。服务器仍只监听 `127.0.0.1`，使用一次性 setup token、Host/Origin 校验和固定动作白名单；token URL 只短暂写入受 ACL 限制的本机文件，启动后即删除。安全取舍是：本机获得该 token 的程序可以触发向导的固定动作，因此不要把它暴露给其他本机用户。`/NOELEVATE` 可退回以普通用户运行向导（后续依赖安装可能再次弹 UAC）。
- 安装器界面默认简体中文（可切换 English），包含 Docker Desktop 许可确认页、安装前要求页和自定义路径迁移警告；详见 0.11。
- 重复安装会进入升级/修复流程，不覆盖已有 `secrets/` 和 Docker 命名卷。

### 0.3 向导流程

1. 打开“清衡设置”，浏览器进入本机向导。
2. 环境检查：Windows 版本/64 位、管理员、安装盘空间、Docker 数据盘空间、WSL2、Docker Desktop、Tailscale、端口、已有安装。
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
- `WAITING_REBOOT` 会写当前用户的 `RunOnce`，重启登录后自动重新打开向导（`-Resume`）。
- 依赖安装的提权 helper 已检测并使用 Windows PowerShell 5.1 的 `powershell.exe`，不再要求 PowerShell 7。

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

v1.3.0 资产（可以直接转发给用户）：

- 下载：<https://github.com/zxyzx0430/open-margin/releases/download/v1.3.0/OpenMargin-Offline-1.3.0.tar.gz>
- 大小：161,126,294 字节（约 154 MiB）
- SHA256：`d74f570191302c023639fa2eeb52526f53a49a6a6212e26c3e185e026f543783`
- 校验文件：同目录的 `OpenMargin-Offline-1.3.0.tar.gz.sha256`

向导内导入：

1. 打开“清衡设置”，进入第 1 步“环境检查”，找到“Docker 下载失败？使用离线镜像包”卡片。
2. 点“下载离线镜像包”，下载完成后核对卡片上显示的 SHA256（或 Release 旁的 `.sha256`）。
3. 点“我已下载，导入”。向导服务器会在本机弹出 Windows 文件选择框；选中 `.tar.gz` 后开始导入。浏览器不需要上传 161 MB 文件。
   - 如果文件选择框打不开，点“手动输入路径”，粘贴完整路径（例如 `D:\Downloads\OpenMargin-Offline-1.3.0.tar.gz`）。
   - 导入过程：校验 SHA256 → 拒绝路径穿越/符号链接并安全解压到临时目录 → 校验 `manifest.json` / `images.tar` → `docker load -i images.tar`。可能需要几分钟。
   - 成功后写入 `offlineImported=true`；从这一步开始的部署会自动使用 `docker compose up --no-build`，不再重新构建镜像。后续“一键修复/重新开启”也复用已加载镜像。
   - 失败时不会删除已下载的离线包；Docker 未安装或未运行时会在解压前返回明确错误。SHA 不一致、解压失败、manifest 版本不匹配都会停止导入。

手动恢复（不使用向导时）：

```powershell
Get-FileHash .\OpenMargin-Offline-1.3.0.tar.gz -Algorithm SHA256
tar -xzf .\OpenMargin-Offline-1.3.0.tar.gz
docker load -i .\images.tar
```

然后再从开始菜单打开“清衡设置”继续。手动 `docker load` 不会写入向导状态；如果要在向导里继续用 `--no-build`，请使用上面的“导入”入口。

**验证边界**：离线包资产已在 GitHub Release 上（本线程通过 GitHub API 核对了名称、大小与 SHA256）。本线程用 Pester 和假的 `docker` 验证了 SHA 校验、路径穿越/符号链接拒绝、manifest 校验、`docker load -i` 参数与 `offlineImported` 状态；**没有在本机实际下载并加载 161 MB 实包，也没有在干净 Windows 虚拟机做完全离线部署**。发布前仍需按第 7 节实机验收。

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
$app = 'D:\OpenMargin\app'   # 或你安装时选择的目录（无 D: 时默认 E:\OpenMargin\app）
docker compose --project-name personal-finance-system --project-directory $app -f "$app\compose.yaml" down
docker volume ls --filter label=com.docker.compose.project=personal-finance-system
docker volume rm <上一步列出的卷名>
Remove-Item -Recurse -Force "$app\secrets"
Remove-Item -Force "$app\.env"
Remove-Item -Recurse -Force "$env:LOCALAPPDATA\OpenMargin"
```

**升级 / 覆盖安装：** Inno 对相同 `AppId` 的新版本采用覆盖式升级（追加卸载日志，不会自动运行旧版卸载程序）。因此 `OpenMargin.iss` 的 `PrepareToInstall` 会把随安装包携带的 `Stop-OpenMargin.ps1` / `Lifecycle.Common.ps1` 提取到 `{tmp}` 后运行，先停掉旧的隐藏向导、看门狗和 compose 项目，再替换文件，避免锁文件或继续跑旧代码。`[UninstallRun]` 只用于真正的卸载，并在 `waituntilterminated` 下等待清理完成；`RunOnceId` 防止多次升级后重复执行。从更早版本（没有 lifecycle 脚本）升级时，Pascal 代码回退为 `uninstall-watchdog` + `schtasks /End` + `docker compose ... down`。升级后旧容器已停止，重新打开“清衡设置”并执行一次部署即可；数据卷和 `secrets/` 不受影响。

**单实例：** 隐藏向导把 PID/端口写入 `%LOCALAPPDATA%\OpenMargin\setup-web.json`。`Stop-OpenMargin.ps1 -Status -Json` 返回：

```json
{"wizard":{"running":true,"pids":[1234],"statePid":1234,"stateMatches":true,"url":"http://127.0.0.1:18770/"}}
```

`Launch-Setup.ps1` 在选择新端口前先调用它：`running=true` 时直接打开已有 URL；`statePid` 存活但 `stateMatches=false` 时先调用 `Stop-OpenMargin.ps1 -WizardOnly` 清理过期状态，不会静默在 18771 再起一个向导。`Stop-OpenMargin.ps1 -WizardOnly` 也用于明确停止旧向导。从开始菜单启动时，`Launch-Setup.ps1` 默认请求一次 UAC 以管理员运行（与安装器的“一次 UAC”设计一致），`/NOELEVATE` 可强制普通用户运行；浏览器 token 文件 `setup-web-url.txt` 在向导运行期间保留，向导退出时删除。

**本地权限（`Set-AppAcl.ps1` / `Install-UrlAcl.ps1`）：**

- URL ACL 不再授权 `BUILTIN\Users`（`S-1-5-32-545`）或 `Everyone`；改为 `sddl=D:(A;;GX;;;<安装用户SID>)(A;;GX;;;S-1-5-32-544)`，只给安装用户和 Administrators。SDDL 被系统拒绝时回退为仅安装用户的 `user=*<SID>` 预留，并在输出中警告。向导如果以管理员运行，URL ACL 本身不是必需；这条窄预留只服务 `-NoElevate` 路径。
- 应用根目录只有安装/原始用户可写（Modify），Administrators / SYSTEM 完全控制；`backend`、`deploy`、`docs` 给 Users 只读+执行（RX）；`secrets/` 仅安装用户 / Administrators / SYSTEM。升级时会显式删除旧安装留下的 Users/Everyone 显式 ACE（`/remove:g`），不只依赖 `/inheritance:r`。
- 脚本接受 `-OriginalUserSid` 和 `-OriginalUser`；正常 UAC 提权时当前进程身份仍是同一用户 SID，可作为兜底。若安装器由另一个管理员账户“以其他用户身份”提权，`{username}` 是管理员账户而不是原用户，此时应由集成方传入 `-OriginalUserSid <原用户SID>`；否则原用户可能无法写入 `.env` 和 `secrets/`。这是已知限制。

### 0.10 本线程实际验证了什么 / 没验证什么

已验证（静态与安装器编译）：

- 全部 `deploy/windows/**` PowerShell 文件通过 `[System.Management.Automation.Language.Parser]::ParseFile`；新增 `Migrate-OpenMargin.ps1` 同时通过 PowerShell 7 与 Windows PowerShell 5.1 parser。
- `docker compose --profile windows-private-sync config -q` 与 `--profile windows-public-sync config -q`（使用临时占位 secrets）。
- 新增 GitHub Actions YAML 通过 Python YAML 解析。
- `python scripts/check-public-ready.py` 通过（若本机依赖可用）。
- Pester 5.7.1：`deploy/windows/setup/tests` 共 **174 项全部通过（0 失败、0 跳过）**，本机 Windows 10 19045；含 `Acceptance.Tests.ps1`（18 项真实安装故障回归）、`Lifecycle.Tests.ps1`、`OfflineBundle.Tests.ps1`（20 项，含路径穿越/符号链接拒绝）、`Installer.Tests.ps1`、`Probes.Tests.ps1`、`Integration.Tests.ps1` 和 `Dependencies.Tests.ps1`（27 项，含 Docker 许可门禁、进度文件锁定、URL ACL 183、Tailscale operator 回退与 reboot 语义）。Lifecycle 测试使用临时目录 + 假 `docker.cmd` / `tailscale.cmd`，覆盖：dry-run 不产生外部调用、真实路径 `compose down` 无 `-v`、只杀命令行匹配本安装目录的向导进程、`setup-web.json` 单实例状态、保留 `secrets/`、Funnel/Serve 只删自己的路由、Docker/Tailscale 缺失时继续、ACL 的 `icacls`/`netsh` 参数计划、`OpenMargin.iss` 的 `[UninstallRun]`/`PrepareToInstall` 静态接线。
- 真实安装故障回归：进度文件改用唯一临时文件 + `[IO.File]::Replace`/`Copy` 重试，目标被锁定也不抛错、不中断依赖安装；`tailscale set` 不支持 `--operator` 时探测能力并回退 `tailscale up`（仅在节点 Running 时），否则以 `ok=true, warning=true` 跳过；URL ACL 已存在（netsh 183）视为成功且不删除；`pendingReboot` 只由新装 WSL 或安装器 3010/needsReboot 触发，不再被通用 `PendingFileRenameOperations` 误置。
- 安装器已用 **Inno Setup 6.7.4** 和 **6.3.3** 在本机实际编译通过：`Build-Installer.ps1 -OutputDir ... -NoAutoInstall` 分别产出 `OpenMargin-Setup.exe`。编译覆盖中文语言文件、`MinVersion`、Docker 许可自定义页、`REQUIREMENTS.txt` 的 `InfoBeforeFile` 和自定义目录警告。6.3.3 只对 6.5 新增的下载/校验消息给出“未识别、忽略并回退英文”警告（仅下载/哈希错误路径），不影响安装主流程。
- ego-browser 在 `-Dev -DryRun` 向导上对本机 18790 做了页面验收：9 个屏幕（含运行状态）的“返回主界面”按钮均可见；离线卡片的下载链接与 SHA256 正确、手动路径行可展开；Docker 许可复选框默认显示，未勾选时“一键安装依赖”被前端拦截；页面无 console error，DOM/URL 中没有 40+ 字符的 setup token。
- 单实例运行验收：用临时 StateRoot 启动 `Launch-Setup.ps1 -NoBrowser` 两次，第二次复用同一隐藏向导（进程数保持 1）并打印带 token 的 URL；构造 `pid=4` 的过期 `setup-web.json` 后启动，`Stop-OpenMargin.ps1 -WizardOnly` 清除过期状态并只启动一个新向导；`Stop-OpenMargin.ps1 -WizardOnly` 随后把向导进程降到 0。
- `Migrate-OpenMargin.ps1` 在 Windows PowerShell 5.1 下以只读模式识别到本机现有安装 `D:\OpenMargin\app`（未修改/卸载该目录）；在 `E:\DevCaches` 合成目录完成 `-Backup -Yes` 与 `-CopySecrets -Yes`，验证备份清单、SHA256 与不覆盖行为。未对真实安装执行 `-StopOld`/`-CopySecrets`。

未验证（发布前必须做）：

- CI（`windows-setup-installer.yml`，choco 安装最新 Inno）尚未在本线程运行；本机只验证了 Inno 6.7.4 与 6.3.3，Inno 6.4 未单独编译。
- 安装器 UI 在干净 Windows 10/11 上的实际点击、中文渲染、Docker 许可页勾选和目录迁移警告未做人工走查。
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

### 0.11 安装器界面、许可与迁移（安装器 UX/合规）

- **界面语言**：`[Languages]` 第一项为简体中文，`LanguageDetectionMethod=uilanguage`；非中文 Windows UI 仍可在安装器里切换 English。中文语言文件随安装器打包在 `installer/languages/ChineseSimplified.isl`（来自 Inno Setup 官方翻译，仅把版本过低提示改成 Open Margin 专用文案），不依赖本机是否安装编译器语言包。
- **系统门槛**：`MinVersion=10.0.19044`（64 位 Windows 10 21H2，也是 Docker Desktop 的最低要求）。`[Code]` 的 `InitializeSetup` 再检查 64 位与内部版本；Windows 10 21H2（19044）会提示“建议升级 22H2/Windows 11”但继续；系统盘可用空间低于 15 GB 只警告不阻止；硬件虚拟化仍由向导 `Probes.ps1` 检测。
- **版本过低文案**：`ChineseSimplified.isl` 的 `WinVersionTooLowError` 已改为中文，明确写出 64 位、19044/22H2、Windows Update 和“安装已停止”。
- **Docker Desktop 许可/同意**：安装器在安装任何文件前显示自定义“Docker Desktop 许可与依赖说明”页，内容来自 `DockerDesktopNotice.txt`；必须勾选“我已阅读并理解上述说明”才能继续。文案说明 Docker Subscription Service Agreement、个人/教育/非商业开源/小企业免费条件（员工 <250 且年收入 <$10M）、商业订阅链接，以及向导的 `--accept-license` 会代表用户接受 Docker 条款。勾选后在 `{app}\docker-license-ack.txt` 写确认标记（时间、安装器版本、说明文件名；不含账号、密码或订阅密钥）。
  - **集成结果**：向导的 `install-deps` 卡片现在显示 Docker 许可同意复选框；`lib/Dependencies.ps1` 的 `Install-OmDockerDesktop` 只有在 `{app}\docker-license-ack.txt` 存在（安装器已确认）或本次请求携带 `licenseAcknowledged=true`（网页复选框）时才传 `--accept-license`，否则返回 `DOCKER_LICENSE_REQUIRED` 并提示用户确认许可。
- **安装前要求页**：`REQUIREMENTS.txt` 通过 `InfoBeforeFile` 在 Ready 页前显示；涵盖 Windows 22H2+/19044、虚拟化、15 GB 磁盘、UAC、Docker/Tailscale、SmartScreen、SHA256、自定义路径、浏览器兜底和离线包位置。`README-INSTALL.txt` 是开始菜单“安装说明”和同目录文本说明。
- **浏览器兜底与单实例**：安装完成后 `[Run]` 启动 `Launch-Setup.ps1`，且已去掉 `skipifsilent`，静默安装也会拉起向导。向导在 18770–18779 选空闲端口并打开浏览器。README-INSTALL 说明浏览器未打开时查看 `%LOCALAPPDATA%\OpenMargin\setup-web.json` 的端口、结束旧隐藏 `powershell.exe` 后重新打开“清衡设置”，并明确只允许同时运行一个向导。`Launch-Setup.ps1` 会用 `explorer.exe` 把 URL 交给原用户 shell，等待浏览器进程出现；若没有出现则退回 `Start-Process`，仍失败时弹出包含完整 token URL 的 MessageBox。token 文件在向导运行期间保留，避免 handoff 失败后无法恢复。
  - **集成结果**：`Start-OpenMarginSetupWeb.ps1` 不再吞掉 `Start-Process $url` 失败，会把 URL 写入仅当前用户可读的 `setup-web-url.txt`；`/api/config` 同时返回 `elevated`，非管理员向导（`/NOELEVATE`）会在环境页显示说明。`open-margin-setup.ps1 check`/`status` 与网页 `check` 都会忽略向导自身的 setup 端口（含 `setup-web.json` 记录的实时端口），不会再误报 `PORT_CONFLICT`。
- **自定义路径迁移**：`UsePreviousAppDir=yes` 会优先沿用注册表里的 `InstallLocation`（本机现有安装即 `D:\OpenMargin\app`）；`DirExistsWarning=no` 保留 Inno 的升级/修复行为。`[Code]` 在选择目录页检测“上一安装目录 ≠ 新目录”或“默认目录（D:\OpenMargin\app，无 D: 时 E:\OpenMargin\app，再退回系统盘）已存在”，用中文警告说明继续会留下两套程序目录/secrets/看门狗，旧容器和命名卷不会自动迁移或删除。
  - `Migrate-OpenMargin.ps1`（新增，安装后在 `{app}\deploy\windows\setup\installer\`）默认只读：扫描 Inno 卸载注册表（AppId `{9D2D6B5A-...}`）和常见路径，报告容器/命名卷/看门狗状态；`-Backup -Yes` 把 `secrets/`、`.env`、`compose.yaml` 复制到 `%LOCALAPPDATA%\OpenMargin\migration-backups\<timestamp>` 并写 `MIGRATION.json`（含 SHA256）；`-StopOld -Yes` 执行 `docker compose ... stop` 并结束旧看门狗任务（保留计划任务、容器和卷）；`-CopySecrets -Yes` 把旧 `secrets/` 复制到新根且不覆盖已有文件。脚本不删除文件、不卸载、不执行 `docker compose down -v`。
- **离线包位置**：仍是 GitHub Release 的独立资产 `OpenMargin-Offline-<version>.tar.gz` + `.sha256`；安装器和 `REQUIREMENTS.txt` 不承诺“完全离线可装”。

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
