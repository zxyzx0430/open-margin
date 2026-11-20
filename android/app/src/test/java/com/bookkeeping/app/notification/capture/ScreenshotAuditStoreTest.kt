package com.bookkeeping.app.notification.capture

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotAuditStoreTest {
    @Test
    fun `one hundred independent concurrent save rounds preserve all entries`() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(100) { round ->
                val root = Files.createTempDirectory("screenshot-store-round-$round").toFile()
                try {
                    val stores = listOf(CaptureCandidateStore(root), CaptureCandidateStore(root))
                    val now = System.currentTimeMillis()
                    fun candidate(id: String) = CaptureCandidate(
                        packageName = null,
                        pageIdentity = "screenshot:wechat:payment",
                        amountCents = 2000,
                        merchantCandidates = listOf("shop"),
                        orderId = null,
                        capturedAtMs = now,
                        screenshotPath = null,
                        confidence = 0.6f,
                        reason = "test",
                        candidateId = id,
                    )
                    assertTrue("round $round seed", stores[0].save(candidate("seed"), null) != null)
                    val start = CountDownLatch(1)
                    val workers = stores.mapIndexed { index, store ->
                        executor.submit<Int> {
                            start.await()
                            (0 until 5).count { store.save(candidate("$index-$it"), null) != null }
                        }
                    }
                    start.countDown()
                    assertEquals("round $round all writes succeed", 10, workers.sumOf { it.get(30, TimeUnit.SECONDS) })
                    assertEquals("round $round all saved candidates remain", 11, stores[0].latest(200).size)
                } finally {
                    root.deleteRecursively()
                }
            }
            println("100 concurrent save rounds passed: 10 writes and 11 retained records each")
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `concurrent stores retain every saved candidate`() {
        val root = Files.createTempDirectory("screenshot-store-race").toFile()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = CaptureCandidateStore(root)
            val second = CaptureCandidateStore(root)
            val now = System.currentTimeMillis()
            fun candidate(id: String) = CaptureCandidate(
                packageName = null,
                pageIdentity = "screenshot:wechat:payment",
                amountCents = 2000,
                merchantCandidates = listOf("shop"),
                orderId = null,
                capturedAtMs = now,
                screenshotPath = null,
                confidence = 0.6f,
                reason = "test",
                candidateId = id,
            )
            assertTrue(first.save(candidate("seed"), null) != null)
            val start = CountDownLatch(1)
            val workers = listOf(first, second).mapIndexed { index, store ->
                executor.submit<Int> {
                    start.await()
                    (0 until 30).count { store.save(candidate("$index-$it"), null) != null }
                }
            }
            start.countDown()
            val successful = workers.sumOf { it.get(30, TimeUnit.SECONDS) }
            assertEquals("all writes succeed", 60, successful)
            assertEquals("all saved candidates remain in the index", 61, first.latest(200).size)
        } finally {
            executor.shutdownNow()
            root.deleteRecursively()
        }
    }
}
