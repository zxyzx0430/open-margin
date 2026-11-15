package com.bookkeeping.app.notification

import com.bookkeeping.app.data.local.entity.TransactionCategory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class CategoryLearningTest {

    @Before
    fun reset() {
        CategoryLearning.clear()
    }

    @After
    fun clear() {
        CategoryLearning.clear()
    }

    @Test
    fun oneCorrectionIsOnlyASuggestion() {
        CategoryLearning.record("示例奶茶店", TransactionCategory.FOOD)
        assertNull(CategoryLearning.bestCategory("示例奶茶店"))
    }

    @Test
    fun twoConsistentCorrectionsBecomeLearned() {
        CategoryLearning.record("示例奶茶店", TransactionCategory.FOOD)
        CategoryLearning.record("示例奶茶店", TransactionCategory.FOOD)
        assertEquals(TransactionCategory.FOOD, CategoryLearning.bestCategory("示例奶茶店"))
    }

    @Test
    fun conflictingCorrectionsDoNotGuess() {
        CategoryLearning.record("示例商户", TransactionCategory.FOOD)
        CategoryLearning.record("示例商户", TransactionCategory.SHOPPING)
        assertNull(CategoryLearning.bestCategory("示例商户"))
    }

    @Test
    fun strictMajorityWinsAndDisabledMerchantIsSkipped() {
        repeat(2) { CategoryLearning.record("示例商户", TransactionCategory.FOOD) }
        CategoryLearning.record("示例商户", TransactionCategory.SHOPPING)
        assertEquals(TransactionCategory.FOOD, CategoryLearning.bestCategory("示例商户"))

        CategoryLearning.disable("示例商户")
        assertNull(CategoryLearning.bestCategory("示例商户"))
    }

    @Test
    fun classifierUsesLearnedMappingAfterThreshold() {
        repeat(2) { CategoryLearning.record("示例奶茶店", TransactionCategory.HEALTH) }
        val result = CategoryClassifier.classify(
            ClassificationInput(merchantRaw = "示例奶茶店"),
        )
        assertEquals(TransactionCategory.HEALTH, result.category)
        assertEquals("learned:示例奶茶店", result.ruleId)
    }
}
