# 清衡 v1.2.0 | Windows 私有同步与首次连接

> 仅针对 Windows 自托管安装；VPS 部署继续使用原 HTTPS 入口。
> 此模式是可选私有连接方式，不能与 VPS 自动组成多主账本。

## 用户只需要安装

- 电脑：Docker Desktop（Linux 容器 + WSL2）、Tailscale、Git（用于获取仓库）。
- Android：Tailscale + 清衡 App（从项目构建的 APK）。
- 不必手动安装 Python/Node；PowerShell 已内置于 Windows。

## 电脑上的首次安装向导

1. Windows Docker Desktop 启动，Tailscale 已登录，管理控制台已启用 MagicDNS 与 HTTPS。
2. 将项目克隆到本地，例如 `git clone https://github.com/zxyzx0430/open-margin.git`。
3. 在项目根目录启动 PowerShell：
   `powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy\windows\Start-OpenMarginSetup.ps1`
4. 向导检查 Docker 和 Tailscale，自动读取本机 `*.ts.net` 地址，启动容器但暂不配置 Serve。
5. 在本机 `http://127.0.0.1:18082/` 注册首位 Wallos 用户。注册完成输入 `READY`，向导才开启 API-only Serve。
6. Android 连接同一 Tailscale 网络，在设置页打开 **Windows 首次连接向导**，输入电脑 HTTPS 地址（格式 `https://<pc>.<tailnet>.ts.net/finance`）和 `secrets/backend.env` 中的 `FINANCE_DEVICE_TOKEN`。
7. 点击「测试连接」：只读调用 `GET /finance/api/v2/sync/changes`，验证设备密钥、`server_id` 和版本游标。
8. 确认自己管理该服务器后启用双向同步，查看已有的同步状态卡片；用**模拟交易**验证上传、网页修改后下载、删除墓碑。

### 可选私有完整网页访问

`-ExposureMode PrivateFull` 可为首次安装明确选用完整私有访问，**不属于默认模式**。
从其他模式切换不是无损勾选：安装器拒绝隐式迁移和覆盖既有 Tailscale Serve 路由，需要审计原配置后显式迁移。
默认的 ApiOnly 模式仅本机能够进入 Wallos；手机远程网页 404 是预期现象。

## API-only 安全边界

- Windows: Finance API、Wallos 数据库和订阅网页继续由 Docker 运行；宿主端口仅绑定 loopback。
- Compose profile `windows-private-sync` 才启动独立 Nginx 网关，监听宿主 `127.0.0.1:18768`。
- Tailscale Serve 在 tailnet 内公开 `https://<pc>.<tailnet>.ts.net/`，只转发到上述网关。
- 网关显式允许：
  - `GET /finance/health`
  - `GET /finance/api/v2/sync/changes`
  - `POST /finance/api/v2/sync/push`
  - `POST /finance/api/v1/transactions/batch`（旧记录兼容）
  - `POST /finance/api/v1/transactions/correct-rounding`（旧记录修复）
- 其他路径默认 404；上述接口的非许可方法返回 405；编码路径被拒绝。
- 业务侧 `X-Device-Token` 认证和服务器账本身份校验不变。Tailscale **不能**替代后端认证。
- 不得启用 Funnel，不得直接开放 8767/18082/18768 公网端口。
- 安卓 VPN 可能与代理工具互斥；若 Tailscale 未连接，交易留在本地等待重试。

## 界面状态与恢复

| 阶段 | 用户看到的状态 | 操作 |
| --- | --- | --- |
| 准备 | Docker、Tailscale、Wallos 首次注册清单 | 手动确认环境，然后下一步 |
| 输入 | HTTPS 域名、掩码密钥 | 填写后测试 |
| 检测中 | 正在验证地址、认证、账本身份 | 不允许重复点击 |
| 401/403 | 密钥无效 | 在电脑核对密钥，不关闭认证 |
| 404/405 | 同步路由不存在 | 检查 ApiOnly 网关及 Serve |
| 网络错误 | 电脑/网络/VPN 不可用 | 保留本地数据，恢复后重试 |
| 身份冲突 | 账本或游标不匹配 | 停止连接，检查备份/迁移 |
| 成功 | 服务器标识、继续按钮 | 明确确认后开启同步 |
| 已启用 | 现有双向同步状态卡片 | 显示最近成功、冲突和补传状态 |

测试接口完全只读。修改任意输入后会重置先前的成功结果；只有确认步骤才保存地址与密钥并开启后台同步。

## 验收

1. 新设备无数据：Windows ApiOnly 初始化、手机读取账本身份、首次启用双向同步成功。
2. 正常往返：手机上传，网页修改，手机下载；删除和冲突按现有 v2 协议处理。
3. 断网恢复：Windows/Docker/Tailscale 断开期间手机不丢交易，恢复后补传。
4. 安全：正确密钥可以访问同步接口；未授权被拒绝；Wallos、OAuth、MCP 和其他接口在 ApiOnly 模式不可访问。
5. 路径变形：编码斜杠、目录绕过、非许可 HTTP 方法无法抵达 Finance 后端。
6. 升级：旧密钥和 SQLite 卷未重置；现有 Serve 其他应用路由不得被覆盖。
7. Windows 与 VPS 不得自动双主同步或直接交换客户端游标。
8. Android/Windows 完整实机端到端测试未通过前不得声称正式发布就绪。

回归入口：`python3 scripts/test_private_sync_gateway.py`（需要本地 nginx），Android `./gradlew testDebugUnitTest`（需要项目指定的 Java 与 Android SDK）。在 Windows 上需单独用 PowerShell Parser 检查安装脚本。
