package com.bookkeeping.app.notification.capture

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APPROVED 是候选状态的不可逆终点。
 *
 * 真机证据（files/page_capture/candidates.jsonl）：同一个 candidateId 先
 * APPROVED，随后又被写成 IGNORED，于是「账本里 8.00 已经入账」和「候选说这笔
 * 已忽略」同时成立，用户以为漏账。这里钉住三件事：
 * 1. 已 APPROVED 的候选拒绝任何降级 mark；
 * 2. 新候选第一次判 IGNORE 仍然正常工作（不能为了修 bug 把拒绝路径堵死）；
 * 3. 重复 save 同一张已入账的卡片不会 append 一条 PENDING 把它复活。
 */
class CaptureCandidateTerminalStateTest {

    private val capturedAt = System.currentTimeMillis()

    private fun candidate(id: String = "candidate-1") = CaptureCandidate(
        packageName = null,
        pageIdentity = "screenshot:wechat:PAYMENT:500:禧迎迎",
        amountCents = 500L,
        merchantCandidates = listOf("禧迎迎"),
        orderId = null,
        capturedAtMs = capturedAt,
        screenshotPath = null,
        confidence = 0.9f,
        reason = "high confidence; auto booked",
        paymentPlatform = CaptureCandidate.PLATFORM_WECHAT,
        confidenceTier = CaptureCandidate.TIER_HIGH,
        candidateId = id,
    )

    private fun withStore(block: (CaptureCandidateStore) -> Unit) {
        val dir: File = Files.createTempDirectory("candidate-terminal").toFile()
        try {
            block(CaptureCandidateStore(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `an approved candidate cannot be downgraded to ignored`() = withStore { store ->
        val card = candidate()
        assertNotNull(store.save(card, null))
        assertTrue(store.mark(card, CaptureCandidate.STATUS_APPROVED))

        assertFalse(
            "a duplicate ingest must not turn an approved card into an ignored one",
            store.mark(card, CaptureCandidate.STATUS_IGNORED),
        )

        assertEquals(
            CaptureCandidate.STATUS_APPROVED,
            store.findByCandidateId(card.candidateId)?.status,
        )
        assertTrue("the card must not surface in the ignored list", store.ignored().isEmpty())
        assertEquals(1, store.latest().size)
    }

    @Test
    fun `an approved candidate rejects every other status write`() = withStore { store ->
        val card = candidate()
        store.save(card, null)
        store.mark(card, CaptureCandidate.STATUS_APPROVED)

        listOf(
            CaptureCandidate.STATUS_IGNORED,
            CaptureCandidate.STATUS_PENDING,
            CaptureCandidate.STATUS_WAITING,
            CaptureCandidate.STATUS_LINKED,
        ).forEach { status ->
            assertFalse("$status must not overwrite APPROVED", store.mark(card, status))
            assertEquals(
                CaptureCandidate.STATUS_APPROVED,
                store.findByCandidateId(card.candidateId)?.status,
            )
        }
    }

    @Test
    fun `a new candidate still becomes ignored on its first ignore`() = withStore { store ->
        val card = candidate(id = "fresh")
        store.save(card, null)

        assertTrue(store.mark(card, CaptureCandidate.STATUS_IGNORED))

        assertEquals(
            CaptureCandidate.STATUS_IGNORED,
            store.findByCandidateId(card.candidateId)?.status,
        )
        assertEquals(listOf("fresh"), store.ignored().map { it.candidateId })
    }

    @Test
    fun `re-saving an approved card never appends a pending record`() = withStore { store ->
        val card = candidate()
        store.save(card, null)
        store.mark(card, CaptureCandidate.STATUS_APPROVED)

        assertNull(
            "a settled card must not be re-stored as a fresh pending candidate",
            store.save(card, null),
        )

        assertEquals(
            CaptureCandidate.STATUS_APPROVED,
            store.findByCandidateId(card.candidateId)?.status,
        )
        assertEquals(1, store.latest().size)
    }
}
