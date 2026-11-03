# 清衡 / Open Margin — Windows MCP 接入架构研究与实施 RFC

状态：**架构提案，附本地只读 stdio 原型；云端隧道尚未实现**。研究日期：2026-10-13。
基于仓库主线 `9f9406d`。VPS 的 MCP/OAuth 已投产；Windows 目前仅有本地 Finance API + Wallos + Android Tailscale API-only 同步通道。

## 0. 核心决定

- **不另写一套 MCP 工具。** 复用 `backend/mcp_service.py` 的 11 个工具、`backend/mcp_access.py` 的 7 read / 4 write 语义，以及 SQLite / Wallos 业务代码。
- **保持 Android 同步的 Tailscale Serve API-only 不变。** 现有 `deploy/windows/private-sync-gateway.conf` 必须继续默认拒绝 `/mcp`、OAuth、Wallos 和管理 API。
- Windows 开放 MCP 是**独立的、默认关闭的功能**。先交付本地 MCP 客户端；再提供面向 ChatGPT 的私有隧道或 HTTPS OAuth 接入。
- 任何默认通道必须最小权限（只读）；写入需要用户明确启用、不同凭证和可审计记录。**严禁把 Finance Admin Token 或 Android Device Token 作为长期隧道凭证。**
- 原 VPS 模式、现有生产数据库、备份和同步游标不参与 Windows 接入开发和迁移。

## 1. 已核对的代码事实

| 现状 | 代码位置 / 结果 |
| --- | --- |
| 一套 Compose，Finance API、Wallos、SQLite volumes | `compose.yaml` |
| Finance HTTP MCP Streamable，复用业务工具 | `backend/app.py`, `backend/mcp_service.py` |
| OAuth 2.1 + DCR + PKCE 已存在 | `backend/oauth_support.py` |
| 全部 11 个工具注册，7 只读/4 写入注解 | `backend/mcp_access.py`, `backend/test_mcp_protocol.py` |
| Windows Finance 回环端口 | `127.0.0.1:8767` |
| Windows Wallos 回环端口 | `127.0.0.1:18082` |
| Windows API-only 回环网关 | `127.0.0.1:18768` |
| Tailscale Serve 默认只开放五个健康/同步入口 | `deploy/windows/private-sync-gateway.conf` |
| Windows 安装向导目前将 `FINANCE_PUBLIC_URL` 与 Android `*.ts.net/finance` 关联 | `deploy/windows/Install-OpenMargin.ps1` |
| OAuth `issuer`/`resource`、MCP 允许的 Host/Origin 和 Web 登录依赖统一 `PUBLIC_BASE` | `backend/config.py`, `backend/oauth_support.py`, `backend/mcp_service.py`, `backend/web_login.py` |

**关键限制：** 当前 OAuth 的 issuer/resource 从 `FINANCE_PUBLIC_URL` 生成。简单将 `/finance/mcp` 通过另一个公网域名代理出去，会使 OAuth 元数据中的地址指向仅 tailnet 可访问的 `*.ts.net`；ChatGPT 浏览器授权、token 交换以及元数据匹配可能失败。不能只改 Nginx 路由。

## 2. 三种接入场景

### A. Windows 上运行的本地 AI 客户端（可在无公网/无 OpenAI Platform 帐号时使用）

```text
IDE / Claude Desktop / local MCP client
   -> process stdio (Windows)
   -> docker exec/compose exec -T [finance-api 内的 Python stdio MCP]
   -> 同一个业务层 -> Finance/Wallos SQLite
```

- `stdio` 是本地进程管道，不需要开启公网或监听 TCP；但它不是 ChatGPT 网页版的本地传输方式。
- 应构建一个**专用的 stdio 启动器/进程**，复用现有 MCP Tool 对象，并在启动前按用户配置筛选只读工具；不能误把全部 Finance Admin 环境变量复制到 Windows 主机进程。
- 优先测试容器内 `mcp.run(transport="stdio")` 可否与 `docker compose exec -T finance-api ...` 正确交换 JSON-RPC；尤其验证 stdin、stdout 无非协议日志，Docker Desktop 关闭时的错误提示。
- 需保证本机用户边界以及客户端配置文件权限，不要把数据库卷挂成 Windows 可随意访问的目录。
- **本地原型已实现：** backend/mcp_stdio.py 启动前核对 11 个注册工具并删除 4 个写工具；deploy/windows/Start-OpenMarginLocalMcp.ps1 通过 Docker Compose exec -T 启动同一 MCP；backend/test_mcp_stdio.py 使用临时 SQLite 进行真实 stdio 会话测试。当前未进行 Windows 实机验证。
- 实现前需完成 Docker Desktop + 一个实际本地 MCP 客户端的端到端验证。

### B. Windows + ChatGPT 云端（首选私有方案，技术用户）

