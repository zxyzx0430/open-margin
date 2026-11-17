# 日历页标题与日历网格对齐记录

## 四个页面顶部标题的改前实值

字号与行高来自 `ui/theme/Type.kt` 的 `Typography`；默认字体缩放为 1.0 时，行高的 sp 数值等于 dp 数值。

| 页面 | 标题水平起点 | typography 档位 | 字号 | 字重 | 行高 |
|---|---:|---|---:|---|---:|
| 日历 | 16dp（页面 Column 的水平 padding） | `headlineSmall` | 24sp | Bold / 700 | 30sp |
| 账单 | 16dp（LazyColumn 的 start contentPadding） | `headlineMedium` | 28sp | Bold / 700 | 34sp |
| 统计 | 16dp（Material 3 TopAppBar 默认标题起点） | `headlineSmall` | 24sp | Bold / 700 | 30sp |
| 设置 | 16dp（Material 3 TopAppBar 默认标题起点） | `headlineSmall` | 24sp | Bold / 700 | 30sp |

日历、统计、设置的标题字号/字重/行高原本一致；账单的「清衡」使用更大的既有 `headlineMedium` 品牌标题档位。四页标题有效左起点均为 16dp，因此不改水平 padding，也不新造 typography。

## 日历标题改前 → 改后

| 项目 | 改前 | 改后 | 理由 |
|---|---|---|---|
| 左起点 | 16dp | 16dp | 与账单内容及统计/设置标题一致；已经对齐。 |
| 字号 / 字重 / 行高 | `headlineSmall`：24sp / Bold 700 / 30sp | 不变 | 已与统计、设置相同；保留既有 typography 档位。 |
| 标题行高度 | 只由 30sp 文本行高决定（页面顶部另有 4dp padding） | `heightIn(min = 56.dp)` | 让标题在 56dp 标题行中垂直居中；默认字号下标题中心从内容起点约 19dp（4 + 30/2）移到 32dp（4 + 56/2），对齐账单标题行和标准 TopAppBar 的标题中心。大字体时行可自然增高。 |

## 星期栏、日期格与内容卡片

日历页面外层 Column 的左右 padding 为 16dp；星期栏 Row、日期网格 Box/各周 Row 与汇总内容卡片都 `fillMaxWidth()`，因此它们的左右外边界都落在页面内容边界 x=16dp / 右侧 16dp 处。日期格内部各自的 2dp padding 是格子间留白，不是网格外边距。检查结果已对齐，未改星期栏或日期格边距。

本次仅调整日历标题行的最小高度并记录数值；未改交互、热力高亮、跨月逻辑、其他页面、数据库或同步。
