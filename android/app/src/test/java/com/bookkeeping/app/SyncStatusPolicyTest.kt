package com.bookkeeping.app

import com.bookkeeping.app.i18n.EnglishUiText
import com.bookkeeping.app.sync.SyncConflictDiff
import com.bookkeeping.app.sync.SyncConflictField
import com.bookkeeping.app.sync.SyncConflictSide
import com.bookkeeping.app.sync.SyncResultCode
import com.bookkeeping.app.sync.SyncState
import com.bookkeeping.app.sync.SyncStateKind
import com.bookkeeping.app.sync.formatConflictAmount
import com.bookkeeping.app.sync.relativeSyncTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状态判定与冲突对比是「状态页说什么」的唯一来源，必须能脱离 Compose 单测。
 */
class SyncStatusPolicyTest {

    @Test
    fun disabledSyncWinsOverEverythingElse() {
        assertEquals(
            SyncStateKind.NOT_ENABLED,
            SyncState.of(enabled = false, configured = true, code = SyncResultCode.AUTH, lastSuccessMs = 1L),
        )
    }

    @Test
    fun invalidCredentialsReadAsNotConfigured() {
        assertEquals(
            SyncStateKind.NOT_CONFIGURED,
            SyncState.of(enabled = true, configured = false, code = null, lastSuccessMs = 0L),
        )
        // Worker 关闭同步时也会写 CONFIG；开关关着的时候仍然先说「同步未开启」。
        assertEquals(
            SyncStateKind.NOT_CONFIGURED,
            SyncState.of(enabled = true, configured = true, code = SyncResultCode.CONFIG, lastSuccessMs = 5L),
        )
    }

    @Test
    fun structuredCodeDrivesStatusWord() {
        assertEquals(
            SyncStateKind.OK,
            SyncState.of(true, true, SyncResultCode.OK, 0L),
        )
        assertEquals(SyncStateKind.RETRY, SyncState.of(true, true, SyncResultCode.RETRY, 0L))
        assertEquals(SyncStateKind.BLOCKED, SyncState.of(true, true, SyncResultCode.BLOCKED, 0L))
        assertEquals(SyncStateKind.AUTH, SyncState.of(true, true, SyncResultCode.AUTH, 0L))
    }

    @Test
    fun noCodeFallsBackToLastSuccess() {
        assertEquals(SyncStateKind.NEVER_RUN, SyncState.of(true, true, null, 0L))
        assertEquals(SyncStateKind.OK, SyncState.of(true, true, null, 1_000L))
    }

    @Test
    fun configurationMatchesWorkerValidation() {
        assertTrue(SyncState.isConfigured("https://pc.tailnet.ts.net/finance", "0123456789abcdef"))
        assertFalse(SyncState.isConfigured("http://pc.tailnet.ts.net/finance", "0123456789abcdef"))
        assertFalse(SyncState.isConfigured("https://pc.tailnet.ts.net/finance", "short"))
        assertFalse(SyncState.isConfigured("", ""))
        assertFalse(SyncState.isConfigured(null, null))
    }

    @Test
    fun reconnectOnlyOfferedWhenSettingsCanFixIt() {
        assertTrue(SyncStateKind.NOT_CONFIGURED.needsReconnect)
        assertTrue(SyncStateKind.AUTH.needsReconnect)
        assertTrue(SyncStateKind.BLOCKED.needsReconnect)
        assertTrue(SyncStateKind.NOT_ENABLED.needsReconnect)
        // 网络类异常：点「立即同步」重试即可，去设置页没有可改的东西。
        assertFalse(SyncStateKind.RETRY.needsReconnect)
        assertFalse(SyncStateKind.OK.needsReconnect)
        assertTrue(SyncStateKind.OK.syncAvailable)
        assertTrue(SyncStateKind.RETRY.syncAvailable)
        assertFalse(SyncStateKind.NOT_ENABLED.syncAvailable)
        assertFalse(SyncStateKind.NOT_CONFIGURED.syncAvailable)
    }

    @Test
    fun conflictDiffNamesEveryChangedField() {
        val phone = SyncConflictSide(
            timestampMs = 1_700_000_000_000L,
            merchant = "星巴克",
            amountCents = 1_200L,
            currency = "CNY",
            category = "FOOD",
        )
        val server = SyncConflictSide(
            timestampMs = 1_700_000_060_000L,
            merchant = "Starbucks",
            amountCents = 1_800L,
            currency = "CNY",
            category = "SHOPPING",
        )
        assertEquals(
            listOf(
                SyncConflictField.TIME,
                SyncConflictField.MERCHANT,
                SyncConflictField.AMOUNT,
                SyncConflictField.CATEGORY,
            ),
            SyncConflictDiff.differingFields(phone, server),
        )
    }

