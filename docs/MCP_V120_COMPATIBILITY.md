# 清衡 v1.2.0 · MCP 兼容性和权限验收

适用范围：已有 MCP 连接、11 个已注册工具、OAuth 授权、FastAPI/Wallos 交易与订阅功能。此工作**不增加 v1.3.0 的对账、待处理中心、诊断或分类学习工具**。

## 当前 MCP 接口

- Streamable HTTP：生产反向代理下的 `/finance/mcp/`，仅经已配置的 HTTPS 入口访问。
- OAuth 资源：`/finance/mcp/`；授权服务器 `/finance/oauth`。
- 支持动态客户端注册、授权码 + PKCE S256、轮换刷新令牌和单账本所有者授权。
- 默认请求 `scope` 为空时，仅授权 `finance:read`。需要写入的客户端须明确请求 `finance:write`，并由账本所有者确认。
- 旧的、已经签发的令牌不因本次变更被自动提权、降权或重新签发。令牌到期后的权限随原授权范围续期。
- 管理员令牌的原有授权方式保持兼容（仅用于可信后端客户端）。

### 11 个工具，名称和参数保持不变

| finance:read | finance:write |
| --- | --- |
| list_recent_transactions | add_subscription |
| spending_summary | edit_record |
| get_subscription_list | register_merchant_rule |
| get_recurring_expense_candidates | link_refund |
| personal_finance_long_term_trends | |
| finance_reconciliation_queue | |
| finance_duplicate_review | |

任何新增 MCP 工具必须先加入 `backend/mcp_access.py` 中的显式权限表，否则 OAuth 请求会被拒绝；不能将未知工具默认为只读。读取工具需要 `finance:read`；写入工具需要 `finance:write`。无效 JSON-RPC、未知工具名和批量调用一律拒绝。

**MCP 工具执行一次可导致真实持久化变更。** 不要仅凭工具注册列表认为某项写操作已经由用户确认；在聊天代理层发起写操作前仍需用户授权。此版本不会通过 MCP 执行真实支付。

## 可重现测试（必须使用隔离临时数据）

在 Python 3.11 和项目依赖准备完成后运行：

```sh
cd backend
python -m unittest test_mcp_protocol test_oauth -v
python -m unittest discover -p 'test_*.py' -v
```

`test_mcp_protocol` 使用独立临时 OAuth/交易 SQLite，只以合成交易与模拟 Wallos API 验证所有 11 个工具，包括四个写工具。它验证：

1. 11 个工具全部在权限表中。
2. 匿名请求及错误令牌返回 401，读令牌调用写工具和写令牌调用读工具返回 403。
3. 未知工具、异常参数、无效 JSON 与批量请求 fail-closed。
4. 读写工具可按现有 JSON-RPC 接口执行，返回值可用，模拟账本状态正确。
5. OAuth 未指定范围时默认只读，授权界面说明实际权限。
6. 管理员令牌兼容原有协议。

线上回归（只读）可以检查 `GET /finance/health`、OAuth 元数据、未授权 MCP 返回 401。不要向生产发起模拟交易或调用写工具进行测试。

## 发布与部署边界

- 先按版本规范备份 Finance、Wallos 和 OAuth 数据库；生产代码采用明确的 Git SHA、可回滚。
- 保持 `/finance/mcp/` 的合法 HTTPS 反向代理入口；不要将管理员密钥、设备密钥、OAuth 密钥写进代码或日志。
- Windows Tailscale **API-only** 模式默认不开放 MCP，这是特意限制的攻击面；未来若设计远程 MCP，需独立授权入口和验收。
- 上线前必须验证当前 ChatGPT MCP 插件/客户端可完成 OAuth、`tools/list`、读操作以及有授权时的写操作。隔离单测不等于线上验收。
- 若用户以只读方式连接但希望使用写工具，需在客户端重新授权并明确请求写入范围；不可通过修改服务器默认权限绕过。
