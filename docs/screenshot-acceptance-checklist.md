# 截图采集真机验收清单

- 文档基线：`fix/active-screenshot-acceptance-20261120` @ `b49213dd5dcd95a21a5132dccc55f1188b00f452`；语义基线：本轮审计修复（R3 未知方向闸门、R5 等待项唯一结算、R6 无订单号不自动关联），来自 `chore/audit-fixes-20261120` 系列。被测包必须这三项齐全——缺任一项时第 8 节与第 12 节会以失败形态出现，那是包不对，不是文档写错。
- 范围：分享入口、相册选图、新截图监听、待确认通知、零写入、未完成状态、时间处理、重复导入、状态隔离、七天保留、收支方向闸门、等待项结算、重复判定收紧
- 性质：**操作手册**。本文只写“按当前实现应当发生什么”，不含任何真机执行结果，也不代表 OCR 在真实截图上的准确率。执行人填完第 13 节的记录表后，才产生验收结论。

代码位置以本基线为准。涉及的关键实现：

- `android/app/src/main/java/com/bookkeeping/app/capture/screenshot/`（入口 Activity、监听器、通知、解释器、保留策略）
- `android/app/src/main/java/com/bookkeeping/app/notification/capture/`（候选模型、入库闸门、等待关联）
- `android/app/src/main/java/com/bookkeeping/app/ui/screen/capture/`（待确认列表与确认映射）
- `android/app/src/main/res/xml/backup_rules.xml`、`data_extraction_rules.xml`

## 0. 准备

### 0.1 安装包

| 项 | 要求 |
|---|---|
| 渠道 | GitHub 实验版：`.github/workflows/android-build.yml` 用 `-PopenMarginScreenshotWatcher=true` 构建。本地打包也要带这个参数，否则设置页永远显示“此版本不启用自动监听”，第 3 节无法执行。 |
| ABI | 只打 `arm64-v8a`：x86_64 模拟器装不上，需要 arm64 真机（或 arm64 模拟器）。 |
| 系统 | 一台 Android 13 以上；Android 14 单独一台用于“部分照片授权”项。 |
| 权限 | 通知权限允许（否则第 4 节无法执行）。分享与选图不需要相册权限。 |
| 调试能力 | 第 10 节的目录检查需要 debug 包（`adb shell run-as com.bookkeeping.app`）。release 包只能做“过期后条目消失”的观察。 |

### 0.2 测试素材

准备真实截图，不要用修图软件改金额后再当作识别结果。允许用同一张真实截图的拼接版验证“一次只问一个”这类交互顺序，但要标注“拼接图，只验交互”。

| 编号 | 素材 |
|---|---|
| M1 | 微信或支付宝“已完成”的商户付款详情，含金额、收款方、状态、时间 |
| M2 | 没有“交易成功/支付成功”字样的账单详情（中置信度） |
| M3 | 转账“待领取/处理中”页面 |
| M4 | M3 同一笔转账的“已完成”页面（同商户、同金额） |
| M5 | 支付失败页面 |
| M6 | 先用后付/预计扣费页面（订单已生成、预计扣费金额） |
| M7 | 只有 `11月19日 20:10`、没有年份的详情页 |
| M8 | 完全读不到时间的详情页 |
| M9 | 同一张图里同时有退款卡片和普通付款卡片 |
| M10 | 已完成付款，旁边有一条无关的“已取消” |
| M11 | 非交易图（风景、自拍、聊天截图） |
| M12 | 与 M1 完全相同的文件，用于重复导入 |
| M13 | 同一商户、同一金额、同一平台的两笔真实付款，两张都不显示订单号，间隔 1 分钟内 |
| M14 | 金额、商户清楚，但没有任何“支出/收入/付款/收款”字样的交易详情页 |
| M15 | 同一商户、同一金额的两笔“待领取”转账（两张截图，记为 M15-a / M15-b；可不带单号，也可各带不同单号） |
| M16 | “支付成功”只出现在备注/留言/商品说明里、状态位不是“交易成功”（或没有状态行），但页面能看出是付款（有“付款/支付给”字样）的详情页 |

### 0.3 账本行数怎么数

不要靠肉眼数列表。用 设置 → 导入 / 导出账单 → 导出全部账单，前后各导一次 CSV，按“金额 + 商户 + 日期”数行。列表只用于快速判断“有没有多出一行”。

截图写入的账本行，详情页备注以 `[扫描]` 开头；可作为“这行是不是截图来的”的判据。

需要看候选本身的状态（只适用于 debug 包）：

```
adb exec-out run-as com.bookkeeping.app cat files/page_capture/candidates.jsonl > candidates.jsonl
```

文件是逐行 JSON 追加，同一个 `candidateId` 以最后一条为准；`status` 取值有 `PENDING`、`APPROVED`、`IGNORED`、`WAITING_EVIDENCE`、`LINKED_EXISTING`。第 6、10 节会用到。release 包看不到这个文件，只能看界面。

### 0.4 记录方式

每条记：编号、通过/失败、机型与系统版本、截图或录屏、实际看到的现象。失败时写“实际发生了什么”，不要只写“不符合预期”。

### 0.5 通用失败信号

- 崩溃、ANR、白屏。
- 停在“正在识别截图”超过 60 秒没有任何结果。
- 账本出现与截图无关的行。
- 页面金额是 48.00，候选写成 4800.00 或其他数字（金额错位比不识别更严重）。

## 1. 分享入口（微信 / 支付宝交易详情页）

