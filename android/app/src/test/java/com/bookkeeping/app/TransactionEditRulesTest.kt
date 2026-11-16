package com.bookkeeping.app

import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionSource
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.sync.UploadPayload
import com.bookkeeping.app.ui.screen.transactions.TransactionEditFields
import com.bookkeeping.app.ui.screen.transactions.TransactionEditRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** 全字段编辑页的校验与写回逻辑；页面只做展示，规则全在这里。 */
class TransactionEditRulesTest {

    private fun entity(
        amountCents: Long = 1234L,
        type: String = TransactionType.EXPENSE.name,
        category: String = TransactionCategory.FOOD.name,
        merchant: String = "星巴克",
        note: String = "",
        account: String? = "微信",
        source: String = TransactionSource.WECHAT.name,
        timestamp: Long = 1_760_000_000_000L,
        reviewStatus: String = "unverified",
        duplicateOfId: Long? = null,
        syncDirty: Boolean = false,
        remoteEventId: String? = "evt-1",
    ) = TransactionEntity(
        amountCents = amountCents,
        type = type,
        category = category,
        merchant = merchant,
        note = note,
        account = account,
        source = source,
        timestamp = timestamp,
        remoteEventId = remoteEventId,
        remoteReviewStatus = reviewStatus,
        duplicateOfId = duplicateOfId,
        syncDirty = syncDirty,
    )

    private fun fields(
        amountCents: Long = 1234L,
        type: String = TransactionType.EXPENSE.name,
        category: String = TransactionCategory.FOOD.name,
        merchant: String = "星巴克",
        note: String = "",
        account: String = "微信",
        source: String = TransactionSource.WECHAT.name,
        timestampMs: Long = 1_760_000_000_000L,
        reviewStatus: String = "unverified",
    ) = TransactionEditFields(
        amountCents = amountCents,
        type = type,
        category = category,
        merchant = merchant,
        note = note,
        account = account,
        source = source,
        timestampMs = timestampMs,
        reviewStatus = reviewStatus,
    )

    @Test
    fun amountMustBePositiveWithAtMostTwoDecimals() {
        assertNull(TransactionEditRules.amountError("12.34"))
        assertNull(TransactionEditRules.amountError("12"))
        assertEquals("金额最多保留两位小数", TransactionEditRules.amountError("12.345"))
        assertEquals("金额不能为负数，请用支出/收入切换方向", TransactionEditRules.amountError("-12"))
        assertEquals("金额不能为负数，请用支出/收入切换方向", TransactionEditRules.amountError("−12"))
        // 0 元不是有效账单（MoneyAmount.cents 也拒绝）
        assertEquals("请输入有效金额", TransactionEditRules.amountError("0"))
        assertEquals("请输入有效金额", TransactionEditRules.amountError("0.00"))
        assertEquals("请输入有效金额", TransactionEditRules.amountError(""))
        assertEquals("请输入有效金额", TransactionEditRules.amountError("abc"))
        assertEquals(1234L, TransactionEditRules.amountCents("12.34") ?: -1L)
    }

    @Test
    fun timestampAllowsFiveMinuteClockSkewOnly() {
        val now = 1_760_000_000_000L
        assertNull(TransactionEditRules.timestampError(now, now))
        assertNull(TransactionEditRules.timestampError(now + 4 * 60_000L, now))
        assertEquals(
            "时间不能晚于当前时间 5 分钟以上",
            TransactionEditRules.timestampError(now + 6 * 60_000L, now),
        )
        assertNull(TransactionEditRules.timestampError(now - 365L * 24 * 60 * 60 * 1000, now))
    }

    @Test
    fun reviewStatusesMatchTheSyncPayloadAndStayUserSelectable() {
        // 不新造审核状态：编辑页接受的集合必须覆盖上传层已有的全部取值。
        assertTrue(
            TransactionEditRules.STORED_REVIEW_STATUSES.containsAll(UploadPayload.REVIEW_STATUSES)
        )
        assertTrue(TransactionEditRules.SELECTABLE_REVIEW_STATUSES.isNotEmpty())
        assertTrue(
            TransactionEditRules.STORED_REVIEW_STATUSES.containsAll(
                TransactionEditRules.SELECTABLE_REVIEW_STATUSES
            )
        )
        // 自动标记不提供给用户手动选择。
        assertFalse("possible_duplicate" in TransactionEditRules.SELECTABLE_REVIEW_STATUSES)
        // refund 只由服务端下发，同步白名单里没有它（协议不改）。
        assertTrue("refund" in TransactionEditRules.SELECTABLE_REVIEW_STATUSES)
        assertFalse("refund" in UploadPayload.REVIEW_STATUSES)
        TransactionEditRules.SELECTABLE_REVIEW_STATUSES.forEach {
            assertNull(TransactionEditRules.reviewStatusError(it))
        }
        assertEquals("请选择有效审核状态", TransactionEditRules.reviewStatusError("bogus"))
        assertEquals("请选择有效审核状态", TransactionEditRules.reviewStatusError(""))
    }

    @Test
    fun categoryAndSourceMustComeFromExistingEnums() {
        assertNull(TransactionEditRules.categoryError("FOOD"))
        assertEquals("请选择有效分类", TransactionEditRules.categoryError("GROCERIES"))
        assertNull(TransactionEditRules.sourceError("BANK_SMS"))
        assertEquals("请选择有效来源", TransactionEditRules.sourceError("WECHAT_PAY"))
    }

