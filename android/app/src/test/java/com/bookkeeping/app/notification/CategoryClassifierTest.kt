package com.bookkeeping.app.notification

import com.bookkeeping.app.data.local.entity.TransactionCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryClassifierTest {

    @Test
    fun longestMerchantKeywordWins() {
        assertEquals(TransactionCategory.ENTERTAINMENT, CategoryClassifier.classify("Apple Music"))
        assertEquals(TransactionCategory.TRANSPORT, CategoryClassifier.classify("美团单车"))
        assertEquals(TransactionCategory.SHOPPING, CategoryClassifier.classify("抖音商城"))
        assertEquals(TransactionCategory.HEALTH, CategoryClassifier.classify("美团买药"))
        assertEquals(TransactionCategory.FOOD, CategoryClassifier.classify("美团外卖"))
    }

    @Test
    fun longerContextCanCompleteATruncatedMerchant() {
        assertEquals(
            TransactionCategory.SHOPPING,
            CategoryClassifier.classify("抖音", "抖音商城"),
        )
        assertEquals(
            TransactionCategory.TRANSPORT,
            CategoryClassifier.classify("美团", "美团单车"),
        )
        assertEquals(
            TransactionCategory.ENTERTAINMENT,
            CategoryClassifier.classify("Apple", "Apple Music"),
        )
    }

    @Test
    fun merchantBeatsContextWords() {
        assertEquals(
            TransactionCategory.UTILITIES,
            CategoryClassifier.classify("中国电信", "充值"),
        )
        assertEquals(
            TransactionCategory.FOOD,
            CategoryClassifier.classify("星巴克", "交通银行"),
        )
    }

    @Test
    fun contextIsUsedOnlyWhenMerchantHasNoKeyword() {
        assertEquals(TransactionCategory.FOOD, CategoryClassifier.classify("", "麦当劳"))
        assertEquals(TransactionCategory.TRANSPORT, CategoryClassifier.classify("未知商户", "地铁"))
    }

    @Test
    fun redPacketCouponIsNotATransfer() {
        assertEquals(TransactionCategory.OTHER, CategoryClassifier.classify("未知商户", "红包抵扣"))
        assertEquals(TransactionCategory.TRANSFER, CategoryClassifier.classify("未知商户", "收到红包"))
    }
}