进入方式：在交易详情页找系统“分享/发送”入口，选“清衡”。若该页面本身没有分享入口，先截屏，再在系统截图预览或相册里对该图点“分享”，选“清衡”。两条路进入同一个 `ScreenshotImportActivity`（`ACTION_SEND`，`image/*`），验收点相同，记录时注明走的是哪条。

| 编号 | 操作 | 必须发生 | 判失败 |
|---|---|---|---|
| 1.1 | 用 M1 从交易详情页分享到本应用 | 打开本应用的识别页，显示“正在识别截图”；识别完成后显示“已记录 1 笔”，约 1 秒后自动关闭；账本新增 1 行，详情备注以 `[扫描]` 开头，金额、商户、日期与页面一致 | 没有出现在系统分享面板；打开后空白；账本没有新增；金额或商户与页面不符 |
| 1.2 | 用 M2（无完成状态）分享 | 弹出应用内对话框，标题“请确认这笔交易”；正文逐行给出 金额（带 −/+）、收支类型、商户（含平台）、发生时间、状态、确认原因；“确认原因”是中文短语，例如“截图未明确显示交易状态”，不是英文内部原因码；按钮为 确认记入 / 忽略 / 稍后；此时账本 0 新增 | 直接入账；对话框缺金额或商户；确认原因显示 `needs confirmation before booking` 这类英文；按钮只有“确定” |
| 1.3 | 用含两笔中置信度交易的拼接图分享 | 只出现一个对话框；点“确认记入”或“忽略”后才出现第二个；不会两个弹窗叠在一起 | 两个对话框同时叠在屏幕上；第二笔在第一笔未处理时就入账；处理完第一笔后第二笔丢失 |
| 1.4 | 在 1.3 的对话框点“稍后” | 对话框关闭，提示“这笔交易仍待确认”；两笔都留在 记账页“待确认扫描结果”里；账本 0 新增 | 点稍后后候选消失；账本多行 |
| 1.5 | 用 M11 分享 | 提示“这张图片里没有交易”或“没认出是微信或支付宝的交易页”；账本 0 新增；待确认 0 新增 | 产生候选；账本多行；提示“已记录” |
| 1.6 | 查看系统分享面板里的应用名 | 显示“清衡”（`@string/app_name`），不是包名或空白 | 显示 `com.bookkeeping.app` |

## 2. 相册选图（Photo Picker）

进入方式：设置 → “自动识别新截图”卡片 → “从相册选截图”。

| 编号 | 操作 | 必须发生 | 判失败 |
|---|---|---|---|
| 2.1 | 点“从相册选截图” | 打开系统图片选择器，只列图片；从选择器返回本页时显示“正在识别截图”和一个“选择图片”按钮 | 打不开选择器且没有“当前系统不支持图片选择器”提示；列出非图片文件 |
| 2.2 | 选 M1 | 结果与 1.1 完全一致（同一管线、同一提示、同样 1 行） | 结果与分享路径不同（例如一处弹窗一处不弹） |
| 2.3 | 打开选择器后直接返回 | 识别页关闭或停在初始状态；账本 0 新增；待确认 0 新增 | 报错、崩溃、多出候选 |
| 2.4 | 选一张大于 12 MB 的图 | 提示“图片太大，请先裁剪”；账本 0 新增 | 卡住无提示；产生候选 |
| 2.5 | 选 M12（与已分享过的 M1 同一文件） | 提示“这张截图已经处理过”；账本 0 新增 | 新增第二行 |

## 3. 新截图监听（MediaStore）

前提：安装包带 `openMarginScreenshotWatcher=true`。本节的开关、副标题文案都来自 `ScreenshotCaptureCard` 与 `ScreenshotWatchPermissionPolicy`。

| 编号 | 操作 | 必须发生 | 判失败 |
|---|---|---|---|
| 3.1 | 全新安装后进入 设置 | “自动识别新截图”开关为关；副标题“默认关闭，开启后只处理新截图” | 默认就是开；副标题空白 |
| 3.2 | 打开开关（Android 13+） | 弹出照片权限请求（`READ_MEDIA_IMAGES`）；Android 12 及以下是存储权限 | 不弹权限就直接变开；弹的是无关权限 |
| 3.3 | Android 14 上在权限弹窗选“选择照片”（部分授权） | 开关落定为关；副标题“所选照片权限无法可靠读取新截图；自动监听未开启，仍可分享或手动选图”；此后拍一张新截图，不产生候选、不写账本、不发通知 | 开关显示为开；部分授权下仍然处理新截图；没有解释文案 |
| 3.4 | 重新打开开关并选“允许全部” | 开关为开；副标题“发现新截图后自动识别” | 仍然显示部分授权文案；开关自动弹回 |
| 3.5 | 3.4 之后退到桌面，截一张 M1 同类交易页，等 60 秒 | 出现结果：账本新增 1 行，或通知栏出现待确认通知（取决于置信度）；不会重复处理同一张 | 60 秒内没有任何反应；同一张图被处理两次 |
| 3.6 | 3.3 部分授权后，去系统设置把照片权限改为“允许全部”，再重开应用 | 开关自动为开（开启意图已记住），副标题“发现新截图后自动识别” | 仍为关；需要再手动开一次才生效（可接受但需记录） |
| 3.7 | 关闭开关，再截一张交易图 | 无候选、无通知、账本不变 | 仍然处理 |
| 3.8 | 开启监听后，打开相册上下滚动查看开启前的旧截图 | 不产生任何候选（监听只处理开启之后新增的图片） | 开启瞬间把相册旧图批量导入 |
| 3.9 | 用相机拍一张普通照片（DCIM/Camera，文件名 `IMG_*.jpg`） | 不产生候选（文件名和路径都不含 screenshot/截图） | 被当成截图识别 |
| 3.10 | 在不带 `openMarginScreenshotWatcher` 的包上打开设置 | 副标题“此版本不启用自动监听”；点开关没有反应，保持关闭 | 开关能打开但实际不监听 |