    @Test
    fun errorsOfFlagsEveryIllegalField() {
        val now = 1_760_000_000_000L
        val clean = TransactionEditRules.errorsOf(fields(timestampMs = now), now)
        assertTrue(clean.isValid)

        val broken = TransactionEditRules.errorsOf(
            fields(
                amountCents = 0L,
                category = "NOPE",
                source = "NOPE",
                reviewStatus = "NOPE",
                timestampMs = now + 60 * 60_000L,
            ),
            now,
        )
        assertEquals("请输入有效金额", broken.amount)
        assertEquals("时间不能晚于当前时间 5 分钟以上", broken.timestamp)
        assertEquals("请选择有效分类", broken.category)
        assertEquals("请选择有效来源", broken.source)
        assertEquals("请选择有效审核状态", broken.reviewStatus)
        assertFalse(broken.isValid)
    }

    @Test
    fun unchangedFieldsCountAsNoChanges() {
        val original = entity()
        assertFalse(TransactionEditRules.hasChanges(original, fields()))
        // account 由 null 变空白不应算改动（入库时空白也会写回 null）。
        assertFalse(
            TransactionEditRules.hasChanges(entity(account = null), fields(account = "  "))
        )

        assertTrue(TransactionEditRules.hasChanges(original, fields(amountCents = 1235L)))
        assertTrue(
            TransactionEditRules.hasChanges(
                original, fields(type = TransactionType.INCOME.name)
            )
        )
        assertTrue(
            TransactionEditRules.hasChanges(
                original, fields(category = TransactionCategory.TRANSPORT.name)
            )
        )
        assertTrue(TransactionEditRules.hasChanges(original, fields(merchant = "瑞幸")))
        assertTrue(TransactionEditRules.hasChanges(original, fields(note = "加班")))
        assertTrue(TransactionEditRules.hasChanges(original, fields(account = "招商银行")))
        assertTrue(
            TransactionEditRules.hasChanges(
                original, fields(source = TransactionSource.ALIPAY.name)
            )
        )
        assertTrue(TransactionEditRules.hasChanges(original, fields(timestampMs = 1_760_000_060_000L)))
        assertTrue(TransactionEditRules.hasChanges(original, fields(reviewStatus = "excluded")))
    }

    @Test
    fun applyToWritesEveryEditableFieldAndKeepsSyncMetadata() {
        val original = entity(
            syncDirty = false,
            duplicateOfId = 42L,
            reviewStatus = "possible_duplicate",
        )
        val edited = TransactionEditRules.applyTo(
            original,
            fields(
                amountCents = 98_765L,
                type = TransactionType.INCOME.name,
                category = TransactionCategory.SALARY.name,
                merchant = "公司",
                note = "十月工资",
                account = "招商银行",
                source = TransactionSource.MANUAL.name,
                timestampMs = 1_760_000_123_000L,
                reviewStatus = "confirmed",
            ),
        )

        assertEquals(98_765L, edited.amountCents)
        assertEquals(TransactionType.INCOME.name, edited.type)
        assertEquals(TransactionCategory.SALARY.name, edited.category)
        assertEquals("公司", edited.merchant)
        assertEquals("十月工资", edited.note)
        assertEquals("招商银行", edited.account)
        assertEquals(TransactionSource.MANUAL.name, edited.source)
        assertEquals(1_760_000_123_000L, edited.timestamp)
        assertEquals("confirmed", edited.remoteReviewStatus)

        // 只读字段保持原样：同步标记由 repository.update 负责，编辑页不得伪造。
        assertEquals(original.id, edited.id)
        assertEquals(original.currency, edited.currency)
        assertEquals(original.remoteEventId, edited.remoteEventId)
        assertEquals(original.duplicateOfId, edited.duplicateOfId)
        assertEquals(original.syncDirty, edited.syncDirty)
        assertEquals(original.syncModifiedMs, edited.syncModifiedMs)
    }

    @Test
    fun blankAccountIsStoredAsNull() {
        val edited = TransactionEditRules.applyTo(entity(account = "微信"), fields(account = "   "))
        assertNull(edited.account)
    }

    @Test
    fun amountFormattingKeepsTwoDecimals() {
        assertEquals("12.34", TransactionEditRules.formatAmount(1234L))
        assertEquals("0.05", TransactionEditRules.formatAmount(5L))
        assertEquals("100.00", TransactionEditRules.formatAmount(10_000L))
    }

    @Test
    fun dateAndTimePickPreserveTheOtherHalf() {
        val calendar = Calendar.getInstance().apply {
            set(2026, Calendar.MARCH, 15, 14, 30, 45)
            set(Calendar.MILLISECOND, 0)
        }
        val original = calendar.timeInMillis

        val retimed = TransactionEditRules.withLocalTime(original, 9, 5)
        assertEquals(9, TransactionEditRules.hourOf(retimed))
        assertEquals(5, TransactionEditRules.minuteOf(retimed))
        val retainedDate = Calendar.getInstance().apply { timeInMillis = retimed }
        assertEquals(2026, retainedDate.get(Calendar.YEAR))
        assertEquals(Calendar.MARCH, retainedDate.get(Calendar.MONTH))
        assertEquals(15, retainedDate.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, retainedDate.get(Calendar.SECOND))

        // 日期选择只换年月日，时分保持不变。
        val utcDate = TransactionEditRules.localDateToUtcMillis(original)
        val redated = TransactionEditRules.applyUtcDateToMillis(utcDate, original)
        assertEquals(14, TransactionEditRules.hourOf(redated))
        assertEquals(30, TransactionEditRules.minuteOf(redated))
        val redatedCalendar = Calendar.getInstance().apply { timeInMillis = redated }
        assertEquals(2026, redatedCalendar.get(Calendar.YEAR))
        assertEquals(Calendar.MARCH, redatedCalendar.get(Calendar.MONTH))
        assertEquals(15, redatedCalendar.get(Calendar.DAY_OF_MONTH))
    }
}
