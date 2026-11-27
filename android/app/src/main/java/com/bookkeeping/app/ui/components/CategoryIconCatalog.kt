package com.bookkeeping.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.DirectionsSubway
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.ui.graphics.vector.ImageVector

object CategoryIconCatalog {
    fun iconForKey(key: String): ImageVector = when (key) {
        "restaurant" -> Icons.Outlined.Restaurant
        "cafe" -> Icons.Outlined.LocalCafe
        "subway" -> Icons.Outlined.DirectionsSubway
        "car" -> Icons.Outlined.DirectionsCar
        "flight" -> Icons.Outlined.Flight
        "shopping" -> Icons.Outlined.ShoppingBag
        "esports" -> Icons.Outlined.SportsEsports
        "home" -> Icons.Outlined.Home
        "bolt" -> Icons.Outlined.Bolt
        "medical" -> Icons.Outlined.MedicalServices
        "school" -> Icons.Outlined.School
        "fitness" -> Icons.Outlined.FitnessCenter
        "pets" -> Icons.Outlined.Pets
        "phone" -> Icons.Outlined.PhoneAndroid
        "payments" -> Icons.Outlined.Payments
        "star" -> Icons.Outlined.Star
        "savings" -> Icons.Outlined.Savings
        "health" -> Icons.Outlined.HealthAndSafety
        "gift" -> Icons.Outlined.CardGiftcard
        "swap" -> Icons.Outlined.SwapHoriz
        else -> Icons.Outlined.MoreHoriz
    }
}