## 4. 待确认通知

前提：第 3 节已开启监听并取得全部照片权限，通知权限已允许。只有 MediaStore 来源、且被判为需要确认的候选才走通知；分享和选图走应用内对话框。

| 编号 | 操作 | 必须发生 | 判失败 |
|---|---|---|---|
| 4.1 | 截一张 M2 类页面（中置信度） | 通知栏出现“截图识别待确认”，通知渠道名“截图待确认” | 没有通知；通知标题是“发现一笔交易”这类泛化文案 |
| 4.2 | 展开通知 | 正文包含 金额、收支类型、商户（含平台）、发生时间、状态、确认原因，各占一行；不是一句笼统描述；方向读不出来时“收支类型”显示“方向待确认”、金额不带 −¥，不写成“支出” | 只有标题；缺金额或商户；出现英文原因码；把未知方向写成“支出” |
| 4.3 | 查看通知上的动作 | 有“记入”和“忽略”两个动作（`ledgerWriteAllowed=false` 的预览候选只有“忽略”；方向未知的候选同样只有“忽略”，闸门不放行） | 没有动作；动作点了没反应；方向未知却给了“记入” |
| 4.4 | 点击通知正文（不是动作按钮） | 不打开任何 Activity：停留在当前应用或桌面，不跳转识别页、不跳转记账页 | 打开了截图识别页或主界面（说明挂了 `contentIntent`） |
| 4.5 | 点“忽略” | 通知消失或刷新为下一笔；账本行数不变；“待确认扫描结果”数量减 1；该笔不再出现 | 账本多行；该笔仍在待确认 |
| 4.6 | 再触发一笔，点“记入” | 账本 +1 行，备注 `[扫描]`，金额商户与截图一致；通知消失 | 没有入账；入账两次；金额错 |
| 4.7 | 连续触发两笔待确认 | 只存在一条通知，正文末尾“还有 1 笔待确认”；处理完一笔后同一条通知刷新为剩下那笔 | 通知栏叠出两条以上；正文不显示剩余数量 |

## 5. 零写入检查

账本行数一律按 0.3 的方法在操作前后各取一次。

| 编号 | 操作 | 必须发生 | 判失败 |
|---|---|---|---|
| 5.1 | 分享 M2，在弹出的对话框点“稍后” | 账本前后行数相同；该笔仍在“待确认扫描结果” | 账本多 1 行；候选消失 |
| 5.2 | 分享 M2，在对话框中点“忽略”；再在记账页“待确认扫描结果”里对已存在的一笔点“忽略” | 两处都：账本不变；待确认数量减 1；该笔不再出现 | 账本多行；忽略后仍出现 |
| 5.3 | 通知上点“忽略”（见 4.5） | 同 5.2 | 同 5.2 |
| 5.4 | 分享 M2，对话框弹出后按返回键或在对话框外点击 | 对话框关闭，提示“这笔交易仍待确认”；账本不变；候选仍在待确认 | 账本多行；候选被悄悄标成已处理 |
| 5.5 | 分享 M11（非交易图） | 账本不变；待确认不变 | 任一项变化 |
| 5.6 | 预览路径（`allowAuto=false` / `previewOnly=true`） | 当前生产代码没有把该开关暴露到界面（`ScreenshotIntakeCoordinator.ingest` 的默认值是 `allowAuto=true, previewOnly=false`，三处入口都不传这两个参数）。真机上只能验证：调试构建里手工调用 `ingest(..., allowAuto = false)` 时，候选进入待确认，对话框出现“仅预览，不能记入账本”，确认按钮变成“继续”（没有“确认记入”），账本 0 行。常规验收标“未覆盖”，由单元测试证明 | 在没有该调用的构建上声称已验证；或预览候选被确认后写进账本 |
| 5.7 | 分享一张全黑图或纯文字无金额的图 | 账本不变，待确认不变；设置 → 诊断日志 → 账单诊断日志 新增一条：阶段“导入”、结果“失败”、详情是 `OCR_EMPTY` / `NOT_TRANSACTION` 这类稳定代码 | 诊断行里出现 OCR 原文、商户名、金额；或失败导入产生候选 |

## 6. 未完成状态与后续证据

| 编号 | 操作 | 必须发生 | 判失败 |
|---|---|---|---|
| 6.1 | 分享 M3（转账待领取/处理中） | 提示“交易还没完成，稍后再看”；账本 0 新增；该笔不出现在“待确认扫描结果”（等待状态不是待确认） | 直接入账；弹出确认对话框；进入待确认列表 |
| 6.2 | 分享 M5（支付失败） | 同 6.1，不入账、不弹确认 | 入账；弹确认 |
| 6.3 | 分享 M6（先用后付/预计扣费） | 同 6.1；识别出的金额是“预计扣费金额”，不是同页的“订单金额” | 把订单总额当成本次扣款；入账 |
| 6.4 | 6.1 之后 10 分钟内，分享 M4（同一笔转账的已完成页）；此时同商户、同金额、同方向的等待项必须只有这一条 | “待确认扫描结果”里只有 1 条（新的那条）；账本 0 行；debug 包里 `candidates.jsonl` 中旧占位最后一条记录是 `LINKED_EXISTING`（不再是 `WAITING_EVIDENCE`）；在弹出对话框点“确认记入”（或稍后在“待确认扫描结果”里点“记入账本”）后恰好 1 行 | 待确认里出现两条；确认后账本 2 行；M4 与占位对不上（商户、金额、方向、交易类型、平台任一不同）却把占位结算掉 |
| 6.5 | 6.1 之后超过 10 分钟再分享 M4 | 不关联：旧记录在 `candidates.jsonl` 里仍是 `WAITING_EVIDENCE`，但它本来就不进“待确认扫描结果”，界面上看不到；待确认只有新的 1 条，账本 0 行；旧记录留到 7 天过期。记录实际间隔，不判失败 | 待确认里出现两条；账本 2 行 |
| 6.6 | 分享 M5（支付失败，进入等待）后 10 分钟内，分享同一笔重试成功的“支付成功”页（同商户、同金额；两张都不带订单号，或带同一个订单号） | 账本恰好 +1 行（成功页可直接入账），旧的失败占位在 `candidates.jsonl` 里标为 `LINKED_EXISTING` | 账本 2 行；旧占位被结算但商户、方向或交易类型对不上（误结算）。成功页带新单号而占位没有时旧占位仍是 `WAITING_EVIDENCE`，这是正确行为，不判失败，记录页面是否带单号 |

