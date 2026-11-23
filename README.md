# 清衡 · Open Margin

[中文](README.md) · [English](README.en.md)

**一款支持自动记账、主动截图记账、本地语音、订阅管理和 AI MCP 接入的开源自托管记账工具。**

一直觉得，记账不该是一件需要每天花时间手动维护的事，更不该把「自动记账、语音记账、识图记账、多端同步、无广告」这些本来就该好用的能力全部锁在会员付费墙后面。

**清衡想打破这些付费壁垒**：所有高级能力全部开源免费，平时几乎不用管，账自动帮你记好；想复盘的时候，直接通过 AI 问一句就知道钱花去哪了、怎么开源节流。

> **手机端装上就能单机用**，不需要先搭服务器；如果你想要电脑端报表、订阅管理或接 AI 管账，可以再部署到自己的 VPS 或 Windows 电脑上开启同步。

- **Android 下载**：[国内直达下载页](https://zxyzx.me/open-margin/installer/) · [GitHub Releases](https://github.com/zxyzx0430/open-margin/releases/latest)（Android 7.0+）
- **Windows 电脑端**：下载 `OpenMargin-Setup.exe` 一键安装（[部署说明](docs/DEPLOY_WINDOWS.md)）
- **VPS 自托管**：Docker Compose 部署（[部署说明](docs/DEPLOY_VPS.md)）

<p align="center">
  <img src="docs/assets/mobile-screens.jpg" width="860" alt="手机端：账单、新建记账、日历与统计">
</p>

<p align="center">
  <img src="docs/assets/desktop-screens.jpg" width="860" alt="桌面端：总览、日历、统计与订阅管理">
</p>

## 为什么做清衡（核心优势）

### 1. 打破付费壁垒，高级功能全部开源免费
市面上的记账软件常把自动记账、语音转写、截图识图、多端同步、去广告、导出数据做成层层叠叠的订阅会员。清衡完全开源免费、无广告，没有功能阉割，也不绑云端会员。

### 2. 自动记账 + 主动截图，尽量不让你手动填表
- **自动采集**：开启通知使用权和页面识别后，微信、支付宝和银行短信的日常付款会自动入账；遇到疑似重复或信息不全的交易会放进待确认列表，宁可多问你一句，也不乱删乱并。
- **主动截图记账**：不想开常驻后台权限，或者偶尔漏记也没关系——**双击首页「+」**或直接从相册分享微信 / 支付宝付款截图（一次最多 10 张），手机本地识别直接记好。截图原图只保存在你的手机本地（不上传、不同步，自动按期清理），在账单详情里随时能点开原图核对。
- **官方账单导入**：支持导入微信 / 支付宝导出的 CSV / XLSX 账单，入账前可预览并与现有记录比对防重，适合初始化历史账本或定期补账。

### 3. 一句话快记 & 本地语音，一个「+」搞定所有记账
首页底部「+」按钮支持三种手势：**单击**打开新建账单，**双击**批量导截图，**长按**直接开始语音记账。

```text
午饭 25 微信
昨天 星巴克 32 支付宝
工资 8000 银行卡 收入
```

直接打一句人话或者说一句话，金额、分类、账户、日期和收支方向都会自动填好。语音识别完全在手机本地离线完成，音频不会上传。

### 4. 通过 MCP 接入 AI，像聊天一样查账和管账
清衡内置了 18 个 MCP 工具，支持通过 OAuth 2.1 接入 ChatGPT、Claude 以及各类支持 MCP 的 Agent 客户端。连上之后你可以直接对 AI 说：

- “帮我看看这个月的钱主要花在哪些地方了，和上个月比怎么样？”
- “刚才在楼下买咖啡花了 22 块，微信付的，帮我记一下”
- “我身上有哪些快续费的订阅，哪些平时几乎没用？”

AI 读写的是你自己的同一份账本，权限按需授权（只读 / 写入分离），账本控制权始终在你手里。

<details>
<summary>查看全部 18 个 MCP 工具（8 只读 / 10 写入）</summary>

| 只读工具 | 写入工具 |
|---|---|
| `list_recent_transactions` | `add_record` |
| `spending_summary` | `edit_record` |
| `personal_finance_long_term_trends` | `exclude_record` |
| `get_subscription_list` | `delete_record` |
| `get_recurring_expense_candidates` | `register_merchant_rule` |
| `get_monthly_budget` | `link_refund` |
| `finance_reconciliation_queue` | `add_subscription` |
| `finance_duplicate_review` | `edit_subscription` |
| | `delete_subscription` |
| | `set_monthly_budget` |

</details>

### 5. 订阅管理不混账，日常功能齐全
- 集中记录各类会员、云服务和周期性订阅，追踪下次扣款日、到期提醒、月均与年度支出；订阅计划和日常消费分开统计，不会把还没扣的钱算进本月已花支出。
- 手机端同时内置日历热力图与拖拽区间统计、分类占比、每日/月度趋势、月度预算、商户自动分类规则、退款关联、多币种独立汇总以及 CSV 导出。

### 6. 数据完全归自己，支持多端同步
账本默认存在你的手机本地，**没有任何数据会经过清衡开发者的服务器**。开启同步后，手机、桌面网页和 AI 共用同一份账本，两端同时修改同一笔时会让你逐条选择保留哪一版。

<p align="center">
  <img src="docs/assets/sync-architecture.png" width="640" alt="手机 App、Finance API、Wallos 桌面端与 MCP 的同步架构">
</p>

## 快速开始

| 使用方式 | 适合人群 | 说明 |
|---|---|---|
| **仅用 Android 手机** | 想先体验自动记账 / 截图 / 语音 | 从[下载页](https://zxyzx.me/open-margin/installer/)装上即可单机使用 |
| **手机 + Windows 电脑** | 不想买服务器和域名 | 运行 `OpenMargin-Setup.exe`，通过 Tailscale Funnel 同步，电脑关机手机照常记，开机自动补同步（[指南](docs/DEPLOY_WINDOWS.md)） |
| **手机 + 自有 VPS** | 希望 24 小时随时同步与接 AI | 用 Docker Compose 部署，支持任意标准 HTTPS 域名（[指南](docs/DEPLOY_VPS.md)） |

**手机扫码配对**：部署好服务端后，在 Wallos 后台头像菜单进入「手机配对」生成临时二维码（或使用 Windows 安装向导最后一步的二维码），手机端进入「设置 → 同步 → 重新配对」扫码即可连上。二维码包含长期设备密钥，切勿截图外发；如泄露请在服务端轮换 `FINANCE_DEVICE_TOKEN`。

## 使用说明与注意事项

- **关于自动采集覆盖率**：Android 系统对跨应用读取账单有限制，清衡不依赖 ROOT；不同手机和国产 ROM 的省电策略会影响通知抓取稳定性，建议按 App 内「后台运行设置」加进自启动和省电白名单，漏记时可用主动截图或导入账单补齐。
- **关于备份**：服务端提供交易与订阅数据库的定时备份命令（`docker compose --profile maintenance run --rm backup`），重要账本建议额外保留异地备份；卸载旧版 Android 包前，请先确认未同步账单已备份或同步完成。
- **单用户设计**：当前面向个人自托管使用，不连接银行账号密码，也不提供多租户隔离。

<details>
<summary>开发者测试与验收说明</summary>

```sh
python3 -m pip install -r backend/requirements.txt "httpx>=0.27,<1"
cd backend && FINANCE_PUBLIC_URL=https://finance.example.com/finance python3 -m unittest discover -p "test_*.py" -v
cd .. && python3 scripts/check-public-ready.py
```

`scripts/smoke_clean_install.py` 用于临时空栈集成测试（会写入合成数据，请勿对着线上数据运行）。完整验收流程见 [docs/ACCEPTANCE.md](docs/ACCEPTANCE.md)。

</details>

## 开源许可

根目录采用 GPL-3.0 许可（覆盖 Wallos 衍生分发部分）。Android 客户端基于 [Seamless Bookkeeping](https://github.com/DykiSensei/seamless-bookkeeping)（MIT）二次开发并保留原 MIT 许可，署名见 `android/LICENSE`；订阅与桌面报表基于 [Wallos](https://github.com/ellite/Wallos)（GPL-3.0）。第三方组件说明见 `THIRD_PARTY.md`。
