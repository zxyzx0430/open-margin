package com.bookkeeping.app.data.diagnostics

import com.bookkeeping.app.data.local.dao.TransactionDiagnosticDao
import com.bookkeeping.app.data.local.entity.TransactionDiagnosticEventEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Raw input must survive verbatim.
 *
 * The log used to store only a length ("正文 47 字"), which is unusable when a
 * user reports "WeChat says I paid ¥5 and it was not recorded": without the
 * original we cannot tell whether the parser missed or the notification never
 * arrived. These tests pin the four producers, the independent 4000-char bound
 * and the one-action erase.
 */
class DiagnosticRawTextTest {

    // ---- 1. Notification: title + body, newline preserved --------------------

    @Test
    fun `notification stores title and body with the newline kept`() {
        val stored = DiagnosticRawText.titleAndBody("微信支付", "已支付¥5.00")

        assertEquals("微信支付\n已支付¥5.00", stored)
        assertTrue("the two halves must stay on separate lines", stored!!.contains("\n"))
    }

    @Test
    fun `notification with several body lines keeps every line`() {
        val body = "付款金额 ¥5.00\n收款方 祐迎迎生鲜超市\n支付方式 零钱"

        val stored = DiagnosticRawText.titleAndBody("微信支付", body)

        assertEquals("微信支付\n$body", stored)
        assertEquals(3, stored!!.lines().size - 1)
    }

    // ---- 2. Notification: empty body -> title only, never "" ----------------

    @Test
    fun `notification with an empty body stores the title alone`() {
        assertEquals("微信支付", DiagnosticRawText.titleAndBody("微信支付", null))
        assertEquals("微信支付", DiagnosticRawText.titleAndBody("微信支付", "   "))
        assertEquals("微信支付", DiagnosticRawText.titleAndBody("微信支付", ""))
    }

    @Test
    fun `both halves blank stores null instead of an empty string`() {
        assertNull(DiagnosticRawText.titleAndBody(null, null))
        assertNull(DiagnosticRawText.titleAndBody("", "  \n "))
        // Trim-only input must not become "" in the column: NULL means "nothing
        // was captured", and the screen hides the block on NULL.
        assertNull(DiagnosticRawText.store("   \n\t "))
    }

    // ---- 3. Screenshot: blocks joined top-to-bottom --------------------------

    @Test
    fun `screenshot blocks are joined in reading order one per line`() {
        val blocks = listOf(
            RawLine(text = "支付成功", top = 120, left = 40),
            RawLine(text = "¥5.00", top = 200, left = 40),
            RawLine(text = "祐迎迎生鲜超市", top = 200, left = 220),
            RawLine(text = "完成", top = 40, left = 40),
        )

        val stored = DiagnosticRawText.ocrLines(blocks)

        // Sorted by top then left: 完成 (40) -> 支付成功 (120) -> ¥5.00 / 商户 (200).
        assertEquals("完成\n支付成功\n¥5.00\n祐迎迎生鲜超市", stored)
    }

    @Test
    fun `screenshot blocks without bounds sink to the end instead of the top`() {
        val blocks = listOf(
            RawLine(text = "无坐标行", hasBounds = false),
            RawLine(text = "第二行", top = 200, left = 10),
            RawLine(text = "第一行", top = 100, left = 10),
        )

        assertEquals("第一行\n第二行\n无坐标行", DiagnosticRawText.ocrLines(blocks))
    }

    @Test
    fun `blank ocr lines are dropped and an empty page stores null`() {
        assertEquals("只有这一行", DiagnosticRawText.ocrLines(listOf(
            RawLine(text = "  ", top = 10),
            RawLine(text = "只有这一行", top = 20),
        )))
        assertNull(DiagnosticRawText.ocrLines(emptyList()))
    }

    // ---- 4. Screenshot: 4000-char bound carries a marker ---------------------

    @Test
    fun `ocr text longer than the bound is truncated and marked`() {
        val long = (1..400).joinToString("\n") { "第 $it 行 支付金额 ¥1.00" }

        val stored = DiagnosticRawText.ocrLines(long.lines().mapIndexed { index, text ->
            RawLine(text = text, top = index * 10, left = 0)
        })!!

        assertTrue("must not exceed the raw bound", stored.length <= DiagnosticRawText.MAX_LENGTH)
        assertTrue("must end with the truncation marker", stored.endsWith(DiagnosticRawText.TRUNCATED_MARK))
        assertTrue("the marker must survive the clip", stored.contains("已截断"))
    }

