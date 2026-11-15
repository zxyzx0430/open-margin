# 清衡 Android 分类与学习开发规格

- 日期：2026-11-16
- 状态：设计规格；已按 `v1.2.0-rc.10` 现状校正；页面记忆仅作为评估结论，不进入主方案
- 基线：`main` / `v1.2.0-rc.10`（versionCode 15）
- 范围：Android 端交易自动分类、用户修正学习、与通知/短信/无障碍/账单导入四条采集路径的接入
- 关联代码：
  - [CategoryClassifier.kt](../android/app/src/main/java/com/bookkeeping/app/notification/CategoryClassifier.kt)
  - [MerchantRules.kt](../android/app/src/main/java/com/bookkeeping/app/data/merchant/MerchantRules.kt)
  - [NotificationParser.kt](../android/app/src/main/java/com/bookkeeping/app/notification/NotificationParser.kt)
  - [SmsParser.kt](../android/app/src/main/java/com/bookkeeping/app/notification/SmsParser.kt)
  - [BillImportMapping.kt](../android/app/src/main/java/com/bookkeeping/app/ui/screen/billimport/BillImportMapping.kt)
  - [PageRecognitionService.kt](../android/app/src/main/java/com/bookkeeping/app/notification/accessibility/PageRecognitionService.kt)
  - [PaymentCardDetector.kt](../android/app/src/main/java/com/bookkeeping/app/notification/accessibility/PaymentCardDetector.kt)
  - [PageActivityGuard.kt](../android/app/src/main/java/com/bookkeeping/app/notification/accessibility/PageActivityGuard.kt)
  - [CaptureReviewMapping.kt](../android/app/src/main/java/com/bookkeeping/app/ui/screen/capture/CaptureReviewMapping.kt)
  - [MerchantIconCatalog.kt](../android/app/src/main/java/com/bookkeeping/app/data/merchant/MerchantIconCatalog.kt)
  - [TransactionEntity.kt](../android/app/src/main/java/com/bookkeeping/app/data/local/entity/TransactionEntity.kt)
- 关联文档：[产品迭代路线图](PRODUCT_ITERATION_ROADMAP.md) · [Android UI 重构与微信账单导入](ANDROID_UI_REFRESH_AND_WECHAT_IMPORT_PLAN.md)

## 0. 结论先行

1. **分类的最终形态不是一张更大的关键词表，而是一条统一流水线：统一输入 → 商户归一化 → 分层规则 → 结算门 → 用户修正学习。** 分类器只是中间一层。
2. **分类规则要可解释、可排序、可覆盖。** 借鉴 Actual Budget 的“从宽到具体 + 后规则覆盖前规则 + pre/default/post 阶段”，以及 Firefly III 的“条件 + 动作 + stop processing”，而不是继续维护一个顺序敏感的首命中列表。
3. **学习闭环是准确率的分水岭。** 用户改一次分类，下一次同一商户应自动学会；学习必须可见、可编辑、可按商户/全局关闭。参考 Actual Budget 的自动规则和 smart_importer 的“本地训练 + 人工复核 + 修正回流”。
4. **先做确定性层，再做学习，最后才考虑本地模型。** 不内置 LLM、不默认上云；本地 ML 只作为低置信度时的建议，不替代确定性规则，也不自动入账。
5. **分类不能脱离状态机单独做。** 先用后付“已生成/已取消”、待确认转账、退款冲正必须先确定是否 final，再决定要不要分类和入账。
6. **免 Root 是当前基线，采集门先于分类。** 通知、短信、无障碍节点、非 secure 截图和官方导入已经覆盖大部分数据；WebView 私有 JS/数据库和 secure 页面不承诺，Root/LSPosed/LSPatch 直采只作为后续可选通道，不作为普通 APK 的前置条件。

## 1. 目标与非目标

### 1.1 目标

- 同一个商户在通知、短信、无障碍扫描、账单导入、手动输入里得到一致的分类。
- 所有自动分类都能回答“为什么”：`category + confidence + ruleId + reason`。
- 用户修正一次后，同商户的下一次自动分类优先使用学习结果；学习可关闭、可清除、可回滚。
- 具体短语优先于品牌词，避免 `Apple Music`、`美团买药`、`话费充值` 这类稳定误判。
- 为后续本地 ML/AI 建议层留出接口，但不把模型作为第一版依赖。

### 1.2 非目标（本规格不承诺）

- 不在本轮修改 `TransactionEntity` 表结构或引入数据库迁移。
- 不在本轮实现用户可见的规则编辑器、学习管理页或分类体系大改版。
- 不实现跨设备同步学习结果；学习数据默认只留本机。
- 不内置 LLM、不调用云端 AI 分类；与 [产品迭代路线图](PRODUCT_ITERATION_ROADMAP.md) 的“当前不内置 LLM”保持一致。
- 不保证自动分类 100% 准确；目标是把可解释的确定性覆盖率做高，把不确定的送进复核。

## 2. 现状盘点

> 本节按 `v1.2.0-rc.10` 的 main 代码校正。rc.10 已经修过一轮分类器和采集页面守卫，旧版规格里“首命中关键词表”“扫描固定 OTHER”的描述已不再成立。

### 2.1 当前分类器

[CategoryClassifier.kt](../android/app/src/main/java/com/bookkeeping/app/notification/CategoryClassifier.kt) 当前的执行顺序是：

```text
1. MerchantRules.categoryFor(merchant)        // 用户修正/学习规则，最高优先
2. bestMatch(merchant)                        // 商户字段内最长关键词
3. bestMatch(contextText)                     // 上下文内最长关键词
4. 只有上下文关键词比商户关键词更长，
   且上下文包含商户关键词时，才允许上下文胜出
5. 都不命中 → OTHER
```

特点：

- 分类枚举已扩展为 15 个：`FOOD / TRANSPORT / SHOPPING / ENTERTAINMENT / HOUSING / UTILITIES / HEALTH / EDUCATION / SALARY / BONUS / INVESTMENT / INSURANCE / GIFT / TRANSFER / OTHER`。
- 同一字段内按关键词长度优先，分类顺序只在长度相同时兜底；`Apple Music`、`美团买药`、`京东健康`、`话费充值`、`万达广场` 已靠更长关键词修正。
- 已有负向规则：红包抵扣/优惠/封面不算转账；`基金会/公益基金` 不算理财。
- 已有用户商户规则 [MerchantRules.kt](../android/app/src/main/java/com/bookkeeping/app/data/merchant/MerchantRules.kt)：手动改分类或确认扫描结果时写入 DataStore，下次同商户优先使用。
- 已有 [CategoryClassifierTest.kt](../android/app/src/test/java/com/bookkeeping/app/notification/CategoryClassifierTest.kt)，覆盖用户规则优先、最长关键词、上下文补齐、商户优先、对抗长文本、红包优惠负向规则。

