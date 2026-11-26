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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookkeeping.app.data.local.entity.TransactionCategory
import com.bookkeeping.app.data.merchant.MerchantIconCatalog
import com.bookkeeping.app.ui.theme.LocalQingDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun CategoryIcon(
    category: TransactionCategory,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    cornerRadius: Dp = size * 0.34f,
) {
    val visual = categoryVisual(category)
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
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
    cornerRadius: Dp = size * 0.34f,
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
                .clip(RoundedCornerShape(cornerRadius)),
        )
        return
    }
    val spec = MerchantIconCatalog.forTransaction(merchant, merchantIconKey)
    if (spec == null) {
        CategoryIcon(category = category, modifier = modifier, size = size, cornerRadius = cornerRadius)
        return
    }
    val color = Color(spec.colorArgb)
    val displayColor = if (LocalQingDark.current) {
        lerp(color, Color.White, 0.25f)
    } else {
        color
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(displayColor.copy(alpha = 0.13f)),
        contentAlignment = Alignment.Center,
    ) {
        val icon = merchantSystemIcon(spec.systemIcon)
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = displayColor,
                modifier = Modifier.size(size * 0.46f),
            )
        } else {
            Text(
                text = spec.initials,
                color = displayColor,
                fontSize = (size.value * 0.34f).sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