    @Test
    fun `text just under the bound is stored whole`() {
        val exact = "x".repeat(DiagnosticRawText.MAX_LENGTH)

        assertEquals(exact, DiagnosticRawText.store(exact))
        assertFalse(DiagnosticRawText.store(exact)!!.contains(DiagnosticRawText.TRUNCATED_MARK))
    }

    // ---- 5. Manual: merchant + note assembly --------------------------------

    @Test
    fun `manual entry joins merchant and note`() {
        assertEquals("祐迎迎生鲜超市\n晚餐", DiagnosticRawText.manualEntry("祐迎迎生鲜超市", "晚餐"))
    }

    @Test
    fun `manual entry drops a blank field rather than leaving a dangling line`() {
        assertEquals("祐迎迎生鲜超市", DiagnosticRawText.manualEntry("祐迎迎生鲜超市", ""))
        assertEquals("晚餐", DiagnosticRawText.manualEntry("   ", "晚餐"))
        assertNull(DiagnosticRawText.manualEntry(null, "  "))
    }

    // ---- 6. The 160-char summary clip must never touch raw text -------------

    @Test
    fun `raw text does not go through the 160-char summary clip`() = runBlocking {
        // A realistic notification is longer than DiagnosticSummary.MAX_LENGTH;
        // clipping it to 160 would store a truncated body and defeat the purpose.
        val body = "付款金额 ¥5.00\n" + "收款方 祐迎迎生鲜超市\n".repeat(20) + "支付方式 零钱"
        assertTrue("fixture must be longer than the summary clip", body.length > DiagnosticSummary.MAX_LENGTH)

        val dao = RecordingDiagnosticDao()
        TransactionDiagnostics(dao).record(
            attemptId = "attempt-notification",
            stage = TransactionDiagnostics.STAGE_PARSE,
            result = TransactionDiagnostics.RESULT_SUCCESS,
            rawText = DiagnosticRawText.titleAndBody("微信支付", body),
        )

        val stored = dao.inserted.single().rawText!!
        assertEquals(DiagnosticRawText.titleAndBody("微信支付", body), stored)
        assertTrue(
            "raw text was clipped by DiagnosticSummary.MAX_LENGTH (${stored.length} chars)",
            stored.length > DiagnosticSummary.MAX_LENGTH,
        )
        assertFalse(stored.endsWith("…"))
        // The summary columns keep their own clipping; only raw text is exempt.
        assertEquals(DiagnosticRawText.MAX_LENGTH, DiagnosticRawText.store("x".repeat(9_000))!!.length)
    }

    @Test
    fun `a 4000-char raw text reaches the entity unchanged`() = runBlocking {
        val payload = "长".repeat(DiagnosticRawText.MAX_LENGTH)
        val dao = RecordingDiagnosticDao()

        TransactionDiagnostics(dao).record(
            attemptId = "attempt-long",
            stage = TransactionDiagnostics.STAGE_CAPTURE,
            result = TransactionDiagnostics.RESULT_SUCCESS,
            rawText = payload,
        )

        assertEquals(payload, dao.inserted.single().rawText)
    }

    @Test
    fun `summary columns still clip to 160 while raw text does not`() = runBlocking {
        val long = "x".repeat(400)
        val dao = RecordingDiagnosticDao()

        TransactionDiagnostics(dao).record(
            attemptId = "attempt-both",
            stage = TransactionDiagnostics.STAGE_CAPTURE,
            result = TransactionDiagnostics.RESULT_SUCCESS,
            inputSummary = long,
            outputSummary = long,
            callSite = long,
            rawText = long,
        )

        val row = dao.inserted.single()
        assertEquals(DiagnosticSummary.MAX_LENGTH, row.inputSummary!!.length)
        assertEquals(DiagnosticSummary.MAX_LENGTH, row.outputSummary!!.length)
        assertEquals(DiagnosticSummary.MAX_LENGTH, row.callSite!!.length)
        assertEquals(400, row.rawText!!.length)
    }

    // ---- 7. One action erases every raw text, nothing else ------------------

    @Test
    fun `clearing raw text nulls every row and leaves the rest intact`() = runBlocking {
        val dao = InMemoryDiagnosticDao(
            listOf(
                event(id = "a", rawText = "微信支付\n已支付¥5.00"),
                event(id = "b", rawText = "招商银行\n您账户转入 100.00 元"),
                event(id = "c", rawText = null),
            ),
        )

        val cleared = TransactionDiagnostics(dao).clearRawText()

        assertEquals("all rows lose their raw text, including already-empty ones", 3, cleared)
        assertTrue(dao.rows.all { it.rawText == null })
        // Everything a user reads to trace a chain survives.
        assertEquals(listOf("a", "b", "c"), dao.rows.map { it.eventId })
        assertEquals(listOf("capture", "parse", "save"), dao.rows.map { it.stage })
        assertEquals(listOf("success", "warning", "success"), dao.rows.map { it.result })
        assertEquals(listOf("-¥5.00", "-¥100.00", "-¥1.00"), dao.rows.map { it.inputSummary })
        assertEquals(listOf("微信 通知 1 条（正文 12 字）", "", ""), dao.rows.map { it.details })
    }

