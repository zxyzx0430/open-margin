package com.bookkeeping.app.ui.screen.billimport

import com.bookkeeping.app.billimport.BillDirection
import com.bookkeeping.app.billimport.BillFormat
import com.bookkeeping.app.billimport.BillImportParser
import com.bookkeeping.app.billimport.BillImportPreview
import com.bookkeeping.app.billimport.BillImportRow
import com.bookkeeping.app.billimport.BillPreviewKind
import com.bookkeeping.app.billimport.BillPreviewRow
import com.bookkeeping.app.billimport.BillSource
import com.bookkeeping.app.billimport.BillStatus
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionEntity
import com.bookkeeping.app.data.local.entity.TransactionSource
import com.bookkeeping.app.data.merchant.MerchantRules
import com.bookkeeping.app.data.local.entity.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BillImportMappingTest {

    private fun row(
        merchant: String = "星巴克",
        amountCents: Long = 3_800L,
        direction: BillDirection = BillDirection.EXPENSE,
        status: BillStatus = BillStatus.SUCCESS,
        orderId: String? = "ORDER-1",
        rowNumber: Int = 3,
        source: BillSource = BillSource.WECHAT,
        rawFields: List<String> = emptyList(),
    ) = BillImportRow(
        source = source,
        rowNumber = rowNumber,
        occurredAtMs = 1_700_000_000_000L,
        merchant = merchant,
        amountCents = amountCents,
        direction = direction,
        status = status,
        orderId = orderId,
        paymentMethod = "零钱",
        rawFields = rawFields,
    )

    @Test
    fun mapsExpenseToLedgerWithImportNoteAndSource() {
        val entity = BillImportMapping.toTransactionEntity(row())
        assertNotNull(entity)
        entity!!
        assertEquals(3_800L, entity.amountCents)
        assertEquals(TransactionType.EXPENSE.name, entity.type)
        assertEquals(TransactionSource.WECHAT.name, entity.source)
        assertEquals("星巴克", entity.merchant)
        assertEquals("starbucks", entity.merchantIconKey)
        assertEquals(1_700_000_000_000L, entity.timestamp)
        assertEquals(true, entity.note.contains("[导入]"))
        assertEquals(true, entity.note.contains("第3行"))
        assertEquals(true, entity.note.contains("ORDER-1"))
    }

    @Test
    fun mapsIncomeAndFallsBackForUnknownMerchant() {
        val entity = BillImportMapping.toTransactionEntity(
            row(merchant = "", direction = BillDirection.INCOME, orderId = null),
        )
        assertNotNull(entity)
        entity!!
        assertEquals(TransactionType.INCOME.name, entity.type)
        assertEquals("", entity.merchant)
        assertNull(entity.merchantIconKey)
    }

    @Test
    fun refundRowsAreKeptAsExcludedIncomeAndOriginalExpense() {
        val refundIncome = BillImportMapping.toTransactionEntity(
            row(
                merchant = "自来充-退款",
                amountCents = 145L,
                direction = BillDirection.INCOME,
                status = BillStatus.REFUND,
                orderId = "REFUND-1",
            ),
        )!!
        assertEquals(TransactionType.INCOME.name, refundIncome.type)
        assertEquals("refund", refundIncome.remoteReviewStatus)

        val originalExpense = BillImportMapping.toTransactionEntity(
            row(status = BillStatus.REFUND, orderId = "ORDER-2"),
        )!!
        assertEquals(TransactionType.EXPENSE.name, originalExpense.type)
        assertEquals("refund", originalExpense.remoteReviewStatus)
    }

    @Test
    fun withdrawalNeutralRowIsKeptAsTransferExpense() {
        val entity = BillImportMapping.toTransactionEntity(
            row(
                merchant = "零钱提现",
                direction = BillDirection.NEUTRAL,
                orderId = "WITHDRAW-1",
            ).copy(rawFields = listOf("零钱提现", "提现已到账")),
        )!!
        assertEquals(TransactionType.EXPENSE.name, entity.type)
        assertEquals("TRANSFER", entity.category)
        assertEquals("transfer", entity.remoteReviewStatus)
    }

    @Test
    fun rejectsFailedPendingAndZeroRowsButKeepsNeutralAccountMoves() {
        assertNull(BillImportMapping.toTransactionEntity(row(status = BillStatus.FAILED)))
        assertNull(BillImportMapping.toTransactionEntity(row(status = BillStatus.PENDING)))
        assertNull(BillImportMapping.toTransactionEntity(row(amountCents = 0L)))

        val neutral = BillImportMapping.toTransactionEntity(
            row(direction = BillDirection.NEUTRAL, merchant = "示例基金", orderId = "N1"),
        )!!
        assertEquals(TransactionType.EXPENSE.name, neutral.type)
        assertEquals(TransactionCategory.TRANSFER.name, neutral.category)
        assertEquals("transfer", neutral.remoteReviewStatus)
        assertTrue(neutral.note.contains("不计收支"))
    }

    @Test
    fun existingLedgerKeysUseTheParserNormalization() {
        val transaction = TransactionEntity(
            amountCents = 3_800L,
            type = TransactionType.EXPENSE.name,
            category = "FOOD",
            merchant = "星巴克",
            source = TransactionSource.WECHAT.name,
            timestamp = 1_700_000_000_000L,
        )
        val keys = BillImportMapping.existingRowKeys(listOf(transaction))
        val imported = row(orderId = null)
        assertEquals(true, keys.contains(BillImportParser.duplicateKey(imported)))
        assertEquals(true, keys.contains(BillImportMapping.ledgerRowKey(imported)))
    }

    @Test
    fun orderIdIsStructuredAndNamespacedBySource() {
        val entity = BillImportMapping.toTransactionEntity(row(orderId = "ORDER-1"))!!
        assertEquals("ORDER-1", entity.sourceTransactionId)
        val keys = BillImportMapping.existingRowKeys(listOf(entity))
        assertTrue(keys.contains(BillImportMapping.ledgerRowKey(row(orderId = "ORDER-1"))))
        assertFalse(
            keys.contains(
                BillImportMapping.ledgerRowKey(
                    row(orderId = "ORDER-1", source = BillSource.ALIPAY),
                ),
            ),
        )
    }

    @Test
    fun categoryUsesMerchantCatalogAndFallsBackToOther() {
        assertEquals("FOOD", BillImportMapping.categoryFor("星巴克").name)
        assertEquals("TRANSPORT", BillImportMapping.categoryFor("地铁").name)
        assertEquals("OTHER", BillImportMapping.categoryFor("完全未知的小店").name)
        assertEquals("FOOD", BillImportMapping.categoryFor("采蝶轩").name)
        assertEquals("TRANSPORT", BillImportMapping.categoryFor("自来充").name)
        assertEquals("ENTERTAINMENT", BillImportMapping.categoryFor("杭州游卡").name)
        assertEquals("UTILITIES", BillImportMapping.categoryFor("中国移动").name)
        assertEquals("EDUCATION", BillImportMapping.categoryFor("五邑大学").name)
        assertEquals("SHOPPING", BillImportMapping.categoryFor("思雨宠物用品").name)
        assertEquals("FOOD", BillImportMapping.categoryFor("示例炸鸡店").name)
    }

    @Test
    fun merchantWinsOverIncidentalContextKeywords() {
        assertEquals("UTILITIES", BillImportMapping.categoryFor("中国电信", "充值").name)
        assertEquals(
            "SHOPPING",
            BillImportMapping.categoryFor("拼多多平台商户", "交通银行信用卡").name,
        )
        assertEquals(
            "OTHER",
            BillImportMapping.categoryFor("腾讯公益慈善基金会", "交通银行信用卡").name,
        )
        assertEquals("TRANSFER", BillImportMapping.categoryFor("发给示例联系人").name)
    }

    @Test
    fun transferToPersonIsExcludedFromSpending() {
        val entity = BillImportMapping.toTransactionEntity(
            row(merchant = "发给示例联系人", amountCents = 666L, orderId = "TRANSFER-1"),
        )!!
        assertEquals(TransactionType.EXPENSE.name, entity.type)
        assertEquals(TransactionCategory.TRANSFER.name, entity.category)
        assertEquals("transfer", entity.remoteReviewStatus)
    }

    @Test
    fun personTransferUsesRawTransferHintEvenWhenMerchantIsJustAName() {
        val entity = BillImportMapping.toTransactionEntity(
            row(
                merchant = "张三",
                rawFields = listOf("转账", "已转账"),
                orderId = "TRANSFER-2",
            ),
        )!!
        assertEquals(TransactionCategory.TRANSFER.name, entity.category)
        assertEquals("transfer", entity.remoteReviewStatus)
    }

    @Test
    fun userRuleCannotReclassifyAStrongTransfer() {
        try {
            MerchantRules.put("张三", TransactionCategory.FOOD)
            val entity = BillImportMapping.toTransactionEntity(
                row(
                    merchant = "张三",
                    rawFields = listOf("转账"),
                    orderId = "TRANSFER-3",
                ),
            )!!
            assertEquals(TransactionCategory.TRANSFER.name, entity.category)
            assertEquals("transfer", entity.remoteReviewStatus)
        } finally {
            MerchantRules.clear()
        }
    }

    @Test
    fun redPacketUsesRawHintButDiscountIsNotTransfer() {
        val redPacket = BillImportMapping.toTransactionEntity(
            row(
                merchant = "李四",
                rawFields = listOf("微信红包"),
                orderId = "RED-1",
            ),
        )!!
        assertEquals(TransactionCategory.TRANSFER.name, redPacket.category)
        assertEquals("transfer", redPacket.remoteReviewStatus)

        val discounted = BillImportMapping.toTransactionEntity(
            row(
                merchant = "星巴克",
                rawFields = listOf("红包抵扣", "优惠"),
                orderId = "COFFEE-1",
            ),
        )!!
        assertEquals(TransactionCategory.FOOD.name, discounted.category)
        assertEquals("unverified", discounted.remoteReviewStatus)

        val redPacketMention = BillImportMapping.toTransactionEntity(
            row(
                merchant = "星巴克",
                rawFields = listOf("红包"),
                orderId = "COFFEE-2",
            ),
        )!!
        assertEquals(TransactionCategory.FOOD.name, redPacketMention.category)
        assertEquals("unverified", redPacketMention.remoteReviewStatus)
    }

    @Test
    fun receivedRedPacketCountsAsIncomeInsteadOfTransfer() {
        val entity = BillImportMapping.toTransactionEntity(
            row(
                merchant = "示例联系人",
                amountCents = 888L,
                direction = BillDirection.INCOME,
                rawFields = listOf("微信红包"),
                orderId = "RED-IN-1",
            ),
        )!!
        assertEquals(TransactionType.INCOME.name, entity.type)
        assertEquals(TransactionCategory.GIFT.name, entity.category)
        assertEquals("unverified", entity.remoteReviewStatus)
        assertTrue(entity.note.contains("红包"))
    }

    @Test
    fun previewMarksFuzzyNotificationDuplicatesLikeConfirmDoes() {
        val notification = TransactionEntity(
            id = 42,
            amountCents = 3_800L,
            type = TransactionType.EXPENSE.name,
            category = "FOOD",
            merchant = "微信支付",
            source = TransactionSource.WECHAT.name,
            timestamp = 1_700_000_020_000L,
        )
        val preview = BillImportPreview(
            source = BillSource.WECHAT,
            format = BillFormat.XLSX,
            rows = listOf(
                BillPreviewRow(
                    kind = BillPreviewKind.VALID,
                    rowNumber = 3,
                    rawFields = emptyList(),
                    parsed = row(orderId = "ORDER-1"),
                ),
            ),
        )
        val marked = BillImportMapping.markExistingRows(
            preview = preview,
            transactions = listOf(notification),
            duplicateMessage = "dup",
        )
        assertEquals(BillPreviewKind.DUPLICATE, marked.rows.single().kind)
        assertTrue(marked.rows.single().message!!.contains("dup"))
        assertTrue(marked.rows.single().enrichable)
        assertEquals(notification.id, marked.rows.single().matchedTransactionId)
        assertEquals(0, marked.validRows.size)
    }

    @Test
    fun fuzzyDuplicateCatchesNotificationWithoutOrderId() {
        val notification = TransactionEntity(
            amountCents = 3_800L,
            type = TransactionType.EXPENSE.name,
            category = "FOOD",
            merchant = "星巴克",
            source = TransactionSource.WECHAT.name,
            timestamp = 1_700_000_030_000L,
        )
        assertTrue(
            BillImportMapping.isAlreadyPresent(
                row(orderId = null),
                listOf(notification),
            ),
        )
    }

    @Test
    fun orderIdImportCatchesNotificationWithFallbackMerchant() {
        val notification = TransactionEntity(
            amountCents = 3_800L,
            type = TransactionType.EXPENSE.name,
            category = "FOOD",
            merchant = "微信支付",
            source = TransactionSource.WECHAT.name,
            timestamp = 1_700_000_020_000L,
        )
        assertTrue(
            BillImportMapping.isAlreadyPresent(
                row(orderId = "ORDER-1", merchant = "星巴克"),
                listOf(notification),
            ),
        )
    }

    @Test
    fun differentMerchantOrOrderIsNotTreatedAsDuplicate() {
        val notification = TransactionEntity(
            amountCents = 3_800L,
            type = TransactionType.EXPENSE.name,
            category = "FOOD",
            merchant = "另一家咖啡",
            source = TransactionSource.WECHAT.name,
            timestamp = 1_700_000_030_000L,
        )
        assertFalse(
            BillImportMapping.isAlreadyPresent(
                row(orderId = null),
                listOf(notification),
            ),
        )
        val ordered = notification.copy(
            merchant = "星巴克",
            sourceTransactionId = "ORDER-2",
        )
        assertFalse(
            BillImportMapping.isAlreadyPresent(
                row(orderId = "ORDER-1"),
                listOf(ordered),
            ),
        )
    }

    @Test
    fun categoryRulesCoverRealWechatMerchants() {
        assertEquals(TransactionCategory.FOOD, BillImportMapping.categoryFor("星巴克"))
        assertEquals(TransactionCategory.FOOD, BillImportMapping.categoryFor("采蝶轩"))
        assertEquals(TransactionCategory.FOOD, BillImportMapping.categoryFor("蜜雪冰城"))
        assertEquals(TransactionCategory.FOOD, BillImportMapping.categoryFor("luckin coffee"))
        assertEquals(TransactionCategory.SHOPPING, BillImportMapping.categoryFor("禧迎迎生鲜超市(岭南店)"))
        assertEquals(TransactionCategory.SHOPPING, BillImportMapping.categoryFor("美宜佳便利店"))
        assertEquals(TransactionCategory.SHOPPING, BillImportMapping.categoryFor("岭南印象小店"))
        assertEquals(TransactionCategory.EDUCATION, BillImportMapping.categoryFor("五邑大学"))
        assertEquals(TransactionCategory.EDUCATION, BillImportMapping.categoryFor("墨墨背单词"))
        assertEquals(TransactionCategory.UTILITIES, BillImportMapping.categoryFor("中国电信"))
        assertEquals(TransactionCategory.TRANSPORT, BillImportMapping.categoryFor("自来充"))
        assertEquals(TransactionCategory.TRANSPORT, BillImportMapping.categoryFor("P云停车平台"))
        assertEquals(TransactionCategory.TRANSPORT, BillImportMapping.categoryFor("广东联合电子服务股份有限公司"))
        assertEquals(TransactionCategory.HEALTH, BillImportMapping.categoryFor("国控国大(江门)医药有限公司"))
        assertEquals(TransactionCategory.ENTERTAINMENT, BillImportMapping.categoryFor("杭州游卡"))
        assertEquals(TransactionCategory.ENTERTAINMENT, BillImportMapping.categoryFor("哔哩哔哩"))
        assertEquals(TransactionCategory.TRANSFER, BillImportMapping.categoryFor("发给示例联系人"))
        assertEquals(TransactionCategory.OTHER, BillImportMapping.categoryFor("腾讯公益慈善基金会"))
    }

    @Test
    fun fallbackKeyMatchesOrderlessNotificationRecord() {
        val amount = 840L
        val timestamp = 1_764_000_000_000L
        val notification = TransactionEntity(
            amountCents = amount,
            type = TransactionType.EXPENSE.name,
            category = TransactionCategory.SHOPPING.name,
            merchant = "拼多多平台商户",
            source = TransactionSource.WECHAT.name,
            timestamp = timestamp,
            sourceTransactionId = null,
        )
        val billRow = BillImportRow(
            source = BillSource.WECHAT,
            rowNumber = 17,
            occurredAtMs = timestamp,
            merchant = "拼多多平台商户",
            amountCents = amount,
            direction = BillDirection.EXPENSE,
            status = BillStatus.SUCCESS,
            orderId = "4200003208202611145734008044",
        )

        val keys = BillImportMapping.existingFallbackKeys(listOf(notification))
        val rowKey = BillImportMapping.rowFallbackKey(billRow)

        assertNotNull(rowKey)
        assertTrue(rowKey in keys)
    }

    @Test
    fun fallbackKeyDoesNotCollapseRowsThatAlreadyHaveOrderIds() {
        val imported = TransactionEntity(
            amountCents = 840L,
            type = TransactionType.EXPENSE.name,
            category = TransactionCategory.SHOPPING.name,
            merchant = "拼多多平台商户",
            source = TransactionSource.WECHAT.name,
            timestamp = 1_764_000_000_000L,
            sourceTransactionId = "another-order-id",
        )

        assertTrue(BillImportMapping.existingFallbackKeys(listOf(imported)).isEmpty())
    }

    @Test
    fun neutralRowsHaveNoFallbackKey() {
        val neutral = BillImportRow(
            source = BillSource.WECHAT,
            rowNumber = 1,
            occurredAtMs = 1_764_000_000_000L,
            merchant = "示例银行(0000)",
            amountCents = 50_000L,
            direction = BillDirection.NEUTRAL,
            status = BillStatus.UNKNOWN,
        )
        assertNull(BillImportMapping.rowFallbackKey(neutral))
    }

    @Test
    fun merchantNormalizationIgnoresWhitespaceAndParentheticalSuffix() {
        val notification = TransactionEntity(
            amountCents = 100L,
            type = TransactionType.EXPENSE.name,
            category = TransactionCategory.OTHER.name,
            merchant = "示例联系人 (示例商户)",
            source = TransactionSource.WECHAT.name,
            timestamp = 1_764_000_000_000L,
            sourceTransactionId = null,
        )
        val billRow = BillImportRow(
            source = BillSource.WECHAT,
            rowNumber = 1,
            occurredAtMs = 1_764_000_000_000L,
            merchant = "示例联系人(示例商户)",
            amountCents = 100L,
            direction = BillDirection.EXPENSE,
            status = BillStatus.SUCCESS,
        )
        val key = BillImportMapping.rowFallbackKey(billRow)
        assertNotNull(key)
        assertTrue(key in BillImportMapping.existingFallbackKeys(listOf(notification)))
    }

    @Test
    fun fallbackKeyRequiresSameDirectionAndAmount() {
        val notification = TransactionEntity(
            amountCents = 100L,
            type = TransactionType.INCOME.name,
            category = TransactionCategory.TRANSFER.name,
            merchant = "示例联系人",
            source = TransactionSource.WECHAT.name,
            timestamp = 1_764_000_000_000L,
            sourceTransactionId = null,
        )
        val billRow = BillImportRow(
            source = BillSource.WECHAT,
            rowNumber = 1,
            occurredAtMs = 1_764_000_000_000L,
            merchant = "示例联系人",
            amountCents = 100L,
            direction = BillDirection.EXPENSE,
            status = BillStatus.SUCCESS,
        )
        val key = BillImportMapping.rowFallbackKey(billRow)
        assertNotNull(key)
        assertFalse(key in BillImportMapping.existingFallbackKeys(listOf(notification)))
    }

    @Test
    fun duplicateEnrichmentFillsWeakMerchantOrderAndCategory() {
        val existing = TransactionEntity(
            id = 7,
            amountCents = 3_800L,
            type = TransactionType.EXPENSE.name,
            category = TransactionCategory.OTHER.name,
            merchant = "微信支付",
            note = "[扫描] 微信",
            source = TransactionSource.WECHAT.name,
            timestamp = 1_700_000_020_000L,
        )
        val imported = row(
            merchant = "星巴克",
            orderId = "ORDER-1",
            rawFields = listOf("餐饮", "星巴克"),
        ).copy(sourceCategory = "餐饮", description = "拿铁")

        val enriched = BillImportMapping.enrichExisting(existing, imported)!!
        assertEquals("星巴克", enriched.merchant)
        assertEquals("ORDER-1", enriched.sourceTransactionId)
        assertEquals(TransactionCategory.FOOD.name, enriched.category)
        assertTrue(enriched.note.contains("单号 ORDER-1"))
    }

    @Test
    fun duplicateEnrichmentDoesNotOverwriteManualOrStrongRows() {
        val manual = TransactionEntity(
            id = 8,
            amountCents = 3_800L,
            type = TransactionType.EXPENSE.name,
            category = TransactionCategory.OTHER.name,
            merchant = "我自己的备注",
            source = TransactionSource.MANUAL.name,
            timestamp = 1_700_000_020_000L,
        )
        assertNull(BillImportMapping.enrichExisting(manual, row()))

        val strong = TransactionEntity(
            id = 9,
            amountCents = 3_800L,
            type = TransactionType.EXPENSE.name,
            category = TransactionCategory.FOOD.name,
            merchant = "另一家咖啡",
            source = TransactionSource.WECHAT.name,
            timestamp = 1_700_000_020_000L,
        )
        val enriched = BillImportMapping.enrichExisting(strong, row(merchant = "星巴克"))!!
        assertEquals("另一家咖啡", enriched.merchant)
        assertEquals(TransactionCategory.FOOD.name, enriched.category)
    }

    @Test
    fun enrichmentPreviewCountsTowardImportableRows() {
        val existing = TransactionEntity(
            id = 10,
            amountCents = 3_800L,
            type = TransactionType.EXPENSE.name,
            category = TransactionCategory.OTHER.name,
            merchant = "支付宝",
            source = TransactionSource.ALIPAY.name,
            timestamp = 1_700_000_000_000L,
        )
        val preview = BillImportPreview(
            source = BillSource.ALIPAY,
            format = BillFormat.CSV,
            rows = listOf(
                BillPreviewRow(
                    kind = BillPreviewKind.VALID,
                    rowNumber = 3,
                    rawFields = emptyList(),
                    parsed = row(source = BillSource.ALIPAY, merchant = "星巴克", orderId = "ORDER-1"),
                ),
            ),
        )
        val marked = BillImportMapping.markExistingRows(
            preview = preview,
            transactions = listOf(existing),
            duplicateMessage = "dup",
        )
        assertEquals(1, marked.enrichableRows.size)
        assertEquals(1, marked.importableRows.size)
        assertEquals(0, marked.validRows.size)
    }

    @Test
    fun importMatchesOrderlessScanWithinTwoMinutesButNotDifferentMerchant() {
        val scan = TransactionEntity(
            amountCents = 200L,
            type = TransactionType.EXPENSE.name,
            category = TransactionCategory.SHOPPING.name,
            merchant = "印象小店",
            source = TransactionSource.WECHAT.name,
            timestamp = 1_700_000_060_000L,
        )
        assertTrue(
            BillImportMapping.isAlreadyPresent(
                row(merchant = "印象小店", amountCents = 200L, orderId = "ORDER-IMPRESSION"),
                listOf(scan),
            ),
        )
        assertFalse(
            BillImportMapping.isAlreadyPresent(
                row(merchant = "另一家店", amountCents = 200L, orderId = "ORDER-OTHER"),
                listOf(scan),
            ),
        )
    }

    @Test
    fun openMarginExportRoundTripsCategoryAccountSourceNoteAndDedupesCleanly() {
        val csv = """
            时间,类型,金额（元）,分类,商户,账户,来源,备注
            2026-11-23 16:43:36,支出,7.00,餐饮,采蝶轩,微信,微信,[扫描] 微信
            2026-11-22 10:30:00,支出,74.81,其他,"Langbase, Inc.",花呗,支付宝,[自动支付] 支付宝
            2026-11-22 08:28:54,支出,4.00,其他,,微信,微信,[自动抓取] 微信支付 已支付¥4.00
            2026-10-11 18:03:34,收入,500.00,转账,,,手动,
        """.trimIndent() + "\n"

        val preview = BillImportParser.parse(csv.toByteArray(Charsets.UTF_8), "清衡账单-20261123.csv")
        val entities = preview.validRows.mapNotNull(BillImportMapping::toTransactionEntity)
        assertEquals(4, entities.size)

        assertEquals(TransactionType.EXPENSE.name, entities[0].type)
        assertEquals(TransactionCategory.FOOD.name, entities[0].category)
        assertEquals("采蝶轩", entities[0].merchant)
        assertEquals("微信", entities[0].account)
        assertEquals(TransactionSource.WECHAT.name, entities[0].source)
        assertEquals("[扫描] 微信", entities[0].note)

        assertEquals("Langbase, Inc.", entities[1].merchant)
        assertEquals("花呗", entities[1].account)
        assertEquals(TransactionSource.ALIPAY.name, entities[1].source)

        assertEquals("", entities[2].merchant)
        assertEquals("[自动抓取] 微信支付 已支付¥4.00", entities[2].note)

        assertEquals(TransactionType.INCOME.name, entities[3].type)
        assertEquals(TransactionCategory.TRANSFER.name, entities[3].category)
        assertEquals(TransactionSource.MANUAL.name, entities[3].source)
        assertEquals(null, entities[3].account)
        assertEquals("", entities[3].note)

        // Re-importing the same CSV into a ledger containing those entities
        // marks all rows as duplicates without falsely flagging them as enrichable.
        val marked = BillImportMapping.markExistingRows(
            preview = preview,
            transactions = entities,
            duplicateMessage = "账本已有相同记录",
        )
        assertEquals(0, marked.validRows.size)
        assertEquals(4, marked.duplicateRows.size)
        assertEquals(0, marked.enrichableRows.size)
    }
}
