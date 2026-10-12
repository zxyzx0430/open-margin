# Wallos 原生财务融合 · 2026-10-12

## 已实现

- P0 数据层：复用 `/api/v1/wallos-ledger/month` 为 Finance 的唯一实际账本查询。前端通过统一 `finance-unified-common.php` 的只读模型读取 CNY 日/月支出、收入、净现金流、预算、分类以及排除记录。附加 `previous_same_period_totals`，使当前月与上月**同日数**比较，已结束月份用完整月对比。
- 订阅口径：由 Wallos SQLite 读取当前生效订阅计划，计算 **本月计划到期人民币金额**与**按周期折合的人民币月均成本**；实际订阅占比仅使用 Finance 中已计入的 `SUBSCRIPTION` 类支出。两者并排但不重复求和。外币到期事项单独显示，不做无汇率的加总。
- P1 原生仪表盘：替换跳转卡片，展示本月收支、净现金流、Finance 消费预算、7 日/整月柱图、计划到期清单；保留原生 Wallos 订阅管理和详情组件。Wallos 原有预算明确标注为订阅预算。
- P2 原生日历：支持查看历史月份；在原本的订阅日期单元格中增加实际已计入人民币支出；触摸/点击/键盘选择某天打开详情弹层，列出真实交易、排除金额、收入与订阅计划。支持在弹层直接编辑金额、商户、分类和核对状态，成功后刷新统一数据；失败时不假装成功。
- P3 统计：增加综合财务、消费分类和订阅分析三个内部视图，分类排行可下钻至当月交易；既保留 Wallos 原有订阅统计，又区分实际订阅支出占比、计划到期金额对预算的比例和周期折算的月均固定费用。
- P4 部署工程：基于锁定的 Wallos 上游镜像，把 `index.php`、`calendar.php`、`stats.php` 和新增独立 JS/CSS/PHP 组件固化到镜像；构建校验覆盖所有文件。PWA 不再缓存财务 JSON 或三张动态页面，静态 JS/CSS 网络优先并清理旧缓存。

## 统一的统计原则

- 真实支出、收入、净现金流从 Finance 获取；不使用 Wallet 订阅计划推测扣款。
- `review_status IN (excluded,transfer,refund)` 或 `category=TRANSFER` 不进入已计入总额和柱状图；排除金额仅在明细中展示。
- 计划订阅不计入实际支出。真实订阅占比 = 实际有效订阅支出 / 实际有效全部支出；计划预算负担 = 当前订阅计划本月到期 CNY / 当月 Finance 消费预算。未设置预算或总支出为零时不输出虚构比例。
- 一次性计划不会折算成固定月均成本；年付及季付按周期折算。历史订阅预测使用**当前配置**，不代表已核对的历史扣款。
- 只以人民币作为合计，无法可靠兑换的外币单独注明。

## 交互和系统限制（后续工作）

- Android 本地删除交易目前仍不会自动同步 VPS 的 `excluded` 状态。**此次未改动 Android APK**；需要独立实现可靠的删除/排除同步消息队列和设备验证。不得因此静默从 Finance 图表删掉记录。
- 当前 Finance 编辑路由支持金额、分类和商户；统一弹层暂未提供交易时间的原地编辑（可回到全量流水）。不声称已支持。
- 退款净额与原购买日期间的关联账务仍依赖现有 Finance 规则；后续如果要以净额统一所有页面，需要先明确跨月份退款的财务口径并单独回归测试。
- 外币订阅无法在缺少可靠外汇信息时算入人民币计划金额或月均成本。
- 尚未完成真实 Android 设备的系统级通知抓取、OEM 后台限制测试。测试中的保存请求全部使用模拟后端或被拦截，不修改真实 Finance 账单。

## 源码构成

```text
deploy/wallos-extension/
  index.php
  calendar.php
  stats.php
  finance-unified-common.php
  finance-unified-data.php
  finance-unified-action.php
  finance-unified.js
  finance-unified.css
  Dockerfile
  service-worker.php
backend/wallos_ledger.py
deploy/test_native_finance_browser.py
deploy/test_native_finance_mutations.py
```

## 验收准则

- 新版 PHP 和 JS/CSS 语法检查、构建镜像 SHA 验证、既有 Finance 后端单元测试和 Wallos 原有账本功能回归。
- 与生产隔离的 Wallos 测试容器必须通过 320/390/768/1440px × 亮暗主题 × 仪表盘/日历/统计的 Chromium 交互，以及历史月的 30 天日期定位。
- 编辑、预算、核对等动作使用独立 PHP 模拟接口验证路径、参数、CSRF 拒绝和非法输入拒绝。
- 生产部署后只读校验月度汇总、上月同期、图表日支出、订阅计划；原生 Wallos 订阅详情、导航与旧的日常消费页面仍能使用。

## 实际执行的验收记录

- 后端全量测试：生产版 39/39，开源版 40/40。
- Chromium：仪表盘、日历、统计三页 × 320/390/768/1440px × 亮暗主题 = 24/24；历史月份导航通过。
- 交易写入链路：独立 PHP 模拟后端验证编辑、核对、预算，拒绝无效 CSRF 与非法输入；浏览器测试中正式写请求均被拦截。
- 线上只读复核：生产新镜像 8 个关键页面/脚本 SHA256 与源码一致，Finance 日汇总合计等于月汇总；统计与订阅计划模型正常渲染。
- Wallos 和 Finance API 可用；原订阅详情和消费页面保留。上游页面登录校验对未登录请求保持 HTTP 302。
- Android 本地删除向 VPS 同步排除、退款跨月净额语义、真实 Android 设备测试仍是未完成项目，不能当作通过验收。
