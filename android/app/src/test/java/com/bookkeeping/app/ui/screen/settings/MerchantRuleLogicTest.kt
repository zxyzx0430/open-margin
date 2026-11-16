package com.bookkeeping.app.ui.screen.settings

import com.bookkeeping.app.data.local.entity.TransactionCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MerchantRuleLogicTest {

    private fun entry(merchant: String, category: TransactionCategory = TransactionCategory.FOOD) =
        MerchantRuleEntry(merchant = merchant, category = category, learningPaused = false)

    @Test
    fun entriesSortedByNormalizedNameAndPausedFlag() {
        val entries = merchantRuleEntries(
            rules = mapOf(
                "starbucks" to TransactionCategory.FOOD,
                "星巴克" to TransactionCategory.FOOD,
                "全家" to TransactionCategory.SHOPPING,
            ),
            learningPaused = setOf("全家"),
        )
        assertEquals(listOf("starbucks", "全家", "星巴克"), entries.map { it.merchant })
        assertEquals(listOf(false, true, false), entries.map { it.learningPaused })
        assertEquals(TransactionCategory.SHOPPING, entries[1].category)
    }

    @Test
    fun searchIgnoresCaseSpacesAndBrackets() {
        val entries = listOf(
            entry("starbucks"),
            entry("星巴克"),
            entry("麦当劳"),
        )
        assertEquals(listOf("starbucks"), filterMerchantRules(entries, "StarBucks").map { it.merchant })
        assertEquals(listOf("starbucks"), filterMerchantRules(entries, " star bucks ").map { it.merchant })
        assertEquals(listOf("星巴克"), filterMerchantRules(entries, "星巴克（外卖）").map { it.merchant })
        assertEquals(entries, filterMerchantRules(entries, "   "))
        assertTrue(filterMerchantRules(entries, "肯德基").isEmpty())
    }

    @Test
    fun validationBlocksEmptyNameMissingCategoryAndOtherRulesName() {
        val existing = setOf("星巴克", "麦当劳")

        assertEquals(
            MerchantRuleIssue.EMPTY_NAME,
            merchantRuleIssue("  ", TransactionCategory.FOOD, existing),
        )
        assertEquals(
            MerchantRuleIssue.NO_CATEGORY,
            merchantRuleIssue("肯德基", null, existing),
        )
        assertEquals(
            MerchantRuleIssue.DUPLICATE,
            merchantRuleIssue("星巴克 ", TransactionCategory.FOOD, existing),
        )
        assertNull(merchantRuleIssue("肯德基", TransactionCategory.FOOD, existing))
        // 编辑自己这条：名字没改，不算重复。
        assertNull(
            merchantRuleIssue("星巴克", TransactionCategory.SHOPPING, existing, originalMerchant = "星巴克"),
        )
    }

    @Test
    fun candidatesFilterAndPushExistingRulesToTheEnd() {
        val recent = listOf("星巴克", "肯德基", "麦当劳", "肯德基", "", "瑞幸咖啡")
        val existing = setOf("星巴克", "麦当劳")

        val all = merchantCandidates(recent, existing, query = "")
        assertEquals(listOf("肯德基", "瑞幸咖啡", "星巴克", "麦当劳"), all.map { it.merchant })
        assertEquals(listOf(false, false, true, true), all.map { it.hasRule })

        val filtered = merchantCandidates(recent, existing, query = "肯德")
        assertEquals(listOf("肯德基"), filtered.map { it.merchant })
    }

    @Test
    fun candidatesRespectLimit() {
        val recent = (1..30).map { "商户$it" }
        assertEquals(10, merchantCandidates(recent, emptySet(), query = "").size)
    }
}