    // ---- Entity guard: one nullable text column, nothing binary -------------

    @Test
    fun `raw text is a nullable text column beside the summaries`() {
        val field = TransactionDiagnosticEventEntity::class.java.getDeclaredField("rawText")

        assertEquals(String::class.java, field.type)
        assertFalse("raw text must not be a primitive", field.type.isPrimitive)
        // Reflection cannot prove nullability, so construct without it: the default
        // is null, which the screen reads as "nothing captured or already cleared".
        val legacy = TransactionDiagnosticEventEntity(
            eventId = "event-1",
            attemptId = "attempt-1",
            transactionId = null,
            occurredAtMs = 1L,
            stage = "save",
            result = "success",
            details = "",
        )
        assertNull(legacy.rawText)
    }

    @Test
    fun `raw text is not written to logcat anywhere in the producer paths`() {
        // A cheap structural guard: the payload must never be handed to Log.*.
        val sources = listOf(
            "src/main/java/com/bookkeeping/app/notification/PaymentNotificationListenerService.kt",
            "src/main/java/com/bookkeeping/app/notification/SmsBroadcastReceiver.kt",
            "src/main/java/com/bookkeeping/app/data/repository/TransactionRepository.kt",
        ).map { relative ->
            listOf(java.io.File(relative), java.io.File("app/$relative"), java.io.File("../app/$relative"))
                .firstOrNull { it.isFile }
        }.filterNotNull()
        assertTrue("producer sources must be readable from the module directory", sources.isNotEmpty())
        sources.forEach { file ->
            file.readLines().forEach { line ->
                if (line.contains("Log.") && line.contains("rawText")) {
                    throw AssertionError("raw text must never reach Logcat: ${file.name}: $line")
                }
            }
        }
    }
}

private fun event(
    id: String,
    rawText: String?,
) = TransactionDiagnosticEventEntity(
    eventId = id,
    attemptId = "attempt-$id",
    transactionId = null,
    occurredAtMs = 1_800_000_000_000L,
    stage = when (id) {
        "a" -> "capture"
        "b" -> "parse"
        else -> "save"
    },
    result = if (id == "b") "warning" else "success",
    details = if (id == "a") "微信 通知 1 条（正文 12 字）" else "",
    inputSummary = when (id) {
        "a" -> "-¥5.00"
        "b" -> "-¥100.00"
        else -> "-¥1.00"
    },
    rawText = rawText,
)

/** Captures the entity instead of touching Room. */
private class RecordingDiagnosticDao : TransactionDiagnosticDao {
    val inserted = mutableListOf<TransactionDiagnosticEventEntity>()

    override suspend fun insert(event: TransactionDiagnosticEventEntity) {
        inserted += event
    }

    override suspend fun pruneBefore(cutoffMs: Long): Int = 0

    override suspend fun clearRawText(): Int {
        inserted.indices.forEach { index ->
            inserted[index] = inserted[index].copy(rawText = null)
        }
        return inserted.size
    }

    override fun observeEvents(
        cutoffMs: Long,
        transactionId: Long?,
        query: String,
        limit: Int,
        offset: Int,
    ): Flow<List<TransactionDiagnosticEventEntity>> = emptyFlow()

    override fun observeRecentTransactionIds(cutoffMs: Long, limit: Int): Flow<List<Long>> = emptyFlow()
}

/** In-memory table so the clear statement can be asserted without a device. */
private class InMemoryDiagnosticDao(
    private val initial: List<TransactionDiagnosticEventEntity>,
) : TransactionDiagnosticDao {
    var rows: List<TransactionDiagnosticEventEntity> = initial

    override suspend fun insert(event: TransactionDiagnosticEventEntity) {
        rows = rows + event
    }

    override suspend fun pruneBefore(cutoffMs: Long): Int = 0

    override suspend fun clearRawText(): Int {
        rows = rows.map { it.copy(rawText = null) }
        return rows.size
    }

    override fun observeEvents(
        cutoffMs: Long,
        transactionId: Long?,
        query: String,
        limit: Int,
        offset: Int,
    ): Flow<List<TransactionDiagnosticEventEntity>> = emptyFlow()

    override fun observeRecentTransactionIds(cutoffMs: Long, limit: Int): Flow<List<Long>> = emptyFlow()
}
