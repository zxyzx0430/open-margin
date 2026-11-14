package com.bookkeeping.app.ui.screen.stats

import androidx.compose.ui.graphics.Color
import com.bookkeeping.app.data.local.entity.TransactionCategory

// 每个分类的固定颜色，统一饼图、柱状图、列表标记的视觉
// 采用 Material Design 调色板里饱和度中等的色调，避免过艳
private val PALETTE: Map<TransactionCategory, Color> = mapOf(
    TransactionCategory.FOOD          to Color(0xFFD98F6A),  // 橙 - 餐饮
    TransactionCategory.TRANSPORT     to Color(0xFF6E9BE8),  // 蓝 - 交通
    TransactionCategory.SHOPPING      to Color(0xFF9C7BD6),  // 紫 - 购物
    TransactionCategory.ENTERTAINMENT to Color(0xFFD97B98),  // 粉 - 娱乐
    TransactionCategory.HOUSING       to Color(0xFF7E8BD8),  // 靛 - 住房
    TransactionCategory.UTILITIES     to Color(0xFF58B7A5),  // 青 - 水电
    TransactionCategory.HEALTH        to Color(0xFFA98A7A),  // 棕 - 医疗
    TransactionCategory.EDUCATION     to Color(0xFFE0B25E),  // 橙 - 教育
    TransactionCategory.SALARY        to Color(0xFF7DB86A),  // 绿 - 工资
    TransactionCategory.TRANSFER      to Color(0xFF8E7DD1),  // 深紫 - 转账
    TransactionCategory.OTHER         to Color(0xFF9AA6AD),  // 灰 - 其他
)

fun categoryColor(category: TransactionCategory): Color =
    PALETTE[category] ?: Color(0xFF90A4AE)
