package com.bookkeeping.app.voice.asr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「引擎常驻 + 懒加载」的状态机：纯 JVM，用假引擎替代 sherpa。
 *
 * 钉住的都是产品承诺：
 * - 不 app 启动就加载（构造 holder 不建实例）；
 * - 并发 acquire 只 prepare 一次；
 * - 面板反复开关不重建（常驻）；
 * - 只有 releaseNow 才 close，且下一次 acquire 必须重建（close 后的实例不可复用）；
 * - prepare 失败不会留下半死的实例，下次还能重试。
 */
class VoiceAsrResidencyTest {

    private class FakeEngine {
        var creates = 0
        var prepares = 0
        var warmUps = 0
        var closes = 0
        var closed = false
        var failPrepare = false
        var failWarmUp = false
        var prepareGate: CompletableDeferred<Unit>? = null
        var prepareStarted: CompletableDeferred<Unit>? = null
    }

    private class Harness {
        val engine = FakeEngine()
        val logs = mutableListOf<String>()
        var factoryCalls = 0

        val residency = VoiceAsrResidency(
            factory = {
                factoryCalls += 1
                engine.creates += 1
                engine
            },
            prepare = { target, _ ->
                target.prepares += 1
                target.prepareStarted?.complete(Unit)
                target.prepareGate?.await()
                if (target.failPrepare) {
                    Result.failure(IllegalStateException("模型损坏"))
                } else {
                    Result.success(Unit)
                }
            },
            close = { target ->
                target.closes += 1
                target.closed = true
            },
            warmUp = { target ->
                target.warmUps += 1
                if (target.failWarmUp) error("warm-up 失败")
            },
            log = { message -> logs += message },
        )
    }

    @Test
    fun constructionDoesNotLoadAnything() {
        val harness = Harness()
        assertEquals(0, harness.factoryCalls)
        assertNull(harness.residency.current())
        assertFalse(harness.residency.isReady())
    }

    @Test
    fun firstAcquireCreatesPreparesAndWarmsUpExactlyOnce() = runBlocking {
        val harness = Harness()
        val first = harness.residency.acquire()
        assertTrue(first.isSuccess)
        assertSame(harness.engine, first.getOrNull())
        assertEquals(1, harness.factoryCalls)
        assertEquals(1, harness.engine.prepares)
        assertEquals(1, harness.engine.warmUps)
        assertTrue(harness.residency.isReady())

        // 第二次 acquire 是常驻命中：不建实例、不重复 prepare / warm-up。
        val second = harness.residency.acquire()
        assertSame(harness.engine, second.getOrNull())
        assertEquals(1, harness.factoryCalls)
        assertEquals(1, harness.engine.prepares)
        assertEquals(1, harness.engine.warmUps)
    }

    @Test
    fun concurrentAcquirePreparesOnlyOnce() = runBlocking {
        val harness = Harness()
        val gate = CompletableDeferred<Unit>()
        harness.engine.prepareGate = gate
        val started = CompletableDeferred<Unit>()
        harness.engine.prepareStarted = started

        val jobs = (1..8).map {
            async(Dispatchers.Default) { harness.residency.acquire() }
        }
        withTimeout(5_000) { started.await() }
        gate.complete(Unit)
        val results = withTimeout(5_000) { jobs.awaitAll() }

        assertTrue(results.all { it.isSuccess })
        assertTrue(results.all { it.getOrNull() === harness.engine })
        assertEquals(1, harness.factoryCalls)
        assertEquals(1, harness.engine.prepares)
        assertEquals(1, harness.engine.warmUps)
    }

    @Test
    fun releaseNowClosesAndTheNextAcquireRebuildsTheInstance() = runBlocking {
        val harness = Harness()
        harness.residency.acquire()
        assertTrue(harness.residency.releaseNow("test"))

        assertEquals(1, harness.engine.closes)
        assertTrue(harness.engine.closed)
        assertNull(harness.residency.current())
        assertFalse(harness.residency.isReady())

        // close() 后的实例按契约不可复用：必须重新建。
        val again = harness.residency.acquire()
        assertTrue(again.isSuccess)
        assertEquals(2, harness.factoryCalls)
        assertEquals(2, harness.engine.prepares)

        // 没有实例时 releaseNow 是空操作。
        harness.residency.releaseNow("test")
        assertFalse(harness.residency.releaseNow("test"))
    }

    @Test
    fun prepareFailureReleasesTheInstanceSoTheNextAcquireRetriesCleanly() = runBlocking {
        val harness = Harness()
        harness.engine.failPrepare = true
        val failed = harness.residency.acquire()
        assertTrue(failed.isFailure)
        assertEquals(1, harness.engine.closes)
        assertNull(harness.residency.current())
        assertFalse(harness.residency.isReady())

        harness.engine.failPrepare = false
        val retried = harness.residency.acquire()
        assertTrue(retried.isSuccess)
        assertEquals(2, harness.factoryCalls)
        assertTrue(harness.residency.isReady())
    }

    @Test
    fun warmUpFailureStillLeavesTheEngineReady() = runBlocking {
        val harness = Harness()
        harness.engine.failWarmUp = true
        val result = harness.residency.acquire()
        assertTrue(result.isSuccess)
        assertTrue(harness.residency.isReady())
    }

    @Test
    fun releaseDuringPrepareDiscardsTheHalfLoadedInstance() = runBlocking {
        val harness = Harness()
        val gate = CompletableDeferred<Unit>()
        harness.engine.prepareGate = gate
        val started = CompletableDeferred<Unit>()
        harness.engine.prepareStarted = started

        val pending = async(Dispatchers.Default) { harness.residency.acquire() }
        withTimeout(5_000) { started.await() }

        // 加载到一半系统喊内存紧张：实例被 close，加载完成后不能再交出去。
        harness.residency.releaseNow("trim-memory")
        gate.complete(Unit)

        val result = withTimeout(5_000) { pending.await() }
        assertTrue(result.isFailure)
        assertNull(harness.residency.current())
        assertFalse(harness.residency.isReady())
    }
}
