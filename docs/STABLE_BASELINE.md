# 清衡 Open Margin｜稳定使用基线与开发基线

**基线日期**：2026-11-05  
**代码基线**：`main@48ec793`（2026-11-04，本地验证补丁未提交）  
**产品版本**：Android `1.2.0` / versionCode `11`；后端 `/health` 报告 `1.5.0`

> 这是**预发布基线**，不是正式发布声明。真实手机的通知采集、无障碍绑定、国产 ROM 兼容仍未经真机验证；不能把本文件当作“采集已可靠”或“银行账单已对账”的证据。

## 01. 结论

- **可稳定使用**：Windows / VPS 单用户自托管、Wallos 日常账本、手动记账、预算、CSV、v1/v2 双向同步、OAuth 2.1 + PKCE、MCP 只读/授权写入、三库备份。
- **可稳定开发**：后端 93 个测试（74 功能 + 17 同步/上传压力 + 2 跨层契约）、Android 151 个 JVM 单测全绿；Debug/Release APK 均可构建。
- **仍属实验**：Android 支付通知与无障碍采集、跨渠道去重（D05 仍为影子模式）、真实设备覆盖率。
- **未验证**：Android 16 真机、模拟器 instrumentation、GitHub CI 绿灯、正式签名 APK、v1.1.3 → v1.2.0 同签名升级。

## 02. 本次验证矩阵

| 验证项 | 命令 / 方式 | 结果 |
|---|---|---|
| 后端全量单测 | `python -m unittest discover -p "test_*.py"` | **93 tests, OK**（74 功能 + 17 压力/鲁棒性 + 2 跨层契约） |
| Android JVM 单测 | `gradlew testDebugUnitTest` | **151 tests, 0 failures** |
| 通知/短信解析字段契约 | `NotificationParserTest` + `SmsParserTest` | **38 tests, 0 failures** |
| 解析器 fuzz/超长对抗 | 20,000 随机输入 + 10 万字符病态文本 | **全过；最慢单项约 0.16 s** |
| 结构化页面规则/金额边界 | `WechatTransferPageRuleTest` | **16 tests, 0 failures** |
| 上传字段映射 | `UploadPayloadTest` | **6 tests, 0 failures** |
| 提取→上传跨层契约 | `backend/test_extraction_upload_contract.py` | **2 tests, OK** |
| Debug APK | `gradlew assembleDebug` | **成功，18.90 MB** |
| Release 未签名 APK | `gradlew assembleRelease` | **成功，12.61 MB** |
| APK 元数据 | `aapt dump badging` | debug `com.bookkeeping.app.sync2`、release `com.bookkeeping.app`；versionCode 11；minSdk 24；targetSdk 35 |
| 公开就绪静态扫描 | `python scripts/check-public-ready.py` | **OK（262 files）** |
| Python 语法 | `python -m compileall -q backend deploy scripts` | **OK** |
| 启动图标资产 | `python scripts/test_launcher_assets.py` | **全部 PASS** |
| 私有同步网关 | `python scripts/test_private_sync_gateway.py` | **5 允许 / 12 拒绝，无绕过** |
| Windows 安装脚本 | PowerShell Parser 解析 3 个脚本 | **全部 PASS** |
| Compose 配置 | `docker compose -p pfs-validation config --quiet` | **OK** |
| 隔离清洁安装烟测 | `scripts/smoke_clean_install.py` | **ALL_STAGING_INTEGRATION_CHECKS_PASSED** |
| 三库备份 | `docker compose ... run --rm backup` | **transactions / wallos / oauth 全部校验通过** |
| 重启持久化 | 重启 finance-api + wallos 前后查 SQLite | **3 → 3，未丢失** |
| Android instrumentation | `connectedDebugAndroidTest` | **未运行**：模拟器/系统镜像安装未完成，且本机可用内存不足 |
| Android 16 真机 | D01/D06/D07 真机流程 | **未验证** |
| GitHub CI | `main` 最新工作流 | **已推送，Android/后端/就绪检查全部通过** |

### 隔离烟测的环境说明

本机 Docker 缓存中的 Wallos 固定上游层已损坏，多个镜像源均返回 HTML/白名单错误，无法重新拉取。为验证**当前** `deploy/wallos-extension` 文件，本次使用本机已有的 PR11 acceptance Wallos 镜像作为基础镜像，叠加当前扩展文件后运行；finance-api 镜像则由完整的 `python:3.11-slim` 本地镜像干净构建。

因此该烟测证明**当前代码、Compose 接线、Wallos 扩展页面和 Finance API 的集成可用**，但不等同于“从公网重新拉取上游 Wallos 的完整洁净安装”。换一台 Docker 缓存正常的机器仍需重跑一次。

