package com.bookkeeping.app.billimport

/** Source inferred from the file name or the export headers. */
enum class BillSource { WECHAT, ALIPAY, UNKNOWN }

enum class BillFormat { CSV, TSV, TEXT, XLSX }

enum class BillDirection { EXPENSE, INCOME, NEUTRAL }

enum class BillStatus { SUCCESS, REFUND, FAILED, PENDING, UNKNOWN }

enum class BillPreviewKind { VALID, DUPLICATE, ERROR }

/**
 * One parsed bill row. The preview never writes to the ledger; the caller must
 * explicitly confirm [BillImportPreview.validRows].
 */
data class BillImportRow(
    val source: BillSource,
    val rowNumber: Int,
    val occurredAtMs: Long,
    val merchant: String,
    val amountCents: Long,
    val direction: BillDirection,
    val currency: String = "CNY",
    val status: BillStatus,
    val orderId: String? = null,
    val paymentMethod: String? = null,
    val rawFields: List<String> = emptyList(),
)

data class BillPreviewRow(
    val kind: BillPreviewKind,
    val rowNumber: Int,
    val rawFields: List<String>,
    val parsed: BillImportRow? = null,
    val message: String? = null,
    val duplicateOfRow: Int? = null,
)

data class BillImportPreview(
    val source: BillSource,
    val format: BillFormat,
    val rows: List<BillPreviewRow>,
) {
    val validRows: List<BillImportRow>
        get() = rows.mapNotNull { if (it.kind == BillPreviewKind.VALID) it.parsed else null }

    val duplicateRows: List<BillPreviewRow>
        get() = rows.filter { it.kind == BillPreviewKind.DUPLICATE }

    val errorRows: List<BillPreviewRow>
        get() = rows.filter { it.kind == BillPreviewKind.ERROR }

    /** Import is always a user-confirmed action; there is no auto-write path. */
    val requiresConfirmation: Boolean get() = true
}

data class BillParserOptions(
    /**
     * Duplicate keys already present in the ledger, in the same format produced
     * by [BillImportParser.duplicateKey]. Used to flag rows that already exist
     * before import.
     */
    val existingDuplicateKeys: Set<String> = emptySet(),
    val timeZoneId: String = "Asia/Shanghai",
    val maxRows: Int = 50_000,
)
