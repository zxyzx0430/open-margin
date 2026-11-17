# 英文口述账单解析（Voice English Parser）

语音识别是中英双语的，但历史上只有中文解析器。本页记录英文路径支持的句式、
产出语义和明确不支持的边界，避免 UI 侧对解析能力做错误假设。

- 实现：`android/app/src/main/java/com/bookkeeping/app/voice/VoiceEnglishParser.kt`
- 分流入口：`VoiceTransactionParser.parse()` 开头的 `VoiceEnglishParser.looksEnglish()`
- 测试：`android/app/src/test/java/com/bookkeeping/app/voice/VoiceEnglishParserTest.kt`

## 一、语言分流

纯函数 `looksEnglish(raw)`，规则只有三条：

| 输入 | 走向 | 说明 |
| --- | --- | --- |
| 含汉字（`\u4E00-\u9FFF` 等） | 中文路径 | 中英混合也算中文，英文片段当商户名 |
| 无汉字，含拉丁字母或 `$` | 英文路径 | `spent 36 at Starbucks`、`$36.50` |
| 无汉字，纯数字（`36.5`） | 中文路径 | 中文路径本来就认这种写法，既有行为不变 |

`starbucks 花了 36` 走中文路径：金额 36 元，商户 `starbucks`，分类餐饮。
中文关键词表认不出的英文品牌会回落到英文侧的商户→分类表，所以混合句也能分类。

## 二、支持句式

### 金额

金额一律换算成**整数分**，不经过 Float/Double。

| 写法 | 例子 | 结果 |
| --- | --- | --- |
| 小数一位 | `36.5` | 3650 |
| 小数两位 | `36.50` | 3650 |
| 美元符号 | `$36.50` | 3650 |
| 千分位 | `1,234.56` | 123456 |
| dollars / bucks | `36 dollars`、`36 bucks` | 3600 |
| yuan / rmb | `36 yuan`、`36 rmb` | 3600 |
| 分币单位 | `99 cents` | 99 |
| 拼写十位 | `twenty five` | 2500 |
| 拼写整数+分 | `thirty six fifty` | 3650 |
| 拼写百位 | `one hundred and five` | 10500 |
| 拼写百位+分 | `one hundred and five eighty` | 10580 |
| 美元+分 | `twelve dollars (and) fifty cents` | 1250 |

拼写数字的口语规则和中文版对齐：**小于 10 的数字必须带货币词**才认金额，
否则更像数量（`two coffees` 不产生金额，`five dollars` 才是 5 元）。
`million` 及以上不认。

### 方向

判定顺序：退款 → 转账 → 收入 → 支出；都没有则按支出并给 `TYPE_UNCERTAIN`。

| 关键词 | 类型 | warning |
| --- | --- | --- |
| `refund` / `refunded` / `money back` / `cash back` | REFUND | `REFUND_NEEDS_CONFIRM` |
| `transfer` / `transferred` / `sent` / `wire` / `venmo` | TRANSFER | `TRANSFER_NEEDS_CONFIRM` |
| `received` / `got paid` / `salary` / `paycheck` / `bonus` | INCOME | — |
| `spent` / `paid` / `bought` / `cost` / `charged` / `ordered` | EXPENSE | — |

`got paid` 先于裸 `paid` 匹配，所以工资不会被记成支出。

### 商户

介词在前后 4 个词内，保留大小写，去掉尾部标点：

| 说法 | 商户 |
| --- | --- |
| `at McDonald's` | `McDonald's` |
| `from Taobao` | `Taobao` |
| `on Amazon` | `Amazon` |
| `at the Apple Store` | `Apple Store` |
| `at Starbucks, for lunch` | `Starbucks` |
| `at 7-11` | `7-11` |
| `Starbucks 36`（无介词，品牌兜底） | `Starbucks` |

`on` 后面跟时间/账户/用途词时不当商户：`spent 20 on lunch` 的商户是空，
不是 `lunch`。

### 账户

| 说法 | 账户 |
| --- | --- |
| `wechat pay` / `wechat` / `weixin` | 微信 |
| `alipay` / `ali pay` | 支付宝 |
| `cash` | 现金 |
| `card` / `credit card` / `debit card` / `bank card` | 银行卡 |

### 时间

只认日期和时段，**不认钟点**：没说钟点就 `preciseTime = false`，
`occurredAtMs` 只是当天 0 点占位，绝不编造 00:00。

| 说法 | 日期 | `timeOfDayLabel` |
| --- | --- | --- |
| `today` | 今天 | — |
| `yesterday` | 昨天 | — |
| `last night` | 昨天 | 晚上 |
| `this morning` | 今天 | 早上 |
| `this afternoon` | 今天 | 下午 |
| `this evening` / `tonight` | 今天 | 晚上 |
| `noon` | 今天 | 中午 |
| `midnight` | 今天 | 半夜 |

