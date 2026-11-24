package com.bookkeeping.app.billimport

import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.local.entity.TransactionSource
import com.bookkeeping.app.data.local.entity.TransactionType
import com.bookkeeping.app.ui.screen.billimport.BillImportMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookkeepingAppImportTest {

    @Test
    fun parsesQianjiCsvWithBomTransfersAndFallbacks() {
        val csv = "\uFEFF" + """
            时间,分类,二级分类,类型,金额,账户1,账户2,备注,账单图片,手续费
            2026-11-23 12:15:30,餐饮,咖啡,支出,32.50,微信,,瑞幸生椰拿铁,,0.00
            2026-11-22 18:00:00,购物,,支出,"1,299.00",招商银行,,"双十一, 买耳机",,
            2026-11-21 09:30:00,工资,,收入,15000.00,工商银行,,,,
            2026-11-20 20:00:00,转账,,转账,500.00,招商银行,支付宝,,,
            2026-11-19 10:00:00,信用,,还款,800.00,微信,花呗,还花呗,,
        """.trimIndent() + "\n"

        // Generic filename verifies pure header-based auto-detection.
        val preview = BillImportParser.parse(csv.toByteArray(Charsets.UTF_8), "export.csv")
        assertEquals(BillSource.QIANJI, preview.source)
        assertEquals(BillFormat.CSV, preview.format)
        assertEquals(5, preview.validRows.size)
        assertEquals(0, preview.errorRows.size)
        assertEquals(0, preview.skippedRows.size)

        val coffee = preview.validRows[0]
        assertEquals(BillDirection.EXPENSE, coffee.direction)
        assertEquals(3_250L, coffee.amountCents)
        assertEquals("餐饮", coffee.sourceCategory)
        assertEquals("咖啡", coffee.description)
        assertEquals("微信", coffee.account)
        assertEquals("瑞幸生椰拿铁", coffee.note)
        assertEquals("瑞幸生椰拿铁", coffee.merchant)
        assertEquals("钱迹", coffee.ledgerSource)

        val shopping = preview.validRows[1]
        assertEquals(129_900L, shopping.amountCents)
        assertEquals("购物", shopping.sourceCategory)
        assertEquals("招商银行", shopping.account)
        assertEquals("双十一, 买耳机", shopping.note)
        assertEquals("双十一, 买耳机", shopping.merchant)

        val salary = preview.validRows[2]
        assertEquals(BillDirection.INCOME, salary.direction)
        assertEquals(1_500_000L, salary.amountCents)
        assertEquals("工资", salary.sourceCategory)
        assertEquals("工资", salary.merchant)
        assertEquals("工商银行", salary.account)

        val transfer = preview.validRows[3]
        assertEquals(BillDirection.NEUTRAL, transfer.direction)
        assertEquals(50_000L, transfer.amountCents)
        assertEquals("招商银行", transfer.account)
        assertEquals("转账", transfer.merchant)

        val repayment = preview.validRows[4]
        assertEquals(BillDirection.NEUTRAL, repayment.direction)
        assertEquals("还花呗", repayment.merchant)

        // Verify mapping to TransactionEntity and re-import deduplication.
        val entities = preview.validRows.mapNotNull(BillImportMapping::toTransactionEntity)
        assertEquals(5, entities.size)
        assertEquals(TransactionCategory.FOOD.name, entities[0].category)
        assertEquals("微信", entities[0].account)
        assertEquals(TransactionSource.OTHER.name, entities[0].source)
        assertTrue(entities[0].note.contains("钱迹账单"))
        assertTrue(entities[0].note.contains("瑞幸生椰拿铁"))

        assertEquals(TransactionCategory.SHOPPING.name, entities[1].category)
        assertEquals(TransactionType.INCOME.name, entities[2].type)
        assertEquals(TransactionCategory.SALARY.name, entities[2].category)
        assertEquals(TransactionCategory.TRANSFER.name, entities[3].category)
        assertEquals(TransactionCategory.TRANSFER.name, entities[4].category)

        val marked = BillImportMapping.markExistingRows(
            preview = preview,
            transactions = entities,
            duplicateMessage = "账本已有相同记录",
        )
        assertEquals(0, marked.validRows.size)
        assertEquals(5, marked.duplicateRows.size)
        assertEquals(0, marked.enrichableRows.size)
    }

    @Test
    fun parsesQianjiTransferWithEmptyNoteAndCategoryUsingAccountPairFallback() {
        val csv = """
            时间,分类,类型,金额,账户1,账户2,备注
            2026-11-23 10:00:00,,转账,200.00,招商银行,支付宝,
        """.trimIndent() + "\n"

        val preview = BillImportParser.parse(csv.toByteArray(Charsets.UTF_8), "bills.csv")
        assertEquals(BillSource.QIANJI, preview.source)
        assertEquals(1, preview.validRows.size)
        val row = preview.validRows.single()
        assertEquals(BillDirection.NEUTRAL, row.direction)
        assertEquals("招商银行 -> 支付宝", row.merchant)
        assertEquals("招商银行", row.account)
    }

    @Test
    fun parsesYimuCsvWithBomQuotedNotesAndVaryingDateFormats() {
        val csv = "\uFEFF" + """
            日期,收支类型,金额,类别,子类,账户,备注
            2026-11-23 19:20:00,支出,45.80,餐饮,晚餐,支付宝,"聚餐, AA制"
            2026/1/5 9:05,支出,6.00,交通,地铁,云闪付,
            2026/11/10 14:30,收入,3500.00,奖金,季度奖,招商银行,项目奖金
            2026-11-08 11:00:00,转账,1000.00,转账,,微信,转入零钱通
        """.trimIndent() + "\n"

        val preview = BillImportParser.parse(csv.toByteArray(Charsets.UTF_8), "my_data.csv")
        assertEquals(BillSource.YIMU, preview.source)
        assertEquals(4, preview.validRows.size)
        assertEquals(0, preview.errorRows.size)

        val dinner = preview.validRows[0]
        assertEquals(BillDirection.EXPENSE, dinner.direction)
        assertEquals(4_580L, dinner.amountCents)
        assertEquals("餐饮", dinner.sourceCategory)
        assertEquals("晚餐", dinner.description)
        assertEquals("支付宝", dinner.account)
        assertEquals("聚餐, AA制", dinner.note)
        assertEquals("聚餐, AA制", dinner.merchant)
        assertEquals("一木记账", dinner.ledgerSource)

        val subway = preview.validRows[1]
        assertEquals(600L, subway.amountCents)
        assertEquals("交通", subway.sourceCategory)
        assertEquals("地铁", subway.description)
        assertEquals("地铁", subway.merchant)
        assertEquals("云闪付", subway.account)

        val bonus = preview.validRows[2]
        assertEquals(BillDirection.INCOME, bonus.direction)
        assertEquals(350_000L, bonus.amountCents)
        assertEquals("奖金", bonus.sourceCategory)
        assertEquals("项目奖金", bonus.merchant)

        val transfer = preview.validRows[3]
        assertEquals(BillDirection.NEUTRAL, transfer.direction)

        val entities = preview.validRows.mapNotNull(BillImportMapping::toTransactionEntity)
        assertEquals(4, entities.size)
        assertEquals(TransactionCategory.FOOD.name, entities[0].category)
        assertTrue(entities[0].note.contains("一木记账"))
        assertTrue(entities[0].note.contains("聚餐, AA制"))
        assertEquals(TransactionCategory.TRANSPORT.name, entities[1].category)
        assertEquals(TransactionCategory.BONUS.name, entities[2].category)
        assertEquals(TransactionCategory.TRANSFER.name, entities[3].category)
    }

    @Test
    fun parsesSharkCsvWithAndWithoutOptionalAccount() {
        val csvWithoutAccount = "\uFEFF" + """
            日期,类型,分类,金额,备注
            2026-11-23,支出,水电,120.50,电费充值
            2026/11/20 8:15,支出,娱乐,39.90,
            2026-11-15,收入,工资,8000.00,11月薪资
        """.trimIndent() + "\n"

        val preview = BillImportParser.parse(csvWithoutAccount.toByteArray(Charsets.UTF_8), "records.csv")
        assertEquals(BillSource.SHARK, preview.source)
        assertEquals(3, preview.validRows.size)
        assertEquals(0, preview.errorRows.size)

        val utility = preview.validRows[0]
        assertEquals(BillDirection.EXPENSE, utility.direction)
        assertEquals(12_050L, utility.amountCents)
        assertEquals("水电", utility.sourceCategory)
        assertEquals("电费充值", utility.note)
        assertEquals("电费充值", utility.merchant)
        assertEquals("鲨鱼记账", utility.ledgerSource)
        assertEquals(null, utility.account)

        val entertainment = preview.validRows[1]
        assertEquals("娱乐", entertainment.sourceCategory)
        assertEquals("娱乐", entertainment.merchant)

        val entities = preview.validRows.mapNotNull(BillImportMapping::toTransactionEntity)
        assertEquals(3, entities.size)
        // Explicit "水电" category should beat generic "充值" keyword in note.
        assertEquals(TransactionCategory.UTILITIES.name, entities[0].category)
        assertTrue(entities[0].note.contains("鲨鱼记账"))
        assertEquals(TransactionCategory.ENTERTAINMENT.name, entities[1].category)
        assertEquals(TransactionCategory.SALARY.name, entities[2].category)

        val csvWithAccount = """
            日期,类型,分类,金额,账户,备注
            2026-11-23 12:00:00,支出,餐饮,28.00,微信,沙县小吃
        """.trimIndent() + "\n"
        val withAccountPreview = BillImportParser.parse(csvWithAccount.toByteArray(Charsets.UTF_8), "shark_export.csv")
        assertEquals(BillSource.SHARK, withAccountPreview.source)
        val row = withAccountPreview.validRows.single()
        assertEquals("微信", row.account)
        assertEquals("沙县小吃", row.merchant)
        assertEquals("微信", BillImportMapping.toTransactionEntity(row)?.account)
    }

    @Test
    fun reportsMalformedRowsAndSkipsZeroAmountsAcrossNewSources() {
        val malformedYimu = """
            日期,收支类型,金额,类别,子类,账户,备注
            ,支出,10.00,餐饮,,微信,缺日期
            not-a-date,支出,10.00,餐饮,,微信,坏日期
            2026-11-23 10:00:00,支出,,餐饮,,微信,缺金额
            2026-11-23 10:00:00,支出,abc,餐饮,,微信,坏金额
            2026-11-23 10:00:00,支出,0.00,餐饮,,微信,零元
            2026-11-23 10:00:00,支出,18.00,餐饮,,微信,正常一笔
        """.trimIndent() + "\n"

        val preview = BillImportParser.parse(malformedYimu.toByteArray(Charsets.UTF_8), "yimu.csv")
        assertEquals(BillSource.YIMU, preview.source)
        assertEquals(1, preview.validRows.size)
        assertEquals(1, preview.skippedRows.size)
        assertEquals(4, preview.errorRows.size)
        assertEquals("交易时间为空", preview.errorRows[0].message)
        assertTrue(preview.errorRows[1].message!!.contains("无法解析交易时间"))
        assertEquals("金额为空", preview.errorRows[2].message)
        assertTrue(preview.errorRows[3].message!!.contains("无法解析金额"))
        assertNotNull(preview.validRows.single())
        assertEquals("正常一笔", preview.validRows.single().merchant)
    }

    @Test
    fun sourceLabelsCoverAllSupportedPlatforms() {
        assertEquals("微信账单", BillImportMapping.sourceLabel(BillSource.WECHAT))
        assertEquals("支付宝账单", BillImportMapping.sourceLabel(BillSource.ALIPAY))
        assertEquals("钱迹账单", BillImportMapping.sourceLabel(BillSource.QIANJI))
        assertEquals("一木记账", BillImportMapping.sourceLabel(BillSource.YIMU))
        assertEquals("鲨鱼记账", BillImportMapping.sourceLabel(BillSource.SHARK))
        assertEquals("账单", BillImportMapping.sourceLabel(BillSource.UNKNOWN))
    }
}