```text
ChatGPT hosted MCP interface
   -> OpenAI Secure MCP Tunnel (tunnel_id, workspace/org binding)
   -> tunnel-client.exe on Windows (outbound api.openai.com:443)
   -> http://127.0.0.1:8767/mcp/
   -> Finance API -> same databases
```

OpenAI 在 2026 年提供 Windows `amd64/arm64` 预构建的 `tunnel-client`，支持 HTTP Streamable MCP 和 stdio。客户端由 Windows 主动出站轮询；不需要公网 IP、开放路由器端口、Tailscale Funnel，也不需要把 Wallos/管理 API 公开。

**真实先决条件：** OpenAI Platform `tunnel_id`、运行密钥、Tunnels Read+Use 权限、ChatGPT workspace 关联，以及 Windows 主机的出站 HTTPS。不是所有 ChatGPT 用户都自然拥有这些资格；这是“可选高级接入”，不能作为普通 Windows 安装的强制步骤。OpenAI 官方文档明确说 Secure Tunnel 面向私有连接测试，不直接支持把该私有隧道作为公开上架插件的地址。

**OAuth/授权陷阱：** Secure MCP Tunnel 可以转发 MCP OAuth 发现，但不会自动让本机 OAuth 授权服务器的网页可公网访问。当前 `FINANCE_PUBLIC_URL=*.ts.net/finance` 并不能用于云端浏览器直接授权；**不能声称现有 OAuth 即插即用**。

最小可行安全实施（需测试该版本 tunnel-client 的 header 注入与 probe 行为）：

1. 后端新增独立的 `FINANCE_MCP_TUNNEL_READ_TOKEN`，并支持其**只读**验证。不能复用能访问所有 API 的 Admin Token，也不能使用 Android Device Token。
2. ChatGPT 隧道入口作为受 Platform 隧道权限控制的私有客户端；由 `tunnel-client` 在本地 MCP HTTP 请求中加入专用凭证；不把凭证送给 ChatGPT、写进命令行或公开日志。官方客户端支持 `mcp.extra_headers` 和 `mcp.discovery_extra_headers`，两者须使用环境/受保护文件引用来避免明文。
3. MCP 工具校验必须是**执行时的后端强制范围**，而非仅靠 `readOnlyHint` 注解。优先确保 `tools/list` 在只读模式下只返回 7 个只读工具；否则至少确保 4 个写工具调用返回 403。
4. 明确启用写入时，改为不同凭证/授权通道，默认不提供；添加财务修改的确认、审计。任何写入测试必须使用临时合成账本。
5. Windows 进程离线、Docker 停止或设备休眠时，ChatGPT MCP 应显示不可用/可重试，不能回退到其他 VPS 的独立账本。
6. 如果需要完整的 OAuth 网页授权，应单独解决公开认证服务器可达性；不能误以为 Tunnel 包办了 OAuth 网站。是否采用 tunnel 身份 + 专用 token 代替 OAuth，须通过用户/工作区安全验收。

安装向导添加独立开关 `EnableMcpTunnel`，缺省关闭，并通过 `doctor` 和工具列表进行预检；首版允许 Windows 用户手动获取 `tunnel_id` / API key，向导只负责读取本地凭证、配置守护、健康状态与安全回滚。不得未经用户授权代为创建 OpenAI Platform 资源、订阅或收费项。

### C. Windows + ChatGPT 云端（公网 OAuth，通用独立自托管）