带时段词但没钟点时给 `DATE_APPROXIMATE`（和中文路径一致）。
`dateKnown` 只在说了日期时为 true。

### 分类

先问 `CategoryClassifier`（用户规则 → 学习纠正 → 内置关键词），
再用英文品牌/品类表兜底，最后 `OTHER`：

- 餐饮：`Starbucks`、`McDonald's`、`KFC`、`coffee`、`lunch`
- 交通：`Uber`、`taxi`、`metro`、`parking`、`Shell`
- 购物：`Amazon`、`Taobao`、`Costco`、`Walmart`、`IKEA`
- 娱乐：`Netflix`、`Spotify`、`Steam`、`movie`
- 水电：`electricity`、`internet`、`phone bill`
- 医疗：`pharmacy`、`hospital`、`dentist`
- 教育：`tuition`、`course`、`textbook`
- 工资：`salary`、`paycheck`；奖金：`bonus`；理财：`stock`、`crypto`

### 备注

| 说法 | 备注 |
| --- | --- |
| `for lunch` / `for dinner` / `for groceries` / `for rent` | `lunch` 等 |
| `note: team coffee` / `note team coffee` | `team coffee` |

取不到就留空，不把整句塞进备注。

## 三、不支持（留空 + warning，或直接忽略）

| 情况 | 行为 |
| --- | --- |
| 外币 `euros` / `pounds` / `yen` / `won` / `rupees` | 金额留空 + `CURRENCY_UNSUPPORTED`，**不做汇率换算** |
| 一句话多笔（`coffee 5 and taxi 12`） | 取第一个金额 + `MULTI_AMOUNT` |
| 日期区间（`from Monday to Friday`） | 不认，`dateKnown = false` |
| 相对周（`last Monday`）、`tomorrow` | 不认，不猜日期 |
| 钟点（`at 7pm`、`at 19:30`） | 不认，`preciseTime = false` |
| `million` 及以上拼写数字 | 不认，`AMOUNT_MISSING` |
| 数量（`two coffees`、`3 tickets`） | 不当金额，`AMOUNT_MISSING` |
| 百分比、时长、比分、编号（`5%`、`20 minutes`、`3:2`、`7-11` 的 7） | 不当金额 |
| 商户识别不出 | `merchant = null` + `MERCHANT_UNKNOWN` |

## 四、产出契约

英文路径产出的 `VoiceDraft` 和中文路径完全一致，字段语义不变：

- `amountCents`：整数分，可能为 null
- `type`：`EXPENSE` / `INCOME` / `TRANSFER` / `REFUND`
- `merchant`：保留原句大小写，去掉尾部标点
- `category`：`TransactionCategory`，认不出是 `OTHER`
- `occurredAtMs` / `dateKnown` / `preciseTime` / `timeOfDayLabel`：见上文时间表
- `account`：中文账户名（微信/支付宝/现金/银行卡），和中文路径同一套取值
- `note`：`for ...` / `note ...` 的短语，取不到为 null
- `warnings`：风险提示集合
- `raw`：原始文本（trim 后）

## 五、本地验证

VPS 上没有 Android SDK，用 kotlinc 2.0.21 + JUnit 4.13.2 直接编译解析器和测试：

```bash
kotlinc -jvm-target 17 -cp junit-4.13.2.jar:hamcrest-core-1.3.jar \
  <TransactionCategory stub> \
  android/app/src/main/java/com/bookkeeping/app/data/merchant/MerchantRules.kt \
  android/app/src/main/java/com/bookkeeping/app/notification/{CategoryRule,ClassificationModel,CategoryLearning,MerchantNormalizer,CategoryClassifier}.kt \
  android/app/src/main/java/com/bookkeeping/app/voice/{VoiceTransactionParser,VoiceEnglishParser}.kt \
  android/app/src/test/java/com/bookkeeping/app/voice/{VoiceTransactionParserTest,VoiceEnglishParserTest}.kt \
  android/app/src/test/java/com/bookkeeping/app/notification/CategoryClassifierTest.kt \
  -d out
java -cp out:junit-4.13.2.jar:hamcrest-core-1.3.jar:kotlin-stdlib-2.0.21.jar \
  org.junit.runner.JUnitCore \
  com.bookkeeping.app.voice.VoiceTransactionParserTest \
  com.bookkeeping.app.voice.VoiceEnglishParserTest \
  com.bookkeeping.app.notification.CategoryClassifierTest
```

真实 CI 走 `./gradlew testDebugUnitTest`，同一批测试在 Android 单测任务里再跑一遍。
