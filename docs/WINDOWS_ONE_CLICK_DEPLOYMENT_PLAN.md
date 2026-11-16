# Windows 一键部署向导｜详细实施计划

> 版本：v2（已吸收独立 subagent 的“傻瓜用户”二审）｜2026-11-17｜状态：待产品负责人确认后执行
>
> 关联：[Windows 部署说明](DEPLOY_WINDOWS.md) · [首次连接](WINDOWS_PRIVATE_FIRST_CONNECTION.md) · [MCP 架构](WINDOWS_MCP_ARCHITECTURE.md) · [Tailscale Funnel](https://tailscale.com/kb/1223/funnel)

## 0. Context

当前 Windows 部署是终端 PowerShell 向导：要求用户先装 Docker Desktop 和 Tailscale、自己注册 Wallos、自己处理 Tailscale Serve，失败时看英文报错。它适合开发者，不适合普通用户。

本计划把它升级成产品级的一键部署：

- 用户从 GitHub Releases 下载 `OpenMargin-Setup.exe`，双击后浏览器自动打开本机向导。
- 向导检测并自动安装 Docker Desktop、Tailscale、WSL2；重启后自动继续。
- 用户只做无法代替的动作：UAC、Tailscale 注册/登录、Funnel 授权、管理员密码、重启、手机安装与扫码。
- v1 先交付**远程 Funnel 单模式**；现有私有同步脚本保持不动，网页向导只做检测/状态展示，后续再迁入。
- 手机扫码使用**一次性配对码**，不在二维码里放长期 device token。
- 外部 AI / MCP 只预留模块接口，本次不实现、不暴露。

这不是“再写一个脚本”，而是把安装、配置、验证、恢复拆成可测试的状态机。

## 1. 范围决定（v1）

### 做

- `OpenMargin-Setup.exe` 安装包 + 内置运行文件。
- 本机网页向导 + 确定性部署引擎 + CLI。
- 依赖自动安装：WSL2、Docker Desktop、Tailscale。
- 远程模式：Tailscale Funnel 443 → API-only 同步网关。
- Wallos 管理员自动注册（官方 `/registration.php`）。
- 一次性配对码 + 手机扫码。
- 自测、断开/关闭、状态页、日志、修复。
- 在线安装 + 离线镜像包（国内网络兜底）。
- Pester / 网关 / Android / 真机测试。

### 不做

- 私有同步的新网页向导（现有 `Start-OpenMarginSetup.ps1` 继续可用；后续版本迁入）。
- 仅本机 / 局域网模式。
- MCP 实现与公网暴露（只留模块注册表和禁用卡片）。
- 自动更新、自动卸载、macOS/Linux。
- 修改同步协议、Room schema、账本语义。

### 关键决定

| 决定 | 选择 | 理由 |
|---|---|---|
| v1 主模式 | 远程 Funnel | 用户手机常开商业 VPN，私有 Tailscale 会与 VPN 抢 Android 唯一的 VPN 槽 |
| 私有模式 | 保持现有 CLI 脚本 | 不把两个模式同时塞进 v1，避免半成品 |
| 配对方式 | 二维码直接带设备密钥（本机显示、10 分钟隐藏、可轮换） | 少改后端；用显示时限、警告和轮换降低泄露风险 |
| 网页服务器 | HttpListener + URL ACL，TcpListener 兜底 | 不默认自研完整 HTTP 栈；URL ACL 在提权阶段一次完成 |
| 安装包 | Inno Setup，内置运行文件 | 用户不需要 Git/Python/Node/PowerShell 7 |
| 国内网络 | 在线安装 + 离线镜像包 | 不能把“配镜像”丢给小白 |
| 远程端口 | Funnel 443 | 与现有 Android URL 校验 `https://*.ts.net/finance` 兼容 |
| `FINANCE_PUBLIC_URL` | 保持 Web/OAuth/MCP 基址 | 不因远程同步破坏本机网页登录 |
| 手机同步地址 | 新增 `FINANCE_SYNC_PUBLIC_URL` | 只给手机/二维码，后端不消费 |
| 自启/守护 | 登录后隐藏计划任务 + PowerShell 看门狗 | 不弹窗；Docker/Tailscale/容器/Funnel 掉线自动拉起 |

## 2. 用户流程

### 2.1 从下载到可用

```text
下载 OpenMargin-Setup.exe
  -> 双击（可能遇到 SmartScreen：更多信息 -> 仍要运行）
  -> 自动打开 http://127.0.0.1:18770/
  -> 欢迎页：开始
  -> 环境检查
       ├─ 缺 WSL2 / Docker / Tailscale：一键安装（一次 UAC）
       ├─ 需要重启：保存工作 -> 立即重启 / 稍后重启
       └─ 虚拟化未开：图文提示进 BIOS（无法自动）
  -> 注册/登录 Tailscale
  -> 开启 MagicDNS / HTTPS（自动检测 + 直达链接）
  -> 安全确认（v1 只有远程同步，不需要选模式）
  -> 设置管理员账号
  -> 一键部署
  -> Funnel 授权：浏览器弹窗点“允许”
  -> 自测 4 项
  -> 完成页：下载 App 二维码 + 一次性配对二维码 + 注意事项
  -> 手机安装 App、授权、扫码、测试连接
  -> 可选：发一笔真实小额交易，确认自动采集
  -> 以后从开始菜单“清衡设置”打开状态页
```

### 2.2 用户必须亲手做的动作

| 动作 | 次数 | 为什么不能代替 |
|---|---:|---|
| SmartScreen“仍要运行” | 0–1 次 | 未签名安装器的系统提示 |
| UAC 点“是” | 1 次（批量安装） | Windows 安全边界 |
| 注册/登录 Tailscale | 1 次 | 账号安全；新用户还要邮箱验证 |
| 管理台开 MagicDNS / HTTPS | 1 次 | Tailscale 账号权限 |
| 浏览器允许 Funnel | 1 次 | Tailscale 账号权限 |
| 设置管理员用户名/密码 | 1 次 | 用户自己要记住 |
| 重启（如需） | 1 次 | WSL2/Docker 安装 |
| 手机允许未知来源、安装 APK | 1 次 | Android 安全 |
| 手机授权通知/短信/无障碍/电池 | 1 次 | Android 权限 |
| 手机扫码 | 1 次 | 手机端操作 |

除此之外：下载、校验、安装、启动、配置、创建管理员、生成配对码、自测、重启续装、失败重试，全部自动。

### 2.3 完成页必须说清楚

- 只暴露同步接口；Wallos、MCP、OAuth、管理 API 不可达。
- 电脑休眠/关机/Docker 停止时同步暂停；手机继续本地记账，恢复后补传。
- 一次性配对码 5 分钟内有效；二维码不要截图分享。
- 建议点“一键备份”；本机备份不等于异地备份。
- 怎么关闭远程模式、怎么重新开启。
- 外部 AI 本次不包含。

### 2.4 用户怎么拿到安装包

- 从 GitHub Releases 下载 `OpenMargin-Setup.exe`；安装包内置部署引擎、网页资源、backend、compose、deploy 配置。
- 不需要 Git、Python、Node、PowerShell 7；Windows PowerShell 5.1 即可。
- 只有 Docker 镜像需要联网；国内网络失败时使用离线镜像包。
- 如果 GitHub 慢，提供镜像/网盘说明；安装包可以作为文件转发。
- 已有仓库目录时，安装器提供“使用现有目录”，不重复下载。

## 3. 总体架构

```text
浏览器（本机）
  └─ http://127.0.0.1:18770/   网页向导（静态 HTML/JS）
        └─ 本机部署引擎（PowerShell 5.1 模块，仅 127.0.0.1）
             ├─ 状态机 / 状态文件 / 脱敏日志
             ├─ 环境探测
             ├─ 依赖安装（WSL2 / Docker / Tailscale）
             ├─ Docker Compose 控制
             ├─ Tailscale Funnel 控制
             ├─ Wallos 管理员创建
             ├─ 一次性配对码
             └─ 自测

CLI（高级用户 / 本地 Agent）
  └─ open-margin-setup.ps1 status/check/install-deps/deploy/verify/disable/repair

手机（只开商业 VPN）
  └─ https://<pc>.<tailnet>.ts.net/finance
       └─ API-only 网关 127.0.0.1:<gateway-port>
            ├─ 同步接口
            └─ 一次性配对接口

外部 AI（以后，不在本次）
  └─ https://.../finance/mcp
       └─ 独立 MCP 网关 + OAuth
```

### 3.1 组件职责

| 组件 | 职责 | 不做什么 |
|---|---|---|
| 部署引擎 | 固定动作、状态机、探测、安装、配置、自测 | 不调用大模型、不接受任意 shell |
| 网页向导 | 展示状态、收集必要输入、触发固定动作 | 不直接执行系统命令 |
| CLI | 给高级用户/本地 Agent 的机器可读接口 | 不绕过检查 |
| Compose | Finance API、Wallos、同步网关 | 不暴露额外端口 |
| Tailscale 层 | Funnel 公网入口 | 不与别的 Tailscale 路由冲突 |
| Android | 扫码、一次性配对、同步 | 不自动启用、不信任未验证账本 |

### 3.2 设计原则

1. 确定性：没有大模型参与部署决策。
2. 可重入：重复运行先检查现状，不重复做。
3. 可恢复：关浏览器、重启、断网后能从状态文件继续。
4. 失败安全：失败停在当前步骤，不破坏已有数据，不覆盖别人的路由。
5. 最小暴露：公网只放行同步和一次性配对接口；MCP 单独模块、默认关闭。
6. 密钥不落日志/状态文件；二维码不含长期 token。
7. 所有动作有结构化结果和可执行的修复建议。

## 4. 二审发现与修订（必须解决的真问题）

### 4.1 安装包不存在（P0，已补）

原计划假设“双击安装包”，但没有定义安装包。v1 必须交付：

- Inno Setup 构建的 `OpenMargin-Setup.exe`。
- 内置 `deploy/windows/setup/`、`backend/`、`compose.yaml`、`deploy/`。
- 安装目录 `%ProgramData%\OpenMargin\app`。
- 开始菜单“清衡设置”快捷方式。
- 代码签名（有证书则签；无证书给 SmartScreen 图文说明和 SHA256）。
- GitHub Actions 在 tag 时构建并附加到 Release。

### 4.2 国内网络闭环（P0，已补）

- 在线安装器先试 Docker Hub；失败后提供“下载离线镜像包”按钮。
- 离线镜像包由 CI 构建：`docker compose build` + `docker save` 基础镜像和构建镜像，压缩后附加到 Release。
- 离线包校验 SHA256；安装器 `docker load` 后继续。
- 不把“请输入镜像地址”作为小白的主路径；镜像输入只放在高级选项。
- Tailscale 控制面/DERP/Funnel 预检；失败明确告知并给出 VPS/Cloudflare 备选，不假装成功。

### 4.3 手机侧不能一句“手机步骤”带过（P0，已补）

完成页必须是一个可执行的手机清单：

1. 下载 App（独立二维码/按钮，指向最新签名 APK）。
2. 允许安装未知应用。
3. 安装并打开 App。
4. 按 App 内“自动记账设置”授权：通知使用权、短信（可选）、无障碍页面识别（可选）、电池优化白名单。
5. 设置 → 扫码连接，扫一次性配对二维码。
6. 点“测试连接”，看到账本身份和成功。
7. 可选：发一笔真实小额支付，确认自动采集。

向导状态增加 `PHONE_VERIFIED`；手机测试连接成功后才算完成。

### 4.4 Tailscale 前置不是“登录一次”（P0，已补）

- 新用户需要注册账号；登录方式可能是 Google/Microsoft/GitHub；国内网络下 Google 可能不可用，提供 Microsoft/邮箱方式说明。
- 邮箱验证。
- 管理台开启 MagicDNS + HTTPS。
- Funnel 授权。
- 向导逐项检测并给直达链接；不假设用户已有账号。

### 4.5 私有模式与商业 VPN 冲突（P0，已收窄）

- Android 只有一个 VPN 槽；Tailscale 和商业 VPN 不能同时开。
- v1 不做私有模式的新向导；现有 CLI 脚本保留。
- 网页向导只提供远程 Funnel；如果用户不需要公网，明确告知“请使用现有私有同步脚本，或等待后续版本”。
- 不再把私有模式叫“只在家里”（它需要在手机上装 Tailscale）。

### 4.6 Funnel 国内可达性必须先验证（P0，已补）

M0 技术验证必须用中国 SIM + 商业 VPN 实测：

- Windows 上 Tailscale 能否登录、保持连接。
- 手机通过商业 VPN 能否访问 `https://<host>.ts.net/finance/health`。
- Funnel 授权页能否打开。
- DNS 传播时间。

失败则远程模式不成立，必须转 VPS/Cloudflare Tunnel；在验证通过前不投入完整向导开发。

### 4.7 重启后没有入口（P0，已补）

- 开始菜单快捷方式“清衡设置”：启动本机向导并打开浏览器。
- 开始菜单快捷方式“清衡账本”：打开本机 Wallos/账本页。
- Docker Desktop 登录自启（征得同意）；Tailscale 服务默认自启。
- 向导服务器按需启动，不要求常驻；端口变化时更新快捷方式。
- 完成后状态页可随时打开。

### 4.8 二维码直接带设备密钥（已按产品决定调整）

- 产品决定：v1 二维码直接包含 `url + token + server_id`，不新增配对码表和后端接口。
- 降低风险的措施：
  - 二维码只在完成页/状态页本机显示，默认 10 分钟后自动隐藏；重新显示需要重新打开向导。
  - 完成页明确警告：二维码=账本钥匙，截图/转发=把账本交给别人。
  - 状态页提供“重新生成设备密钥”：重写 `secrets/backend.env` 的 `FINANCE_DEVICE_TOKEN`，重启 finance-api，旧二维码立即失效，需要重新配对。
  - token 不写日志、不进 URL、不进备份。
  - 后续版本再评估一次性配对码。

### 4.9 流程顺序（P1，已改）

先选使用方式/安全确认，再做 Funnel 授权；避免选私有模式的用户白授权一次。v1 只有远程模式，但仍先确认再授权。

### 4.10 提权与后台 Job 的 IPC（P1，已补）

- 提权 helper 是独立进程；进度写入 `%ProgramData%\OpenMargin\jobs\<jobId>.json`。
- 网页 Job 轮询该文件；helper 退出码 + 结果文件决定成功/失败。
- 不通过 `Start-Job` 提权；提权用 `Start-Process -Verb RunAs`。
- 非提权动作（Compose、Tailscale、Wallos）在用户进程的 Job 中执行。

### 4.11 UAC 拒绝 / 无管理员 / 重启（P1，已补）

- 开屏即说明“需要管理员账户安装运行环境”。
- UAC 拒绝：停止并说明“没有安装任何东西”；可重试。
- 重启页提供“先保存工作”“稍后重启”；不强制立即重启。
- 无管理员：给“让管理员帮你运行一次”的说明，不假装能继续。

### 4.12 端口硬编码（P1，已补）

- 所有端口参数化：`FINANCE_API_PORT`、`WALLOS_PORT`、`GATEWAY_PORT`、`SETUP_PORT`。
- Nginx 配置由模板生成（`envsubst` 或引擎写临时 conf），不再硬编码 18768。
- Serve/Funnel 目标使用实际 gateway 端口。
- 端口被占用时自动尝试下一个，或停止并显示占用者。
- Android 只认路径 `/finance`，端口是本地/网关细节。

### 4.13 443 被占用时的建议（P1，已改）

- 私有 Serve 和公网 Funnel 都需要 443；443 被外人占用时两种模式都不可用。
- 向导停止并显示占用详情；不再建议“改用私有同步”。
- 8443 作为后续版本（需要 Android 支持带端口地址）。

### 4.14 Wallos 手动兜底（P1，已补）

- 自动注册失败时，打开中文步骤说明 + Wallos 注册页。
- 用容器内 PHP 查询 owner 数，不依赖宿主机 sqlite3：
  `docker compose exec -T wallos php -r '...'`。
- `zh_CN` 未验证时先用 `en_US` 注册，完成后再在 Wallos 设置里改中文。
- 密码不写日志/状态；失败可重输。

### 4.15 已有 VPS/旧账本迁移（P1，已补）

- 检测到手机已同步过、或已有 `secrets/`/volumes 时，进入“连接/迁移”分支，不直接新建。
- 提供“备份当前数据 → 显式确认 → 迁移”的流程；不自动清空。
- Android 端已有 `SyncEndpointPolicy` 的防换服务器逻辑；向导不绕过。

### 4.16 不做模式选择（已改）

- v1 只有远程同步；向导不再让用户选“私有/远程”，减少一次误解和一次白授权。
- 保留安全确认：公网地址只开放同步和一次性配对接口，需要设备密钥。
- 现有私有 CLI 脚本继续保留在文档里，但网页向导不展示、不引导。
- 未来增加本机/私有模式时，再在向导里加“使用方式”页；当前不做。

### 4.17 备份（P1，已补）

- 状态页/完成页提供“一键备份”按钮，调用现有 `backup` compose profile。
- 默认备份到 `D:\OpenMarginBackups`（不存在则让用户选文件夹），显示路径和 SHA256。
- 提示“本机备份不等于异地备份”。

### 4.18 Windows 10 / WSL / winget（P1，已补）

- Windows 10 22H2 已停止支持：检测并警告，不直接阻止；建议升级 Windows 11。
- Docker Desktop / Tailscale 设最低版本并记录实际版本；不盲目追 latest。
- winget 不可用时走官方安装器。
- `wsl --install --no-distribution` 在旧 inbox WSL 可能不支持；兜底：
  - `dism.exe /online /enable-feature /featurename:Microsoft-Windows-Subsystem-Linux /all /norestart`
  - `dism.exe /online /enable-feature /featurename:VirtualMachinePlatform /all /norestart`
  - 安装 WSL2 内核更新包；重启后继续。

### 4.19 状态机与状态文件（P1，已补）

- 增加 `SWITCHING`、`DISABLED -> READY`（重新启用）。
- 状态文件放 `%LOCALAPPDATA%\OpenMargin\setup-state.json`（用户所有，避免提权 ACL 问题）。
- 机器级安装日志放 `%ProgramData%\OpenMargin\logs`；提权 helper 写自己的日志。
- 多用户机器按 SID 分目录；不共享状态。

### 4.20 命名统一（P2，已改）

| 旧名 | 新名 | 用途 |
|---|---|---|
| `ApiOnly` | `remote_sync` | 公网 Funnel，仅同步接口 |
| `PrivateFull` | `private_sync` | 现有 Tailscale Serve 完整网页 |
| `RemoteFunnel` | `remote_sync` | 不再单独出现 |
| `finanePublicUrl` | `financePublicUrl` | 修正拼写 |

UI 只显示中文标签，不显示枚举名。

## 5. 代码结构与文件

### 5.1 新增目录

```text
deploy/windows/setup/
  OpenMargin.Setup.psd1
  OpenMargin.Setup.psm1
  open-margin-setup.ps1            # CLI
  Start-OpenMarginSetupWeb.ps1     # 启动本机向导
  web/
    index.html
    app.js
    styles.css
    qrcode.min.js
  lib/
    State.ps1
    Log.ps1
    Probes.ps1
    Dependencies.ps1
    Compose.ps1
    Tailscale.ps1
    Wallos.ps1
    Pairing.ps1
    Verify.ps1
    HttpServer.ps1
    Jobs.ps1
    Security.ps1
    Ports.ps1
  tests/
    Setup.Tests.ps1
    Fixtures/
      tailscale-serve-empty.json
      tailscale-serve-ours.json
      tailscale-serve-foreign.json
      tailscale-funnel-ours.json
      docker-info-ok.txt
      docker-info-fail.txt
      compose-ps-ok.txt
```

### 5.2 修改现有文件

- `deploy/windows/Install-OpenMargin.ps1`
  - 保留兼容入口；新增 `-ExposureMode RemoteFunnel` 或委托新引擎。
  - Funnel 检测从“一律拒绝”改为“只拒绝非本项目路由”。
  - 关键函数抽到 `lib/`，避免两套逻辑。
- `deploy/windows/Start-OpenMarginSetup.ps1`
  - 保留为私有模式命令行向导；提示网页向导 v1 只做远程模式。
- `deploy/windows/private-sync-gateway.conf`
  - 抽出公共 allowlist；新增 `public-sync-gateway.conf`，加限流、超时、配对接口。
- `compose.yaml`
  - 新增 `windows-public-sync` profile；挂载 public gateway；端口参数化。
  - 增加 `finance-api` 对 `secrets/pairing` 的只读/可写挂载（按 4.8 设计）。
- `backend/app.py` / `backend/sync_v2.py`
  - 不新增配对接口；二维码直接使用现有 `FINANCE_DEVICE_TOKEN`。
  - 仅在部署引擎里实现 token 重写与 finance-api 重启（密钥轮换）。
- `deploy/windows/setup` 新增 GitHub Actions 构建安装器/离线镜像包。
- `android/app/src/main/java/com/bookkeeping/app/update/UpdateInstallFlow.kt`、`ApkIntegrity.kt`、`UpdateUrlPolicy.kt`
  - 复用 APK 下载/校验/安装流程；网页向导只提供 Release 下载地址和安装指引，不重复实现。
- `.github/workflows/windows-script-validation.yml`
  - 增加新脚本解析检查。
- 新增 `.github/workflows/windows-setup-tests.yml`
  - windows-latest 跑 Pester 5。

### 5.3 不修改

- `TransactionRepository`、Room schema、Android 同步协议。
- Wallos 上游代码；只通过官方注册表单交互。
- 现有 Android 同步端点语义；只新增配对接口。

## 6. 安装包与分发

- Inno Setup 构建 `OpenMargin-Setup.exe`；内置运行文件；安装到 `%ProgramData%\OpenMargin\app`。
- 安装器检查：
  - Windows 版本/64 位/管理员/磁盘/网络。
  - 已有安装：进入修复/升级，不覆盖 `secrets/` 和 volumes。
- 开始菜单：
  - “清衡设置” → 启动向导 + 打开浏览器。
  - “清衡账本” → 打开本机 Wallos/账本页。
- 代码签名：
  - 有证书：签名安装器和脚本。
  - 无证书：发布页给 SHA256、SmartScreen 图文说明；不诱导关闭杀软。
- 离线镜像包：
  - CI 构建 `docker save` 包（基础镜像 + 构建镜像）。
  - 在线安装失败时，向导给“下载离线包”按钮和校验值。
- 版本：安装器版本与应用 tag 一致；状态页显示版本。

## 7. 状态机与状态文件

### 7.1 状态

```text
UNINITIALIZED
  -> CHECKING
  -> NEEDS_USER
  -> INSTALLING_DEPS
  -> WAITING_REBOOT
  -> READY
  -> DEPLOYING
  -> VERIFYING
  -> PHONE_VERIFIED
  -> COMPLETE

COMPLETE -> DISABLING -> DISABLED -> READY（重新启用）
任意步骤 -> FAILED（step, code, message, remediation）
```

### 7.2 状态文件

位置：`%LOCALAPPDATA%\OpenMargin\setup-state.json`（按用户 SID 隔离）。
安装日志：`%ProgramData%\OpenMargin\logs\install-YYYYMMDD.log`。
运行日志：`%LOCALAPPDATA%\OpenMargin\logs\setup-YYYYMMDD.log`。

字段：

```json
{
  "schema": 1,
  "engineVersion": "1.2.5",
  "appRoot": "C:\\ProgramData\\OpenMargin\\app",
  "mode": "remote_sync",
  "step": "VERIFYING",
  "publicSyncUrl": "https://pc.tailnet.ts.net/finance",
  "financePublicUrl": "https://local.openmargin.invalid/finance",
  "gatewayPort": 18768,
  "ownerCreated": true,
  "pendingReboot": false,
  "phoneVerified": false,
  "lastCheck": {},
  "lastError": null,
  "jobId": null,
  "updatedAt": "2026-11-17T..."
}
```

规则：

- 不存 device token、管理员密码、admin token、配对码明文。
- 原子写入；命名 Mutex 防并发。
- 启动时探测实际状态；状态与事实不一致标记 `DRIFT` 并提供修复。
- 状态文件损坏时备份并重建，不静默丢失。

### 7.3 重启续装

- 写 `HKCU\...\RunOnce` 启动向导 `--resume`。
- 状态记 `WAITING_REBOOT`；登录后自动打开。
- 用户选择“稍后重启”时保持状态，下次打开继续。

## 8. 引擎动作与 CLI

### 8.1 动作

| 动作 | 输入 | 输出 | 关键检查 |
|---|---|---|---|
| `check` | mode | checks[] | Windows/虚拟化/磁盘/网络/Docker/Tailscale/端口/已有安装 |
| `install-deps` | 无 | progress | 下载、签名、UAC、重启、离线包 |
| `login-tailscale` | 无 | status | `tailscale status --json`、DNSName |
| `approve-funnel` | 无 | status | `tailscale funnel status --json`、公共 DNS |
| `create-owner` | username/password/email | ownerId | `/registration.php`、`user` 表 |
| `create-pair-qr` | 无 | QR payload | URL/token/server_id 校验；本机显示 |
| `rotate-token` | 无 | new token | 重写 backend.env、重启 finance-api、旧二维码失效 |
| `deploy` | mode + 输入 | progress | compose、gateway、Tailscale、owner、pair code |
| `verify` | 无 | checks[] | health、401、200、404、Wallos、owner、配对 |
| `disable` | mode | status | 移除 Funnel、恢复 marker |
| `repair` | 无 | progress | 重跑检查、修复安全项 |
| `status` | 无 | state | 状态文件 + 实际探测 |

### 8.2 CLI

```text
open-margin-setup.ps1 status --json
open-margin-setup.ps1 check --mode remote_sync --json
open-margin-setup.ps1 install-deps --json
open-margin-setup.ps1 deploy --mode remote_sync --admin-user <u> --admin-password-file <path> --ack-public --json
open-margin-setup.ps1 verify --json
open-margin-setup.ps1 disable --mode remote_sync --json
open-margin-setup.ps1 repair --json
open-margin-setup.ps1 logs --tail 200
```

- 所有命令支持 `--json`。
- 密码用文件/stdin，不用命令行参数。
- 退出码：`0` 成功；`10` 需要用户操作；`20` 可重试失败；`30` 不可恢复失败。
- `--non-interactive` 遇到用户操作时返回 `action_required` + URL，不阻塞。

### 8.3 结果结构

```json
{
  "ok": false,
  "step": "funnel",
  "code": "FUNNEL_PORT_CONFLICT",
  "message": "443 端口已被其他 Tailscale 服务占用",
  "remediation": [
    {"label": "查看占用", "action": "open_tailscale_status"},
    {"label": "停止并退出", "action": "abort"}
  ],
  "retryable": false
}
```

## 9. 网页向导

### 9.1 本机 HTTP 服务

- 首选 `HttpListener` 绑定 `http://127.0.0.1:18770/`；提权 helper 一次性执行 `netsh http add urlacl` 给当前用户。
- 失败时回退 `TcpListener` 最小 HTTP/1.1 服务（只处理 `Connection: close` + `Content-Length`）。
- 端口被占用时自动尝试 18771–18779。
- 启动时生成 32 字节 setup token，写入同源 HttpOnly cookie；API 校验 Host/Origin/`X-OpenMargin-Setup`。
- CSP：`default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'`。
- 静态文件白名单；拒绝路径穿越。
- 长任务：`POST /api/actions/{name}` 返回 jobId；前端轮询 `/api/jobs/{id}`。
- 提权 helper 进度写 `%ProgramData%\OpenMargin\jobs\<id>.json`。

### 9.2 API

```text
GET  /api/status
POST /api/check
POST /api/actions/install-deps
POST /api/actions/login-tailscale
POST /api/actions/approve-funnel
POST /api/actions/create-owner
POST /api/actions/create-pair-qr
POST /api/actions/rotate-token
POST /api/actions/deploy
POST /api/actions/verify
POST /api/actions/disable
POST /api/actions/repair
GET  /api/jobs/{id}
GET  /api/logs?tail=200
GET  /api/qr
```

### 9.3 页面

1. 欢迎：一句话 + 预计时间 + 开始。
2. 环境检查：清单 + 一键安装依赖 + 离线包入口 + 重新检测。
3. Tailscale：注册/登录 + MagicDNS/HTTPS 检测 + 直达链接。
4. 安全确认：列出暴露面 + 勾选。
5. 管理员账号：用户名/密码/确认；可选邮箱。
6. 部署：步骤进度 + 折叠日志。
7. Funnel 授权：打开授权页 + 轮询。
8. 自测：4 项 + 失败修复。
9. 完成：下载 App 二维码 + 一次性配对二维码 + 手机清单 + 注意事项 + 备份/关闭。
10. 状态/修复：当前模式、看门狗状态、最近检查、日志、关闭、重新启用、修复。

文案：

- 不出现 `API-only gateway`、`Funnel node attribute`、`CSRF`。
- 用“私有连接 / 远程访问 / 同步通道”。
- 每个失败有“为什么”和“下一步”。
- 默认中文；主按钮和错误提供英文。

## 10. 依赖安装

### 10.1 探测矩阵

| 项目 | 探测 | 失败处理 |
|---|---|---|
| Windows | 版本/构建/64 位 | 不支持则停止；Win10 EOL 警告 |
| 虚拟化 | `Get-ComputerInfo` / `systeminfo` | BIOS 图文指引 |
| 管理员 | WindowsPrincipal | 提示需要管理员 |
| 磁盘 | 系统盘 ≥ 15GB | 提示清理 |
| 网络 | Docker Registry / Tailscale / GitHub | 代理/离线包/重试 |
| WSL2 | `wsl --status` | `wsl --install` 或 DISM 兜底 |
| Docker | `docker version` / `docker info` | 安装/启动/等待 |
| Tailscale | `tailscale version` / `status --json` | 安装/登录 |
| 端口 | API/Wallos/Gateway/Setup | 自动换端口或停止 |
| 已有安装 | `secrets/`、volumes、marker | 修复/迁移 |

### 10.2 安装顺序

1. 提权 helper（一次 UAC）：
   - WSL2：`wsl --install --no-distribution`；失败用 DISM 启用两个功能。
   - Docker Desktop：winget 优先；否则官方安装器 `--quiet --accept-license`。
   - Tailscale：MSI `/qn`。
   - `tailscale set --operator=<user>`。
   - `netsh http add urlacl` 给向导端口。
2. 需要重启则写 RunOnce，退出。
3. 重启后：启动 Docker Desktop，轮询 `docker info`。
4. 启动 Tailscale，`tailscale up` 打开登录；轮询 `BackendState=Running`。

### 10.3 下载与校验

- winget 优先；否则官方 URL。
- SHA256 + Authenticode 校验；失败停止。
- 下载进度、断点/重试；离线包校验。
- Docker/Tailscale 记录实际版本；设最低版本。

### 10.4 Docker 自启与电源

- 征得同意后设置 Docker Desktop 登录自启或登录启动任务。
- 可选“接通电源时禁用休眠”，明确告知功耗。
- 笔记本合盖/电池场景提示可能暂停同步。

## 11. 远程模式（Tailscale Funnel）

### 11.1 前置

- Tailscale 已登录。
- MagicDNS/HTTPS 已开启。
- Funnel 可用（免费计划可用，beta）。
- 443 没有别人的 Serve/Funnel 路由。
- 国内网络预检通过（M0）。

### 11.2 部署

1. 启动 `windows-public-sync` profile + 网关。
2. 记录当前 Serve/Funnel 状态。
3. 移除本项目旧的 Serve 443（不碰别人的路由）。
4. `tailscale funnel --bg --https=443 http://127.0.0.1:<gateway-port>`。
5. 浏览器授权页点“允许”。
6. 轮询 `tailscale funnel status --json`，确认 `AllowFunnel` 和本项目路由。
7. 等待公共 DNS（最长 10 分钟），探测 `/finance/health`。
8. 生成一次性配对码。
9. 自测。

### 11.3 关闭与切换

- 关闭：移除 Funnel 路由，保留数据；更新 marker。
- 重新启用：重新走 Funnel + 自测。
- 443 被占用：停止，显示占用；不建议“改用私有”（私有也需要 443）。
- 8443：后续版本；需要 Android 支持带端口地址。

### 11.4 已知限制

- Funnel beta；带宽有限；只适合小数据同步。
- 公共 DNS 最长 10 分钟。
- 电脑休眠/关机/Funnel 断开时同步暂停。
- 手机侧商业 VPN 必须能访问 `*.ts.net`；M0 验证。

### 11.5 开机自启与看门狗

目标：用户重启电脑后，不需要打开任何窗口，Docker、Tailscale、同步网关自动恢复；看门狗在后台静默运行，不弹窗、不打扰。

#### 自启组成

| 组件 | 启动方式 | 说明 |
|---|---|---|
| Tailscale | Windows 服务，默认自启 | 登录状态持久化 |
| Docker Desktop | 登录自启（征得同意） | 桌面应用，需要用户登录 Windows |
| 同步容器 | 看门狗检查/拉起 | `windows-public-sync` profile |
| 看门狗 | 登录时隐藏计划任务 | 无窗口；Task Scheduler 失败重启 |
| 向导 | 按需启动 | 不常驻；开始菜单打开 |

#### 看门狗实现

- 脚本：`deploy/windows/setup/lib/Watchdog.ps1`。
- 启动：计划任务调用 `wscript.exe` + `.vbs` 隐藏启动，或 `powershell.exe -NoProfile -WindowStyle Hidden`；避免窗口闪烁。
- 运行身份：当前用户，不提权；不需要 UAC。
- 间隔：60 秒；启动后先等 60 秒，避免开机竞争。
- 日志：`%LOCALAPPDATA%\OpenMargin\logs\watchdog.log`，轮转 7 天，不含密钥。
- 状态：`%LOCALAPPDATA%\OpenMargin\watchdog-state.json`，记录最后检查、最后修复、暂停标志、无法修复的原因。

#### 检查与修复

| 检查 | 修复 |
|---|---|
| 向导正在 `INSTALLING_DEPS` / `DEPLOYING` | 暂停，避免和向导抢 |
| `docker info` 失败 | 启动 Docker Desktop；等待最多 3 分钟 |
| compose 容器缺失/退出 | `docker compose --profile windows-public-sync up -d` |
| `/finance/health` 失败 | 重启 finance-api；仍失败则重启网关 |
| `tailscale status` 未连接 | `tailscale up`；若未登录则记录“需要登录”，不能静默登录 |
| Funnel 路由缺失 | 重新执行 `tailscale funnel --bg --https=443 http://127.0.0.1:<gateway-port>` |
| 磁盘不足 | 记录并停止重试，状态页提示 |
| 连续失败 | 指数退避，最多 3 次/10 分钟；之后只记录，不无限重启 |

#### 不做什么

- 不弹窗、不弹通知、不打开浏览器。
- 不自动登录 Tailscale（账号安全）。
- 不自动重新授权 Funnel（需要用户点“允许”）。
- 不重启 Windows。
- 不修改用户文件。

#### 用户控制

- 状态页显示“看门狗：运行中 / 已暂停 / 需要处理”。
- 提供“暂停 1 小时 / 恢复”按钮；暂停状态写入 `watchdog-state.json`。
- 关闭远程模式时暂停看门狗；重新启用时恢复。
- 卸载时删除计划任务和看门狗脚本。

#### 限制

- Docker Desktop 是桌面应用：Windows 未登录用户时不会运行，看门狗也无法启动它。需要“无人登录也同步”的场景要用 Windows 服务/迷你主机，本次不做。
- 电脑休眠时看门狗也休眠；唤醒后恢复检查。
- Tailscale 登录过期、Funnel 授权被撤销，看门狗无法代替用户，只能在状态页提示。

#### 测试

- 杀掉 Docker Desktop → 看门狗拉起。
- 停止 finance-api 容器 → 看门狗恢复。
- `tailscale down` → 看门狗 `tailscale up`。
- 删除 Funnel 路由 → 看门狗重建。
- 暂停看门狗 → 不再修复；恢复后继续。
- 重启 Windows → 登录后看门狗自动运行，无窗口。
- 日志无 token/密码。

## 12. Wallos 管理员

- 先检查 `user` 表数量；已有 owner 则跳过。
- 无 owner：
  1. `GET http://127.0.0.1:<wallos-port>/registration.php` 建立会话。
  2. `POST` 表单：username, firstname, lastname, email, password, confirm_password, main_currency=CNY, language=en_US（`zh_CN` 未验证）。
  3. 期望 301/302/303 到 `login.php`。
  4. 容器内 PHP 查询 `SELECT COUNT(*) FROM user`；校验 owner id。
- 失败回退：打开中文步骤 + 注册页，用户手动注册；向导用容器 PHP 轮询。
- 密码只在内存；不写日志/状态；CLI 用密码文件/stdin。
- 不直接写 SQLite。

## 13. Android 配对与手机设置

### 13.1 二维码内容与安全

- 产品决定：v1 二维码直接包含
  `{"v":1,"kind":"open-margin-pair","url":"...","token":"...","server_id":"..."}`。
- 不新增后端配对码表/接口；使用现有 `FINANCE_DEVICE_TOKEN`。
- 二维码只在完成页/状态页本机显示，默认 10 分钟后自动隐藏；重新显示需重新打开向导。
- 完成页警告：二维码=账本钥匙，不要截图/转发。
- 状态页提供“重新生成设备密钥”：重写 `secrets/backend.env`、重启 finance-api、旧二维码立即失效、需要重新配对。
- 手机扫码后仍要调用现有只读探测，校验 `server_id`，不自动启用。

### 13.2 Android 改动

- 新增 `com.journeyapps:zxing-android-embedded`（离线、不依赖 Play Services）。
- `AndroidManifest.xml` 增加 `CAMERA`；`REQUEST_INSTALL_PACKAGES` 已在 v1.2.5 存在；`uses-feature camera required=false`。
- 新增 `sync/QrPairingScanner.kt`、`sync/PairingPayload.kt`、`sync/PairingClient.kt`。
- 在 `WindowsFirstConnectionWizard` 第一步增加“扫码连接”。
- 扫码后预填 URL/token，调用现有探测；不自动启用。
- 防错账本：任何历史成功同步记录时禁止换不同 `server_id`；显式迁移。
- token 不写日志；SharedPreferences 已排除备份。

### 13.3 手机设置清单

- 下载 App（独立二维码/按钮）。
- 允许未知来源。
- 安装并打开。
- App 内“自动记账设置”：通知使用权、短信（可选）、无障碍页面识别（可选）、电池优化白名单。
- 设置 → 扫码连接。
- 测试连接成功。
- 可选：真实小额支付验证采集。
- 向导状态 `PHONE_VERIFIED`。

## 14. 安全模型

### 14.1 本机向导

- 只绑定 `127.0.0.1`。
- 同源 cookie + `X-OpenMargin-Setup` + Host/Origin 校验。
- CSP、静态白名单、无目录穿越。
- 固定动作白名单，无任意命令执行。
- 长任务在后台 Job；API 不返回密钥。

### 14.2 公网网关

- 只允许：`/finance/health`、`/finance/api/v2/sync/changes`、`/finance/api/v2/sync/push`、两个 legacy v1 端点。
- 其他路径 404；错误方法 405；编码路径拒绝。
- `limit_req` / `limit_conn`；`client_max_body_size 4m`。
- 不记录 `X-Device-Token`；访问日志不含请求头。
- 只通过 Tailscale Funnel 的 TLS 入口访问。

### 14.3 密钥

- `secrets/` ACL 收紧到当前用户 + Administrators + SYSTEM。
- 不把 device/admin/ledger token 写入状态文件或日志。
- 二维码含 device token；只在本机显示、10 分钟隐藏、可轮换；不写日志。
- 设备 token 显示默认掩码。
- 记录 token 轮换步骤；本次提供“重新生成配对码”，不改长期 token。

## 15. 失败处理与恢复

- 每个动作返回结构化错误码、用户文案、修复动作。
- 网络/下载/Docker 启动可重试；路由冲突/不支持的系统不重试。
- 浏览器关闭：后台 Job 继续；重新打开可看进度。
- 引擎崩溃：状态标记 `DEPLOYING`；重启后探测实际状态，继续或安全停止。
- 重启：RunOnce + 状态。
- 关闭远程：移除 Funnel 路由，保留数据；不 `down -v`。
- 卸载：二次确认，默认保留数据卷；提示先备份。
- 已有数据/路由：检测、停止、备份提示、不覆盖。

## 16. 测试计划

### 16.1 单元（Pester 5）

- 状态机转换（含 SWITCHING/DISABLED->READY）。
- 状态文件原子写、损坏恢复、SID 隔离。
- Serve/Funnel JSON 解析与路由所有权。
- 端口参数化、冲突顺延。
- URL/配对 payload 校验。
- 日志脱敏。

### 16.2 集成（假 CLI）

- 临时目录放 `docker.cmd` / `tailscale.cmd`，PATH 注入。
- Fixtures：Docker 未装/未启动/正常；Serve 空/本项目/外来；Funnel 未授权/已授权/外来；Wallos owner 0/1。
- 跑 `check -> install-deps (fake) -> deploy -> verify -> disable`。
- 断言调用序列、状态文件、无密钥输出。
- 网关测试扩展：限流、404/405、编码路径、token 不落日志；确认没有额外接口。

### 16.3 网页

- Playwright/ego-browser 打开向导。
- 测试 fixtures 走完检查 → 部署 → 完成。
- 无 console error；密钥不出现在 DOM/网络日志；二维码可渲染。
- 未授权 API、错误 Origin/Host、路径穿越。

### 16.4 Android

- `PairingPayloadTest`：合法/非法、端口、路径、code、server_id。
- `PairingClientTest`：TTL/一次性/错误码（用 mock server）。
- 现有 `WindowsConnectionPolicyTest` / `SyncServerIdentityPolicyTest` 回归。
- 手动扫码；错账本阻断。

### 16.5 真机矩阵

- Windows 10 Home 22H2 干净 VM：无 Docker/Tailscale。
- Windows 11 干净 VM。
- 已装 Docker/Tailscale；非管理员；虚拟化关闭。
- 国内网络/代理/慢网；Docker Hub 不可达；离线包。
- 已有 Open Margin 数据；已有别人的 Tailscale 路由。
- 远程模式 → 关闭 → 重新开启。
- 重启续装、浏览器关闭、断网恢复。
- 中国 SIM + 商业 VPN 手机扫码同步；Windows 休眠后恢复补传。

### 16.6 安全

- 未授权/错误 token/错误 Origin/Host 的向导 API。
- 公网 Wallos/MCP/OAuth/管理 API 全部 404。
- 二维码只在本地显示、10 分钟隐藏；密钥轮换后旧 token 立即失效；日志不泄露 token。
- 日志无 token/密码。

### 16.7 CI

- 新增 `windows-setup-tests.yml`：windows-latest，Pester 5。
- 扩展 `windows-script-validation.yml` 解析新脚本。
- 保留现有 Android/后端/Open Source Readiness 工作流。
- 新增安装器/离线包构建工作流。

## 17. 里程碑

| 里程碑 | 内容 | 退出标准 |
|---|---|---|
| M0 技术验证 | 国内网络 Funnel 可达性、winget/静默安装、WSL DISM 兜底、Tailscale operator、Funnel 授权、Wallos 注册、HttpListener URL ACL、Docker 自启 | 每项在 Windows 10 Home 真机/中国 SIM 跑通，记录命令和输出 |
| M1 引擎骨架 | 状态机、状态文件、日志、CLI、check/status | Pester 通过；CLI JSON 可用 |
| M2 安装包 + 依赖 | Inno Setup、内置载荷、UAC helper、重启续装、离线包、开机自启/看门狗安装 | 干净 VM 从无到有装好；登录后看门狗自动运行 |
| M3 远程模式 | compose、gateway、Funnel、owner、一次性配对、verify、看门狗 Funnel 恢复 | 手机只开商业 VPN 能同步 |
| M4 网页向导 | HttpListener、API、页面、Job、错误文案 | 浏览器走完主流程 |
| M5 Android | ZXing、配对客户端、错账本保护、手机清单 | 扫码配对成功 |
| M6 安全与测试 | 限流、脱敏、Pester、Playwright、网关测试、看门狗故障注入、CI | 安全测试通过；CI 绿 |
| M7 真机验收 | 矩阵、novice review、文档、安装器签名 | 验收清单全部通过 |

## 18. 风险与回退

| 风险 | 影响 | 回退 |
|---|---|---|
| 国内网络无法拉镜像 | 部署失败 | 离线镜像包；代理；VPS |
| Tailscale/Funnel 被阻断 | 远程模式不可用 | VPS/Cloudflare；M0 先验证 |
| Docker Desktop 安装失败 | 无法继续 | 诊断；手动安装；停止 |
| 虚拟化未开 | Docker 无法运行 | BIOS 指引；停止 |
| Wallos 注册表单变化 | 管理员创建失败 | 手动注册页；轮询 |
| Funnel beta 行为变化 | 远程模式失效 | 检测；停止；文档 |
| 443 被占用 | 无法开 Funnel | 停止；显示占用；8443 后续 |
| 用户电脑休眠 | 同步暂停 | 完成页提示；可选电源设置 |
| 二维码泄露 | 账本被访问 | 一次性 5 分钟码；重新生成 |
| 公网扫描 | 攻击面 | 限流、token、只放必要接口 |
| 已有数据/路由 | 数据损坏 | 检测、停止、备份、不覆盖 |
| Windows 10 EOL | 兼容性 | 警告；建议升级；不阻止 |

## 19. 验收清单

- [ ] 干净 Windows 10 Home 22H2（无 Docker/Tailscale）能通过向导完成远程同步。
- [ ] 用户手动动作不超过：1 次 UAC、1 次 Tailscale 注册/登录、1 次 MagicDNS/HTTPS、1 次 Funnel 允许、1 次管理员表单、1 次重启（如需）、1 次 APK 安装、1 次扫码。
- [ ] 登录 Windows 后看门狗自动运行，无窗口、无弹窗。
- [ ] 杀掉 Docker Desktop、停止容器、`tailscale down`、删除 Funnel 路由后，看门狗能自动恢复；无法恢复时状态页显示原因。
- [ ] 看门狗暂停/恢复可用；向导部署期间不抢操作。
- [ ] 国内网络下 Docker 镜像可获取（在线或离线包）。
- [ ] 自测：health 200、无 token 401、有 token 200、Wallos/MCP 404、配对码兑换成功。
- [ ] 手机只开商业 VPN 能同步；Windows 休眠/断网后恢复补传。
- [ ] 配对二维码只在本机显示，10 分钟后自动隐藏；密钥轮换后旧二维码失效。
- [ ] 关闭远程模式后公网不可达，数据完整；可重新开启。
- [ ] 重启、关浏览器、断网后能续装/恢复。
- [ ] 不覆盖别人的 Tailscale 路由；已有 Open Margin 数据不被重置。
- [ ] 日志和状态文件不含 token/密码。
- [ ] 开始菜单能重新打开向导/账本。
- [ ] Pester、网关、Android 单测、真机 E2E 通过。
- [ ] 完成页把限制和注意事项说清楚。

## 20. 已确认的决定

1. 二维码直接带设备密钥：本机显示、10 分钟隐藏、可轮换；不新增后端配对表。
2. 离线镜像包：单独下载，不塞进安装包。
3. 安装器无代码签名证书：v1 走 SmartScreen 说明 + SHA256；后续再补签名。
4. 分发只放 GitHub Releases；离线包同样作为 Release asset，允许用户手动转发。
