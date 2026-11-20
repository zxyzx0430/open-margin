package com.bookkeeping.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Device-private trace event. Deliberately has no FK: deletion must not erase its audit trail.
 *
 * The three explanation columns are nullable on purpose: rows written before
 * schema v11 only had a stage and a code, and they must keep rendering after
 * the upgrade. New rows carry who called (callSite) and a structured summary of
 * what went in and what came out — never raw OCR text, screenshots, audio or
 * a whole import file.
 */
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
    /** Dotted entry point, e.g. "ScreenshotIntakeCoordinator.ingest". */
    val callSite: String? = null,
    /** Structured input summary, e.g. "截图 1 张，OCR 14 个文本块". */
    val inputSummary: String? = null,
    /** Structured output summary, e.g. "-¥8.00 · 祐迎迎生鲜超市". */
    val outputSummary: String? = null,
    /**
     * The raw input this chain actually received: notification title + body, SMS
     * sender + body, page OCR text (one line per block, reading order) or the
     * manual / ASR utterance. Stored so a user-reported bug can be reproduced.
     *
     * Bounded by [com.bookkeeping.app.data.diagnostics.DiagnosticRawText.MAX_LENGTH]
     * (never by the 160-char summary clip), device-private, excluded from the
     * sync payload, never written to Logcat, and erasable by the user in one
     * action. Never image bytes, audio, or a whole import file.
     */
    val rawText: String? = null,
)
