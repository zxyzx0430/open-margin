# 清衡 Open Margin v1.2.0｜集成验收与发布门禁

## 集成基线

- `main`: 正式 v1.1.3，保持不变直至最终验收
- 同步与部署修复：`fix/v1.2.0-sync-deploy-audit-20261013`（`6190640`）
- Windows 私有同步与向导：`feature/v120-windows-private-wizard-20261013`（`76c1b3b`）
- 集成分支：`integration/v1.2.0-final-20261013`
- 双亲合并提交：`7a67281`（父提交 `6190640`、`76c1b3b`）

## 本版整合功能

1. Android Room v1→v2→v3 非破坏性迁移；原 applicationId，versionName 1.2.0，当前 RC5 versionCode 10（RC4 为 9）；离线交易、后台 WorkManager 同步。
2. Android 与 Finance API 交易双向新增、修改、删除；服务器修订号、幂等操作、删除墓碑、逐笔冲突人工确认、服务器身份与游标检查；手机端可以更新排除状态。
3. 单一 Docker Compose 支持 Windows（Docker Desktop）与 Linux/VPS；Finance API、Wallos、SQLite 数据模型一致。
4. Windows 本地仅 `127.0.0.1` 访问；可选 `windows-private-sync` Nginx 网关，API-only 私有 Tailscale Serve；显式 `PrivateFull` 模式。
5. Windows PowerShell 首次安装向导 + Android 三步首次连接、只读服务器身份检查、明确启用确认；保留已有同步卡和 VPS 模式。
6. Finance、Wallos 及已初始化 OAuth 三库备份；Wallos Owner 访问隔离；Web 订阅预算、统计、OAuth/MCP 保留。

## 集成合并冲突

- `compose.yaml`：保留 Finance/Wallos/备份数据库路径与新 Windows 私有网关 profile，自动干净合并。
- `deploy/windows/Install-OpenMargin.ps1`：手动保留本地无域名首次安装、旧配置/密钥不覆盖、从本地占位地址显式升级 Tailscale URL、ApiOnly/PrivateFull、Serve 路由检查，避免简单 ours/theirs。
- Android 向导不取代旧 `SyncSettingsCard`，通过现有 `TransactionSyncWorker.configure` 开启同步。

## 已执行隔离验证（模拟账单）

- 后端 50 项单元测试通过。
- Nginx 5 项允许路由与 12 项拒绝路由通过。
- 新 Docker Compose（三服务含私有网关）成功构建启动，所有服务健康。
- Wallos 首次注册/登录、未登录拒绝、模拟交易、网页统计/日历、CSV、OAuth/MCP、v1/v2 双向同步、墓碑、幂等及备份综合烟测通过。
- 重启后模拟记录数一致；在线三库备份成功。
- PowerShell 安装脚本使用 GitHub Windows Actions 解析器校验。
- 最终 Android CI、Windows 干净安装及真实 Android 端到端结果需以对应 commit SHA 的最新状态为准。

## 正式发布强制门禁

- [ ] 整合最终提交 Android 单元测试及 Release APK 构建通过。
- [ ] Windows 10/11 Docker Desktop 空白环境，按公开 README 实际安装、注册、登录、配置 Tailscale、同步成功。
- [ ] Android 真机 v1.1.3 → 1.2.0 **同证书签名**直接覆盖安装，Room、未同步交易和同步设置保留，校验新版本 versionCode 10 或最终发布更高的代码。
- [ ] 退款关联、服务器数据库旧备份恢复后游标与身份一致性、手机离线恢复端到端验收。
- [ ] 使用隔离生产数据副本对照旧版与新版汇总，完成生产全库在线备份及恢复演练；配置、证书、旧镜像及回滚路径均可用。
- [ ] 完成 PR 审查、最新 CI、明确生产变更批准，合并 `main`，由原签名证书签署正式 APK 并上传 GitHub v1.2.0 Release。

**未全部勾选之前：只能称作开发集成候选版本，不得声称正式发布成功；不得改动生产卷和正式 APK。**
