package com.bookkeeping.app

import com.bookkeeping.app.i18n.EnglishUiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishUiTextTest {
    @Test
    fun billImportExportLabelsTranslate() {
        val expected = mapOf(
            "账单导入" to "Bill import",
            "导入 / 导出账单" to "Import / export bills",
            "支持微信/支付宝 CSV、XLSX 或文本；确认前不写入账本" to
                "Supports WeChat/Alipay CSV, XLSX or text; nothing is written until you confirm.",
            "选择账单文件" to "Choose bill file",
            "导出全部账单" to "Export all bills",
            "账单已导出" to "Bills exported",
            "账单导出失败" to "Bill export failed",
        )
        expected.forEach { (source, translated) ->
            assertTrue("Missing English UI label: $source", EnglishUiText.hasTranslation(source))
            assertEquals(translated, EnglishUiText.translate(source))
        }
    }

    @Test
    fun transactionAndNavigationTranslations() {
        val expected = mapOf(
            "账单" to "Records",
            "统计" to "Statistics",
            "预算" to "Budget",
            "设置" to "Settings",
            "支出" to "Expense",
            "收入" to "Income",
            "餐饮" to "Food",
            "转账" to "Transfer",
            "已记录" to "Recorded",
            "同步关闭或尚未执行" to "Sync off or not yet run",
            "启用双向同步" to "Enable two-way sync",
            "待更新组件：" to "Update components: ",
            "关于与更新" to "About & updates",
            "关于清衡" to "About Qingheng",
            "清衡 Open Margin" to "Qingheng Open Margin",
            "工作邮箱 zxyzx0430@outlook.com" to "Work email zxyzx0430@outlook.com",
            "GitHub 仓库" to "GitHub repository",
            "开源许可" to "Open-source license",
            "源代码以 GNU GPL v3 发布" to "Source code is released under GNU GPL v3",
            "所有数据不上传到开发者服务器：账本存储在本机，开启同步后保存在你自己的服务器；语音转写在本机完成；反馈只带版本与机型，不含账本内容" to
                "No data is uploaded to the developer's servers: the ledger stays on this device and, with sync enabled, on your own server. Voice transcription runs locally; feedback includes only the app version and device model — never ledger contents.",
            "检查更新" to "Check for updates",
            "浏览全部发布版本" to "Browse all releases",
            "仅 Android APP 更新" to "Android app only",
            "仅 Windows / VPS 更新" to "Windows / VPS only",
            "Android APP 与 Windows / VPS 都有更新" to "Both Android app and Windows / VPS",
            "更新范围未注明" to "Update scope not specified",
            "点选单日 · 长按日期拖动选区间，拖到空白处稍作停留可跨月" to
                "Tap a day · long-press and drag to select a range; pause on the empty space to cross months",
            "分类占比" to "By category",
            "已选中" to "Selected",
            "最高" to "Highest",
            "可在「账单」页查看，列表会自动刷新。" to
                "View them on the Records tab; the list refreshes automatically.",
        )
        expected.forEach { (source, translated) ->
            assertTrue("Missing English UI label: $source", EnglishUiText.hasTranslation(source))
            assertEquals(translated, EnglishUiText.translate(source))
        }
    }

    @Test
    fun windowsQrPairingLabelsTranslate() {
        val expected = mapOf(
            "配对电脑" to "Pair computer",
            "多端同步 · 看板 · MCP" to "Multi-device sync · Dashboard · MCP",
            "扫一扫" to "Scan now",
            "手机、电脑、网页多端同步" to "Sync across phone, computer and web",
            "在手机、电脑和网页之间同步同一份账本。" to
                "Keep one ledger in sync across your phone, computer and web.",
            "电脑端看板" to "Computer dashboard",
            "查看长期趋势、订阅预测、对账和重复复核。" to
                "Review long-term trends, subscription forecasts, reconciliation and duplicate reviews.",
            "MCP 接入" to "MCP integration",
            "接入自己的 AI 客户端，可查账、记账、改账、管理订阅、设置预算。" to
                "Connect your own AI client to query, create and edit records, manage subscriptions and set budgets.",
            "手动填写地址和密钥" to "Enter the address and key manually",
            "二维码无效，请扫描电脑上的清衡配对二维码。" to
                "Invalid QR code. Scan an Open Margin pairing code from your computer.",
            "未获得相机权限，无法扫码，请在系统设置中允许相机权限。" to
                "Camera permission is required to scan. Allow camera access in system settings.",
            "扫码连接" to "Scan QR code",
            "从相册选择二维码" to "Choose QR from gallery",
            "对准电脑上的配对二维码" to "Point at the pairing QR on your PC",
            "② 电脑已开启 Tailscale Funnel，手机不需要安装 Tailscale。" to
                "② Tailscale Funnel is enabled on the PC; the phone does not need Tailscale.",
            "③ 手机用自己的网络或常用 VPN 即可访问 https://电脑名.网络名.ts.net/finance。" to
                "③ The phone can use its own network or usual VPN to reach https://device.tailnet.ts.net/finance.",
            "④ 在电脑完成页点「扫码连接」，或手动填写显示的地址和设备同步密钥。" to
                "④ Tap \"Scan QR code\" on the PC completion page, or type the displayed URL and device key.",
            "二维码内容为空，请重新扫描。" to "The QR code is empty. Scan it again.",
            "二维码里的账本身份无效（至少 16 位）。" to
                "The QR ledger identity is invalid (at least 16 characters).",
        )
        expected.forEach { (source, translated) ->
            assertTrue("Missing English UI label: $source", EnglishUiText.hasTranslation(source))
            assertEquals(translated, EnglishUiText.translate(source))
        }
        listOf(
            "最快的方式：扫描电脑完成页上的配对二维码，会自动填入地址和密钥，再经只读验证后才允许启用。",
            "已读取配对二维码，正在做只读验证…",
            "未获得相机权限，无法扫码。可改用「从相册选择二维码」，或在系统设置中允许相机权限。",
            "所选图片中没有识别到二维码，请换一张清晰的二维码截图。",
            "所选图片无法读取（权限或格式问题），请重新选择。",
            "手机已有成功同步记录，但这次配对的地址或账本身份与历史不同。为避免跨账本覆盖交易，已阻止配对；请先备份原账本并完成显式迁移。",
            "二维码里的账本身份与服务器返回的不一致，已阻止连接。",
            "二维码里的账本身份与验证结果不一致，已阻止启用。",
            "检测到历史同步记录与本次配对不一致，已阻止启用；请先备份并完成显式迁移。",
            "二维码内容过长，已拒绝。请扫描电脑完成页上的配对二维码。",
            "这不是清衡配对二维码（内容格式不正确）。",
            "配对二维码版本不受支持，请把手机 App 更新到最新版本。",
            "这不是清衡的配对二维码。",
            "二维码里的服务器地址无效：需要 https://设备名.网络名.ts.net/finance，且不能带端口、参数或用户名。",
            "二维码里的设备密钥无效（至少 16 位，不能包含空白字符）。",
        ).forEach { source ->
            assertTrue("Missing English UI label: $source", EnglishUiText.hasTranslation(source))
        }
    }

    @Test
    fun historicalSyncStatusesWithDynamicNumbersTranslate() {
        assertEquals(
            "Local transaction 12 is invalid and cannot sync",
            EnglishUiText.translate("本地交易 12 数据异常，无法同步")
        )
        assertEquals(
            "Server returned 503; retrying later",
            EnglishUiText.translate("服务器返回 503，稍后重试")
        )
        assertEquals(
            "Synced non-conflicting records; 3 changes still need review. Do not uninstall.",
            EnglishUiText.translate("已同步无冲突交易；仍有 3 笔修改待核对，请不要卸载应用")
        )
    }

    @Test
    fun unknownUserNotesStayUntouched() {
        // Never translate free-form user data or bank/notification parser data.
        assertEquals("我的自定义商户", EnglishUiText.translate("我的自定义商户"))
    }

    @Test
    fun updateInstallFlowLabelsTranslate() {
        val expected = mapOf(
            "发现新版本" to "New version available",
            "APK 大小" to "APK size",
            "发布日期" to "Release date",
            "下载" to "Download",
            "下载中" to "Downloading",
            "安装" to "Install",
            "下载失败，请重试" to "Download failed. Try again.",
            "没有可下载的正式版 APK" to "No stable APK is available for download.",
        )
        expected.forEach { (source, translated) ->
            assertTrue("Missing English UI label: $source", EnglishUiText.hasTranslation(source))
            assertEquals(translated, EnglishUiText.translate(source))
        }
        // The removed supplementary notice must not linger as an orphan key.
        assertEquals(
            "检查更新仅访问项目的公开 GitHub Releases，不上传财务数据；更新需要手动下载安装。",
            EnglishUiText.translate("检查更新仅访问项目的公开 GitHub Releases，不上传财务数据；更新需要手动下载安装。"),
        )
    }

    @Test
    fun transactionDialogAndImportMessagesTranslate() {
        assertEquals("Edit transaction", EnglishUiText.translate("编辑账单"))
        assertEquals("Date", EnglishUiText.translate("日期"))
        assertEquals("Invalid amount", EnglishUiText.translate("金额格式不正确"))
        assertEquals("Refund", EnglishUiText.translate("退款"))
        assertEquals(
            "Could not parse amount: 12.3.4",
            EnglishUiText.translate("无法解析金额：12.3.4"),
        )
        assertEquals(
            "Duplicate in this file; first occurrence on row 7",
            EnglishUiText.translate("文件内重复，首次出现在第 7 行"),
        )
        assertEquals("Daily average income", EnglishUiText.translate("日均收入"))
        assertEquals("Total expenses this month", EnglishUiText.translate("该月总支出"))
        assertEquals("Set this month's budget", EnglishUiText.translate("设置该月预算"))
        assertEquals("No data this month", EnglishUiText.translate("本月没有数据"))
    }

    @Test
    fun statsReadabilityLabelsTranslate() {
        // 统计页环形图标签、图例展开与分类明细跳转文案：中文是 key，缺一条英文就会静默显示中文。
        assertEquals("Last month's total", EnglishUiText.translate("上月金额"))
        assertEquals("Last year's total", EnglishUiText.translate("上一年金额"))
        assertEquals("Collapse", EnglishUiText.translate("收起"))
        assertEquals("View transaction details", EnglishUiText.translate("查看账单详情"))
        assertEquals("categories", EnglishUiText.translate("类"))
    }

    @Test
    fun calendarScreenLabelsTranslate() {
        // 日历页是「中文是 key」最容易漏的一块：底部 tab、标题、汇总卡、菜单、
        // 空状态、农历开关、周起始日、月份选择器，缺一条英文就会在英文模式里露出中文。
        val expected = mapOf(
            "日历" to "Calendar",
            "日历设置" to "Calendar settings",
            "上个月" to "Previous month",
            "下个月" to "Next month",
            "本月汇总" to "This month's summary",
            "支出" to "Expense",
            "收入" to "Income",
            "结余" to "Balance",
            "笔" to "transactions",
            "清除" to "Clear",
            "清除选择" to "Clear selection",
            "日期高亮：" to "Day highlight: ",
            "关闭" to "Close",
            "周起始日：" to "Week starts on: ",
            "周一" to "Monday",
            "周日" to "Sunday",
            "农历：" to "Lunar calendar: ",
            "显示" to "Show",
            "隐藏" to "Hide",
            "点选单日 · 长按日期拖动选区间，拖到空白处稍作停留可跨月" to
                "Tap a day · long-press and drag to select a range; pause on the empty space to cross months",
            "长按日期拖动选择区间，或点选单日" to
                "Press and drag on a day to select a range, or tap a single day",
            "这一天你还没有任何记账" to "No transactions recorded on this day",
            "这天没有记账" to "No transactions recorded on this day",
            "这段时间你还没有任何记账" to "No transactions recorded during this period",
            "滑动日期选择区间，或点选单日" to "Swipe to select a date range, or tap a single day",
            "未知商户" to "Unknown merchant",
            "退款" to "Refund",
            "待确认重复" to "Possible duplicates",
            "转账" to "Transfer",
            "不计入" to "Excluded",
        )
        expected.forEach { (source, translated) ->
            assertTrue("Missing English UI label: $source", EnglishUiText.hasTranslation(source))
            assertEquals(translated, EnglishUiText.translate(source))
        }
    }

    @Test
    fun monthPickerLabelsTranslate() {
        assertEquals("Select month", EnglishUiText.translate("选择月份"))
        assertEquals("Back to this month", EnglishUiText.translate("回到本月"))
        assertEquals("Back to this month", EnglishUiText.translate("已回到本月"))
        assertEquals("Previous year", EnglishUiText.translate("上一年"))
        assertEquals("Next year", EnglishUiText.translate("下一年"))
        assertEquals("Cancel", EnglishUiText.translate("取消"))
    }

    @Test
    fun quickEntryAndVoiceFabLabelsTranslate() {
        // 表单内的一句话记账、长按 FAB、首次提示：中文是 key，缺一条英文就会在英文模式里露中文。
        val expected = mapOf(
            "比如：11月16日中午12点在麦当劳花了36块5，餐饮，微信支付，备注加班餐" to
                "e.g. Nov 16, 12:00 PM at McDonald's, 36.50, food, WeChat Pay, note: overtime meal",
            "一句话记账" to "Quick entry",
            "例：午餐 36 元，微信支付" to "e.g. Lunch, ¥36, paid with WeChat",
            "可留空；识别后自动填表" to "Optional; recognized text fills the form",
            "写下消费或收入，自动帮你填好账单" to
                "Describe the expense or income and the form fills itself",
            "语音记账" to "Voice entry",
            "新建账单，长按说话记账" to "New transaction; press and hold to speak",
            "长按 1 秒说话，点停止结束" to "Hold for 1 second and speak; tap Stop to finish",
            "没看出金额，请手动补上" to "No amount found; please fill it in",
            "已按这句话填好，请核对金额、分类和时间" to
                "Filled from your sentence; check the amount, category and time",
        )
        expected.forEach { (source, translated) ->
            assertTrue("Missing English UI label: $source", EnglishUiText.hasTranslation(source))
            assertEquals(translated, EnglishUiText.translate(source))
        }
    }

    @Test
    fun voiceCaptureSheetLabelsTranslate() {
        // 录音面板：准备 → 听 → 收尾 → 失败。文案在中文里是 key，英文用户不能看到中文。
        val expected = mapOf(
            "正在准备语音模型…" to "Preparing the voice model…",
            "正在请求麦克风权限…" to "Requesting microphone permission…",
            "请说话…" to "Speak now…",
            "停止" to "Stop",
            "录音中…" to "Recording…",
            "已停止录音" to "Recording stopped",
            "点停止结束，说话时可以直接改" to
                "Tap Stop to finish; you can edit the text while speaking",
            "已暂停自动更新" to "Auto-update paused",
            "恢复" to "Resume",
            "识别更新了，点此替换" to "Recognition updated; tap to replace",
            "填入账单" to "Fill the form",
            "重说" to "Say again",
            "正在识别最后几个字…" to "Finishing the last words…",
            "本地识别，音频不会上传" to "Recognized on this device; audio is never uploaded",
            "去系统设置授权" to "Allow in system settings",
            // 引擎错误码对应的提示（VoiceCaptureLogic.messageFor）。
            "没有麦克风权限，无法语音记账" to
                "No microphone permission, so voice entry is unavailable",
            "安装包缺少语音模型，请重新安装应用" to
                "This build is missing the voice model. Reinstall the app.",
            "语音模型损坏，请重装应用后再试" to
                "The voice model is corrupted. Reinstall the app and try again.",
            "此设备的 CPU 架构不支持本地语音识别" to
                "This device's CPU architecture does not support on-device speech recognition",
            "语音引擎初始化失败，请重试" to "The speech engine failed to start. Try again.",
            "麦克风被占用或打不开，请稍后重试" to
                "The microphone is busy or unavailable. Try again shortly.",
            "语音引擎还没准备好，请重试" to "The speech engine is not ready yet. Try again.",
            "语音会话已经结束，请重新长按" to
                "The voice session has ended. Press and hold again.",
            "没有听清，请再说一次" to "Didn't catch that. Please say it again.",
        )
        expected.forEach { (source, translated) ->
            assertTrue("Missing English UI label: $source", EnglishUiText.hasTranslation(source))
            assertEquals(translated, EnglishUiText.translate(source))
        }
    }

    @Test
    fun voicePrepareProgressTranslate() {
        // 引擎 prepare() 给的进度文案会直接进面板（tr(progress)），英文模式不能露中文。
        assertEquals("Voice model ready", EnglishUiText.translate("语音模型已就绪"))
        assertEquals("Verifying the voice model", EnglishUiText.translate("正在校验语音模型"))
        assertEquals("Loading the speech recognizer", EnglishUiText.translate("正在加载语音识别引擎"))
        assertEquals(
            "Unpacking voice model ctc-chunk-16-left-64.int8.onnx",
            EnglishUiText.translate("正在释放语音模型 ctc-chunk-16-left-64.int8.onnx"),
        )
    }

    @Test
    fun merchantRulesPageLabelsTranslate() {
        // 商户分类记忆页：标题栏、搜索、列表状态、空状态、编辑面板、清除确认。
        val expected = mapOf(
            "商户分类记忆" to "Merchant category memory",
            "添加" to "Add",
            "搜索商户" to "Search merchants",
            "没有匹配的商户" to "No matching merchants",
            "已生效" to "Active",
            "已暂停学习" to "Learning paused",
            "还没有自定义规则" to "No custom rules yet",
            "清除全部分类记忆" to "Clear all category memory",
            "清除全部分类记忆？" to "Clear all category memory?",
            "已清除" to "Cleared",
            "已保存" to "Saved",
            "已删除" to "Deleted",
            "添加规则" to "Add rule",
            "编辑规则" to "Edit rule",
            "最近商户" to "Recent merchants",
            "已有规则" to "Has rule",
            "暂停学习此商户" to "Pause learning for this merchant",
            "删除这条规则？" to "Delete this rule?",
            "请先输入商户名" to "Enter a merchant name first",
            "请选择分类" to "Select a category",
            "改为编辑" to "Edit it instead",
        )
        expected.forEach { (source, translated) ->
            assertTrue("Missing English UI label: $source", EnglishUiText.hasTranslation(source))
            assertEquals(translated, EnglishUiText.translate(source))
        }
    }

    @Test
    fun duplicateWarningAndAccountLabels() {
        // 新建账单：账户预设 + 保存前的疑似重复提示。
        val expected = mapOf(
            "账户" to "Account",
            "不填" to "None",
            "微信" to "WeChat",
            "支付宝" to "Alipay",
            "现金" to "Cash",
            "银行卡" to "Bank card",
            "可能重复" to "Possible duplicate",
            "账本里已有一笔相似的账单。" to "There is already a similar record in the ledger.",
            "仍然记这一笔" to "Record it anyway",
            "正在检查…" to "Checking…",
            "时间" to "Time",
            "商户" to "Merchant",
            "金额" to "Amount",
            "来源" to "Source",
        )
        expected.forEach { (source, translated) ->
            assertTrue("Missing English UI label: $source", EnglishUiText.hasTranslation(source))
            assertEquals(translated, EnglishUiText.translate(source))
        }
    }
}
