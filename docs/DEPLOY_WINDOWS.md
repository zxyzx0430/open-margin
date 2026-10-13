# 清衡 Open Margin｜v1.2.0 Windows 本地部署

Windows 和 VPS 共用 `compose.yaml`、Finance API、Wallos、SQLite 结构及 Android 双向同步协议。区别只是连接入口。**默认 Web 仅可从 Windows 本机访问，远程同步接口需要单独开启。**

## 方案 A：只在 Windows 本机使用（不需要 Tailscale）

准备 Windows 10/11、Git、Docker Desktop（Linux containers / WSL2）、PowerShell 5.1+。Docker Desktop 启动后：

~~~powershell
git clone https://github.com/zxyzx0430/open-margin.git
cd open-margin
powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy\windows\Install-OpenMargin.ps1
~~~

打开 `http://127.0.0.1:18082/` 注册首位 Wallos 用户，并使用该账号登录。默认 Finance API 端口 `127.0.0.1:8767`、Wallos `127.0.0.1:18082`；API-only 网关 `127.0.0.1:18768` 使用同一 Compose 可选 profile，但**未启用 Tailscale Serve 时不对远端开放**。

## 方案 B：Windows + Android 私有双向同步（推荐 API-only）

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

如果需要在 tailnet 内访问完整网站，只能在首次安装时明确选用 `-ExposureMode PrivateFull`。已有模式不允许无审计切换，安装器不会自动清空或覆盖其他应用的 Serve 路由；不要使用公开的 `tailscale funnel`。

Android 进入 **设置 → Windows 首次连接向导**，输入 `https://your-pc.your-tailnet.ts.net/finance` 和本机 `secrets/backend.env` 中 `FINANCE_DEVICE_TOKEN`。先做只读连接测试，核对服务器账本身份和同步游标，再确认启用。也可继续使用原 VPS 同步状态卡。

**API-only 正常响应：** `/finance/health` → 200；`/finance/api/v2/sync/changes` 无设备密钥 → 401；`/finance/wallos/` → 404。不要因 404 而关闭 Android HTTPS 校验；这是隔离策略。


## 可选：本机 AI 客户端使用只读 MCP（实验功能）

对于支持 MCP stdio 的桌面 AI 客户端，可将程序配置为调用 Windows PowerShell 脚本 `deploy/windows/Start-OpenMarginLocalMcp.ps1`（以 PowerShell -NoProfile -ExecutionPolicy Bypass -File 启动）。它复用现有 Finance API Docker 容器和同一份账本，只暴露 7 个只读工具。不需要公网地址、OAuth、管理密钥或 Android 设备密钥；只有具有 Docker 容器运行权限的本机用户可以使用。需保持 Docker Desktop 运行。这里不支持云端 ChatGPT 网页版直连，未来需使用独立的安全隧道。Windows + Docker Desktop 实机端到端验收尚未完成；详细架构见 `WINDOWS_MCP_ARCHITECTURE.md`。

## 备份、恢复与排障

- Windows 关闭、Docker 停止或 Tailscale 离线时，Android Room 仍可继续记账，恢复网络后由 WorkManager 补传（后台约 15 分钟起，实际受系统限制）。
- SQLite 使用 Docker 命名卷，源码更新不会主动清空它。**不要使用 `docker compose down -v`，不要删除旧 APK 以尝试升级。**
- 运行 `docker compose --profile maintenance run --rm backup` 备份已初始化的 Finance、Wallos 与 OAuth SQLite。备份在宿主机外的独立介质保留更稳妥。
- `docker compose ps` 检查容器；`docker compose logs --tail=100 finance-api` 排查后端；`tailscale serve status --json` 查看 Serve 路由；同时检查 Docker Desktop、手机 Tailscale 和证书。
- 手机不能直接切换到另一份未经迁移的服务器账本；服务器版本、身份或游标不一致时停止并人工核对。
- 手机与网站目前双向复制交易记录与排除状态，服务器管理退款关系和订阅预算。原 v1.1.3 已删除且未留下删除标记的记录无法从历史中自动重建。

更完整的使用状态见 [Windows 首次连接详细文档](WINDOWS_PRIVATE_FIRST_CONNECTION.md)。Tailscale CLI 规则以 [官方 Serve 文档](https://tailscale.com/docs/reference/tailscale-cli/serve) 为准。

**验收约束：** 只有完成 Windows 实机从零安装、Android 首次连接和 v1.1.3 → v1.2.0 同签名无损升级后，才可将 v1.2.0 宣称为正式发布可用。
