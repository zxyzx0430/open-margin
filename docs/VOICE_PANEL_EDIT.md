# 语音面板：按住即录、松手结束、面板内编辑

本文是「长按 + 说话记账」的交互约定，配合 `docs/VOICE_ASR_ENGINE.md` 读：

- 引擎/模型/包体在那篇（识别怎么出字）；
- 这篇只讲**手势时序、面板结构、编辑冻结规则、什么时候才允许填表**。

代码位置：

| 文件 | 职责 |
| --- | --- |
| `ui/components/VoiceFab.kt` | 按压手势、进度弧、震动、松手/滑走的判定 |
| `ui/components/QuickEntryRule.kt` | 时间常量 + `releaseAction()`（纯函数，单测覆盖） |
| `ui/screen/transactions/VoiceCapturePanel.kt` | 同窗口覆盖层面板、引擎会话、权限、键盘 |
| `ui/screen/transactions/VoiceCaptureLogic.kt` | 状态机 + 冻结/替换/交付判定（纯 JVM） |
| `ui/navigation/RootNavigation.kt` | 面板开合、松手信号、把确认后的文本交给账单页 |

---

## 1. 手势时序：满 1 秒就开始录，不是松手才录

| 时刻 / 事件 | 震动 | 进度弧 | 面板 | 引擎 | 回调 |
| --- | --- | --- | --- | --- | --- |
| 按下 | — | 从 0 开始扫，图标换成麦克风 | 关 | 空闲 | — |
| 500ms | 轻震（TextHandleMove） | 半圈 | 关 | 空闲 | — |
| **1000ms** | **重震（LongPress）** | 满格 | **立即打开** | **`startSession()` 开始录音** | `onHoldStart` |
| 1000ms 之后 | — | 保持满格 | 开着，Partial 实时上屏、可编辑 | 录音中 | — |
| 松手（已开录） | — | 140ms 回弹 | 留在 Review（同一输入框） | `finishInput()`：补 0.6s 静音 → Final | `onHoldRelease` |
| 松手（<1s） | — | 回弹 | 不开 | 不动 | `onTap` → 新建账单表单 |
| 已开录 + 手指滑出两倍触摸阈值 | — | 回弹 | 关闭 | `cancelSession()` | `onHoldAbort` |
| 未开录 + 滑出 / 系统取消 | — | 回弹 | 不开 | 不动 | 无 |

```
按下 ──0.5s(轻震)──1.0s(重震/弧满/开面板/开始录音)──────────松手──> Review（面板不关）
  │                    │                                        │
  │                    └─ 滑出阈值 ──> 取消录音 + 关面板（不填表） │
  └─ <1s 松手 ──> 普通点击：打开新建账单表单，不录音              │
                                                             用户点「填入账单」──> Delivered（解析+表单）
                                                             用户点「取消/下滑/返回」──> Cancelled（不填表）
```

要点：

1. **开录不是松手触发的**。`VoiceFab` 的计时循环跑到 `HOLD_TO_RECORD_MS` 这一帧就回调
   `onHoldStart`，同步把面板挂上、把 `startSession()` 打出去。
2. **松手只结束录音**。`onHoldRelease` 里只做 `finishInput()` + 状态迁移到 Review，
   不交付、不关面板。
3. **滑走 = 整段作废**。开录之后滑出阈值走 `ABORT_RECORDING`（取消 + 关面板）；
   没开录就滑走走 `NOTHING`（既不算点击也不算录音）。判定在
   `QuickEntryRule.releaseAction(armed, cancelled)`，四条分支都有单测。

---

## 2. 为什么面板不是 `ModalBottomSheet`

`ModalBottomSheet` 是 `Dialog`：满 1 秒打开面板的瞬间，新窗口拿到焦点，正在按着 FAB 的那一段
触摸会被系统 `ACTION_CANCEL`，`UP` 永远收不到——「松手结束录音」直接失效（而且看起来像
随机不结束）。

所以面板改成**同一个窗口里的覆盖层**：

