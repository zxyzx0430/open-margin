package com.bookkeeping.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "subscriptions",
    indices = [
        Index(value = ["syncUuid"], unique = true),
    ],
)
data class SubscriptionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val syncUuid: String,
    val wallosId: Long? = null,
    val name: String,
    val amountCents: Long,
    @ColumnInfo(defaultValue = "'CNY'")
    val currency: String = "CNY",
    val cnyEstimateCents: Long? = null,
    val cadence: String,
    val nextDueMs: Long,
    val startMs: Long? = null,
    @ColumnInfo(defaultValue = "''")
    val paymentChannel: String = "",
    @ColumnInfo(defaultValue = "1")
    val autoRenew: Boolean = true,
    @ColumnInfo(defaultValue = "0")
    val pendingCancel: Boolean = false,
    @ColumnInfo(defaultValue = "1")
    val notifyEnabled: Boolean = true,
    @ColumnInfo(defaultValue = "3")
    val notifyDaysBefore: Int = 3,
    @ColumnInfo(defaultValue = "1")
    val active: Boolean = true,
    @ColumnInfo(defaultValue = "''")
    val note: String = "",
    val updatedAtMs: Long,
    val deletedAtMs: Long? = null,
    @ColumnInfo(defaultValue = "1")
    val dirty: Boolean = true,
)

object SubscriptionCadence {
    const val WEEKLY = "weekly"
    const val MONTHLY = "monthly"
    const val QUARTERLY = "quarterly"
    const val YEARLY = "yearly"

    val ALL = setOf(WEEKLY, MONTHLY, QUARTERLY, YEARLY)
}
