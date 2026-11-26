package com.bookkeeping.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DirectionsSubway
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.SimCard
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.ui.screen.stats.categoryColor
import com.bookkeeping.app.ui.theme.LocalQingDark

data class CategoryVisual(
    val background: Color,
    val foreground: Color,
    val icon: ImageVector,
)

/**
 * 分类图标的配色只从 [categoryColor] 来：饼图扇区、图例色块、账单列表图标、预算页图标共用同一份颜色，
 * 不再各算一套，否则同一个分类在不同页面会显示成两种颜色。
 *
 * 浅色模式直接用分类色做前景、把它压到 14% 做底色；暗色模式先把颜色提亮再压底，
 * 保证深色背景上图标仍然看得清。
 */
@Composable
fun categoryVisual(category: TransactionCategory): CategoryVisual {
    val accent = categoryColor(category)
    val icon = categoryIcon(category)
    if (!LocalQingDark.current) {
        return CategoryVisual(
            background = accent.copy(alpha = 0.14f),
            foreground = accent,
            icon = icon,
        )
    }
    val lifted = lerp(accent, Color.White, 0.30f)
    return CategoryVisual(
        background = lifted.copy(alpha = 0.18f),
        foreground = lifted,
        icon = icon,
    )
}

/** 图标与颜色无关，单独一张表；测试可以逐类检查有没有拿到图标。 */
internal fun categoryIcon(category: TransactionCategory): ImageVector = when (category) {
    TransactionCategory.FOOD -> Icons.Outlined.Restaurant
    TransactionCategory.TRANSPORT -> Icons.Outlined.DirectionsSubway
    TransactionCategory.SHOPPING -> Icons.Outlined.ShoppingBag
    TransactionCategory.ENTERTAINMENT -> Icons.Outlined.SportsEsports
    TransactionCategory.HOUSING -> Icons.Outlined.Home
    TransactionCategory.UTILITIES -> Icons.Outlined.Bolt
    TransactionCategory.HEALTH -> Icons.Outlined.MedicalServices
    TransactionCategory.EDUCATION -> Icons.Outlined.School
    TransactionCategory.SALARY -> Icons.Outlined.Payments
    TransactionCategory.BONUS -> Icons.Outlined.Payments
    TransactionCategory.INVESTMENT -> Icons.Outlined.Payments
    TransactionCategory.INSURANCE -> Icons.Outlined.MedicalServices
    TransactionCategory.GIFT -> Icons.Outlined.Payments
    TransactionCategory.TRANSFER -> Icons.Outlined.SwapHoriz
    TransactionCategory.OTHER -> Icons.Outlined.MoreHoriz
}

fun merchantSystemIcon(name: String?): ImageVector? = when (name) {
    "coffee" -> Icons.Outlined.LocalCafe
    "store" -> Icons.Outlined.Storefront
    "restaurant" -> Icons.Outlined.Restaurant
    "shopping" -> Icons.Outlined.ShoppingBag
    "transport" -> Icons.Outlined.DirectionsSubway
    "transfer" -> Icons.Outlined.SwapHoriz
    "salary" -> Icons.Outlined.Payments
    "home" -> Icons.Outlined.Home
    "bolt" -> Icons.Outlined.Bolt
    "health" -> Icons.Outlined.MedicalServices
    "education" -> Icons.Outlined.School
    "ai" -> Icons.Outlined.AutoAwesome
    "code" -> Icons.Outlined.Code
    "video" -> Icons.Outlined.PlayCircle
    "movie" -> Icons.Outlined.Movie
    "music" -> Icons.Outlined.MusicNote
    "cloud" -> Icons.Outlined.Cloud
    "productivity" -> Icons.Outlined.Layers
    "server" -> Icons.Outlined.Dns
    "sim" -> Icons.Outlined.SimCard
    "book" -> Icons.Outlined.MenuBook
    "fitness" -> Icons.Outlined.FitnessCenter
    "game" -> Icons.Outlined.SportsEsports
    else -> null
}