补充说明：

- 6.4 要求两次截图的商户名在识别结果里一致、金额与方向一致、平台一致、交易类型一致（转账对转账），且旧占位没有订单号（有单号时按单号比对，见下）。6.6 同理：失败页与成功页要能读出同一个商户、同一方向，且没有互相冲突的订单号。
- 「唯一候选」是硬条件：同商户、同金额、同方向、同交易类型的等待项同时存在两条时，任何完成页都不会结算它们——实现分不清是哪一笔，宁可留到 7 天过期也不猜。界面上看不出差别（等待项本来就不进待确认），只有 `candidates.jsonl` 能看出，不判失败（见 12.5）。
- 订单号互斥：完成页带订单号而占位没有，或两侧单号不同时，不走“商户 + 金额 + 方向”的模糊匹配，不结算；两侧单号相同才结算。同上，不判失败，记录页面是否带单号（见 12.6）。
- 商户读不出来不结算：完成页或占位的任一侧读不出商户时，等待项保持 `WAITING_EVIDENCE`——空商户是缺证据，不是通配符。不判失败。
- 如果完成页被读成另一种交易类型（转账 vs 普通付款）、方向相反、平台不一致或商户名读成两个不同名字，旧等待记录同样不会被结算。界面上看不出差别（等待记录本来就不在待确认列表里），但那条记录会留到 7 天过期。属于识别层限制（见第 11 节），不判失败，但要记录页面特征。
- 转账确认后写入账本的行会带 `[转账]` 备注并按既有规则从统计中排除，这是既有产品行为，不在本次验收范围。

## 7. 时间处理

| 编号 | 操作 | 必须发生 | 判失败 |
|---|---|---|---|
| 7.1 | 在 2026-11-20 分享 M7（页面只写 `11月19日 20:10`） | 候选与账本记录的发生时间是 2026-11-19 20:10；不显示“时间待确认” | 记成今天（11-20）；记成 2025-11-19；显示“时间待确认” |
| 7.2 | 分享 M8（页面读不到时间） | 发生时间回退为导入时刻，并且在三处都出现“时间待确认”：① 待确认卡片/对话框；② 账本列表该行（警告色徽标）；③ 账本详情“发生时间”下方 | 任何一处缺标记；把回退时间当成真实交易时间且无提示 |
| 7.3 | 分享含完整时间（`2026-11-19 20:10`）的 M1 | 用页面时间，无“时间待确认” | 用导入时间；标记待确认 |
| 7.4 | 分享写成 `昨天 20:10` 的页面 | 时间是导入日的前一天同一时刻 | 记成今天 |
| 7.5 | 分享非法或未来日期（`2月30日 20:10`、`12月24日 20:10` 而当前是 11 月） | 不猜时间：回退导入时刻并标“时间待确认” | 硬套成某个日期；崩溃 |
| 7.6 | 对比 7.1 记录的时间与手机时区 | 时间按设备时区解释，不出现 8 小时偏移 | 差 8 小时 |

## 8. 重复导入

| 编号 | 操作 | 必须发生 | 判失败 |
|---|---|---|---|
| 8.1 | 把 M1 连续分享两次 | 第二次提示“这张截图已经处理过”；账本 0 新增 | 新增第二行；报错 |
| 8.2 | 先分享 M1，再从相册选 M12（同一文件） | 同 8.1 | 新增第二行 |
| 8.3 | 先后分享两张不同商户、同为 ¥18.00 的真实付款（同一天） | 账本 2 行，商户各自正确 | 只留 1 行（漏记）；商户被合并成同一个 |
| 8.4 | 先后分享同一商户、同金额、但页面时间相差 3 分钟以上的两笔 | 账本 2 行 | 只留 1 行 |
| 8.5 | 先后分享同一商户、同金额、同平台、页面时间相差 2 分钟以内，且两张都不显示订单号的两笔 | 第 2 张**不**判为同一笔：弹出“请确认这笔交易”（或进“待确认扫描结果”），此时账本仍 1 行；确认后账本 2 行，忽略后 1 行。只有两侧订单号相同才允许并成一条 | 第 2 张没问就提示“这笔已经在账本里”、账本仍 1 行；第 2 张静默消失；确认后仍只剩 1 行（见 12.1 说明：那是入库层合并，照样按失败记录） |
| 8.6 | 先用通知记过一笔 ¥18.00（同商户），2 分钟内再分享该笔截图；截图不显示订单号 | 不判成同一笔：进待确认，确认后账本 2 行（可能是同一笔，也可能是在同一家店又花了一次，由用户决定）。只有当截图带与账本行相同的订单号时，才提示“这笔已经在账本里”、账本仍 1 行 | 没有订单号却提示“这笔已经在账本里”（吞掉第 2 笔）；带相同订单号却仍新增一行 |