```kotlin
Box(Modifier.fillMaxSize()) {
    Scaffold(...)                      // 账单页 + 底部导航 + FAB
    if (voiceSheetOpen) VoiceCapturePanel(...)   // 全屏遮罩 + 底部 Surface
}
```

- FAB 的 `pointerInput` 用固定 key（`pointerInput(Unit)`），`enabled` 只在**按下那一刻**检查，
  已经开始的这一段不受面板打开影响，`UP` 照常送到 FAB。
- 面板自己实现：遮罩点击取消、顶部拖柄/标题下滑关闭（超过 72dp 取消）、`BackHandler` 返回取消。
- 代价是没有现成的 sheet 动效，入场用 160ms 淡入 + 180ms 上滑（`animateFloatAsState` /
  `animateDpAsState`）补齐。

---

## 3. 状态机

`VoiceCaptureState`（`VoiceCaptureLogic.kt`，无 Android 依赖）：

| 状态 | 含义 | 面板 |
| --- | --- | --- |
| `Preparing(detail)` | 装模型 / 等权限 | 进度 + 「取消」 |
| `Listening(text, autoFollow, suggestedReplacement)` | 手指还按着，麦克风开着 | 可编辑输入框 + 「停止」「取消」 |
| `Review(text, autoFollow, waitingFinal, suggestedReplacement)` | 已松手，麦克风已停 | 同一输入框 + 「填入账单 / 重说 / 取消」 |
| `Failed(code, message)` | 可重试失败 | 「重试」「取消」，权限被拒多一个「去设置」 |
| `Delivered(request)` | 文本已交给上层 | 面板收起（过渡帧） |
| `Cancelled` | 用户取消 | 面板收起，之后的事件全部丢弃 |

迁移（除 `Delivered`/`Cancelled` 外都可逆）：

```
Preparing ──startSession 成功──> Listening ──release()──> Review ──submit()──> Delivered
    │                              │                       │
    └── acquire 失败 ──> Failed <──┴── Error ───────────────┘（Review 有文本时保留文本）
任何状态 ──cancel()（按钮/下滑/返回/切后台/滑走）──> Cancelled
```

---

## 4. 编辑与冻结规则（核心）

输入框文本的真源是 `Listening.text` / `Review.text`，Compose 侧只镜像一份 `TextFieldValue`
（保存光标）。规则全部在 `VoiceCaptureLogic`：

| 场景 | 文本 | `autoFollow` | chip「识别更新了，点此替换」 |
| --- | --- | --- | --- |
| 没动键盘，来了一条 Partial | 直接覆盖 | true | — |
| 用户改了任意一个字 | 保留用户的字 | **false（冻结）** | — |
| 冻结后 Partial 继续来 | **不覆盖** | false | — |
| 冻结后 Final 与当前文本不同 | **不覆盖** | false | **显示**，点了才替换 |
| 冻结后 Final 与当前文本相同 | 不覆盖 | false | 不显示（没有意义） |
| 点「恢复」 | 有 chip 就采用 chip，否则保持 | **true** | 清空 |
| 点 chip | 换成识别结果 | 仍是 false（用户已明确选择） | 清空 |
| 没动键盘，Final 到达 | 直接采用 Final | true | — |
| 没动键盘，Final 是空串 | 保留已显示的 Partial | true | — |

两条硬约定：

1. **绝不静默覆盖用户改过的字**。冻结之后识别结果只能变成一条待点的 chip。
2. **Partial 只刷新预览**。`withPartial()` 在非 Listening 或 `autoFollow == false` 时原样返回。

界面上的三个提示（都有英文）：

- Listening + 跟随中：「松手结束，说话时可以直接改」
- Listening + 冻结：「已暂停自动更新」+「恢复」按钮
- 冻结期间收到不同的 Final：chip「识别更新了，点此替换」

---

## 5. 什么时候才允许填表

`VoiceCaptureLogic.submit(state)` 是**唯一**交付入口：

```kotlin
fun submit(state: VoiceCaptureState): VoiceCaptureState.Delivered? {
    if (state !is Review) return null          // 录音中/准备中/失败/已取消都不交付
    if (!isSubmittable(state.text)) return null // 空、纯空白、纯标点都不交付
    ...
}
```

