# 清衡 v1.2.0 · MCP 兼容性和权限验收

适用范围：已有 MCP 连接、18 个已注册工具、OAuth 授权、FastAPI/Wallos 交易与订阅功能。此工作**不增加 v1.3.0 的对账、待处理中心、诊断或分类学习工具**。

## 当前 MCP 接口

- Streamable HTTP：生产反向代理下的 `/finance/mcp/`，仅经已配置的 HTTPS 入口访问。
- OAuth 资源：`/finance/mcp/`；授权服务器 `/finance/oauth`。
- 支持动态客户端注册、授权码 + PKCE S256、轮换刷新令牌和单账本所有者授权。
- 默认请求 `scope` 为空时，仅授权 `finance:read`。需要写入的客户端须明确请求 `finance:write`，并由账本所有者确认。
- 旧的、已经签发的令牌不因本次变更被自动提权、降权或重新签发。令牌到期后的权限随原授权范围续期。
- 管理员令牌的原有授权方式保持兼容（仅用于可信后端客户端）。

### 18 个工具（原有 12 个名称和参数不变，新增 6 个财务管理工具）

| finance:read | finance:write |
| --- | --- |
| list_recent_transactions | add_subscription |
| spending_summary | edit_subscription |
| get_subscription_list | edit_record |
| get_recurring_expense_candidates | register_merchant_rule |
| personal_finance_long_term_trends | link_refund |
| finance_reconciliation_queue | add_record |
| finance_duplicate_review | exclude_record |
| get_monthly_budget | delete_record |
| | delete_subscription |
| | set_monthly_budget |

`edit_subscription` 早已在源码中注册并属于 `finance:write`；此前文档写成 11 个工具（漏列该工具）是文档口径错误，不是服务端未暴露。若 ChatGPT 仍只看到旧工具，先确认部署的是当前 commit，再重新连接/重新授权以刷新客户端缓存的 `tools/list`。

任何新增 MCP 工具必须先加入 `backend/mcp_access.py` 中的显式权限表，否则 OAuth 请求会被拒绝；不能将未知工具默认为只读。读取工具需要 `finance:read`；写入工具需要 `finance:write`。无效 JSON-RPC、未知工具名和批量调用一律拒绝。

**MCP 工具执行一次可导致真实持久化变更。** 不要仅凭工具注册列表认为某项写操作已经由用户确认；在聊天代理层发起写操作前仍需用户授权。此版本不会通过 MCP 执行真实支付。

## 可重现测试（必须使用隔离临时数据）

在 Python 3.11 和项目依赖准备完成后运行：

```sh
cd backend
python -m unittest test_mcp_protocol test_oauth -v
python -m unittest discover -p 'test_*.py' -v
```

`test_mcp_protocol` 使用独立临时 OAuth/交易 SQLite，只以合成交易与模拟 Wallos API 验证所有 18 个工具，包括十个写工具。它验证：

1. 18 个工具全部在权限表中。
2. 匿名请求及错误令牌返回 401，读令牌调用写工具和写令牌调用读工具返回 403。
3. 未知工具、异常参数、无效 JSON 与批量请求 fail-closed。
4. 读写工具可按现有 JSON-RPC 接口执行，返回值可用，模拟账本状态正确。
5. OAuth 未指定范围时默认只读，授权界面说明实际权限。
6. 管理员令牌兼容原有协议。

## 财务管理 CRUD 扩展（2026-11）

- `add_record`：复用 `/api/v1/wallos-ledger/manual`，以 UUID `request_id` 幂等；支持 `INCOME`/`EXPENSE`。同一 ID 重放返回原记录；若记录之后被编辑，额外返回 `current_record_differs=true`，不会重复写入。
- `exclude_record`：复用 review 的 `excluded` 语义；交易保留、可继续对账，但不计入支出/收入统计。
- `delete_record`：只删除普通账目。存在退款关联或已匹配银行流水时明确拒绝；拆分记账和订阅付款关联一并清理；删除触发 `sync_journal` 的 `delete` tombstone，Android v2 客户端拉到删除。
- `delete_subscription`：调用 Wallos 原生 `set_subscriptions.php` 的 `action=delete`，与 `edit_subscription(active=false)`（停用但保留历史）明确区分；要求 `confirm=true`，删除后再次读取 Wallos 列表验证 ID 已消失。适配器只走 HTTP API，不直接写 Wallos SQLite。
- `get_monthly_budget` / `set_monthly_budget`：个人月度 CNY 日常支出预算，整数分，按月；`amount_cents=0` 清除该月预算。与 Wallos 订阅预算、Android 本地预算相互独立。
- 永久删除类工具 `delete_record` / `delete_subscription` 都要求 `confirm=true`；`finance:read` 令牌不能调用任何写工具。

Wallos 兼容性：固定镜像构建于 2026-10 初；上游 `set_subscriptions.php` 自 v5.0.0（2026-07-11）起支持 `action=delete`，v5.8.3 源码复核仍支持。上线前仍应在该固定镜像上做一次隔离环境的删除/回读验收；未在正式数据上执行删除。

线上回归（只读）可以检查 `GET /finance/health`、OAuth 元数据、未授权 MCP 返回 401。不要向生产发起模拟交易或调用写工具进行测试。

## 发布与部署边界

- 先按版本规范备份 Finance、Wallos 和 OAuth 数据库；生产代码采用明确的 Git SHA、可回滚。
- 保持 `/finance/mcp/` 的合法 HTTPS 反向代理入口；不要将管理员密钥、设备密钥、OAuth 密钥写进代码或日志。
- Windows Tailscale **API-only** 模式默认不开放 MCP，这是特意限制的攻击面；未来若设计远程 MCP，需独立授权入口和验收。
- 上线前必须验证当前 ChatGPT MCP 插件/客户端可完成 OAuth、`tools/list`、读操作以及有授权时的写操作。隔离单测不等于线上验收。
- 若用户以只读方式连接但希望使用写工具，需在客户端重新授权并明确请求写入范围；不可通过修改服务器默认权限绕过。