## 9. 状态隔离

| 编号 | 操作 | 必须发生 | 判失败 |
|---|---|---|---|
| 9.1 | 分享 M9（同页含退款卡 + 普通付款卡） | 产生 2 条候选：退款那条是 收入 / 退款类型、走确认；付款那条是 支出 / 普通支付、可自动入账。账本只多付款 1 行，退款等确认 | 退款把付款改成收入；付款把退款改成支出；只识别出 1 条；账本多出 2 行 |
| 9.2 | 同一个退款页里金额在页头和“退款金额”行各出现一次 | 只产生 1 条退款候选，不是 2 条 | 拆成两条退款 |
| 9.3 | 分享 M10（已完成付款 + 无关“已取消”） | 付款仍是“交易已完成”，可以正常入账；不会被改成“已取消”，也不会变成等待状态 | 状态变成已取消/等待；不入账 |
| 9.4 | 分享“登录通知/验证码”截图 | 0 条候选，账本 0 新增 | 产生候选 |
| 9.5 | 分享“全部账单/收支明细”列表页 | 不逐条生成候选（提示没有可记录的交易）；列表页属于导入流程 | 一页拆出十几条候选 |

说明：9.3 依赖两条证据同属一张卡片。OCR 没有返回坐标时，实现只能按卡片切分判断，邻近的“已取消”更可能被忽略而不是误判；如果真机上出现误判，记录截图与 OCR 是否给出行坐标。

## 10. 七天保留

| 编号 | 操作 | 必须发生 | 判失败 |
|---|---|---|---|
| 10.1 | 处理完一笔截图后，打开系统相册 | 原始截图仍在相册原位；应用只保留自己的私有副本（`files/page_capture/`） | 相册里的原图被删除或移动 |
| 10.2 | debug 包：`adb shell run-as com.bookkeeping.app ls -l files/page_capture` | 能看到 `candidates.jsonl` 和 `shot_*.jpg`；数量与最近的候选数量相当 | 目录不存在却有待确认条目；副本写在外部存储 |
| 10.3 | 把测试机系统日期 +8 天，重开应用，再看待确认列表 | 超过 7 天的候选和副本消失（应用启动时会清理一次，另有 12 小时一次的定期清理）。破坏性操作，只在一台测试机上做，做完把日期改回 | 8 天后旧候选仍在；旧副本仍占空间 |
| 10.4 | 10.3 之后再看相册 | 相册原图仍在 | 原图被清理 |
| 10.5 | 代码审查 `backup_rules.xml` 与 `data_extraction_rules.xml` | 两份都包含 `<exclude domain="file" path="page_capture/"/>` 和 `<exclude domain="sharedpref" path="screenshot_watch.xml"/>`（cloud-backup 与 device-transfer 都要有） | 任一文件缺排除项 |
| 10.6 | 卸载重装 | 旧候选、旧副本全部消失 | 重装后仍出现旧候选 |

## 11. 已知限制（不是缺陷，验收时要记录而不是判失败）

1. **10 分钟关联窗口 + 唯一候选**：后续完成截图只有在上一条等待候选导入后 10 分钟内到达、且按“平台 + 交易类型 + 金额 + 方向 + 商户”只命中唯一一条等待候选时，才会把旧占位结算为已关联；命中两条、任一侧读不出商户、或订单号对不上都不结算（界面同样只看到 1 条待确认，差别只在 `candidates.jsonl`）。超过 10 分钟旧等待记录会一直留到 7 天过期。见 6.5、12.5。
2. **无订单号不再视为同一笔**：截图去重只有“同平台 + 同订单号”才判定“已在账本里”。同商户、同金额、同方向、同币种、2 分钟内但都没有订单号的两笔，只是“像”，不是“是”——候选一律进待确认，由用户决定；两笔真实消费不会再被静默吞掉。见 8.5、12.1。
3. **商户名或交易类型不一致不关联**：OCR 把同一商户读成两个名字、或把完成页读成另一种交易类型（普通付款 vs 转账 vs 先用后付）、方向相反、或平台不一致时，等待候选不会被结算。属于识别层限制，不要在验收里当成关联逻辑缺陷。
4. **部分照片授权不监听**：Android 14 的“选择照片”授权下监听永远不开启，只保留分享与手动选图。这是有意设计（见 3.3）。
5. **账单列表页不逐条识别**：列表页交给导入流程，不生成候选（见 9.5）。
6. **预览路径没有真机入口**：`previewOnly` 目前只由单元测试覆盖（见 5.6）。
7. **复核页还没有方向选择控件**：未知方向的候选被 `CaptureBookGate` 拦住，点“记入账本”不会写库（提示语暂时复用“仅预览，不能记入账本”），但复核卡片只有商户和分类可改，没有收入/支出切换，所以这类候选目前只能“忽略”。闸门已生效、控件待补：验收按 12.3 判，控件缺失本身记在本条，不判失败。

## 12. 本轮修复对应的验收点（R3 / R5 / R6）

本节只覆盖本轮三处语义修正。每条“操作步骤”都能在一台真机上照做，“应该看到”和“判失败的条件”只看界面或 debug 包的 `candidates.jsonl` 就能判真假，不需要读代码。与第 1–10 节措辞冲突时以本节为准（冲突处已同步改写，编号未变）。