仍存在的限制：

- 输入仍只有 `merchant + contextText`，没有 `direction / kind / source / status / sourceCategory`。
- 仍是子串查找，不是规则对象；没有 `specificity`、阶段（PRE/DEFAULT/POST）、`stop`、条件反转。
- 短英文/数字没有词边界，订单号里的 `T3`、`1688` 仍可能误命中。
- `MerchantRules.normalize` 只做去空白、转小写、去括号内容；没有渠道前缀、卡尾号、分店后缀、别名归一。
- 用户规则是单条 `merchant → category` 映射，没有多票学习、阈值、按商户关闭、冲突回退。
- 退款/转账/提现的 POST 安全语义在 `CaptureReviewMapping`、通知/导入路径里，不在分类器内部；直接调用分类器时仍可能把“麦当劳退款”归为 FOOD。

### 2.2 四条采集路径的分类现状

| 路径 | 当前分类方式 | 问题 |
| --- | --- | --- |
| 通知 | [NotificationParser.kt](../android/app/src/main/java/com/bookkeeping/app/notification/NotificationParser.kt) 调用 `CategoryClassifier.classify(merchant, full)`；收到的红包单独归 `GIFT` | 已解析出的方向/kind 没有传给分类器 |
| 银行短信 | [SmsParser.kt](../android/app/src/main/java/com/bookkeeping/app/notification/SmsParser.kt) 调用 `CategoryClassifier.classify("", normalized)` | 没有商户，只能靠正文；消费短信通常落 OTHER |
| 账单导入 | [BillImportMapping.kt](../android/app/src/main/java/com/bookkeeping/app/ui/screen/billimport/BillImportMapping.kt) 先按商户分类，只有商户为空或泛化名称时才用上下文，最后才用图标目录兜底 | 官方账单自带的“交易分类”列尚未进入 `BillImportRow`；图标目录仍是一层粗兜底 |
| 无障碍/OCR 扫描 | 自动入账时调用 `CategoryClassifier.classify(merchant, contextText)`；`CaptureReviewMapping` 对退款/转账/提现/红包做类型覆盖 | 复核弹窗的 PAYMENT 默认分类仍是 OTHER；扫描路径的 direction/kind 没有完整进入分类器 |

### 2.3 仍存在的误判风险

这些是 rc.10 之后仍然可能出现的，不是已经修掉的旧问题：

| 输入 | 当前结果 | 期望 | 原因 |
| --- | --- | --- | --- |
| `小米粥`、`小米椒` | SHOPPING | FOOD | `小米` 是品牌词，没有“手机/家电”上下文门 |
| 订单号/卡号包含 `T3`、`1688` | TRANSPORT / SHOPPING | OTHER 或正确分类 | 短英文/数字没有词边界 |
| `麦当劳退款` 直接调用分类器 | FOOD | 退款语义/OTHER | 分类器没有 kind/status 输入 |
| 无商户的银行消费短信 | OTHER | OTHER + 低置信度 | 没有商户和上下文，属于合理低信息 |
| `Apple` 单独出现、`万达` 单独出现 | SHOPPING / ENTERTAINMENT | 视上下文 | 品牌词和场景词仍可能互相覆盖 |

已在 rc.10 修复并有测试覆盖的旧误判：`Apple Music`→ENTERTAINMENT、`话费/流量充值`→UTILITIES、`美团买药`→HEALTH、`京东健康`→HEALTH、`万达广场`→SHOPPING。

### 2.4 现有测试

- [CategoryClassifierTest.kt](../android/app/src/test/java/com/bookkeeping/app/notification/CategoryClassifierTest.kt)：用户规则优先、最长关键词、上下文补齐、商户优先、对抗长文本、红包优惠负向规则。
- [NotificationParserTest.kt](../android/app/src/test/java/com/bookkeeping/app/NotificationParserTest.kt)：麦当劳→FOOD、收到转账→TRANSFER、美宜佳→SHOPPING 等。
- [SmsParserTest.kt](../android/app/src/test/java/com/bookkeeping/app/SmsParserTest.kt)：工行消费→OTHER、建设银行工资到账→SALARY、中国银行转入→TRANSFER。

任何分类器改造都必须保留这些现有断言，或明确说明为什么需要调整。

## 3. 外部对标

| 项目 | 与分类/学习直接相关的做法 | 本规格借鉴点 |
| --- | --- | --- |
| **Firefly III** | 规则 = 触发条件 + 动作；strict（ALL）与非 strict（ANY）；条件可反转；支持命中后停止处理后续规则/触发器/动作；可对历史交易批量应用 | 规则是数据，不是代码；用 stop processing 表达优先级 |
| **Actual Budget** | 交易按规则顺序执行，所有条件满足才执行动作；后面的规则覆盖前面的；规则按“从宽到具体”自动排序（`is` 高于 `contains`）；pre/default/post 三阶段；用户改分类后自动生成“商户→分类”规则，可按商户/全局关闭学习 | 特异性排序 + 阶段 + 用户修正学习 |
| **AutoAccounting** | 系统/用户规则存数据库，按 app + 数据来源 + sort 排序、可启停，编译成 JS；规则支持关键词、正则、金额提取；商户名先做 `shopNameSanitized` 清洗；分类映射用精确匹配，输出“父类 - 子类” | 规则外置、商户归一化、来源分类精确映射、层级分类 |
| **beancount smart_importer** | 本地 scikit-learn SVC，用历史账目训练，预测账户/收款方；支持 jieba 中文分词；预测必须人工复核，用户修正继续作为训练数据；全程本地 | 本地模型只做 fallback/建议，不自动入账；修正回流 |

参考链接：

