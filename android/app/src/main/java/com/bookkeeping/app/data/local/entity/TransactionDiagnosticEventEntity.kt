package com.bookkeeping.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Device-private trace event. Deliberately has no FK: deletion must not erase its audit trail. */
@Entity(
    tableName = "transaction_diagnostic_events",
    indices = [
        Index(value = ["occurredAtMs"]),
        Index(value = ["transactionId", "occurredAtMs"]),
        Index(value = ["attemptId", "occurredAtMs"]),
    ],
)
data class TransactionDiagnosticEventEntity(
    @PrimaryKey val eventId: String,
    val attemptId: String,
    val transactionId: Long?,
    val occurredAtMs: Long,
    val stage: String,
    val result: String,
    val details: String,
)