| 编号 | 操作步骤 | 应该看到 | 判失败的条件 |
|---|---|---|---|
| 12.1 | R6：用 M13（同商户、同金额、同平台、两张都不显示订单号的两笔真实付款，间隔 1 分钟内）。先分享第 1 张，等它处理完（入账或进待确认都算），60 秒内分享第 2 张 | 第 1 张按原规则处理（高置信度直接入账 1 行）；第 2 张**不**提示“这笔已经在账本里”，而是弹“请确认这笔交易”或进“待确认扫描结果”，此时账本仍 1 行；点“确认记入”后账本 2 行，金额相同、商户相同、时间差 1 分钟内，导出 CSV 能按“金额 + 商户 + 日期”数到 2 行 | 第 2 张直接提示“这笔已经在账本里”；第 2 张没有任何提示也不进待确认（静默丢弃）；确认后账本仍 1 行且第 2 条候选消失（备注仍是第 1 条的 `[扫描]`、金额没有翻倍——这是入库层又合并了一次，仍按失败记录，见本节末） |
| 12.2 | R6 正向对照：把同一笔支付的详情页连拍两次（同一订单号、两个不同文件），先后分享 | 第 2 张提示“这笔已经在账本里”，账本仍 1 行 | 同一订单号却新增第 2 行（收紧过头，把真重复也放进来了） |
| 12.3 | R3：分享 M14（金额、商户清楚，但没有“支出/收入/付款/收款”字样）。先按正常路径分享一次；再在 debug 包的 `candidates.jsonl` 里找 `"direction":"UNKNOWN"` 的候选（找不到就跳过第二步并标“无样本”） | 正常路径：不产生候选、账本 0 新增，提示“没有可记录的交易”——方向不明确不算可记账交易，更不许默认成支出；若确有 `direction=UNKNOWN` 的候选：复核页点“记入账本”不写库、候选仍留在待确认、界面提示不能记入；通知里“收支类型”是“方向待确认”、金额不带 −¥、动作只有“忽略” | 方向未知却写进账本一行（尤其被当成支出）；有 UNKNOWN 候选时点“记入账本”后候选消失但账本没变（静默丢弃）；通知正文把未知方向写成“支出” |
| 12.4 | R3：分享 M16（“支付成功”只出现在备注/留言/商品说明里，状态位不是“交易成功”或没有状态行，但页面能看出是付款） | 不自动入账，账本 0 新增；弹“请确认这笔交易”，其中“状态”是“交易状态未识别”，“确认原因”是“截图未明确显示交易状态” | 直接入账；“状态”显示“交易已完成”；确认原因写成别的原因（说明把备注当成了状态） |
| 12.5 | R5：先分享 M15-a（待领取转账，商户 A、金额 ¥200）；2 分钟后分享 M15-b（同商户、同额、同样待领取、不带订单号）；再等 1 分钟分享其中一笔的“已完成”页（不带订单号）。debug 包确认前两条都是 `WAITING_EVIDENCE` | 完成页进待确认，待确认只多 1 条，账本 0 行；`candidates.jsonl` 里两条等待记录**都还是** `WAITING_EVIDENCE`，没有任何一条变成 `LINKED_EXISTING` | 两条等待里有一条被结算成 `LINKED_EXISTING`（分不清是哪一笔却猜了一笔）；界面多出两条待确认；账本自动多 1 行 |
| 12.6 | R5：让等待占位不带订单号、完成页带一个两侧都没有的订单号，分享完成页 | 完成页照常进待确认；旧占位仍是 `WAITING_EVIDENCE`（debug），不会因为商户 + 金额 + 方向相同被结算 | 旧占位被结算成 `LINKED_EXISTING`；完成页因为“已关联”而没有进待确认（用户失去确认机会） |

执行说明：

- 12.5、12.6 的决定性证据在 debug 包的 `candidates.jsonl`；release 包只能验证“待确认只多 1 条、账本没有自动多行”，这两条在 release 上标“部分覆盖”，不要用“看起来正常”替代状态核对。
- 12.1 的“确认后仍只剩 1 行”有两个可能的层，报告里必须写清是哪一层：
  - 关联层：第 2 张被判定为 `EXACT_MATCH`，直接提示“这笔已经在账本里”。这是本轮要防的问题。
  - 入库层：候选最后一条状态是 `APPROVED`，但写库时被 `CaptureMergePolicy`（同来源 + 同金额/方向/币种 + 120 秒 + 商户一致即自动合并）并进先入库那行。
  - 区分方法：看 `candidates.jsonl` 最后一条状态——`LINKED_EXISTING` 是关联层，`APPROVED` 而账本只有 1 行是入库层。
- 12.3 的第一步验的是入口：当前 OCR 管线不会把方向不明的页面做成候选（直接按低置信度忽略），所以“闸门”只在已存在 `direction=UNKNOWN` 的候选（旧版本遗留、调试注入或其它采集入口）上才看得到。两种结果都算通过，但报告里要写清命中的是哪一种。
- 每条都先看账本行数与候选状态，再看提示文案；行数和状态都对、只有文案不符的，记“文案问题”，不记“吞账”。

## 13. 验收记录表（空白模板）

| 编号 | 机型 / 系统 | 结果 | 证据 | 备注 |
|---|---|---|---|---|
| 1.1 | | | | |
| 1.2 | | | | |
| 3.3 | | | | |
| 6.4 | | | | |
| 7.1 | | | | |
| 10.3 | | | | |
| 12.1 | | | | |
| 12.3 | | | | |
| 12.5 | | | | |

其余编号按同样格式追加。所有行都填完后，本清单才能作为验收结论使用。

# 附录 A：自动化覆盖审计

