package com.bookkeeping.app.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.merchant.MerchantIconCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun CategoryIcon(
    category: TransactionCategory,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
) {
    val visual = categoryVisual(category)
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.34f))
            .background(visual.background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = visual.icon,
            contentDescription = null,
            tint = visual.foreground,
            modifier = Modifier.size(size * 0.48f),
        )
    }
}

@Composable
fun MerchantAvatar(
    merchant: String,
    merchantIconKey: String?,
    category: TransactionCategory,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    merchantIconPath: String? = null,
) {
    val localIcon by produceState<ImageBitmap?>(initialValue = null, merchantIconPath) {
        value = merchantIconPath?.let { path ->
            withContext(Dispatchers.IO) {
                val options = BitmapFactory.Options().apply { inSampleSize = 3 }
                BitmapFactory.decodeFile(path, options)?.asImageBitmap()
            }
        }
    }
    val iconBitmap = localIcon
    if (iconBitmap != null) {
        Image(
            bitmap = iconBitmap,
            contentDescription = merchant,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(size * 0.34f)),
        )
        return
    }
    val spec = MerchantIconCatalog.forTransaction(merchant, merchantIconKey)
    if (spec == null) {
        CategoryIcon(category = category, modifier = modifier, size = size)
        return
    }
    val color = Color(spec.colorArgb)
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.34f))
            .background(color.copy(alpha = 0.13f)),
        contentAlignment = Alignment.Center,
    ) {
        val icon = merchantSystemIcon(spec.systemIcon)
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(size * 0.46f),
            )
        } else {
            Text(
                text = spec.initials,
                color = color,
                fontSize = (size.value * 0.34f).sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