该烟测执行于并发加固之前；加固后的后端由 93 项测试和真实 uvicorn 压测覆盖，受本机可用内存限制，未再次重建 Docker 栈。

### 同步/上传压力与鲁棒性结果

本机真实 uvicorn（单 worker，SQLite WAL，全合成数据）实测：

| 场景 | 结果 |
|---|---|
| 20,000 条上传（200 批 × 100，8 并发） | **2.7–3.2 s**，全部入库（约 6k–7.5k 条/秒） |
| 20,000 条全量重放（8 并发） | **1.9–2.5 s**，`inserted=0`、`already_present=20000` |
| 16 个客户端同时上传同一批 100 条 | 只有 **100 条**入库，其余 1500 次全部 `already_present` |
| 2,000 条 v2 upsert（40 批 × 50，8 并发） | **4.2–4.9 s**，全部 applied |
| 22,100 条变更分页拉取（limit=500） | **1.3–1.7 s / 45 页**，revision 全唯一 |
| 8 个客户端同 base revision 并发写同一条 | **1 applied + 7 conflict**，无丢写 |
| 20 轮 v1 上传 vs v2 推送/删除同一笔 | 创建始终 **1 条**，删除始终 **0 条**，墓碑不被 v1 重放复活 |
| 20 个并发畸形上传请求 | 全部 **422**，无 500 |

压力测试还发现并修复了三个真实问题：

1. **WAL 读事务升级写事务会绕过 `busy_timeout`**：v1 上传先查墓碑/疑似重复再插入，并发时 `BEGIN IMMEDIATE` 之前已持有读事务，部分请求直接 `database is locked`。现在上传批次和一分钱修正都在读操作前 `BEGIN IMMEDIATE` 预占写锁。
2. **上传去重查询缺少索引**：账本到 2 万行时，100 条批次从约 25 ms 退化到约 580 ms，因为按金额/币种/方向/时间窗查询做了全表扫描。新增 `tx_dedup_lookup(amount_cents,currency,direction,timestamp_ms)` 后恢复稳定吞吐。
3. **每个连接都执行 `PRAGMA journal_mode=WAL`**：并发时切换 WAL 需要排他锁，部分请求在连接建立阶段直接 `database is locked`。现在 WAL 切换和建表按数据库路径每进程只初始化一次，后续请求不再争抢排他锁。

可复现的自动化覆盖在 `backend/test_sync_stress.py`：10k 顺序上传、全量/重叠重放、16 路并发同批、20 路并发不同批、v2 最大批量推送/重放、分页、墓碑风暴、最大长度/Unicode、畸形 JSON/字段、批次中途异常回滚、v1 与 v2 混合协议并发等。

### 结构化提取与上传验证

本次新增字段级测试，覆盖三类实际入口和一条跨层契约：

- **通知解析**（`NotificationParserTest`，18 项）：微信/支付宝支出、个人转账收入、“向你付款”、退款、支付宝通用支出、商户、分类、账户、时间、金额边界、促销/失败/待支付排除、Unicode/长文本、20k 随机 fuzz。
- **银行短信**（`SmsParserTest`，20 项）：银行识别、卡尾号、收支方向、余额排除、工资/转账分类、促销/失败/预授权排除、千分位、长文本、20k 随机 fuzz、三类 10 万字符病态输入。
- **结构化页面规则**（`WechatTransferPageRuleTest`，16 项）：金额、方向、完成态、商户、失败原因、区域定位、contentDescription 节点、多卡不串金额、截断页面、金额格式边界。
- **上传字段契约**（`UploadPayloadTest` 6 项 + 后端 `test_extraction_upload_contract.py` 2 项）：`event_id / timestamp_ms / amount_cents / currency / direction / source / category / merchant` 逐字段保留；`note` / `account` 明确不上传；重放幂等。

本轮发现并修复的提取问题：

1. 通知/短信的千分位金额被截断（`￥1,234.56` 读成 1 或 234.56）。
2. `￥12.345`、`￥1.2.3` 等畸形金额被截成合法金额；现在返回 null。
3. 微信“收到 张三 的转账”未识别为收入；现在识别为 INCOME。
4. “付款给 星巴克”未识别为完成支出；现在识别。
5. 促销金额在“付款成功”之后仍被当成成交额；现在优先跳过紧邻“优惠券/红包/余额”的金额。
6. 银行短信缺少促销/失败/预授权排除，可能把“满100元返50元”“消费失败”误记；现在直接跳过。
7. “尾号为1234”未提取卡尾号；现在支持。
8. 旧无障碍提取器用 `Double * 100` 计算分，可能把 0.29 记成 28；现在统一走 `AmountText.parseCents` 精确到分。
9. 金额正则使用无界 `[0-9]+`，遇到 10 万位纯数字发生指数回溯（单项 345 s）；现在数字上限 12 位，同类病态输入降到约 0.16 s。分类器上下文也限制为 4,000 字符。
10. `AmountText` 允许超过服务端上限的金额，可能生成永远无法上传的记录；现在统一限制为 100,000,000,000 分。

