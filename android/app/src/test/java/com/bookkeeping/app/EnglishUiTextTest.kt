package com.bookkeeping.app

import com.bookkeeping.app.i18n.EnglishUiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishUiTextTest {
    @Test
    fun webDavAndCategoryLabelsTranslate() {
        val expected = mapOf(
            "WebDAV 备份目录地址" to "WebDAV backup folder URL",
            "WebDAV 网盘自动备份" to "Automatic WebDAV backup",
            "「合并恢复」只补回缺的账单，不会冲掉新记的账；选「覆盖设置」前会自动在本机悄悄留一份兜底备份。" to "Merge restore adds only missing transactions without replacing new entries. A local safety backup is made before overwriting settings.",
            "上次备份：%s" to "Last backup: %s",
            "上次备份：%s · %s" to "Last backup: %s · %s",
            "仅 Wi-Fi 下自动备份" to "Automatic backup on Wi-Fi only",
            "今天有新账变动时自动备份 1 次" to "Back up once a day when transactions change",
            "保存失败" to "Save failed",
            "保底" to "Required",
            "其中本机还没有的新账单：%d 笔；已有重复账单：%d 笔。" to "New transactions not on this device: %d; existing duplicates: %d.",
            "分类名称（最多 20 字）" to "Category name (up to 20 characters)",
            "分类管理" to "Manage categories",
            "删除「%s」？" to "Delete “%s”?",
            "单机防丢 + 换机搬家 · 只传轻量账本压缩包（不含付款原图），自动留最近 10 份" to "Protect your data and move to a new device. Uploads lightweight ledger ZIPs without payment images and keeps the latest 10.",
            "可新建自定义分类，或删除不需要的分类（「其他」和「转账」保底保留）" to "Create custom categories or remove unused ones (Other and Transfer are required).",
            "合并恢复（推荐）" to "Merge restore (recommended)",
            "合并账单并覆盖设置" to "Merge transactions and overwrite settings",
            "备份中..." to "Backing up...",
            "已删除的系统分类（点击可恢复）" to "Deleted built-in categories (tap to restore)",
            "已启用 %d 个分类 · 可自定义或删除" to "%d active categories · Customize or delete",
            "已配置 · 点击管理备份与恢复" to "Configured · Tap to manage backup and restore",
            "应用密码" to "App password",
            "恢复中..." to "Restoring...",
            "支持坚果云、群晖 NAS、Alist 自动防丢" to "Automatic backup with Nutstore, Synology NAS and AList",
            "新建分类" to "New category",
            "查看并从网盘恢复" to "View and restore remote backups",
            "每日自动备份" to "Daily automatic backup",
            "测试中..." to "Testing...",
            "省流量不走蜂窝网络" to "Save data by avoiding mobile networks",
            "确认从网盘恢复" to "Confirm remote restore",
            "立即备份" to "Back up now",
            "管理" to "Manage",
            "系统保底 · 不可删除" to "Required built-in category · Cannot delete",
            "系统分类" to "Built-in categories",
            "编辑分类" to "Edit category",
            "网盘历史备份（点击可预览并恢复）" to "Remote backup history (tap to preview and restore)",
            "自定义分类" to "Custom categories",
            "读取网盘中..." to "Reading remote backups...",
            "账号（如坚果云注册邮箱）" to "Account (e.g. Nutstore email address)",
            "转移并删除" to "Move and delete",
            "这个分类下目前没有账单，删除后不会影响现有流水。" to "This category has no transactions. Deleting it will not affect existing entries.",
            "这个分类下还有 %d 笔旧账，删掉后要把它们归到哪个分类？" to "This category contains %d transactions. Where should they be moved before deletion?",
            "这份备份里共有 %d 笔账单、%d 项订阅。" to "This backup contains %d transactions and %d subscriptions.",
            "选择图标" to "Choose icon",
            "选择网盘" to "Choose provider",
            "选择颜色" to "Choose color",
        )
        expected.forEach { (key, value) -> assertEquals(value, EnglishUiText.translate(key)) }
    }

    @Test
    fun notificationReplayDiagnosticNamesThePlatformOrderIdIdentity() {
        val summary = "该支付平台的相同订单号已记录，重复通知已跳过"
        val detail = "Exact order ID already recorded for the same payment platform; notification replay skipped"

        assertEquals(
            "The exact order ID is already recorded for this payment platform; notification replay skipped",
            EnglishUiText.translate(summary),
        )
        assertEquals(summary, EnglishUiText.diagnosticDetailTranslationKey(detail))
        assertEquals(
            "The exact order ID is already recorded for this payment platform; notification replay skipped",
            EnglishUiText.translate(EnglishUiText.diagnosticDetailTranslationKey(detail)),
        )
    }

    @Test
    fun screenshotImportConfirmationLabelsTranslate() {
        val expected = mapOf(
            "时间待确认" to "Time to be confirmed",
            "金额待补" to "Amount missing",
            "确认原因" to "Why confirmation is needed",
            "交易已完成" to "Completed",
            "交易处理中" to "In progress",
            "尚未扣款" to "Not charged",
            "交易已取消" to "Cancelled",
            "交易失败" to "Failed",
            "交易状态未识别" to "Status unknown",
            "这类交易需要人工确认" to "This transaction type needs manual confirmation",
            "自动记账已关闭" to "Automatic booking is off",
            "截图未明确显示交易状态" to "The screenshot does not clearly show the transaction status",
            "截图时间为暂用时间，请核对交易时间" to "The screenshot time is a temporary fallback; check the transaction time",
            "请核对截图识别结果" to "Please check the recognised details",
            "确认记入" to "Confirm and record",
            "请确认截图交易" to "Review screenshot transaction",
            "已确认记入 %d 笔截图交易" to "Confirmed %d screenshot transaction(s)",
            "（已更新 %d 笔旧记录）" to "(updated %d earlier item(s))",
            "截图交易确认完成" to "Screenshot review complete",
            "请确认这笔交易" to "Review this transaction",
            "稍后" to "Later",
            "正在更新截图候选" to "Updating the screenshot candidate",
            "候选状态未能更新，请重试" to "Could not update the candidate; try again",
            "这笔交易仍待确认" to "This transaction is still waiting for confirmation",
            "正在记录截图交易" to "Recording the screenshot transaction",
            "这笔交易已在账本中" to "This transaction is already in the ledger",
            "暂时无法记录，请稍后重试" to "Unable to record it right now; try again later",
            "还有 %d 笔待确认" to "%d more waiting for confirmation",
            "仅预览，不能记入账本" to "Preview only; cannot be added to the ledger",
            "待确认存储已满" to "Pending review storage is full",
            "这些识别结果未保存。" to "These results were not saved.",
            "截图或支付页面：现有候选最多保留 7 天，过期后释放名额；之后重新选择截图或重新打开支付页面核对。" to
                "Screenshot or payment page: Existing candidates are kept for up to 7 days. After they expire and free a slot, reselect the screenshot or reopen the payment page to review it.",
            "支付通知：现有候选最多保留 7 天，过期后释放名额；之后打开支付应用的通知或账单，核对后重新采集。" to
                "Payment notification: Existing candidates are kept for up to 7 days. After they expire and free a slot, open the payment app's notifications or bill history, review the transaction, then capture it again.",
            "银行短信：现有候选最多保留 7 天，过期后释放名额；之后在短信应用中查找原消息，核对后重新采集。" to
                "Bank SMS: Existing candidates are kept for up to 7 days. After they expire and free a slot, find the original message in the SMS app, review the transaction, then capture it again.",
            "知道了（仅关闭提示，未保存项目仍需重新采集）" to
                "Got it (closes this warning only; it does not recover the unsaved items)",
            "截图导入未完成" to "Screenshot import incomplete",
            "相册监听" to "Album watcher",
            "分享图片" to "Shared image",
            "选图" to "Photo picker",
            "%s导入未完成：%s。请重新选择原图或从支付应用页面重新采集。" to
                "%s import failed: %s. Select the original image or capture again from the payment app.",
            "请检查图片后重新选择或分享原图。" to
                "Check the image, then reselect or share the original image.",
            "打开待确认列表" to "Open pending reviews",
            "新识别结果未保存" to "Newly recognised result was not saved",
            "新识别结果未保存。请查看现有待确认项；候选保留 7 天，过期释放空间后再从原始来源重新采集。" to
                "Newly recognised information was not saved. Review existing pending items; candidates are kept for 7 days. After they expire and free space, capture the original transaction again.",
            "知道了" to "Got it",
            "待确认候选存储已满；这笔新识别结果未保存。请查看现有候选，候选保留 7 天并在过期后自动清理，再重新识别。" to
                "Pending review storage is full; this new result was not saved. Review the existing candidates; they expire after 7 days, then scan again.",
            "截图暂不可用；记入前请仔细核对提取的金额、商户、收支方向和时间。" to
                "Screenshot unavailable. Check the extracted amount, merchant, direction, and time before recording.",
            "截图未保留（空间不足）；释放空间后重新导入。" to
                "Screenshot was not retained because storage is full. Free space, then import it again.",
            "截图保存失败；请重新选择图片后核对。" to
                "Screenshot save failed. Select the image again and review it.",
            "图片无效，截图未保留；请换图重新导入。" to
                "The image is invalid and was not retained. Choose another image and import it again.",
            "截图未提供或未保留；请重新导入原图。" to
                "The screenshot was missing or not retained. Import the original image again.",
            "截图无法读取；请重新选择图片。" to
                "The screenshot could not be read. Select the image again.",
            "图片格式不支持；请换一张截图。" to
                "This image format is unsupported. Choose another screenshot.",
            "截图未能保留；请重新导入原图。" to
                "The screenshot was not retained. Import the original image again.",
            "疑似与已有记录重复，请核对后再决定是否记入。" to
                "This may duplicate an existing record. Check it before deciding whether to record.",
            "通知记录标识存在歧义，可能是重复提醒，也可能是另一笔交易；请核对后再决定是否记入。" to
                "The notification identity is ambiguous: it may be a replay or another transaction. Review it before recording.",
            "同一张截图里有相似记录，请核对是否重复后再决定是否记入。" to
                "This screenshot contains a similar record. Check whether it is a duplicate before recording.",
        )
        expected.forEach { (source, translated) ->
            assertTrue("Missing English screenshot label: $source", EnglishUiText.hasTranslation(source))
            assertEquals(translated, EnglishUiText.translate(source))
        }
    }

    @Test
    fun screenshotWatchStatesTranslate() {
        // 五种监听状态各一句，且必须互不相同：读起来一样说明状态又糊成一句了。
        val subtitles = listOf(
            "此安装包不含相册监听，用分享或选图",
            "默认关闭，开启后只处理新截图",
            "相册权限已关闭，监听没有运行",
            "只授权了部分照片，监听无法运行",
            "正在监听新增截图，会自动识别",
        )
        assertEquals("screenshot-watch states must read differently", 5, subtitles.toSet().size)

        val expected = mapOf(
            "此安装包不含相册监听，用分享或选图" to "No album watcher here; share or pick a photo",
            "默认关闭，开启后只处理新截图" to "Off by default; only new screenshots are processed",
            "相册权限已关闭，监听没有运行" to "No photo access; watching is off",
            "只授权了部分照片，监听无法运行" to "Partial photo access; watching is off",
            "正在监听新增截图，会自动识别" to "Watching new screenshots automatically",
            "不可用" to "Unavailable",
            "打开系统设置" to "Open system settings",
            "授权全部照片" to "Allow all photos",
            "自动识别新截图" to "Recognise new screenshots",
            "从相册选截图" to "Pick a screenshot",
            "识别微信 / 支付宝交易截图" to "Recognise WeChat / Alipay transaction screenshots",
        )
        expected.forEach { (source, translated) ->
            assertTrue("Missing English screenshot-watch label: $source", EnglishUiText.hasTranslation(source))
            assertEquals(translated, EnglishUiText.translate(source))
        }
    }

    @Test
    fun billImportExportLabelsTranslate() {
        val expected = mapOf(
            "账单导入" to "Bill import",
            "导入 / 导出账单" to "Import / Export bills",
            "导入 CSV、导出全部账单" to "Import CSV, export all bills",
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
    fun diagnosticsAndRemainingUiLabelsTranslate() {
        val expected = mapOf(
            "版本: " to "Version: ",
            "机型: " to "Device: ",
            "Android: " to "Android: ",
            "语言: " to "Language: ",
            "说明: 该报告只含版本与设备信息，不含账单金额、商户、账户或日志内容。" to
                "Note: This report includes only the app version and device details; it contains no bill amounts, merchants, accounts, or log contents.",
            "清衡反馈" to "Qingheng feedback",
            "清衡诊断信息" to "Qingheng diagnostics",
            "清衡崩溃了，日志在这里" to "Open Margin crashed; the log is below.",
            "复制日志" to "Copy log",
            "已复制，粘贴发给 Hermes" to "Copied. Paste it into your message to Hermes.",
            "分享日志" to "Share log",
            "分享崩溃日志" to "Share crash log",
            "将电脑上的二维码放入框内" to "Place the QR code shown on your computer inside the frame.",
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
    fun syncSettingsPageLabelsTranslate() {
        val expected = mapOf(
            "同步中" to "Syncing",
            "同步已关闭" to "Sync is off",
            "上次成功" to "Last successful sync",
            "待上传" to "To upload",
            "查看冲突" to "Review conflicts",
            "服务器地址" to "Server address",
            "尚未配置服务器" to "Server not configured",
            "密钥" to "Key",
            "隐藏密钥" to "Hide key",
            "显示密钥" to "Show key",
            "复制地址" to "Copy address",
            "复制密钥" to "Copy key",
            "重新配对" to "Pair again",
            "扫描电脑上的配对二维码" to "Scan the pairing QR code on your computer",
            "扫码或手动填写电脑上的服务器地址与设备密钥。" to
                "Scan a QR code or manually enter the server address and device key from your computer.",
            "手动填写地址与密钥" to "Enter the address and key manually",
            "高级" to "Advanced",
            "自动同步" to "Automatic sync",
            "请先配对服务器再开启自动同步" to "Pair a server before enabling automatic sync",
            "自动同步已开启" to "Automatic sync enabled",
            "自动同步已关闭" to "Automatic sync disabled",
            "重置连接" to "Reset connection",
            "重置连接说明" to
                "Resetting turns off sync and clears the saved server address and key. Local transactions, pending uploads and conflicts, sync cursors, and server ledger identity are retained. Pair with the original ledger to continue.",
            "重置连接只清除地址和密钥；本机交易与同步账本身份会保留。" to
                "Resetting only clears the address and key; local transactions and server ledger identity are retained.",
            "连接已重置" to "Connection reset",
        )
        expected.forEach { (source, translated) ->
            assertTrue("Missing English UI label: $source", EnglishUiText.hasTranslation(source))
            assertEquals(translated, EnglishUiText.translate(source))
        }
    }

    @Test
    fun unifiedVpsAndWindowsPairingLabelsTranslate() {
        val expected = mapOf(
            "清衡服务器配对" to "Pair Open Margin server",
            "配对服务器" to "Pair server",
            "扫描服务器上的配对二维码" to "Scan the pairing QR code on your server",
            "扫码或手动填写服务器地址与设备密钥。" to "Scan a pairing QR or manually enter the server address and device key.",
            "服务器 HTTPS 地址" to "Server HTTPS address",
            "请输入完整的 HTTPS 服务器地址，例如 https://你的域名/finance" to "Enter the full HTTPS server address, e.g. https://your-domain/finance",
            "对准服务器上的配对二维码" to "Point the camera at the QR code on your server",
        )
        expected.forEach { (source, english) ->
            assertTrue("Missing pairing translation: $source", EnglishUiText.hasTranslation(source))
            assertEquals(english, EnglishUiText.translate(source))
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
        assertEquals("Avg. income/day", EnglishUiText.translate("日均收入"))
        assertEquals("Avg. spend/day", EnglishUiText.translate("日均支出"))
        assertEquals("Total expenses this month", EnglishUiText.translate("该月总支出"))
        assertEquals("Set this month's budget", EnglishUiText.translate("设置该月预算"))
        assertEquals("No data this month", EnglishUiText.translate("本月没有数据"))
    }

    @Test
    fun statsReadabilityLabelsTranslate() {
        // 统计页环形图标签、图例展开与分类明细跳转文案：中文是 key，缺一条英文就会静默显示中文。
        assertEquals("Last month", EnglishUiText.translate("上月金额"))
        assertEquals("Last year", EnglishUiText.translate("上一年金额"))
        assertEquals("Month", EnglishUiText.translate("月度"))
        assertEquals("Count", EnglishUiText.translate("当日交易笔数"))
        assertEquals("Expenses", EnglishUiText.translate("当日支出"))
        assertEquals("Income", EnglishUiText.translate("当日收入"))
        assertEquals("Balance", EnglishUiText.translate("当日结余"))
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
        assertEquals("Return to this month", EnglishUiText.translate("回到本月"))
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
            "例如：午饭 25 微信" to "e.g. Lunch ¥25 · WeChat",
            "打车 18 支付宝" to "Taxi ¥18 · Alipay",
            "没认出金额，请手填" to "No amount recognized; enter it manually",
            "移除" to "Remove",
            "写下消费或收入，自动帮你填好账单" to
                "Describe the expense or income and the form fills itself",
            "语音记账" to "Voice entry",
            "新建账单，长按说话记账" to "New transaction; press and hold to speak",
            "长按开始说话，点停止结束" to "Hold to speak; tap Stop to finish",
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
            "已停止录音" to "Stopped",
            "点停止结束，说话时可以直接改" to
                "Tap Stop to finish; edit as you speak",
            "已暂停自动更新" to "Auto-update paused",
            "恢复" to "Resume",
            "识别更新了，点此替换" to "Updated; tap to replace",
            "填入账单" to "Fill form",
            "重说" to "Say again",
            "正在识别最后几个字…" to "Finishing recognition…",
            "本地识别，音频不会上传" to "On-device; audio isn't uploaded",
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
            "没有听清，请再说一次" to "Didn't catch that. Try again.",
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
            "批量整理其他分类" to "Organize other categories",
            "记住该商户规则，后续自动归类" to "Remember this merchant for future categorization",
            "同时更新该商户历史记录（共 %d 笔）" to "Also update this merchant’s past entries (%d)",
            "保存后，该商户的新记录将自动归类" to "Future entries from this merchant will use this category automatically",
            "请输入具体商户名，占位名称不能保存为规则" to "Enter a specific merchant name; placeholder names cannot be saved as rules",
            "已整理 %d 笔记录" to "Updated %d entries",
            "当前没有待整理的「其他」记录" to "No uncategorized “Other” entries right now",
            "共 %d 个商户 · %d 笔待整理" to "%d merchants · %d entries to organize",
            "全部「其他」记录已整理完成" to "All “Other” entries are organized",
            "单笔处理" to "Single entry",
        )
        expected.forEach { (source, translated) ->
            assertTrue("Missing English UI label: $source", EnglishUiText.hasTranslation(source))
            assertEquals(translated, EnglishUiText.translate(source))
        }
    }

    @Test
    fun budgetLabelsTranslateAndUseCompactActionCopy() {
        val expected = mapOf(
            "月预算" to "Monthly budget",
            "已使用" to "Used",
            "预算剩余" to "Budget remaining",
            "剩余天数" to "Days left",
            "本月节奏" to "This month's pace",
            "预算建议" to "Budget advice",
            "调整预算" to "Adjust",
            "添加分类" to "Add category",
            "按当前节奏，本月可能超支" to "At the current pace, this month may overspend",
            "按当前节奏，本月可控，预计可结余" to
                "At the current pace, this month is under control; projected remaining",
            "建议后续控制在日均 ≤" to "keep future daily spending at or below",
            "建议调整预算或减少后续支出。" to
                "consider adjusting the budget or reducing future spending.",
        )
        expected.forEach { (source, translated) ->
            assertTrue("Missing English UI label: $source", EnglishUiText.hasTranslation(source))
            assertEquals(translated, EnglishUiText.translate(source))
        }
    }

    @Test
    fun systemNotificationLabelsTranslate() {
        val expected = mapOf(
            "清衡 OpenMargin" to "Open Margin",
            "自动记账服务正常运行中" to "Auto capture service is running normally",
            "正在确认运行状态" to "Checking capture status",
            "通知使用权已关闭 · 点击开启" to "Notification access is off · tap to enable",
            "尚未连接过 · 点击检查权限" to "Has not connected yet · tap to check permission",
            "上次连接 %s，当前未连接 · 点击检查权限" to
                "Last connected %s; currently disconnected · tap to check permission",
            "%s 断开 · 已断开 %s · 点击检查权限" to
                "Disconnected at %s · down for %s · tap to check permission",
            "去检查权限" to "Check permission",
            "采集运行状态" to "Capture status",
            "显示自动记账采集是否在运行" to "Shows whether automatic capture is running",
            "自动记账提醒" to "Auto-capture notifications",
            "保存成功后显示金额、商户和时间" to
                "Shows amount, merchant and time after a successful save",
            "自动记账成功" to "Transaction saved",
            "已合并重复采集" to "Duplicate capture merged",
            "已保存，待确认重复" to "Saved — possible duplicate",
            "已合并到已有记录" to "Merged into the existing record",
            "可能与已有记录重复" to "May duplicate an existing record",
            "微信" to "WeChat",
            "支付宝" to "Alipay",
            "银行短信" to "Bank SMS",
            "手动" to "Manual",
            "其他来源" to "Other source",
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
            "正在保存…" to "Saving…",
            "保存失败，请重试" to "Save failed. Try again.",
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

    @Test
    fun siteUpdateLabelsTranslate() {
        val expected = mapOf(
            "应用更新" to "App updates",
            "新版本已下载，点此安装" to "New version downloaded. Tap to install",
            "新版本提醒" to "New version banner",
            "自动下载并安装新版本" to "Download and install updates automatically",
            "预览更新提醒" to "Preview update banner",
            "测试包仅预览，不下载安装" to "Test build: preview only, no download or install",
            "安装包签名与当前应用不一致，已删除" to
                "APK signature does not match this app; file deleted",
        )
        expected.forEach { (source, translated) ->
            assertTrue("Missing English UI label: $source", EnglishUiText.hasTranslation(source))
            assertEquals(translated, EnglishUiText.translate(source))
        }
    }
}