| 情况 | 结果 |
| --- | --- |
| 松手（Listening → Review） | 不交付，面板留着 |
| 文本为空 / 只有标点 | 主按钮变「重说」，提示「没有听清，请再说一次」 |
| 点「填入账单」 | `Delivered(VoiceEntryRequest(trim(text)))` → 上层解析 + 开表单 |
| 点「取消」/ 遮罩 / 下滑 / 返回键 / 切后台 | `Cancelled`，不填表、不写库 |
| 收尾超时（5s 没 Final） | `waitingFinal = false`，**保留文本**，用户可继续改或直接填 |
| Review 阶段引擎报错 | 保留文本（引擎挂了不该连用户改好的字一起没收） |

「有意义」的判定是 `isSubmittable()`：trim 之后至少有一个字母或数字（中文按字母算）。
`36.5` 可以，`。`、`，、！？`、` - ` 不行。

---

## 6. 键盘

- 面板底部对齐，外层用
  `windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))`：
  底部 inset 取 `max(导航栏, 输入法)`，键盘弹出时整块面板上推。
- 输入框与主按钮在同一个 `Column` 里、按钮在输入框下方，所以**输入法不会只盖住按钮**。
- 输入框 `minLines = 2, maxLines = 5`，多行大字；长句不会把面板顶穿。
- 不自动聚焦：松手后键盘不会自己弹出来打断阅读，点一下输入框才起键盘（真机清单里要确认手感）。

---

## 7. 引擎常驻语义没有变

面板只调 `startSession()` / `finishInput()` / `cancelSession()`：

- 松手 → `finishInput()`（补 0.6s 静音，等 Final）；
- 取消 / 下滑 / 返回 / 切后台 / 组合销毁 → `cancelSession()`（放掉麦克风，识别器留在 READY）；
- **任何路径都不 `close()` 引擎**。模型常驻在 `VoiceAsrHolder`，下一次长按直接开口说话。

---

## 8. 纯 JVM 单测

`android/app/src/test/java/com/bookkeeping/app/ui/screen/transactions/VoiceCaptureLogicTest.kt`
（`./gradlew testDebugUnitTest`，CI 每次构建都跑）：

| 测试 | 钉住什么 |
| --- | --- |
| `finalTextIsTrimmedAndBlankBecomesNull` | Final 去首尾空白；空串/纯空格/换行 → `null` |
| `onlyTextWithLettersOrDigitsCanBeSubmitted` | 纯标点/空白不能提交；`36.5`、中文可以 |
| `partialOnlyRefreshesPreviewWhileListening` | Partial 只在 Listening 生效；Review/Failed/Delivered/Cancelled 丢弃 |
| `editingFreezesPartialRefresh` | **编辑后 Partial 不覆盖** |
| `resumeAutoFollowFollowsNewPartialsAgain` | 点「恢复」后重新跟随 |
| `releaseKeepsTextAndIgnoresRepeats` | 松手保留文本、连点停止幂等、准备阶段收不到 |
| `releaseNeverDeliversAnything` | **松手不交付**（提交只认 Review） |
| `releaseBeforeSessionStartsFinishesOnStart` | 松手早于会话就绪 → 会话一开就收尾 |
| `finalWithoutEditsReplacesText` | 没编辑时 Final 直接采用 |
| `emptyFinalKeepsTextAndDeliversNothing` | 空 Final 保留文本、不交付 |
| `finalThatDiffersFromEditedTextIsSuggestedNotApplied` | **Final 不同不静默替换**，只给 chip |
| `finalThatMatchesEditedTextDoesNotSuggestReplacement` | 相同则不出无意义 chip |
| `acceptingReplacementUsesRecognizedTextAndStopsOverwriting` | 点 chip 才替换；之后的迟到 Final 不改字 |
| `resumeAutoFollowPicksUpPendingReplacement` | 恢复时一并采用待替换结果 |
| `finalOutsideListeningOrReviewIsIgnored` | 准备/失败/已交付/已取消收到 Final 不翻盘 |
| `submitOnlyFromReviewAndOnlyWithMeaningfulText` | 空文本、纯标点、录音中、失败态都不能提交 |
| `cancelIsTerminalAndNeverDelivers` | **取消不交付**，之后的事件全部丢弃 |
| `finishTimeoutOnlyAppliesWhileWaitingForFinal` | 超时只停转圈，文本与可提交性都在 |
| `errorKeepsEditableTextWhenAlreadyInReview` | Review 报错保留文本；聆听中报错才转失败态 |
| `everyErrorCodeHasDistinctReadableMessage` | 每个 `AsrErrorCode` 都有独立中文提示；null 码不崩 |
| `everyErrorMessageHasEnglishTranslation` | 错误提示都有 EnglishUiText 条目 |
| `permissionFailureKeepsTheSettingsEscapeHatch` | 权限被拒保留「去设置」 |

