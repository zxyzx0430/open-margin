package com.bookkeeping.app.voice.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引擎状态机的硬约定：
 * - 没 prepare 就 startSession 必须报错（不能悄悄录一段没人解码的音频）；
 * - prepare 失败可以重试；
 * - close 幂等，close 之后任何 prepare 都报错。
 */
class AsrStateMachineTest {

    @Test
    fun startSessionBeforePrepareThrows() {
        val machine = AsrStateMachine()
        val failure = assertThrows(IllegalStateException::class.java) { machine.beginSession() }
        assertTrue(failure.message!!.contains("prepare"))
        assertEquals(AsrState.NEW, machine.state)
    }

    @Test
    fun prepareThenSessionReachesRecording() {
        val machine = AsrStateMachine()
        machine.beginPrepare()
        assertEquals(AsrState.PREPARING, machine.state)
        machine.finishPrepare(true)
        assertEquals(AsrState.READY, machine.state)

        machine.beginSession()
        assertEquals(AsrState.RECORDING, machine.state)

        machine.endSession()
        assertEquals(AsrState.READY, machine.state)
    }

    @Test
    fun failedPrepareReturnsToNewAndBlocksSession() {
        val machine = AsrStateMachine()
        machine.beginPrepare()
        machine.finishPrepare(false)
        assertEquals(AsrState.NEW, machine.state)
        assertThrows(IllegalStateException::class.java) { machine.beginSession() }
    }

    @Test
    fun prepareIsRetryableAfterFailure() {
        val machine = AsrStateMachine()
        machine.beginPrepare()
        machine.finishPrepare(false)
        machine.beginPrepare()
        machine.finishPrepare(true)
        assertEquals(AsrState.READY, machine.state)
    }

    @Test
    fun restartingSessionWhileRecordingIsAllowed() {
        val machine = AsrStateMachine()
        machine.beginPrepare()
        machine.finishPrepare(true)
        machine.beginSession()
        machine.beginSession() // 重复点“开始”不应该崩，内部重置会话
        assertEquals(AsrState.RECORDING, machine.state)
    }

    @Test
    fun closeIsIdempotent() {
        val machine = AsrStateMachine()
        machine.beginPrepare()
        machine.finishPrepare(true)
        assertTrue(machine.close())
        assertEquals(AsrState.CLOSED, machine.state)
        assertFalse(machine.close())
        assertEquals(AsrState.CLOSED, machine.state)
    }

    @Test
    fun operationsAfterCloseThrow() {
        val machine = AsrStateMachine()
        machine.close()
        assertThrows(IllegalStateException::class.java) { machine.beginPrepare() }
        assertThrows(IllegalStateException::class.java) { machine.beginSession() }
    }

    @Test
    fun endSessionWithoutRecordingIsNoOp() {
        val machine = AsrStateMachine()
        machine.beginPrepare()
        machine.finishPrepare(true)
        machine.endSession()
        assertEquals(AsrState.READY, machine.state)
    }
}
