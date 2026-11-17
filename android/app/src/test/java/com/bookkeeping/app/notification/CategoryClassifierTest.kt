package com.bookkeeping.app.notification

import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.merchant.MerchantRules
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class CategoryClassifierTest {

    @Before
    fun resetMerchantRules() {
        MerchantRules.clear()
        CategoryLearning.clear()
    }

    @After
    fun clearMerchantRules() {
        MerchantRules.clear()
        CategoryLearning.clear()
    }

    @Test
    fun userCorrectionWinsAndIgnoresBranchSuffix() {
        MerchantRules.put("星巴克(万象城店)", TransactionCategory.EDUCATION)
        assertEquals(
            TransactionCategory.EDUCATION,
            CategoryClassifier.classify("星巴克(万达店)", "咖啡"),
        )
        MerchantRules.remove("星巴克(万达店)")
        assertEquals(
            TransactionCategory.FOOD,
            CategoryClassifier.classify("星巴克(万达店)", "咖啡"),
        )
    }

    @Test
    fun longestMerchantKeywordWins() {
        assertEquals(TransactionCategory.ENTERTAINMENT, CategoryClassifier.classify("Apple Music"))
        assertEquals(TransactionCategory.TRANSPORT, CategoryClassifier.classify("美团单车"))
        assertEquals(TransactionCategory.SHOPPING, CategoryClassifier.classify("抖音商城"))
        assertEquals(TransactionCategory.HEALTH, CategoryClassifier.classify("美团买药"))
        assertEquals(TransactionCategory.FOOD, CategoryClassifier.classify("美团外卖"))
        assertEquals(TransactionCategory.FOOD, CategoryClassifier.classify("华莱士"))
        assertEquals(TransactionCategory.SHOPPING, CategoryClassifier.classify("名创优品"))
        assertEquals(TransactionCategory.HEALTH, CategoryClassifier.classify("益丰药房"))
        assertEquals(TransactionCategory.BONUS, CategoryClassifier.classify("年终奖"))
        assertEquals(TransactionCategory.INVESTMENT, CategoryClassifier.classify("基金收益"))
        assertEquals(TransactionCategory.INSURANCE, CategoryClassifier.classify("重疾险"))
        assertEquals(TransactionCategory.GIFT, CategoryClassifier.classify("随礼"))
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
        assertEquals(
            TransactionCategory.FOOD,
            CategoryClassifier.classify("星巴克", "公共交通"),
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
    fun adversarialLongTextDoesNotThrow() {
        val huge = "美团" + "啊".repeat(100_000)
        assertEquals(TransactionCategory.FOOD, CategoryClassifier.classify(huge, huge))
        assertEquals(TransactionCategory.OTHER, CategoryClassifier.classify("", ""))
    }

    @Test
    fun redPacketCouponIsNotATransfer() {
        assertEquals(TransactionCategory.OTHER, CategoryClassifier.classify("未知商户", "红包抵扣"))
        assertEquals(TransactionCategory.TRANSFER, CategoryClassifier.classify("未知商户", "收到红包"))
    }

    @Test
    fun structuredInputUsesSourceCategoryAndPostRules() {
        assertEquals(
            TransactionCategory.FOOD,
            CategoryClassifier.classify(
                ClassificationInput(merchantRaw = "麦当劳", sourceCategory = "餐饮美食"),
            ).category,
        )
        assertEquals(
            TransactionCategory.OTHER,
            CategoryClassifier.classify(
                ClassificationInput(merchantRaw = "麦当劳", kind = ClassificationKind.REFUND),
            ).category,
        )
        assertEquals(
            TransactionCategory.TRANSFER,
            CategoryClassifier.classify(
                ClassificationInput(merchantRaw = "张三", kind = ClassificationKind.TRANSFER),
            ).category,
        )
        assertEquals(
            TransactionCategory.GIFT,
            CategoryClassifier.classify(
                ClassificationInput(
                    merchantRaw = "李四",
                    direction = ClassificationDirection.INCOME,
                    kind = ClassificationKind.RED_PACKET,
                ),
            ).category,
        )
    }

    @Test
    fun specificPhrasesBeatBrandAndGenericKeywords() {
        assertEquals(
            TransactionCategory.HEALTH,
            CategoryClassifier.classify(
                ClassificationInput(merchantRaw = "美团", contextText = "美团买药"),
            ).category,
        )
        assertEquals(
            TransactionCategory.UTILITIES,
            CategoryClassifier.classify(
                ClassificationInput(merchantRaw = "中国移动", contextText = "话费充值"),
            ).category,
        )
        assertEquals(
            TransactionCategory.ENTERTAINMENT,
            CategoryClassifier.classify(
                ClassificationInput(merchantRaw = "Apple", contextText = "Apple Music"),
            ).category,
        )
        assertEquals(
            TransactionCategory.SHOPPING,
            CategoryClassifier.classify(ClassificationInput(merchantRaw = "万达广场")).category,
        )
        assertEquals(
            TransactionCategory.ENTERTAINMENT,
            CategoryClassifier.classify(ClassificationInput(merchantRaw = "万达影城")).category,
        )
        assertEquals(
            TransactionCategory.FOOD,
            CategoryClassifier.classify(ClassificationInput(merchantRaw = "小米粥")).category,
        )
    }

    @Test
    fun merchantNormalizerStripsChannelCardAndBranchNoise() {
        assertEquals("星巴克", MerchantNormalizer.normalize("支付宝-星巴克(万象城店)-1234"))
        assertEquals("美团买药", MerchantNormalizer.normalize("微信支付-美团买药"))
        assertEquals("五邑大学", MerchantNormalizer.normalize("五邑大学"))
    }

    // ================================================================ 表单预填用的商户记忆

    @Test
    fun rememberedCategoryPrefersExplicitUserRuleOverKeywords() {
        // 内置关键词说「瑞幸 = 餐饮」，但用户显式规则说购物：规则优先。
        MerchantRules.put("瑞幸", TransactionCategory.SHOPPING)
        assertEquals(TransactionCategory.SHOPPING, CategoryClassifier.rememberedCategory("瑞幸"))
    }

    @Test
    fun rememberedCategoryUsesLearnedCorrectionOverKeywords() {
        // 同商户连续两次改成购物 → 学习成立，压过内置的「餐饮」。
        CategoryLearning.record("麦当劳", TransactionCategory.SHOPPING)
        CategoryLearning.record("麦当劳", TransactionCategory.SHOPPING)
        assertEquals(TransactionCategory.SHOPPING, CategoryClassifier.rememberedCategory("麦当劳"))
    }

    @Test
    fun rememberedCategoryFallsBackToBuiltInKeywords() {
        assertEquals(TransactionCategory.FOOD, CategoryClassifier.rememberedCategory("瑞幸"))
        assertEquals(TransactionCategory.HEALTH, CategoryClassifier.rememberedCategory("美团买药"))
    }

    @Test
    fun rememberedCategoryReturnsNullWhenNothingIsRemembered() {
        // 没有规则、没有学习、没有关键词：返回 null，调用方保留解析器自己的分类，
        // 而不是被兜底成 OTHER。
        assertNull(CategoryClassifier.rememberedCategory("楼下小卖部"))
        assertNull(CategoryClassifier.rememberedCategory(""))
        assertNull(CategoryClassifier.rememberedCategory("   "))
    }

    @Test
    fun rememberedCategoryPrefersExplicitRuleOverLearnedCorrection() {
        MerchantRules.put("瑞幸", TransactionCategory.EDUCATION)
        CategoryLearning.record("瑞幸", TransactionCategory.SHOPPING)
        CategoryLearning.record("瑞幸", TransactionCategory.SHOPPING)
        assertEquals(TransactionCategory.EDUCATION, CategoryClassifier.rememberedCategory("瑞幸"))
    }
}