- [Firefly III — How to use rules](https://docs.firefly-iii.org/how-to/firefly-iii/features/rules/)
- [Actual Budget — Rules](https://actualbudget.org/docs/budgeting/rules/)
- [AutoAccounting](https://github.com/AutoAccountingOrg/AutoAccounting)
- [AutoAccounting CategoryProcessor.kt](https://github.com/AutoAccountingOrg/AutoAccounting/blob/master/server/src/main/java/org/ezbook/server/tools/CategoryProcessor.kt)
- [AutoAccounting RuleGenerator.kt](https://github.com/AutoAccountingOrg/AutoAccounting/blob/master/server/src/main/java/org/ezbook/server/engine/RuleGenerator.kt)
- [beancount/smart_importer](https://github.com/beancount/smart_importer)

## 4. 分类器目标设计

### 4.1 输入契约

分类器不应再只接收 `merchant + contextText`。建议引入一个纯数据输入对象，所有采集路径都构造它：

```kotlin
data class ClassificationInput(
    val merchantRaw: String,
    val contextText: String,
    val direction: TransactionType,          // EXPENSE / INCOME
    val source: TransactionSource,           // WECHAT / ALIPAY / BANK_SMS / MANUAL ...
    val kind: TransactionKind,               // PAYMENT / TRANSFER / REFUND / WITHDRAWAL / RED_PACKET / PAY_AFTER_USE / BILL
    val status: TransactionStatus,            // COMPLETED / PENDING / CREATED / CANCELLED / UNKNOWN
    val sourceCategory: String? = null,       // 微信/支付宝账单自带分类，如“餐饮美食”
    val amountCents: Long? = null,
)

data class ClassificationResult(
    val category: TransactionCategory,
    val confidence: Float,
    val ruleId: String,
    val reason: String,
    val alternatives: List<Pair<TransactionCategory, Float>> = emptyList(),
)
```

兼容策略：

- 保留现有 `classify(merchant, contextText): TransactionCategory` 作为便捷入口，内部转成 `ClassificationInput`，避免一次性改所有调用方。
- 新增 `classify(input): ClassificationResult`；NotificationParser、SmsParser、BillImportMapping、CaptureReviewMapping 逐步迁移。
- `kind`/`status` 可直接复用支付卡片检测器的 `PaymentCardKind`/`PaymentCardStatus`，通知/短信路径用映射后的通用枚举。

### 4.2 商户归一化

分类前先得到稳定的 `merchantKey`，原始文本只用于展示和证据：

1. **字符归一化**：全角数字/字母/标点转半角；英文转小写；连续空白和分隔符折叠。
2. **支付渠道前缀剥离**：`支付宝-`、`微信支付-`、`财付通-`、`银联-`、`云闪付-` 等只作为渠道，不进入商户 key。
3. **银行流水噪声剥离**：`*5C7QC7MH0`、`-1234`、`（1234）`、卡尾号、订单号、日期时间、`第N店`、`xx分店`、`xx广场店` 等后缀。
4. **品牌别名归一**：维护 `alias → canonical` 表，例如 `ALIPAY*`、`财付通`、常见英文/中文品牌变体。
5. **保留原始展示名**：`merchantRaw` 不改写，界面展示仍用原文；`merchantKey` 只用于规则/学习匹配。
6. **不要过度剥离**：`美团买菜`、`美团买药` 中的业务词必须保留；只有渠道前缀、纯编号、纯分店后缀可以剥离。

建议的输出：

```kotlin
data class NormalizedMerchant(
    val merchantKey: String,     // 稳定匹配键，如 "美团买药"
    val displayName: String,     // 展示名，如 "美团买药(望京店)"
    val aliases: List<String>,   // 命中过的别名/原始变体
)
```

### 4.3 规则模型

把现有 `RULES` 改成可排序、可覆盖、可持久化的规则对象：

```kotlin
enum class RuleStage { PRE, DEFAULT, POST }
enum class RuleCreator { SYSTEM, USER, LEARNED }

data class CategoryRule(
    val id: String,
    val stage: RuleStage,
    val specificity: Int,            // 越大越具体，后执行
    val conditions: List<Condition>, // 默认全部满足（AND）
    val category: TransactionCategory,
    val stop: Boolean = false,       // 命中后是否停止本阶段后续规则
    val creator: RuleCreator,
    val enabled: Boolean = true,
    val version: Int = 1,
)

sealed interface Condition {
    data class MerchantIs(val value: String) : Condition
    data class MerchantContains(val value: String) : Condition
    data class MerchantRegex(val pattern: String) : Condition
    data class ContextContains(val value: String) : Condition
    data class DirectionIs(val value: TransactionType) : Condition
    data class SourceIs(val value: TransactionSource) : Condition
    data class KindIs(val value: TransactionKind) : Condition
    data class AmountIn(val minCents: Long, val maxCents: Long) : Condition
    data class SourceCategoryIs(val value: String) : Condition
}
```

关键语义：

- **条件默认 AND**；需要 OR 时拆成多条规则，保持可解释。
- **拉丁/数字关键词必须有词边界**；中文关键词按子串匹配。短词 `T3`、`1688` 不应命中订单号中间的片段。
- **规则只声明“匹配到什么”，不直接写数据库**；分类结果是纯函数输出。
- **规则可持久化**：系统规则、用户规则、学习规则都存本地；用户规则和学习规则可启停、可删除。

### 4.4 分层与冲突解决

最终分类按以下层执行：

| 层 | 内容 | 示例 | 置信度上限 |
| --- | --- | --- | --- |
| **L0 用户锁定** | 用户明确设置/学习的 `merchantKey → category` | 用户把“美团买菜”固定为 SHOPPING | 0.99 |
| **L1 高特异性短语** | 多词、场景明确、优先级高于品牌词 | `美团买药`→HEALTH、`话费充值`→UTILITIES、`Apple Music`→ENTERTAINMENT、`万达影城`→ENTERTAINMENT | 0.95 |
| **L2 品牌/通用关键词** | 品牌名、通用场景词 | 麦当劳→FOOD、星巴克→FOOD、滴滴→TRANSPORT | 0.85 |
| **L3 上下文** | 方向、金额、来源、交易类型、账单自带分类 | `direction=INCOME && context=工资`→SALARY；`kind=REFUND`→退款处理 | 0.75 |
| **L4 本地模型** | 可选建议层 | 历史账目训练的本地分类器 | 0.60 |

冲突解决建议：

1. 先执行 `PRE`：商户归一化、用户锁定、学习映射、来源分类精确映射。
2. 再执行 `DEFAULT`：系统规则按 `specificity` 从低到高执行；同 specificity 按规则 id 稳定排序；后面的规则可覆盖前面的分类。
3. 最后执行 `POST`：安全兜底。`TRANSFER`、`REFUND`、`WITHDRAWAL` 不允许被普通消费规则覆盖；除非存在用户锁定或明确的内部转账规则。
4. `stop=true` 用于“命中即确定”的规则；默认不 stop，保留后续更具体规则覆盖的机会。
5. 所有命中记录 `ruleId + reason`，供复核界面和日志使用。

> 这里借鉴 Actual Budget 的“具体规则后执行、后执行覆盖”，而不是 Firefly III 的纯 stop 链。原因是我们的规则数量会增长，靠人工维护顺序容易回归；让 specificity 决定顺序更稳。

### 4.5 置信度与输出

置信度不是统计概率，而是**确定性层级的策略值**，必须稳定、可解释：

| 来源 | 建议值 | 说明 |
| --- | ---: | --- |
| 用户锁定/学习且一致 | 0.99 | 用户明确选择，最高优先级 |
| 精确商户规则 / 高特异性短语 | 0.95 | `merchant is` 或多词场景短语 |
| 品牌/通用关键词 | 0.85 | 单一品牌词命中 |
| 上下文推断 | 0.75 | 方向、金额、来源、账单分类 |
| 本地模型建议 | 0.60 | 只作为 suggestion |
| 未命中 | 0.00 | OTHER |

输出 `alternatives`（前 2-3 个分类及分数），供复核界面展示“为什么不是另一个分类”。

### 4.6 规则示例

| 优先级 | 条件 | 分类 | 说明 |
| --- | --- | --- | --- |
| L1 | `merchant contains 美团买药` 或 `context contains 买药` | HEALTH | 高于 `美团` 的 FOOD |
| L1 | `context contains 话费充值` / `流量充值` | UTILITIES | 高于 `充值` 的 ENTERTAINMENT |
| L1 | `context contains Apple Music` / `Apple TV` | ENTERTAINMENT | 高于 `Apple` 的 SHOPPING |
| L1 | `merchant contains 万达影城` | ENTERTAINMENT | 高于 `万达广场` 的 SHOPPING |
| L1 | `merchant contains 万达广场` | SHOPPING | 与影城区分 |
| L2 | `merchant contains 麦当劳` / `KFC`（词边界） | FOOD | 品牌词 |
| L2 | `merchant contains 小米` 且 `context contains 手机/平板/家电` | SHOPPING | 避免 `小米粥` |
| L3 | `direction=INCOME` 且 `context contains 工资/薪资` | SALARY | 收支方向参与分类 |
| L3 | `kind=TRANSFER` | TRANSFER | 交易类型兜底 |
| POST | `kind=REFUND` | OTHER/退款处理 | 不允许被 FOOD 等覆盖 |

## 5. 学习闭环

### 5.1 学习信号

按可信度从高到低：

1. **用户对自动捕获交易修改分类**（最强信号）。
2. **账单导入时用户修改分类并确认**。
3. **用户手动新建交易并选择分类**（弱信号，仅用于同商户）。
4. **用户连续接受自动分类而不修改**（很弱，可选；不能单独作为强证据）。
5. **用户删除/忽略候选**：不直接用于分类学习，只记录为噪声/重复信号。

学习只针对 `COMPLETED` 且有稳定 `merchantKey` 的交易；涉及 `TRANSFER`、`REFUND`、`WITHDRAWAL` 的内部资金移动不进入“消费分类”学习，避免污染。

### 5.2 存储模型

建议本地存储（DataStore 或 Room，二选一，优先 Room 便于查询）。按 `(merchantKey, category)` 存多行，才能做多数投票：

```kotlin
data class CategoryLearning(
    val merchantKey: String,
    val category: TransactionCategory,   // 与 merchantKey 组成联合键
    val positiveCount: Int,
    val lastSeenMs: Long,
    val lastSource: TransactionSource,
    val updatedAtMs: Long,
)

data class MerchantLearningState(
    val merchantKey: String,
    val disabled: Boolean = false,       // 按商户关闭学习
    val updatedAtMs: Long,
)
```

- `merchantKey` 是归一化后的稳定键，不是原始商户名。
- 只存分类结果和计数，不存通知原文、金额、截图。
- 默认不参与同步；如未来要同步，只同步 `merchantKey + category` 的脱敏映射，并给用户开关。

### 5.3 应用策略

1. 用户第一次修改：记录 1 次，不立即全局覆盖；下一次同商户自动分类时作为 L0 候选。
2. 连续 2 次一致修正（默认阈值，可配置）：视为稳定映射，L0 直接使用，置信度 0.99。
3. 出现不一致修正：查询该 `merchantKey` 的全部 `CategoryLearning` 行，按最近 N 次（建议 5 次）和 `positiveCount` 做多数投票；票数相同或没有多数时回退到确定性规则，不猜。
4. 学习映射与系统规则冲突时：用户映射优先；但 `kind=REFUND/TRANSFER/WITHDRAWAL` 的 POST 安全规则仍可阻止错误入账。
5. 每次应用学习映射时输出 `ruleId = "learned:<merchantKey>"` 和 `reason`，便于复核和回滚；`MerchantLearningState.disabled=true` 时跳过该商户的学习行。

可选进阶：达到阈值后生成一条 `creator=LEARNED` 的 `CategoryRule`，用户可在规则列表中编辑/停用；第一版建议只做内存/DB 映射，不自动生成大量规则。

### 5.4 用户控制

- 按商户关闭学习：写入 `MerchantLearningState.disabled=true`，保留历史行但不再应用。
- 全局关闭/开启学习：设置项；关闭后不读不写学习映射。
- 清除学习数据：按商户或全部清除，需确认。
- 所有学习结果可编辑：用户改成别的分类时，更新映射而不是新增冲突记录。

### 5.5 本地模型（远期，可选）

- 只在确定性层置信度低于阈值且用户未锁定分类时使用。
- 训练数据：用户确认过的 `merchantKey + 分类` 历史，不直接使用原始通知文本。
- 模型：本地轻量分类器（如朴素贝叶斯/线性模型/SVC），中文可先分词；具体选型另立实验。
- 输出只作为 `alternative`/建议，不自动入账；用户修正继续作为训练标签。
- 隐私：训练和推理都在本机，不上传原始文本/金额/截图。
- 与产品路线图一致：当前不内置 LLM；模型实验不得默认进入正式版本。

### 5.6 质量指标

上线前后都要能回答：

- **覆盖率**：非 OTHER 自动分类占比。
- **自动入账准确率**：自动入账且未被用户修改的比例。
- **修正率**：按分类、规则、来源统计用户改分类的比例。
- **复核率**：进入复核的候选占比。
- **规则命中分布**：每条规则的命中量、修正率；长期高修正率规则降级。
- **新旧差异**：shadow 模式下旧分类器 vs 新分类器的差异清单，人工抽查后再启用。

指标只记录 `ruleId/category/confidence/source`，不记录原始文本。

## 6. 采集门、结算门与状态机的边界

分类器不决定“是否采集”，采集门决定；分类器也不决定“是否入账”，结算门决定。三层必须分开：

```text
页面形态守卫（PageActivityGuard）
  → 页面是否值得解析（强证据 / 已确认页面）
  → 卡片 / OCR 解析
  → 结算门（final、金额唯一、重复证据、来源可信度）
  → 分类器（category + confidence + ruleId + reason）
  → AUTO_BOOK / REVIEW / PENDING / IGNORE
```

### 6.1 采集门（rc.10 已落地）

- [PageActivityGuard.kt](../android/app/src/main/java/com/bookkeeping/app/notification/accessibility/PageActivityGuard.kt)：微信聊天列表、聊天页、通讯录、搜索、服务号、文章页，以及支付宝首页/消息/聊天页，在进入卡片解析前直接拦截。
- 小程序/H5 容器只有在页面同时出现强成功状态和金额时才放行；聊天列表里“已支付 ¥2.00”这类弱预览不再成立。
- 群收款/活动收款页没有本人强成功支付时整页拦截；`你无需支付/他人已支付/代付` 作为负向标记。
- 同屏多张完成卡按历史账单列表处理，不批量入账。
- 这些规则是“形态 + 强证据”，不依赖用户先记住页面。

### 6.2 页面记忆的评估结论

AutoAccounting 的“记住此页面”在它的场景里是必要的：它面向任意 App、任意页面，无法靠固定 Activity 和固定文案覆盖。对我们的场景价值有限：

- 我们只覆盖微信/支付宝，支付结果页的 Activity 集合有限，可用静态 allowlist + 强成功证据覆盖。
- 页面结构签名会随微信/支付宝大版本更新失效，导致“用户已经记住但不再采集”的静默漏记。
- 首次使用多一步确认，和“自动记账”的目标冲突。
- 我们已经有 rc.10 的页面形态守卫，页面记忆不是修复误记的必要条件。

因此：**页面结构签名不作为主采集门，也不进入第一阶段。** 如果后续出现“同一 Activity 下多种页面形态、强证据又不足以区分”的真实案例，再把它作为诊断/兜底手段评估；它不替代 6.1。

### 6.3 结算门

```text
分类器输出：category + confidence + ruleId + reason
结算门输入：分类结果 + 金额置信度 + final 状态 + 重复证据 + 来源可信度
结算门输出：AUTO_BOOK / REVIEW / PENDING / IGNORE
```

- `status != COMPLETED`（先用后付已生成/已取消、待确认转账等）→ 只建 pending event，不分类入账。
- `kind` 为 `REFUND/TRANSFER/WITHDRAWAL` → 按内部资金移动处理，不能仅凭商户关键词归到 FOOD/SHOPPING。
- 金额歧义、多个卡片候选、低置信度分类 → 进复核，附 `alternatives`。
- 自动入账门槛建议：`category.confidence >= 0.85` 且 `isFinal` 且金额唯一且无重复疑点；0.60-0.85 进复核。

## 7. 四条采集路径的接入

### 7.1 通知

- `NotificationParser` 构造 `ClassificationInput(merchantRaw, full, direction, source=WECHAT/ALIPAY, kind=PAYMENT/TRANSFER/REFUND/RED_PACKET/..., status=COMPLETED)`。
- 把解析出的收支方向传给分类器；`收到退款` 不能因为商户是“麦当劳”就归 FOOD。

### 7.2 银行短信

- `SmsParser` 也构造 `ClassificationInput`；`merchantRaw` 为空时主要靠 `contextText`、`direction`、`source=BANK_SMS`。
- 纯“消费人民币25元”没有商户的短信，分类应为 OTHER + 低置信度，而不是强行猜。

### 7.3 账单导入

- 优先使用官方账单自带分类列（支付宝“交易分类”等）；做**精确映射表**，不要用子串替换。
- 没有分类列时，用 `merchant + direction + sourceCategory` 调分类器。
- `MerchantIconCatalog` 继续只负责图标，不再作为分类来源。
- 预览结果分为：`VALID`（可导入）、`DUPLICATE`（已存在，其中 `enrichable=true` 的可补齐）、`SKIPPED`（0 元授权、失败/关闭/待处理等非交易）、`ERROR`（时间/金额/关键字段无法解析）。
- “不计收支”按账户内部流动入账：保留金额、商户、单号和来源分类，分类为 `TRANSFER`，状态为 `transfer`，统计排除；不强行猜成消费或收入。
- 重复行不再直接丢弃：按单号精确匹配或金额/来源/时间窗口模糊匹配旧记录；只补空字段（商户、单号、支付方式、OTHER→已知分类），不覆盖手工记录和已有商户/分类，不新增一笔。
- 确认时新插入与补齐旧记录在同一个 Room 事务中完成。

### 7.4 无障碍/OCR 扫描

- 自动入账路径已经用 `PaymentCardDetection` 的 `merchantCandidates/direction/kind/status` 构造输入，并在 `PageRecognitionService` 调用 `CategoryClassifier.classify(merchant, contextText)`；`CaptureReviewMapping` 负责退款/转账/提现/红包的类型覆盖。
- 复核弹窗（[CaptureReviewDialog.kt](../android/app/src/main/java/com/bookkeeping/app/ui/screen/capture/CaptureReviewDialog.kt)）的 PAYMENT 默认分类仍是 `OTHER`，这是下一步要统一的地方：默认值应来自分类器，并展示 alternatives。
- 免 Root 的数据源边界：

| 通道 | 免 Root | 能看到什么 | 看不到什么 |
| --- | --- | --- | --- |
| 通知 | ✅ | 微信/支付宝/银行通知标题与正文 | 页面上的其他卡片 |
| 短信 | ✅ | 银行消费、工资、转入短信 | 非短信来源 |
| 无障碍节点 | ✅ | 原生控件文本、部分 WebView 子节点 | 不暴露节点的 WebView、canvas/Surface 内容 |
| 无障碍截图 + OCR | ✅ | 非 `FLAG_SECURE` 页面的像素 | `FLAG_SECURE` 页面、截图 API 失败的安全窗口 |
| 官方账单导入 | ✅ | 微信/支付宝 CSV/XLSX 全量历史 | 实时性 |

- Shizuku 是 adb 级 shell，不是读取 WebView 的通道；它只能用于自动开启无障碍、执行 `screencap` 等 shell 操作，不能读 App 私有数据库或 JS bridge。

- 下一项低风险改进：在 [accessibility_page_recognition_config.xml](../android/app/src/main/res/xml/accessibility_page_recognition_config.xml) 增加 `flagRequestEnhancedWebAccessibility`。它让 WebView 尽量把子节点暴露给无障碍树；`ScreenSnapshot` 已经递归遍历子节点，不需要改解析器。若页面仍不暴露节点，继续走截图 + PaddleOCR/ML Kit 回退。
- 不把 `flagRequestEnhancedWebAccessibility` 当作“能读所有 WebView”的承诺：是否暴露由 WebView/宿主 App 决定，微信/支付宝版本、页面实现、安全策略都会影响。
- 页面结构签名（AutoAccounting 的“记住此页面”）不作为本路径的主方案，理由见 6.2。它只解决“何时自动触发 OCR”，不解决“能不能看到内容”；在我们的两 App 场景里收益低于维护成本。
- 真正的 WebView 私有数据（JS bridge、数据库、网络响应）免 Root 拿不到；Root/LSPosed 或 LSPatch 直采放到后续可选通道，见第 12 节。

### 7.5 手动输入

- 用户手动选择的分类是 ground truth；同商户后续自动分类可参考，但需达到学习阈值。
- 手动输入不触发“自动入账”，只更新学习信号。

## 8. 迁移与落地阶段

> 2026-11-16：阶段 0–P3 已按本规格实现，当前状态与验证结果见第 13 节。以下保留分阶段设计，供后续维护和 P4/P5 参考。

### 阶段 0：建立基线与金标准

- 为现有 `CategoryClassifier` 增加 characterization tests，固定当前行为，避免改造时无意回归。
- 收集/脱敏一批真实通知、短信、账单、扫描样本，建立 `golden` 语料（只存必要字段，不存完整 PII）。
- 目标：知道当前分类器的覆盖率、OTHER 比例、典型误判清单。

### 阶段 0.5：采集门与增强 WebView（无 Root）

- 在 `accessibility_page_recognition_config.xml` 增加 `flagRequestEnhancedWebAccessibility`，验证微信/支付宝 WebView 子节点是否更多暴露。
- 用真机分别记录三类页面的覆盖率：节点可直接解析、节点不足需 OCR、`FLAG_SECURE` 直接跳过。
- 保持 rc.10 的 `PageActivityGuard` 和单卡限制，新增聊天列表、搜索页、群收款页的回归用例。
- 不引入“记住此页面”作为主流程；如果真机发现某个高价值 WebView 页面既无强证据、又必须自动采集，再单独评估诊断型页面签名。
- 不改分类器、不改数据库 schema。

### 阶段 1：确定性分类器 v2

- 引入 `ClassificationInput`、`CategoryRule`、`RuleStage`、`specificity`、商户归一化。
- 实现 L1 短语规则、L2 品牌规则、L3 上下文规则、POST 安全规则。
- 保留旧 `classify(merchant, contextText)` 重载，内部转换；所有现有测试必须通过。
- 新增 `CategoryClassifierTest`，覆盖本规格第 2.3 节的误判样例。
- 不涉及数据库 schema、不涉及 UI、不涉及同步。

### 阶段 2：学习闭环

- 在现有 `MerchantRules`（单条 `merchant → category`、DataStore 持久化）上升级为 `CategoryLearning` / `MerchantLearningState`；保留旧规则读取兼容。
- 增加多票/阈值/按商户关闭/冲突回退；用户第一次修改仍只记录，不立即全局覆盖。
- 在交易编辑/复核确认处记录用户修正；应用 L0 映射。
- 设置页在现有“商户分类记忆”基础上增加学习开关/清除学习。
- 学习先默认开启但只影响分类建议；自动入账仍受结算门约束。

### 阶段 3：四路径统一

- NotificationParser、SmsParser、BillImportMapping、CaptureReviewMapping 全部改走 `ClassificationInput`。
- 账单导入接入来源分类精确映射；扫描自动入账和复核弹窗统一到同一个分类入口。
- 复核弹窗的默认分类改为分类器结果 + alternatives，而不是 PAYMENT 固定 OTHER。
- 统一指标：按来源统计覆盖率/修正率。

### 阶段 4：本地模型建议（可选）

- 在新分类器后面加 suggestion 层，只在低置信度时输出 alternatives。
- shadow 运行，对比规则分类和模型建议；人工抽查后再决定是否展示。
- 不自动入账，不替代规则。

## 9. 测试策略

### 9.1 纯 JVM 单测

新增 `android/app/src/test/java/com/bookkeeping/app/notification/CategoryClassifierTest.kt`，至少覆盖：

- 具体短语优先：`美团买药`→HEALTH、`京东健康`→HEALTH、`话费充值`→UTILITIES、`Apple Music`→ENTERTAINMENT、`万达影城`→ENTERTAINMENT、`万达广场`→SHOPPING。
- 品牌词兜底：麦当劳→FOOD、星巴克→FOOD、滴滴→TRANSPORT。
- 词边界：订单号含 `T3`/`1688` 不命中 TRANSPORT/SHOPPING；`KFC` 作为独立词命中。
- 方向/类型：`收到退款` + 麦当劳 → 退款处理，不是 FOOD；`工资到账` → SALARY；`转账` → TRANSFER。
- 商户归一化：`财付通-美团外卖-1234` → `美团外卖`；`支付宝-星巴克(1234)` → `星巴克`。
- 低信息：无商户、无上下文的消费短信 → OTHER，置信度低。
- 规则排序：宽规则 + 具体规则同时命中时，具体规则胜出。
- 学习：连续两次修正后 L0 生效；关闭学习后回退；票数相同回退规则。
- 性能：4000 字符上限、规则数量增长时仍为线性可接受。

### 9.2 回归与金标准

- 现有 `NotificationParserTest`、`SmsParserTest` 必须全绿。
- `golden` 语料跑新旧分类器，输出差异清单；差异逐条人工确认后才改期望值。
- 对高风险样例（退款、转账、工资、医保、话费、外卖、买药）单独建回归用例。

### 9.3 线上 shadow

- 新分类器先在 debug/内部版本 shadow 运行，不改变入账分类；只记录 `category/confidence/ruleId/source`。
- 对比旧分类器与用户实际选择；修正率高的规则先修规则，不急着上模型。
- 不记录原始通知文本、金额、商户原文到日志。

## 10. 风险与未决问题

1. **分类体系是否继续扩展**：当前是 15 个扁平分类（含 BONUS/INVESTMENT/INSURANCE/GIFT）；是否再加日用、通讯、旅行、信贷等，属于产品决策，本规格不擅自加枚举。
2. **学习数据放哪里**：Room vs DataStore；是否同步到其他设备。建议先本地 Room、不同步。
3. **学习反馈回路**：用户一次性修改（如某笔特殊采购）不应立刻改变全局；需要阈值/多数投票，并支持撤销。
4. **商户归一化过度**：剥离分店/编号可能误伤真实品牌；保留 `merchantRaw` 展示，`merchantKey` 只用于匹配，并提供别名表修正。
5. **规则维护成本**：系统规则需要 owner 和版本管理；建议规则文件带 `version`，金标准语料随规则更新。
6. **AI/LLM 政策**：当前产品路线图明确不内置 LLM；本地 ML 实验也不能默认进入正式版本，需单独评审。
7. **隐私**：商户名、通知文本、截图都可能含个人信息；学习只存 `merchantKey + category`，日志只记录 ruleId/来源。
8. **旧数据**：是否对历史交易批量重分类？建议不做自动批量改写；只在新交易/用户主动操作时应用新分类，避免不可逆变更。
9. **跨来源一致性**：同一笔钱可能同时来自通知、短信、账单和扫描；分类应在合并后的 draft 上做一次，而不是每个来源各分一次。
10. **WebView 覆盖不可承诺**：`flagRequestEnhancedWebAccessibility` 只请求暴露，是否生效由 WebView/宿主决定；节点为空时必须降级 OCR，OCR 也不能处理 `FLAG_SECURE`。
11. **页面记忆不进入主方案**：它会让用户多一次确认，并随微信/支付宝改版失效；当前用 Activity allowlist + 强证据 + 页面形态守卫覆盖，若未来出现真实缺口再单独评估。
12. **复核弹窗默认 OTHER**：自动入账已分类，但复核路径仍可能把 PAYMENT 默认成 OTHER；阶段 3 必须统一，否则同一笔账在不同入口分类不一致。
13. **Root/LSPatch 不是基线**：Xposed hook 能读 WebView 私有数据，但需要 Root/LSPosed 或改包，维护成本高；不能把它作为普通 APK 的前置条件。

## 11. 验收标准

- [ ] 现有 `NotificationParserTest`、`SmsParserTest` 全绿，无行为回归。
- [ ] 新增 `CategoryClassifierTest` 覆盖第 9.1 节所有场景。
- [ ] 第 2.3 节列出的误判样例全部修复。
- [ ] 每个自动分类结果都有 `ruleId + reason + confidence`。
- [ ] 用户连续修正同一商户后，下次自动分类使用学习结果；关闭学习后回退规则。
- [ ] 通知、短信、账单导入、扫描捕获四条路径使用同一个分类入口。
- [ ] 非 final 状态（先用后付 created/cancelled、待确认转账）不进入分类入账。
- [ ] 日志、同步 payload 不包含原始通知文本/截图/完整商户敏感信息。
- [ ] shadow 差异报告完成，规则修正率可观测。
- [ ] 聊天列表、聊天页、搜索页、群收款页在 rc.10 守卫下零自动入账。
- [ ] 增强 WebView 无障碍开启后，节点可读/需 OCR/secure 跳过三类页面有真机覆盖记录，且不因新 flag 引入聊天页误记。
- [ ] 复核弹窗的默认分类与自动入账分类一致，不再对 PAYMENT 固定 OTHER。
- [ ] 页面记忆不是验收前置；未记住页面也能靠强成功证据完成首次采集。

## 12. 后续可选：Root/LSPosed/LSPatch 直采

**当前版本不做，也不作为普通 APK 的依赖。** 这里只记录边界和评估条件，避免后续把它误当成“免费升级”。

- **LSPosed（Root）**：通过 Xposed API hook 宿主 App 的 WebView。AutoAccounting 的微信实现 hook `com.tencent.xweb.WebView.evaluateJavascript`，解析 `WeixinJSBridge` 的 `respbuf`；支付宝实现 hook `com.alipay.mobile.nebulacore.web.H5WebView.evaluateJavascript`，注入 JS 包装 `AlipayJSBridge.call`。
- **LSPatch（无 Root）**：不需要 Root，但要把微信/支付宝 APK 打补丁重打包，改签名；不是普通安装路径，且会随宿主版本和签名校验失效。
- **能力**：能拿到 WebView 私有 JS 数据、部分数据库/网络响应；这是免 Root 无障碍 + OCR 拿不到的。
- **代价**：需要用户 Root/装 LSPosed 或接受改包；微信/支付宝升级后类名、协议、混淆都可能变；维护和适配成本高；不应成为基础体验的前置条件。

如果未来要做：

1. 独立成“增强采集”可选通道，单独 APK/模块或明确授权的构建，不混入默认安装包。
2. 输出仍走 `CaptureCandidate` → 采集门 → 结算门 → 分类，不允许绕过 final/去重/金额校验。
3. 按宿主版本维护 adapter，识别失败必须 fail closed，不能猜。
4. 先满足评估条件：真机样本里免 Root 覆盖率低于目标、且用户明确接受 Root/改包成本；否则不启动。

## 13. 实现进度（2026-11-16）

### P0 账单导入完整性（已完成）

- 解析结果新增 `SKIPPED`；0 元、失败、关闭、待处理不再计入“错误”。
- “不计收支”成功/退款行作为转账/退款入账，保留来源分类到备注，统计排除。
- 重复行支持补齐：只补空商户、单号、支付方式、OTHER 分类，不覆盖手工或已有强分类；新插入与补齐在同一 Room 事务。
- 真实支付宝年度账单（415 行）本地回归：`valid=346`、`skipped=69`、`errors=0`、文件内重复 0；未把账单内容写入仓库。

### P1 分类器 v2（已完成）

- 新增 `ClassificationInput / ClassificationResult`、`ClassificationDirection/Kind/Status/Source`、`MerchantNormalizer`、`CategoryRule`。
- 分类顺序：POST 安全规则（退款/转账/提现/红包）→ 用户锁定规则 → 学习映射 → 官方来源分类精确映射 → 具体短语规则 → 原有关键词表的 L2 兜底。
- 具体短语规则覆盖：美团买药/饿了么买药/京东健康、话费/流量/燃气充值、公交卡充值、Apple Music/TV、万达影城 vs 万达广场、小米粥/小米椒、KFC、美团买菜、药店、房租、学费、保费、礼金、信用卡还款。
- 15 个分类全部保留；通知、短信、账单导入、无障碍扫描、复核弹窗均接入结构化输入。

### P2 学习闭环（已完成）

- 新增 `CategoryLearning`：单次修正只记录，不立即全局覆盖；同一商户连续两次一致修正且严格多数时生效；票数相同回退规则。
- 按商户暂停/恢复学习；设置页删除或清除会同时移除显式规则与学习计数。
- 学习结果只存 `merchantKey + category + count`，不存通知原文、金额或截图。

### P3 采集/UI 增强（已完成）

- 页面识别无障碍配置增加 `flagRequestEnhancedWebAccessibility`，尝试暴露 WebView 子节点；节点不足仍走截图 OCR。
- 复核弹窗 PAYMENT 默认分类改为分类器结果，不再固定 OTHER。
- rc.10 页面形态守卫保留；页面记忆仍不作为主采集门。

### 验证

- Android JVM 单测 401 项通过，0 失败（1 项真实账单测试在未设置 `ALIPAY_BILL_CSV` 时跳过）。
- 真实支付宝年度账单：415 行 → 346 可导入、69 跳过、0 解析错误。
- 后端 23 项 Wallos/API/integration 测试通过。

### 未完成/未验证

- 真实微信/支付宝 WebView 页面开启增强 flag 后的节点覆盖率，需要真机样本记录。
- 本次真实账单回归只覆盖支付宝 CSV；微信 XLSX/CSV 仍需用真实导出复验。
- 学习与分类的 UI 只做了设置页可见/删除/暂停，未做单独的“为什么这样分类”诊断页。
- rc.11 真机安装/启动冒烟未完成：构建时手机已断开；rc.10 真机验证仍有效，rc.11 导入页/设置页尚未在真机点开。

## 附录 A：当前关键词表（摘要）

| 分类 | 代表关键词 | 主要风险 |
| --- | --- | --- |
| SALARY | 工资、薪资、薪水、代发、津贴、年终 | 绩效/报销/补贴等归入 BONUS；收入方向仍未参与 |
| FOOD | 星巴克、瑞幸、麦当劳、KFC、美团、饿了么、盒马、叮咚、餐厅、火锅 | `美团买菜`、`饿了么买药` 仍可能因品牌词更短被误分 |
| TRANSPORT | 滴滴、T3、高铁、航空、地铁、公交、ETC、停车、加油、共享单车 | `T3` 无词边界 |
| SHOPPING | 淘宝、天猫、京东、拼多多、苹果、Apple、华为、小米、便利店、超市 | `小米粥`、`小米椒`；单独 `Apple`/`万达` 依赖上下文 |
| ENTERTAINMENT | Steam、腾讯视频、爱奇艺、Netflix、万达、B站、抖音、KTV、游戏、充值 | 通用 `充值` 与具体缴费词竞争；`万达广场` 已由更长关键词修正 |
| HOUSING | 房租、租金、物业、中介费、押金、二房东 | 缺少房贷、装修、家居 |
| UTILITIES | 水费、电费、燃气费、热力、网费、宽带、话费、流量充值 | 与娱乐 `充值` 的边界需持续回归 |
| HEALTH | 医院、药店、药房、挂号、体检、口腔、牙科、眼科、美团买药、京东健康 | `饿了么买药` 等未覆盖的同义词 |
| EDUCATION | 学费、培训、课程、网课、得到、极客时间、教材、书店、当当、Kindle | 缺少部分机构品牌 |
| BONUS | 奖金、年终奖、绩效奖、提成、补贴、报销、退税、兼职、稿费、返现、中奖 | 与 SALARY、TRANSFER 的边界 |
| INVESTMENT | 理财、基金、股票、证券、利息、收益、余额宝、零钱通 | 与 TRANSFER、BONUS 的收益/本金边界 |
| INSURANCE | 保险、保费、重疾险、医疗险、车险、理赔 | 与 HEALTH、INVESTMENT 的边界 |
| GIFT | 礼金、份子钱、随礼、礼物、压岁钱、彩礼 | 与 RED_PACKET/TRANSFER 的语义边界 |
| TRANSFER | 转账、转入、转出、汇款、提现、红包、发给、还款 | 与退款/红包/提现的 POST 语义未统一 |
| OTHER | 未命中 | 覆盖率盲区 |

## 附录 B：分阶段交付清单

| 阶段 | 交付物 | 不包含 |
| --- | --- | --- |
| 0 | 金标准语料、现状 characterization tests、误判清单 | 任何行为变更 |
| 0.5 | 增强 WebView 无障碍 flag、节点/OCR/secure 覆盖率记录、页面守卫回归 | 页面记忆、分类器改动、数据库 |
| 1 | `ClassificationInput`、规则对象、商户归一化、L1-L3 规则、单测 | 学习、UI、数据库 |
| 2 | `CategoryLearning` 存储、编辑/复核回写、学习开关 | 跨设备同步、模型 |
| 3 | 四条路径统一、账单分类精确映射、扫描捕获分类、复核弹窗默认分类 | 分类体系扩展 |
| 4 | 本地模型建议、shadow 指标 | 自动入账、云端 AI |
| 5（可选） | Root/LSPosed/LSPatch 直采增强通道 | 默认安装包依赖、绕过结算门 |