统计口径：`android/app/src/test/` 下的 JVM 单元测试（`@Test` 共 86 个与截图相关的用例，分布在下列 9 个文件；这是本文文档基线时的静态清点，本轮修复新增用例后总数会变，A.1 里的用例名才是判据）。`android/app/src/androidTest/` 下 5 个文件与截图无关，不构成覆盖。

## A.1 逐项覆盖

| 验收项 | 自动覆盖 | 仍需手工 |
|---|---|---|
| 1.1–1.6 分享入口 | 决策层：`ScreenshotImportPipelineTest.kt`（`all three intakes go through the same pipeline and produce the same decision`、`completed merchant payment is still auto booked with real transaction time`、`non transaction photo and unknown page are not booked`）。覆盖“三种来源决策一致”“高置信度自动入账”“非交易图不入账” | 分享面板是否出现、Activity 与对话框的实际文案/按钮顺序、“稍后”行为、`[扫描]` 备注 |
| 2.1–2.5 相册选图 | 决策层同上（`PHOTO_PICKER` 作为 `IntakeSource` 参与同一用例）；尺寸上限：`ScreenshotBoundedReaderTest.kt`（`input exactly at max is returned complete`、`unknown-size input exceeding max…`） | 选择器能否拉起、取消行为、超大图提示 |
| 3.1–3.10 监听 | 权限状态机：`ScreenshotWatchPermissionPolicyTest.kt`（`partial photo access never enables MediaStore watcher`、`full photo access enables watcher only after opt in`）。这是 3.1/3.3/3.4 状态判定的唯一自动证据；`UNSUPPORTED`（3.10）没有断言 | 开关与副标题文案、权限弹窗、Android 14 实际授权结果、`ContentObserver` 是否收到回调、`looksLikeScreenshot` 过滤（**无测试**）、旧图是否补抓 |
| 4.1–4.7 通知 | 只有详情文案：`ScreenshotAuditNotificationSafetyTest.kt`（`unknown direction is not presented as an expense`、`known directions remain accurately labelled`）断言 `candidateDetails` 的方向措辞与金额符号。通知构建、动作列表、无 `contentIntent`、处理完刷新下一条**无测试** | 除“方向待确认”文案外的全部手工 |
| 5.1–5.7 零写入 | 闸门：`CaptureBookGateTest.kt`（`allowAuto false holds the candidate back`、`preview only never writes even when auto is allowed`、`preview candidate stays non-writable after review is queued`）；方向闸门：`ScreenshotAuditReviewSafetyTest.kt`（`unknown direction cannot be confirmed`、`known directions preserve permission to confirm`）；决策层：`ScreenshotImportPipelineTest.kt`（`preview only never yields a ledger booking…`、`auto disabled holds the merchant payment instead of booking`、`queued preview candidates remain non-writable`）；候选持久化：`CaptureCandidateTest.kt`（`previewWritePermissionSurvivesCandidateJson`）；失败诊断内容：`ScreenshotImportDiagnosticsTest.kt`（`failure note records the attempt id and the reason code only`、`every failure reason maps to a stable non sensitive code`）。5.6 只有这一层自动证据，没有真机入口 | 每个按钮点下去账本有没有变；诊断页是否真的出现那一行（Room 查询路径无截图相关测试） |
| 6.1–6.3 未完成状态 | `ScreenshotImportPipelineTest.kt`（`pending transfer waits for evidence and is never auto booked`、`cancelled transfer still waits for evidence instead of asking`）；`ScreenshotPageInterpreterSampleShapeTest.kt`（`future debit page keeps one expected amount and waits for evidence`、`cancelled and received transfers stay separate`） | 页面提示文案、账本行数 |
| 6.4–6.6 后续证据 | `ScreenshotWaitingCorrelationTest.kt` 全部 14 个用例，重点：`final statuses settle while in-flight pages change nothing`、`completed transfer settles exactly one waiting transfer`、`a completed payment never closes a waiting transfer`、`two same amount transfers settle at most one and only by order id`、`two plausible waiting candidates stay unresolved`、`a placeholder carrying its own order id is never settled by amount and merchant`、`an order id that matches nothing never falls back to fuzzy fields`、`a waiting candidate without a merchant is not settled by guesswork`、`settled waiting transfer leaves one pending item and no duplicate`、`two waiting items are left untouched when the new evidence is ambiguous`、`evidence outside the correlation window changes nothing`。这是“一条可操作项、不产生重复账本行、歧义不猜”的自动证据；`store.relatedWaiting` + `mark(LINKED)` 的落盘路径也在其中 | 真实截图两次导入的时序、10 分钟边界、OCR 商户与交易类型是否一致、候选状态落盘（`LINKED_EXISTING` vs `WAITING_EVIDENCE`） |
| 7.1、7.3–7.5 时间 | `ScreenshotImportPipelineTest.kt`（`chinese month day resolves strictly against the capture year`、`impossible or future month day stays provisional instead of using capture time`、`date tokens without a readable clock never become the capture day`、`explicit yesterday overrides same day time inference near day end`、`explicit yesterday crosses midnight in the pinned timezone`、`relative day without a safe clock remains provisional at capture time`） | 7.2 的三处“时间待确认”渲染（**UI 无自动测试**）、时区偏移 |
| 7.2 无时间标记 | 数据层：`ScreenshotImportPipelineTest.kt`（`missing real transaction time falls back to capture time and stays marked`）证明候选被标 `TIME_SOURCE_CAPTURE` 且 `eventTimeNeedsConfirmation=true` | 列表徽标、详情标签、待确认卡片三处的实际显示 |
| 8.1–8.2 同图重复 | `ScreenshotImportPipelineTest.kt`（`re sharing the same image does not book again`） | 第二条入口（先分享再选图）的实际提示 |
| 8.3–8.6 同金额不同笔 | `ScreenshotImportPipelineTest.kt`（`same amount a few minutes apart is not merged without matching merchant`、`same amount same merchant within two minutes links to the notification row`（R6 之前）/ `same amount same merchant within two minutes asks instead of linking`（R6 之后，同一用例改名并改断言）、`exact order id links to the existing ledger row instead of adding one`、`same order id on another payment platform is not linked`、`same fuzzy fields on another payment platform are not linked`、`same amount with an ocr missing merchant is review only, never a link`、`ledger row without a merchant name is never an exact match`、`a second unidentifiable row keeps the duplicate decision ambiguous`、`same amount in another currency is never an exact match`）；R6 收紧分支另有 `ScreenshotAuditDedupTest.kt`（`a matching order id is still an exact match`、`same merchant same amount thirty seconds apart without an order id is never an exact match`、`a second same merchant payment two minutes apart reaches review instead of a silent link`）；键规则：`CaptureCandidateTest.kt`（`dedupKeyIgnoresPageIdentityButKeepsOrderIdentity`、`dedupKeySeparatesAmountMerchantAndDirection`） | 两笔真实消费是否真落成两行（受 OCR 商户一致性影响；用户确认后还要过入库层合并，见 12.1 说明） |
| 9.1–9.5 状态隔离 | `ScreenshotPageInterpreterSampleShapeTest.kt`（`refund card beside an ordinary payment keeps both cards typed`、`refund page produces one refund draft and ignores narration and statistics`、`future debit card does not drop another card order total`、`null bounds page keeps completed status over a stray cancellation`、`a bare cancellation with unknown bounds still waits when nothing confirmed`、`cancelled voice call bubble beside payment does not cancel transfer`、`generic cancelled notification bubble beside a completed payment is isolated geometrically`、`far generic cancelled bubble without nearby payment status remains unknown`、`non payment pages produce no drafts at all`、`only currency lines and labelled amounts are money`） | 真实截图的卡片切分效果（取决于 OCR 坐标） |
| 10.1–10.4 保留 | `ScreenshotRetentionTest.kt`（`records younger than seven days are kept`、`exactly seven days old is expired`、`only unreferenced files are orphans`、`index and in-flight intake files are never deleted`）。只覆盖纯规则；`CaptureCandidateStore.pruneExpired` 的真实文件删除没有测试 | 目录实际内容、日期 +8 天后的行为、相册原图 |
| 10.5 备份排除 | **无自动测试**（XML 资源，JVM 测试不加载） | 静态检查两份 XML；可选 `bmgr` |

