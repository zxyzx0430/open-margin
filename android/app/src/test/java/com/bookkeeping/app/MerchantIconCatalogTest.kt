package com.bookkeeping.app

import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.merchant.MerchantIconCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MerchantIconCatalogTest {
    @Test fun knownMerchantResolvesBySubstring() {
        val spec = MerchantIconCatalog.forMerchant("星巴克咖啡（杭州湖滨店）")
        assertEquals("starbucks", spec?.key)
        assertEquals("星", spec?.initials)
    }

    @Test fun explicitMerchantKeyWins() {
        val spec = MerchantIconCatalog.forTransaction("未知商户", "wechat")
        assertEquals("wechat", spec?.key)
        assertEquals("微", spec?.initials)
    }

    @Test fun unknownMerchantFallsBackToCategoryIcon() {
        assertNull(MerchantIconCatalog.forMerchant("某个没有图标的小店"))
        assertEquals(
            "restaurant",
            MerchantIconCatalog.categoryIconName(TransactionCategory.FOOD),
        )
    }

    @Test fun caseAndSpacingAreTolerated() {
        assertEquals("starbucks", MerchantIconCatalog.forMerchant("  STARBUCKS  ")?.key)
    }
}