**重要边界**：新的 `WechatTransferPageRule` 目前仍只在 DEBUG 影子运行（蓝图 D03/D05），真正写入账本的仍是通知/SMS/旧无障碍提取器。上述测试同时覆盖新结构化规则和实际写入路径；切换写路径仍必须等 D01 真机样本与 D06 双端核验。

## 03. 稳定使用基线

### 适用形态

- **Windows 本机**：Docker Desktop + WSL2，Web 仅 `127.0.0.1`。
- **Windows + Android 私有同步**：Tailscale Serve，默认 `ApiOnly` 网关。
- **VPS / Linux**：Docker Engine + HTTPS 域名，服务只监听回环，由 Nginx 暴露。

### 已端到端验证的能力

- Wallos 首次注册、登录、未登录拒绝。
- 日常账本、手动支出、月度预算、CSV 导出。
- Android 形状的交易批量上传、错误 token 拒绝、重复上传幂等。
- 金额精确到分（1881 分 → ¥18.81）。
- v1 上传 + v2 双向同步、修订号、删除墓碑、离线重放不复活。
- OAuth DCR、S256 PKCE、短期 access token、发现元数据。
- MCP `tools/list`；只读 scope 调用写工具返回 403。
- Finance / Wallos / OAuth 三库在线备份与完整性检查。

### 数据安全底线

- 不要 `docker compose down -v` 删除正式卷。
- 不要卸载含未同步记录的旧 APK。
- 每日运行 `docker compose --profile maintenance run --rm backup`，并保留异机加密备份。
- Finance、Wallos、OAuth 是三个独立 SQLite 库，恢复时分别校验。
- 不把真实通知正文、聊天、卡号、验证码、金额或设备 token 提交到 GitHub。

### 当前不应承诺

- 不承诺所有微信/支付宝通知都能捕获。
- 不承诺通知捕获等于官方账单对账。
- 不承诺国产 ROM 后台不被杀。
- 不承诺多租户隔离、云 TLS、自动银行对账、退款原单关联。
- 不把 v1.2.0 称为正式发布；它仍是 RC6 候选。
- Release APK 是**未签名**包，不能直接当作正式发布包；签名私钥只在发布机上使用。

## 04. 稳定开发基线

### 工具链

| 组件 | 版本 / 路径 |
|---|---|
| 仓库 | `E:\Source\open-margin` |
| JDK | Temurin 21.0.13，`E:\Tools\jdk-21` |
| Gradle | 8.9（wrapper） |
| AGP / Kotlin / KSP | 8.7.3 / 2.0.21 / 2.0.21-1.0.28 |
| compileSdk / targetSdk | 35 / 35 |
| Build Tools | 34.0.0（AGP 默认）+ 35.0.0 |
| Android SDK | `E:\Tools\AndroidSdk` |
| Gradle 缓存 | `E:\DevCaches\gradle` |
| Python | 本地 3.13.13；CI/Docker 3.11 |
| Python venv | `E:\Source\open-margin\.venv` |
| Docker | 29.3.1（Compose v2） |
| nginx（网关测试） | 1.30.6，`E:\Tools\nginx` |

本机验证全部在 E 盘完成；Docker 镜像存储沿用已安装 Docker Desktop 的默认位置。

### 后端测试

```powershell
$env:FINANCE_PUBLIC_URL = 'https://finance.example.com/finance'
cd E:\Source\open-margin\backend
..\.venv\Scripts\python.exe -m unittest discover -p "test_*.py" -v
```

Windows 本地测试必须安装 `tzdata`；该依赖已加入 `backend/requirements.txt`，否则 `ZoneInfo("Asia/Shanghai")` 会在导入 `wallos_ledger` 时失败。

### Android 测试与构建

内存紧张时（本机 15.4 GB，Docker/桌面应用常驻），建议：

```powershell
$env:JAVA_HOME = 'E:\Tools\jdk-21'
$env:GRADLE_USER_HOME = 'E:\DevCaches\gradle'
$env:ANDROID_HOME = 'E:\Tools\AndroidSdk'
$env:ANDROID_SDK_ROOT = 'E:\Tools\AndroidSdk'
cd E:\Source\open-margin\android
.\gradlew.bat --no-daemon --max-workers=1 `
  '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8' `
  testDebugUnitTest assembleDebug assembleRelease
```

低于约 2 GB 可用内存时 Gradle JVM 可能因原生内存分配失败而崩溃；先停掉隔离 Docker 栈、Kotlin daemon 和其他大内存进程再构建。

