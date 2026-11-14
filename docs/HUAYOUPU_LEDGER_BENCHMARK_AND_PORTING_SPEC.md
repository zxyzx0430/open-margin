# huayoupu-ledger 对标拆解与清衡移植规格

- 日期：2026-11-14
- 状态：调研与移植方案，未修改产品代码
- 对标仓库：[AldenHe/huayoupu-ledger](https://github.com/AldenHe/huayoupu-ledger)（约 277★，GPL-3.0-only，Android 11+ ARM64）
- 主报告：[Android UI 重构与微信支付账单/图标提取实施报告](ANDROID_UI_REFRESH_AND_WECHAT_IMPORT_PLAN.md)

## 0. 一句话结论

对标项目的成熟路线可以移植：

1. **支付成功页**：无障碍读取页面身份/成功状态/金额 → 系统截图 → 本地 OCR 补全 → 校验/去重 → 入账。
2. **历史账单**：官方 CSV/XLSX 导入，预览、去重、确认后写入；不对历史账单列表做后台静默抓取。
3. **商户图标**：从同一张成功页截图中按商户文字/几何位置裁剪 128×128 图标；失败用默认图标。
4. **无障碍“破限”**：它用 `com.google.android.accessibility.selecttospeak.SelectToSpeakService` 兼容入口类名 + `isAccessibilityTool=false`，让微信成功页从 1 个空节点变成 32 个节点。这是**未公开兼容 hack**，必须做成可选模式并做 A/B/A 真机验证，不能默认依赖。

## 1. 对标项目到底做了什么

### 1.1 覆盖范围（它的 README/REFERENCE 口径）

- 主要识别微信、支付宝的**支付成功页**；扫码、商户付款、转账、第三方 App 拉起支付后跳成功页均可。
- 不保证后台代扣、历史账单、未展示成功页的付款；这些场景用摇一摇补记、截图导入或手动记账。
- 微信红包暂不支持自动识别。
- 历史账单详情、聊天消息、收款/领取红包页面不属于自动支出采集；与成功页要区分。
- 不使用短信、通知监听；不声明 `INTERNET`；截图和账本留在本机。
- Android 11+、ARM64；依赖系统截图和无障碍。

### 1.2 采集流程

```
页面事件（窗口/内容变化）
  → 合并事件、有界重试
  → 检查当前窗口是否属于已选应用
  → 读取可见节点（text/contentDescription/深层子树）
  → 识别明确“支付成功”状态 + 唯一有效金额
  → 截图前再次核对窗口/成功/金额/页面身份
  → Android 14+ 指定窗口截图；旧系统整屏截图后再次核对窗口
  → 本地 OCR 提取商户/金额/时间/订单号
  → 字段校验、退款/营销金额排除、去重、入账
  → 截图/商户图标本地保存（可选）
```

关键安全点：

- 先确认“应不应该采集”，再截图/OCR；不用截图去猜一个未知页面是否支付成功。
- 密码、验证、可编辑、系统安全窗口、历史账单详情跳过。
- 截图前离开页面/页面变敏感 → 取消采集。
- 同一成功页因窗口重建/服务重连再次出现时，复用支付身份，避免重复入账；能读到交易号时优先按交易号。
- 两笔同金额且无任何可区分信息时，仍可能漏记/需补记，不能承诺 100%。

### 1.3 无障碍“破限”的真实做法

它的 REFERENCE 记录了一个只读 A/B/A 实验：

- 普通入口 A：7 次有效采样均为 1 个空根节点。
- 兼容入口 B：8 次有效采样均读到 32 个节点，能看到“支付成功”“完成”。
- 换回 A：4 次采样再次为空。

实现方式：

- Manifest 注册 `com.google.android.accessibility.selecttospeak.SelectToSpeakService`；
- 该类只继承本应用自己的 `ScreenCaptureService`，没有 Google 代码/朗读能力/额外权限；
- 用户可见名称仍是“花有谱 · 页面识别”，`isAccessibilityTool=false`；
- 服务配置：`canRetrieveWindowContent=true`、`canTakeScreenshot=true`、`flagRetrieveInteractiveWindows`、`flagIncludeNotImportantViews`，不限制 `packageNames`（由应用内开关选择微信/支付宝）。

风险：

- 微信内部为什么按类名返回节点，不可知；微信/系统更新可能失效。
- 类名与 Google 的 SelectToSpeak 同名，可能引发 Play 审核/无障碍政策/商标/冲突风险。
- `isAccessibilityTool=false` 在 Android 13+ 的商店政策中需要更严格的披露；不能把它当成“无隐私风险”。
- 服务切换会重建窗口，A/B/A 不能描述为同一个底层窗口对象。
- 这是实验性兼容入口，不是稳定 API；必须做成可选开关 + 可回退 + 明确用户提示。

### 1.4 OCR 选型与成本

对标项目在 19 张图（13 真实授权截图 + 6 合成边界图）上对比：

| 指标 | PP-OCRv6 Tiny | ML Kit Chinese |
| --- | ---: | ---: |
| 权重 | 约 5.95 MiB | 随 SDK |
| 独立研究 APK | 20.44 MiB | 13.94 MiB |
| 真实支出最终金额 | 10/10 | 9/10 |
| 订单号精确匹配 | 7/7 | 6/7 |
| 商户精确匹配 | **4/9** | **4/9** |
| 退款负例拒绝 | 3/3 | 3/3 |
| 首次 OCR | 719 ms | 1,275 ms |
| 暖态中位数/P95 | 464/559 ms | 1,140/2,173 ms |

注意：

- 商户精确匹配只有 4/9，说明 **OCR 不能单独承担商户名准确性**；商户名应优先用页面文本/现有通知字段，OCR 补金额/订单号/时间。
- 上述耗时来自 API 30 x86_64 模拟器，不能当作真机完整延迟。
- 当前版本关闭 CPU arena、每请求释放会话，暖态中位数约 578/586 ms，换低内存。
- 包体会比 ML Kit 大约 6.5 MiB；是否值得取决于 Phase 0B 的实测。

### 1.5 商户图标裁剪

- 从**同一张成功页截图**中，以 OCR 的商户文字/金额为 hint，在有限邻域找连通区域，判断宽高比/面积/圆度/边界；
- 裁剪为 128×128，白底，PNG/无损 WebP 取较小者；
- 无法可靠定位/候选不唯一 → 默认图标，不阻断入账；
- 同名商户图标可复用；216 种位置/比例/背景/陌生图形组合有回归测试；
- 这是“成功页截图裁剪”，不是“从账单列表批量抽图标”。

## 2. 清衡应该照搬什么、怎么改

### 2.1 三层移植边界

| 层 | 建议 | 理由 |
| --- | --- | --- |
| 成功页采集架构 | **照搬流程** | 先判定再截图、OCR 补全、校验/去重、失败回退，已被验证 |
| 官方 CSV/XLSX 历史导入 | **照搬产品思路** | 最可靠的历史数据源；预览/去重/确认，避免误记 |
| 本地 OCR/图标裁剪 | **可移植，先做引擎选型** | 增加包体/延迟；商户准确率有限，需与现有通知字段合并 |
| 兼容入口类名 hack | **可选、默认关闭、A/B/A 验证** | 未公开、可能失效、政策风险；不能作为唯一通道 |
| 历史账单列表后台抓取 | **不做** | 对标项目自己也不做；不可靠且容易重复/误记 |

### 2.2 与清衡现有采集的关系

清衡现有：

- 通知监听（微信/支付宝/银行通知）→ 实时、字段较全，但通知可能缺失/延迟/聚合；
- 无障碍转账/红包/支付宝转账提取；
- 短信（可选）。

新增成功页 OCR 通道应作为**第三条通道**，不是替换：

```
CaptureCoordinator
  ├─ NotificationCapture（现有，优先）
  ├─ SmsCapture（现有，可选）
  ├─ AccessibilityCapture（现有转账等）
  └─ SuccessPageCapture（新增，可选，OCR + 截图 + 图标）
       → 统一候选 → 统一去重 → 统一确认/入账
```

统一去重需要：

- 支付身份：微信/支付宝各自独立；交易号优先；无交易号时用“页面访问身份 + 金额 + 时间”；
- 与现有 `insertIfNotDuplicate` 的 8 秒高置信度、3 分钟疑似重复策略衔接；
- 新增通道产生的是**候选**，默认进入现有待确认/重复审核，而不是直接删除/覆盖已有记录。

### 2.3 模块拆分（建议文件）

新增（Android）：

- `notification/accessibility/SuccessPageCaptureService.kt`：独立可选服务；主服务保持 `isAccessibilityTool=true`，兼容入口单独声明。
- `notification/accessibility/CompatibilityAccessibilityService.kt`：可选兼容入口，类名按 Phase 0A 结论决定是否使用 `com.google.android.accessibility.selecttospeak.SelectToSpeakService`；默认不启用。
- `notification/accessibility/PaymentPageDetector.kt`：纯规则，识别微信/支付宝成功页、成功状态、唯一金额、订单号、敏感页拒绝。
- `notification/accessibility/AccessibilitySnapshot.kt`：扩展 `SnapshotNode` 保存 `boundsInScreen`、`windowId`、`className`、`isEditable`、`isPassword`。
- `notification/accessibility/ScreenCaptureService.kt`：`canTakeScreenshot`、窗口归属校验、截图队列；只对通过安全检查的页面截图。
- `ocr/OcrEngine.kt` + `ocr/PaddleOcrEngine.kt` / `ocr/MlKitOcrEngine.kt`：统一接口，Phase 0B 选型。
- `ocr/BillParser.kt`：金额/商户/时间/订单号/退款状态解析；纯函数 + 单测。
- `ocr/MerchantIconCropper.kt` + `MerchantIconStore.kt`：128×128、PNG/WebP、私有目录、失败回退。
- `capture/CaptureCoordinator.kt`：三通道合并、支付身份、去重、候选状态。
- `billimport/WechatBillParser.kt` / `AlipayBillParser.kt` / `BillImportPreview.kt`：官方 CSV/XLSX/文本导入；预览、重复、错误行。
- `data/local/entity/TransactionEntity.kt`：新增 `merchantIconKey`/`merchantIconPath`（本地字段，不上传）。
- `SettingsPreferences`：新增 `successPageCaptureEnabled`（默认 false）、`ocrEngine`、`compatibilityAccessibilityEnabled`（默认 false）。

### 2.4 功能开关与回退

- 成功页 OCR 通道默认关闭；设置页单独开关，明确说明“会读取支付成功页并本地截图/OCR；不上传”。
- 兼容入口默认关闭；开启时提示“这是兼容模式，可能随微信/系统更新失效”，并提供“切回标准无障碍”按钮。
- 任何 OCR/图标失败都只影响该笔候选，不影响通知采集和已有账本。
- 失败/低置信度候选进入待确认，不自动入账。

### 2.5 数据与隐私

- 截图/原图/图标只存应用私有目录；默认不上传；同步只传结构化交易字段。
- 不声明 `INTERNET` 的新增需求；OCR 全本地。
- 密码/验证/可编辑/系统安全窗口跳过；不后台轮询截图。
- 图标涉及品牌商标：仓库只分发通用分类图标和可替换映射示例，不打包未授权品牌资源；用户本机裁剪的图标仅本地使用。

### 2.6 许可证

- 清衡是 GPL-3.0；huayoupu-ledger 是 GPL-3.0-only，代码层面对接/移植是兼容的。
- 若直接移植代码/测试/资源，必须在 NOTICE/README 保留版权与来源说明，并继续以 GPL-3.0 分发。
- OCR 运行时（ONNX Runtime/OpenCV）和模型权重有各自许可证；移植前逐项核对 `third_party/ocr-runtime/licenses` 与 PaddleOCR 模型许可。
- 兼容入口类名是行为兼容技巧，不是复制 Google 代码；但仍需评估商店/微信政策。

### 2.7 用户可见名称与 OCR 补充开关

- 无障碍服务用户可见名称统一为 **「清衡 · 页面识别」**；兼容入口的 `com.google.android.accessibility.selecttospeak.SelectToSpeakService` 只作为内部实现类名，不改变用户看到的名称和说明。
- 设置页新增「页面识别（OCR 补充）」分组，默认关闭，文案明确：
  - 仅在受支持的微信/支付宝**支付成功页**工作；
  - 本地截图 + 本地 OCR，不上传原图；
  - 不后台翻页、不抓历史账单；
  - 关闭后不影响现有通知/短信/手动记账；
  - 需要无障碍与系统截图能力，系统安全窗口/密码/验证页面会跳过。
- 兼容入口单独做一个「微信成功页兼容模式（实验）」开关，默认关闭；文案说明这是兼容模式，可能随微信/系统更新失效，并提供一键切回标准无障碍。
- OCR 版本行：显示引擎/模型名称、版本、是否本地可用；升级由用户触发或随 App 版本更新，不静默下载；模型包做 SHA-256 校验，离线可用。若走下载模型包，复用现有网络能力，不新增不必要的权限。
- 权限/能力不足时设置页显示真实原因和跳转入口（无障碍未授权、截图被系统限制、通知权限关闭等），不要显示“已开启”而实际不可用。

### 2.8 历史账单手动导入

- 入口：账单页「导入账单」和/或设置页；支持微信/支付宝官方导出的 CSV/XLSX/表格文本。
- 导入前预览：有效行、重复行、错误行分开标记；重复检测复用现有交易匹配/去重；用户确认后才写入。
- 保留来源、原始行号和导入批次，便于追溯；退款/失败/待支付行不自动入账。
- 不自动抓取微信/支付宝历史账单列表，不后台翻页。
- 文件通常没有商户 Logo：用本地商户图标映射/分类图标；用户确认商户后可补图标。

### 2.9 商户名采集策略（重点）

结论：**清衡已经能抓到一部分商户名，但不能假设所有页面都能抓到；要按来源置信度合并，抓不到就明确“未知商户”，不能静默编造。**

| 来源 | 现状 | 商户名可靠性 | 用法 |
| --- | --- | --- | --- |
| 支付通知 | 现有 `NotificationParser` 已解析 title/body 并产出 merchant | 高 | 主来源；与页面字段交叉校验 |
| 页面无障碍文本 | 新增成功页识别 | 中高 | 在金额/订单号附近取商户；优先于 OCR |
| OCR | 新增 | 低（对标项目商户精确匹配仅 4/9） | 只做补全/候选；不单独决定商户 |
| 官方 CSV/XLSX | 新增 | 高 | 历史账单主来源 |
| 用户编辑/别名 | 新增 | 最高 | 未知时确认；学习别名，后续自动匹配 |

建议新增 `MerchantResolver`：

1. **收集候选**：通知、页面文本、OCR、文件导入、用户输入；每条保留原始文本和来源。
2. **归一化**：去首尾空白、全半角、支付平台后缀（“支付”“收款方”“商户”）、门店括号/分店名；不要丢掉可辨识的品牌主体。
3. **合并**：
   - 通知与页面文本一致 → 高置信，直接使用；
   - 冲突 → 以通知为主，标记“待确认商户”，在交易行/详情里提示；
   - 只有 OCR 低置信 → 显示“未知商户”，不自动填；
   - 绝不拿金额/分类/来源冒充商户名。
4. **未知处理**：交易行显示“未知商户”+ 来源/分类；点击可编辑、重新扫描或导入文件；提供“记住这个商户”别名学习。
5. **别名表**：本地保存 `raw → canonical`（例如“星巴克咖啡（杭州店）”→“星巴克”），后续同 raw 自动用 canonical；本地-only，不上传。
6. **图标联动**：canonical 商户 → 本地图标；未知/无图标 → 分类图标；用户确认商户后可补图标。图标失败不阻断入账。

验收：

- 20 条通知、20 条成功页、20 条官方导入分别统计商户名准确率；
- 未知商户不误填、不静默使用 OCR 低置信结果；
- 通知/页面冲突有可见“待确认商户”标记；
- 用户确认的别名能在下一笔同商户交易复用；
- 商户名与图标映射一致，图标缺失可回退。

## 3. 对标项目的产品功能清单（用于 UI/功能补齐）

| 能力 | huayoupu-ledger | 清衡现状 | 建议 |
| --- | --- | --- | --- |
| 支付成功页自动识别 | 微信/支付宝，OCR + 截图 + 图标 | 通知/转账无障碍，未覆盖成功页 OCR | 作为可选第三通道 |
| 历史账单导入 | 官方 CSV/XLSX/文本，预览去重 | 未实现文件导入 | 优先做 |
| 摇一摇/截图补记 | 有 | 无 | 可后置 |
| 商户图标自动裁剪 | 成功页截图 128×128 | 无 | Phase 0C + 可选 |
| 退款/原付/实付核对 | 有 | 有退款关联/审核，但无 OCR 原文 | 复用现有审核 |
| 账户/余额联动 | 有 | 账户字段有，余额联动未知 | 后置评估 |
| 预算/统计 | 有 | 有月度/分类预算、统计图表 | UI 重构即可 |
| 标签/待报销 | 有 | 无 | 后置 |
| 备份/恢复 | ZIP + CSV | 后端同步/备份 | 不急于照搬 |
| 本地无网络 | 不声明 INTERNET | 有同步/网络 | 保持现有同步，OCR 本地 |

## 4. Phase 0 探针与移植批次

### Phase 0A：兼容入口 A/B/A（1–2 天）

- 在独立 debug 分支/服务中注册兼容入口类名，保持主服务不变。
- 在用户当前 Xiaomi HyperOS Android 16 + 微信最新版上，打开支付成功页，记录：
  - 普通入口节点数/空根节点；
  - 兼容入口节点数；
  - 是否能看到“支付成功/完成/金额/商户”；
  - 截图是否成功、是否被安全窗口拦截；
  - A/B/A 至少各 5 次。
- **Go/No-Go**：兼容入口能稳定读到成功状态+唯一金额才继续；否则不做兼容入口，只做标准无障碍 + 用户主动截图/文件导入。

### Phase 0B：OCR 选型（2–3 天）

- 用 20 张真实成功页/账单截图，对比：
  - ML Kit Chinese bundled；
  - PP-OCRv6 Tiny（或当前可获得的小模型）；
  - 现有通知字段作为基线。
- 指标：金额/订单号/商户/时间准确率；首帧/暖态延迟；APK 增量；峰值内存；许可证。
- **Go/No-Go**：金额 ≥95%、订单号 ≥90%、商户（页面文本 + OCR 合并）≥90%、包体增量可接受。

### Phase 0C：图标探针（1–2 天）

- 检查微信/支付宝支付通知的 `EXTRA_LARGE_ICON` 是否存在；
- 在成功页截图里测试图标裁剪（128×128）20 次；
- 本地商户图标库覆盖率（星巴克/盒马/地铁/微信转账等）。
- **Go/No-Go**：通知大图标可用或截图裁剪 ≥80% 成功；否则第一版只用本地图标。

### 采集批次（与 UI 批次并行）

- **C1（5–7 天）**：成功页检测 + 安全检查 + 截图 + OCR 金额/订单号 + 候选预览；feature flag。
- **C2（4–6 天）**：支付身份/去重、商户字段合并、图标裁剪/本地存储、资源队列与失败回退。
- **C3（4–5 天）**：微信/支付宝官方 CSV/XLSX/文本导入，预览/重复/错误行，确认写入。
- **C4（2–3 天）**：真机 20 笔验收、隐私/政策检查、文档、开关默认关闭。

## 5. 验收标准（采集线）

| 项目 | 标准 |
| --- | --- |
| 成功页识别 | 20 笔真实支付：金额 ≥95%，订单号 ≥90%，商户（页面文本+OCR）≥90% |
| 图标 | 截图裁剪 ≥80% 成功，失败回退默认图标；不阻断入账 |
| 去重 | 同一支付不因窗口重建/服务重连重复入账；与通知通道重复率 0 |
| 安全 | 密码/验证/可编辑/安全窗口/历史账单详情不截图、不入账 |
| 失败处理 | 低置信度进入待确认；OCR/图标失败不影响通知/手动账本 |
| 隐私 | 数据本地；不上传原图；开关默认关闭；用户可撤回 |
| 兼容 | A/B/A 证据；兼容入口失效时可回退标准无障碍/文件导入 |
| 政策 | 评估微信/Google Play 无障碍政策；不把 hack 作为唯一通道 |

## 6. 结论

- **架构可以照搬，产品边界要更保守。**
- 清衡的优势是已有通知/短信/同步/去重/审核；不要为了 OCR 把现有可靠通道换掉，而是加一条**可选的、用户确认的成功页 OCR 通道**。
- 历史账单优先官方文件导入；商户图标优先本地图标，成功页裁剪作为增强。
- 兼容入口是“能不用就不用”的实验特性；先做 Phase 0A 的 A/B/A 证据，再决定是否保留。

## 7. 参考

- [huayoupu-ledger](https://github.com/AldenHe/huayoupu-ledger)
- [huayoupu REFERENCE：自动记账覆盖范围/OCR/兼容入口](https://github.com/AldenHe/huayoupu-ledger/blob/main/.github/REFERENCE.md)
- [huayoupu 兼容入口 SelectToSpeakService](https://github.com/AldenHe/huayoupu-ledger/blob/main/app/src/main/java/com/google/android/accessibility/selecttospeak/SelectToSpeakService.kt)
- [huayoupu 无障碍配置](https://github.com/AldenHe/huayoupu-ledger/blob/main/app/src/main/res/xml/accessibility_service.xml)
- [huayoupu 图标裁剪 MerchantIcon](https://github.com/AldenHe/huayoupu-ledger/blob/main/app/src/main/java/net/ankio/ledger/ocr/MerchantIcon.kt)
- [Android AccessibilityService.takeScreenshot](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshot(int,%20java.util.concurrent.Executor,%20android.accessibilityservice.AccessibilityService.TakeScreenshotCallback))
- [Android Notification.EXTRA_LARGE_ICON](https://developer.android.com/reference/android/app/Notification#EXTRA_LARGE_ICON)