    @Test
    fun identicalContentIsNotPresentedAsDifference() {
        val side = SyncConflictSide(
            timestampMs = 1_700_000_000_000L,
            merchant = "星巴克",
            amountCents = 1_200L,
            currency = "CNY",
            category = "FOOD",
        )
        assertTrue(SyncConflictDiff.differingFields(side, side.copy()).isEmpty())
        // 金额相同但币种不同，仍然是金额项不一致。
        assertEquals(
            listOf(SyncConflictField.AMOUNT),
            SyncConflictDiff.differingFields(side, side.copy(currency = "USD")),
        )
    }

    @Test
    fun unknownTimestampDoesNotFakeADifference() {
        val phone = SyncConflictSide(timestampMs = null, merchant = "星巴克", amountCents = 1_200L)
        val server = SyncConflictSide(timestampMs = 1_700_000_000_000L, merchant = "星巴克", amountCents = 1_200L)
        assertFalse(SyncConflictDiff.differingFields(phone, server).contains(SyncConflictField.TIME))
    }

    @Test
    fun amountAndRelativeTimeUseFixedFormats() {
        assertEquals("CNY 12.00", formatConflictAmount(1_200L, "CNY"))
        assertEquals("CNY 3.00", formatConflictAmount(300L, "CNY"))
        assertEquals("—", formatConflictAmount(null, "CNY"))
        val now = 1_700_000_000_000L
        assertEquals("刚刚", relativeSyncTime(now - 5_000L, now))
        assertEquals("3 分钟前", relativeSyncTime(now - 3 * 60_000L, now))
        assertEquals("2 小时前", relativeSyncTime(now - 2 * 3_600_000L, now))
        assertEquals("尚未同步", relativeSyncTime(0L, now))
    }

    @Test
    fun everyNewSyncLabelHasEnglishText() {
        // 新增文案必须同时给英文，否则英文界面会漏出中文。
        val labels = listOf(
            "同步状态", "待处理", "操作", "重新连接", "最近一次结果", "展开", "复制", "已复制",
            "冲突 %d/%d", "本机", "服务器", "本机这笔记录已不在待上传列表",
            "服务器上找不到这条记录", "不一致", "内容相同，仅版本号不同",
            "日期时间不同：", "商户不同：", "金额不同：", "分类不同：",
            "稍后处理", "稍后处理，仍在待处理列表", "尚未成功同步",
            "%d 笔修改待上传", "%d 笔冲突", "%d 笔待上传",
            "%d 笔冲突待处理，每个按钮只影响对应的一笔", "没有待上传的修改",
            "没有需要逐笔核对的冲突", "无待上传", "逐笔核对后才能继续同步",
            "还有 %d 笔，处理完当前项目后继续查看", "待上传 ",
            "服务器地址、设备同步密钥、双向同步开关", "更换服务器",
            "换地址等于换账本，本地记录不会自动迁移。",
            "换地址等于换账本，本地记录不会自动迁移。请先在原服务器备份并迁移数据；已成功同步的手机不能直接换服务器。",
            "我已备份，继续更换", "已清空地址和密钥：填写新服务器地址和密钥后再保存",
            "短信与 ROM 设置可选",
        )
        labels.forEach { label ->
            assertTrue("Missing English UI label: $label", EnglishUiText.hasTranslation(label))
        }
        assertEquals("Sync status", EnglishUiText.translate("同步状态"))
        assertEquals("Amount differs: ", EnglishUiText.translate("金额不同："))
        assertEquals("Reconnect", EnglishUiText.translate("重新连接"))
    }

    @Test
    fun separatedPendingNumbersReadDifferently() {
        // 待上传和冲突是两件事，文案不能糊在一起。
        assertEquals(
            "Synced; 4 changes still to upload",
            EnglishUiText.translate("已同步；还有 4 笔修改待上传"),
        )
        assertEquals(
            "2 conflicts need review; other changes were uploaded",
            EnglishUiText.translate("有 2 笔冲突待处理；其余修改已上传"),
        )
    }
}