### 静态与部署检查

```powershell
cd E:\Source\open-margin
D:\python\python.exe scripts/check-public-ready.py
D:\python\python.exe -m compileall -q backend deploy scripts
D:\python\python.exe scripts/test_launcher_assets.py
$env:Path = 'E:\Tools\nginx;' + $env:Path
D:\python\python.exe scripts/test_private_sync_gateway.py
```

### 隔离清洁安装（仅 staging）

```powershell
cd E:\Source\open-margin
Copy-Item .env.example .env
# 将 FINANCE_API_PORT / WALLOS_PORT 改为 18767 / 18083
D:\python\python.exe scripts/init-config.py --url https://finance.example.com/finance
docker compose -p pfs-acceptance config -q
docker compose -p pfs-acceptance up -d --build
.\.venv\Scripts\python.exe scripts/smoke_clean_install.py
docker compose -p pfs-acceptance --profile maintenance run --rm backup
docker compose -p pfs-acceptance down -v
```

`smoke_clean_install.py` 会拒绝标准生产端口，只接受示例域名和 staging 端口；不要指向真实数据。

## 05. 本次修复清单（已提交推送）

| 文件 | 修复 |
|---|---|
| `backend/app.py` | WAL/建表改为按 DB 路径每进程只初始化一次；上传/修正先 `BEGIN IMMEDIATE`；新增上传去重复合索引 |
| `backend/test_sync_stress.py` | 同步/上传压力与鲁棒性测试（17 项） |
| `backend/test_extraction_upload_contract.py` | 提取字段 → v1 上传 → 落库的跨层契约（2 项） |
| `backend/testutil.py` | 测试连接器：成功提交、始终关闭 SQLite |
| `backend/test_backup_regression.py` 等 5 个测试 | 改用 `testutil.connect`，修复 Windows 临时目录文件锁 |
| `backend/oauth_support.py` | `owner_ok` 显式关闭 Wallos 连接 |
| `backend/wallos_bridge.py`、`backend/wallos_api.py` | 显式关闭 SQLite 连接 |
| `backend/requirements.txt` | 增加 `tzdata`，支持 Windows 原生运行/测试 |
| `scripts/test_private_sync_gateway.py` | nginx 配置改用 POSIX 路径，创建 Windows 前缀 temp/log 目录并传 `-p` |
| `android/.../NotificationParser.kt` | 千分位、畸形金额边界、“收到 X 的转账”、“付款给”、促销金额优先级、金额数字上限 |
| `android/.../SmsParser.kt` | 千分位、“尾号为”、促销/失败/预授权排除、金额数字上限 |
| `android/.../CategoryClassifier.kt` | 分类上下文限制 4,000 字符，防病态长文本 |
| `android/.../MoneyAmount.kt` | 金额输入限制 12 位数字，防超长 BigDecimal |
| `android/.../PageRules.kt` | `AmountText` 拒绝负数和残缺金额，统一服务端 1000 亿分上限 |
| `android/.../WechatTransferExtractor.kt`、`AlipayTransferExtractor.kt`、`WechatRedPacketExtractor.kt` | 旧写入路径改为精确到分，移除 `Double * 100` |
| `android/.../UploadPayload.kt` | v1/v2 字段映射抽成纯函数；`note`/`account` 不上传 |
| `android/.../NotificationParserTest.kt`、`SmsParserTest.kt` | 通知/短信字段级、fuzz 与超长对抗测试（38 项） |
| `android/.../WechatTransferPageRuleTest.kt` | 结构化页面字段、多卡、截断、金额边界（16 项） |
| `android/.../UploadPayloadTest.kt` | 上传字段映射与敏感字段排除（6 项） |
| `android/.../NotificationTextExtractorTest.kt` | 断言改为“24h 内取最新时间” |
| `android/.../CaptureRobustnessTest.kt` | SAME 不变量允许“交易号优先、金额可被用户改过” |
| `android/.../CapturePipelineDecisionTest.kt` | 新增“同交易号、金额不同仍判同一笔”回归用例 |

## 06. 下一步门槛

1. 用 Android 16 真机样本替换本轮合成页面/通知/短信文案（D01），逐条核对金额、方向、完成态和商户。
2. 用正常 Docker 缓存机器重跑一次完整清洁安装（本次使用了 Wallos 本地回退基础镜像）。
3. 在 Android 16 真机执行 D06 影子双端核验、D07 发布验收。
4. 真机通过后，才更新知识库中“D02–D05 已落地、D01/D06/D07 待真机”的状态，并把结构化页面规则从 DEBUG 影子切换到正式写入。
5. 正式发布前补齐同签名 APK 升级、生产库备份恢复演练和发布门禁清单。
