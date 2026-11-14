package com.bookkeeping.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.DirectionsSubway
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.bookkeeping.app.data.local.entity.TransactionCategory

data class CategoryVisual(
    val background: Color,
    val foreground: Color,
    val icon: ImageVector,
)

fun categoryVisual(category: TransactionCategory): CategoryVisual = when (category) {
    TransactionCategory.FOOD -> CategoryVisual(Color(0xFFFDE8D7), Color(0xFFD96C2F), Icons.Outlined.Restaurant)
    TransactionCategory.TRANSPORT -> CategoryVisual(Color(0xFFDCEBFF), Color(0xFF2F6FE0), Icons.Outlined.DirectionsSubway)
    TransactionCategory.SHOPPING -> CategoryVisual(Color(0xFFF3E4FF), Color(0xFF8552D6), Icons.Outlined.ShoppingBag)
    TransactionCategory.ENTERTAINMENT -> CategoryVisual(Color(0xFFFFE4EC), Color(0xFFD94F78), Icons.Outlined.SportsEsports)
    TransactionCategory.HOUSING -> CategoryVisual(Color(0xFFE8EDFF), Color(0xFF4F5FC7), Icons.Outlined.Home)
    TransactionCategory.UTILITIES -> CategoryVisual(Color(0xFFDFF5F1), Color(0xFF168C7A), Icons.Outlined.Bolt)
    TransactionCategory.HEALTH -> CategoryVisual(Color(0xFFF1E7E3), Color(0xFF7D5F55), Icons.Outlined.MedicalServices)
    TransactionCategory.EDUCATION -> CategoryVisual(Color(0xFFFFF1D6), Color(0xFFC68A1E), Icons.Outlined.School)
    TransactionCategory.SALARY -> CategoryVisual(Color(0xFFE4F5D9), Color(0xFF4F8C32), Icons.Outlined.Payments)
    TransactionCategory.TRANSFER -> CategoryVisual(Color(0xFFEDE7FF), Color(0xFF6A5ACD), Icons.Outlined.SwapHoriz)
    TransactionCategory.OTHER -> CategoryVisual(Color(0xFFECEFF1), Color(0xFF607D8B), Icons.Outlined.MoreHoriz)
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
    else -> null
}
