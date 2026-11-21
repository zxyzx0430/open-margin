package com.bookkeeping.app.data.repository

import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 详情页「时间待确认」点开改时间后的写入前变换。
 *
 * [TransactionRepository] 的落库路径依赖 Room 与 Context，纯 JVM 单测拿不到；
 * 这里断言的是 repository 在 `dao.update` 之前唯一做的那一步字段加工
 * （[withConfirmedEventTime]）：时间真的换成用户选的、待确认标记被清掉、
 * 其余字段和同步标记原样。Room 真写入与同步上传不在单测范围内。
 */
class EventTimeConfirmationTest {

    private val oldTime = 1_795_000_000_000L
    private val newTime = 1_795_003_600_000L

    private fun entity(
        timestamp: Long = oldTime,
        needsConfirmation: Boolean = true,
    ) = TransactionEntity(
        id = 42L,
        amountCents = 3650L,
        type = TransactionType.EXPENSE.name,
        category = TransactionCategory.FOOD.name,
        merchant = "麦当劳",
        note = "加班餐",
        source = "WECHAT",
        account = "微信",
        timestamp = timestamp,
        remoteEventId = "evt-42",
        remoteRevision = 3L,
        syncDirty = false,
        syncModifiedMs = 111L,
        remoteReviewStatus = "confirmed",
        syncMutationId = "mutation-42",
        duplicateOfId = 7L,
        eventTimeNeedsConfirmation = needsConfirmation,
    )

    @Test
    fun confirmingNewTimeWritesTimestampAndClearsFlag() {
        val updated = entity().withConfirmedEventTime(newTime)

        assertEquals(newTime, updated.timestamp)
        assertFalse(updated.eventTimeNeedsConfirmation)
    }

    @Test
    fun confirmingTheSameTimeStillClearsFlag() {
        // 用户打开选择器后原样按「确定」：时间没变，但也算确认过。
        // 编辑页的 TransactionEditRules.applyTo 只按「时间变没变」清标记，会漏掉这一种。
        val updated = entity().withConfirmedEventTime(oldTime)

        assertEquals(oldTime, updated.timestamp)
        assertFalse(updated.eventTimeNeedsConfirmation)
    }

    @Test
    fun otherFieldsAndSyncMarkersAreUntouched() {
        val original = entity()
        val updated = original.withConfirmedEventTime(newTime)

        assertEquals(original.copy(timestamp = newTime, eventTimeNeedsConfirmation = false), updated)
        // 同步 / 重复标记不能因为这次窄更新被清掉：写回仍交给 TransactionRepository.update。
        assertEquals(original.remoteEventId, updated.remoteEventId)
        assertEquals(original.remoteRevision, updated.remoteRevision)
        assertEquals(original.syncModifiedMs, updated.syncModifiedMs)
        assertEquals(original.syncMutationId, updated.syncMutationId)
        assertEquals(original.duplicateOfId, updated.duplicateOfId)
        assertEquals("麦当劳", updated.merchant)
        assertEquals("加班餐", updated.note)
        assertEquals("微信", updated.account)
    }

    @Test
    fun switchingFlagOffKeepsEntityIdenticalApartFromTheFlag() {
        // 已确认过的账再点一次「确认」：除标记外的字段一律不变。
        val alreadyConfirmed = entity(needsConfirmation = false)
        val again = alreadyConfirmed.withConfirmedEventTime(alreadyConfirmed.timestamp)

        assertEquals(alreadyConfirmed, again)
    }
}