``text
ChatGPT -> HTTPS public hostname (Cloudflare Tunnel or secured reverse proxy)
       -> allowlist-only MCP/OAuth gateway on Windows localhost
       -> /finance/mcp/*, OAuth discovery, /finance/oauth/*
       -> Finance API -> local databases

Android -> Tailscale Serve 443 -> existing API-only gateway -> sync endpoints
``

- 允许使用 Cloudflare Tunnel 的 Windows service 和单独域名，也可使用用户现有 VPS 反向代理 + Tailscale 私网转发；这条方案比 OpenAI 私有 Tunnel 更适合希望独立发布公共 MCP 接入的用户，但增加公网攻击面和 DNS/OAuth 运维。
- 新增独立 `private-mcp-gateway` Docker Compose profile，绑定 **127.0.0.1** 的新端口（不得重用 `18768` 或直接暴露 `8767`）。该网关使用严格路径和 HTTP 方法白名单、body size 限制、Host 验证、速率限制、TLS 网关和访问日志脱敏；禁止 Wallos 页面/财务管理 API。
- **拆分**同步服务器的私有基址与公开 MCP/OAuth 身份：例如兼容保留 `FINANCE_PUBLIC_URL` 供 Android/网站旧客户端，新增 `FINANCE_MCP_PUBLIC_BASE` 供 `ISSUER`、`RESOURCE`、MCP Host/Origin、OAuth 的绝对 URL。修改需要兼容性和安全单测，不能直接全局替换 `PUBLIC_BASE`，否则会破坏 Web Cookie/CSRF 和同步身份。
- Cloudflare Access 二次登录可能妨碍 MCP 的标准 OAuth discovery 和 token 自动交换；不能仅测试浏览器登录成功就视为 MCP 正常。
- 若用 Tailscale Funnel，这是**公网**而不是 Serve 私有通道。官方明确同一个 HTTPS 端口不能同时被 Serve 和 Funnel 占用，须使用不同允许端口/路由，并核对 Windows 客户端与 OAuth 回调。不得自动切换当前 443 Serve 到 Funnel。

## 3. Windows 向导应该怎样呈现

保留当前 Windows 首次连接向导和安装器的原本步骤；新增可选的「AI / MCP 接入」模块：

1. **关闭（默认）**：Windows + Android Tailscale 同步照常；外界无法访问 MCP。
2. **仅本地 AI**：产生本地 stdio MCP 启动配置；检测 Docker 和容器；不发布任何网络入口。
3. **ChatGPT 私有隧道（高级）**：明确提醒需要 OpenAI Platform 隧道权限与密钥，提供 `tunnel-client` 版本/哈希验证、私有 scoped token、启动/停止、诊断以及保密提示；无公网 Wallos。
4. **公网 OAuth（高级/可选下一阶段）**：需用户自有域名或受支持的安全中转服务，网关独立开启和额外验收。

安装向导应显示**源账本本机身份**、工具个数、只读/写入模式和连接状态，但绝不直接打印 Secret 内容。Windows 单机关闭后，MCP 暂时不可用是正常行为，不能自动连接另一份账本。

## 4. 开发切片与测试门禁

### 阶段 1 — 本地传输验证（无公网，已有原型）
- 测试 `docker compose exec -T finance-api` 与 `stdio` 一次完整 JSON-RPC 调用；实现安全启动器，Windows PowerShell Parser、CI 模拟、Windows 实机验证。
- 同一个合成账本的工具读取结果需与 HTTP MCP 一致。

### 阶段 2 — 私有 Tunnel MVP（仅测试）
- 专用 MCP-only 只读 token 的生成、保存、轮换、吊销；服务端强制只读，不修改现有 Admin Token、Device Token 或 OAuth。
- 通过 `tunnel-client doctor`、控制平面关联和 ChatGPT 新自定义 Tunnel 连接测试工具发现；Windows 休眠/重启/断网后自动恢复。
- 默认只读，不开放公网任何财务端点。

### 阶段 3 — 可选公共 OAuth 入口
- 完成 `FINANCE_MCP_PUBLIC_BASE` 和 OAuth 绝对地址解耦，不改变 Android 的 Tailscale Serve。
- Gateway Nginx 负向路径测试：未授权 401、错误方法 405/404，管理接口/Wallos 404；OAuth DCR/PKCE/刷新令牌全流程。
- HTTPS hostname、redirect_uri、issuer/resource 元数据一致；跨域和危险重定向拒绝。

### 强制安全回归
- 全部 11 个工具源定义仍一致；读权限不能修改任何记录；写权限必须用户明确开启且由后端校验。
- 真实 Windows + Docker Desktop 和 ChatGPT 账号 E2E，不得仅用 VPS Linux 断言 Windows 可用。
- 使用隔离 SQLite / 模拟 Wallos 写入，永远不以真实生产账本生成测试交易。
- `docker compose down -v` 禁止；关闭隧道/撤销密钥不删除数据库；现有 Android 同步和 Tailscale Serve 路由完整未变。
- 机密在 Windows 凭据存储或受限文件中，API key、OAuth token、完整交易数据均不得进入默认日志或 Git。

## 5. 官方与社区参考（2026-10-13）

- OpenAI Secure MCP Tunnel：https://developers.openai.com/api/docs/guides/secure-mcp-tunnels
- OpenAI tunnel-client 源码 / Windows 发布：https://github.com/openai/tunnel-client
- tunnel-client headers 与连接配置：https://github.com/openai/tunnel-client/blob/master/docs/configuration.md
- tunnel-client OAuth / 联接：https://github.com/openai/tunnel-client/blob/master/docs/onboarding.md
- Home Assistant 的 ChatGPT remote OAuth 要求：https://www.home-assistant.io/integrations/mcp_server
- Actual Budget 的 stdio / HTTP 双传输参考：https://github.com/agigante80/actual-mcp-server
- Tailscale Funnel 与 Serve 隔离：https://tailscale.com/docs/features/tailscale-funnel
- Cloudflare Windows 守护服务：https://developers.cloudflare.com/tunnel/features/locally-managed-tunnels/as-a-service/windows/

**决策建议：** 先做 A（本地 stdio）+ B（OpenAI Secure MCP Tunnel，只读默认），把 C 作为独立的、明确向公网暴露 OAuth 端点的高级模式。Windows 实机当前离线；本 RFC 没有进行 Windows/E2E 部署，也不宣称用户当前 ChatGPT 账号有 OpenAI Platform Tunnel 权限。
