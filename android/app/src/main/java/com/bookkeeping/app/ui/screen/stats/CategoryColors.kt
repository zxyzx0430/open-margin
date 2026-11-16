package com.bookkeeping.app.ui.screen.stats

import androidx.compose.ui.graphics.Color
import com.bookkeeping.app.data.local.entity.TransactionCategory

/**
 * 每个分类的固定颜色，饼图扇区、图例色块、日历/账单里的分类图标共用这一份。
 *
 * 15 个分类按色相拉开：橙 → 黄绿 → 琥珀 → 绿 → 青绿 → 蓝绿 → 蓝 → 靛蓝 → 紫罗兰 →
 * 品红紫 → 粉 → 红 → 棕 → 深灰 → 中灰。灰阶只留给转账和其他，紫系只留给住房与购物，
 * 这样四个最容易撞的分类（购物 / 住房 / 转账 / 其他）一眼就能分开。
 * 颜色都控制在中低明度，白底上看得清；[CategoryColorPaletteTest] 会锁住最小两两色差。
 */
internal val CATEGORY_PALETTE: Map<TransactionCategory, Color> = mapOf(
    TransactionCategory.FOOD          to Color(0xFFE8590C), // 橙 - 餐饮
    TransactionCategory.EDUCATION     to Color(0xFF74B816), // 黄绿 - 教育
    TransactionCategory.BONUS         to Color(0xFFFAB005), // 琥珀 - 奖金
    TransactionCategory.SALARY        to Color(0xFF2F9E44), // 绿 - 工资
    TransactionCategory.UTILITIES     to Color(0xFF0CA678), // 青绿 - 水电
    TransactionCategory.INVESTMENT    to Color(0xFF1098AD), // 蓝绿 - 理财
    TransactionCategory.TRANSPORT     to Color(0xFF1C7ED6), // 蓝 - 交通
    TransactionCategory.INSURANCE     to Color(0xFF4263EB), // 靛蓝 - 保险
    TransactionCategory.HOUSING       to Color(0xFF7048E8), // 紫罗兰 - 住房
    TransactionCategory.SHOPPING      to Color(0xFFAE3EC9), // 品红紫 - 购物
    TransactionCategory.ENTERTAINMENT to Color(0xFFE64980), // 粉 - 娱乐
    TransactionCategory.GIFT          to Color(0xFFF03E3E), // 红 - 红包
    TransactionCategory.HEALTH        to Color(0xFF8C6D3F), // 棕 - 医疗
    TransactionCategory.TRANSFER      to Color(0xFF343A40), // 深灰 - 转账
    TransactionCategory.OTHER         to Color(0xFF868E96), // 中灰 - 其他
)

/** 兜底色：正常不会用到，[CategoryColorPaletteTest] 保证每个分类都在表里。 */
private val CATEGORY_FALLBACK_COLOR = Color(0xFF868E96)

fun categoryColor(category: TransactionCategory): Color =
    CATEGORY_PALETTE[category] ?: CATEGORY_FALLBACK_COLOR