## A.2 完全没有自动覆盖的层

- `ScreenshotImportActivity`（分享/选图界面、对话框顺序、按钮文案、“稍后”）
- `ScreenshotReviewNotifier` / `ScreenshotReviewReceiver`（通知构建、动作、无 `contentIntent`、处理完刷新下一条；只有 `candidateDetails` 的方向文案有测试）
- `ScreenshotWatchController`（`ContentObserver` 注册、`MediaStore` 查询、`looksLikeScreenshot` 文件名过滤、30 秒宽限、只在启动后新增）
- `ScreenshotIntakeCoordinator`（URI 读取、EXIF 旋转、缩放到 1600px、OCR 引擎选择、真实写库）
- 所有 Compose 渲染：待确认列表、账本列表徽标、详情页“时间待确认”
- 两份备份 XML
- OCR 在真实截图上的识别质量（本仓库没有任何真实截图样本集与准确率基线）

## A.3 结论口径

- 自动化能证明的是**决策规则**：给定同样的文字与坐标，三种入口出同样的结论，哪些情况不入账、哪些要确认、哪些要等待、同图不重复、同金额在无订单号时不误并、等待项只在唯一匹配时结算。
- 自动化不能证明的是**识别与呈现**：OCR 能否从真实截图里读对商户、金额、状态、时间；界面是否把结论展示对。
- 因此本清单第 1、2、3、4 节的全部内容，以及第 7 节的三处标记、第 10 节的目录与备份、第 12 节的六条，只能手工验收。

# 附录 B：本 VPS 不能验证的内容

本次文档编写在 Linux VPS 上完成，环境事实：

- `java -version` 为 OpenJDK 17；工程与 CI 使用 Java 21。
- `/usr/lib/android-sdk` 只有一个空的 `build-tools` 目录：没有 `platforms/android-35`，没有 `sdkmanager`，没有 `adb`，没有模拟器。
- 仓库需要的 `android/app/src/main/assets/asr` 与 `app/libs` 原生依赖未下载。

因此以下事项**没有**在本环境执行，文档中也没有任何相关结论：

1. 没有运行 `./gradlew testDebugUnitTest` 或 `assembleDebug`，附录 A 的 86 个用例只是静态清点，不是本次执行结果。
2. 没有安装 APK，没有真机或模拟器。
3. 没有验证 Android 14 的“选择照片”实际授权回调、通知样式与折叠行为、`ContentObserver` 是否收到 `MediaStore` 变更。
4. 没有真实微信/支付宝截图，没有 OCR 准确率、召回率或误识别率数据。
5. 没有等待 7 天验证过期，也没有验证相册原图是否被动过。
6. 没有验证云备份/设备迁移是否真的排除了 `page_capture/`。

能在别的机器上补做的部分：装有 Android SDK 35 与 JDK 21 的构建机执行 `./gradlew testDebugUnitTest`；真机按本清单逐条执行。两者都完成后，才可以把结果写进第 13 节。
