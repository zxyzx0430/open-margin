package com.bookkeeping.app

import com.bookkeeping.app.sync.LegacyUploadAckPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyUploadAckPolicyTest {
    @Test fun successfulUploadAdvancesCursor() {
        assertEquals(LegacyUploadAckPolicy.Decision.ADVANCE_CURSOR,
            LegacyUploadAckPolicy.decide(3,3,0))
    }

    @Test fun oldServerDroppingHistoryMustNotFreezeTheSyncChain() {
        // 旧服务端仍会按账本起始日丢掉几条记录：那是服务端的数据缺失，
        // 本地记录还在手机里，游标必须照常前进，否则一条旧账冻结整条同步链。
        assertEquals(LegacyUploadAckPolicy.Decision.ADVANCE_CURSOR,
            LegacyUploadAckPolicy.decide(3,3,1))
        assertEquals(LegacyUploadAckPolicy.Decision.ADVANCE_CURSOR,
            LegacyUploadAckPolicy.decide(1,1,1))
        assertEquals(LegacyUploadAckPolicy.Decision.ADVANCE_CURSOR,
            LegacyUploadAckPolicy.decide(5,5,5))
    }

    @Test fun partialOrMalformedAckMustRetry() {
        assertEquals(LegacyUploadAckPolicy.Decision.RETRY,
            LegacyUploadAckPolicy.decide(3,2,0))
        assertEquals(LegacyUploadAckPolicy.Decision.RETRY,
            LegacyUploadAckPolicy.decide(2,2,-1))
        assertEquals(LegacyUploadAckPolicy.Decision.RETRY,
            LegacyUploadAckPolicy.decide(3,2,1))
    }
}