手势判定在 `QuickEntryRuleTest.releaseDecidesBetweenTapRecordAndAbort`：
`TAP / NOTHING / FINISH_RECORDING / ABORT_RECORDING` 四条分支。

---

## 9. 真机待测清单

CI 只能证明「能编译、能打包、纯逻辑单测过」。下面这些必须上真机（尤其国产 ROM）：

1. **按住即录**：按住 + 满 1 秒 → 一次重震、面板立刻出现、开始说话；**不要**松手才开始。
   说 3~4 个字时面板里已经能看到 Partial。
2. **松手即停**：说话中途松手 → 面板不关，立刻出现「已停止录音」，半秒内 Final 把最后几个字补齐。
3. **不到 1 秒**：轻点 → 只弹新建账单表单，不出现面板、不亮麦克风指示。
4. **滑走取消**：满 1 秒后把手指拖出 FAB 再松手 → 面板关闭、无表单、系统麦克风指示消失。
5. **说话中改字**（两只手）：一只手指按住 FAB，另一只点输入框改字 → 改完继续说话，
   文本**不被 Partial 冲掉**，出现「已暂停自动更新」+「恢复」。
6. **恢复跟随**：点「恢复」→ 提示消失，下一段 Partial 重新上屏。
7. **Final 替换 chip**：改过字之后等 Final 到达 → 文本不变，出现「识别更新了，点此替换」；
   点一下才变成识别结果。若 Final 与改后的字相同，不出现 chip。
8. **空/纯标点**：捂着麦克风说一句 → 松手 → 主按钮是「重说」+「没有听清，请再说一次」；
   点「重说」重新开录（不需要重新长按 FAB）。
9. **键盘遮挡**：Review 里点输入框 → 键盘弹出，面板整体上推，输入框和「填入账单」都在
   键盘上方；长按键盘上的回车换行，面板不被顶穿。
10. **取消路径**：「取消」按钮、点遮罩、下滑面板、返回键、切后台/锁屏 —— 五种都不填表、
    不新增账单，麦克风指示消失。
11. **填入账单**：改完点「填入账单」→ 面板收起 → 新建表单已打开、顶部一句话输入框是
    面板里的最终文本（不是 Partial，也不是被覆盖掉的旧值）。
12. **面板重开不残留**：填入 → 再长按 → 面板从「准备/聆听」重新开始，不会一开就自我收尾
    （`voiceStopRequested` 已消费）。
13. **连按**：面板开着时连点 FAB 数次 → 只有一个会话、不会叠面板。
14. **引擎常驻**：连续 20 次「长按→取消」，`adb shell dumpsys meminfo <pkg> | grep -i native`
    不应持续增长；每次重开面板应在 1 秒内出字（模型没被卸载）。
15. **英文界面**：切 English → 面板所有新文案（录音中/已停止录音/已暂停自动更新/恢复/
    识别更新了，点此替换/填入账单/重说/松手结束…）都是英文。
16. **TalkBack**：FAB 长按动作能开录（不必一直按住），面板里的「停止」结束录音，
    输入框可读写，按钮都能聚焦。
